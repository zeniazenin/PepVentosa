package com.roundlight.app.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import com.roundlight.app.data.*
import com.roundlight.app.network.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object OAuthRules {
    fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun callback(query: String, state: String, returningClient: String?): Pair<String, String> {
        val params = query.split('&').map {
            val p = it.split('=', limit = 2)
            URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
        }
        require(params.map { it.first }.distinct().size == params.size) { "Repeated sign-in parameters were rejected." }
        val values = params.toMap()
        require(MessageDigest.isEqual(values["state"].orEmpty().toByteArray(), state.toByteArray())) { "Sign-in state did not match. Try signing in again." }
        require(!values.containsKey("error")) { "ChatGPT sign-in was canceled or declined." }
        val issued = values["client_id"] ?: returningClient
        require(issued != null && issued.startsWith("oaiapp_")) { "ChatGPT did not return a valid app registration." }
        require(returningClient == null || issued == returningClient) { "Sign-in returned a different app registration." }
        val code = values["code"].orEmpty()
        require(code.isNotBlank()) { "ChatGPT did not return an authorization code." }
        return code to issued
    }
}

class ChatGptAuth(private val vault: CredentialStore) {
    private val mutable = MutableStateFlow(vault.read())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val resource = "https://api.openai.com/v1"
    private fun save(value: Connections) { vault.write(value); mutable.value = value }
    private fun upsert(account: Account, active: Boolean = false) {
        val current = mutable.value
        save(current.copy(accounts = current.accounts.filterNot { it.clientId == account.clientId } + account,
            activeClientId = if (active) account.clientId else current.activeClientId))
    }
    suspend fun select(clientId: String) = mutex.withLock {
        require(mutable.value.accounts.any { it.clientId == clientId })
        save(mutable.value.copy(activeClientId = clientId))
    }
    suspend fun acknowledgeWelcome() = mutex.withLock { mutable.value.active?.let { upsert(it.copy(welcomed = true)) } }

    suspend fun signIn(existing: Account?, openBrowser: (String) -> Unit, awaitForeground: suspend () -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            withTimeout(300_000) {
                ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 1000
                    val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
                    val state = OAuthRules.random(); val nonce = OAuthRules.random(); val verifier = OAuthRules.random()
                    val url = "https://auth.openai.com/api/accounts/authorize".toHttpUrl().newBuilder()
                        .addQueryParameter("client_id", existing?.clientId ?: "dynamic_agent_client")
                        .addQueryParameter("ext_agent_host_id", mutable.value.hostId)
                        .addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", redirect)
                        .addQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                        .addQueryParameter("resource", resource).addQueryParameter("state", state).addQueryParameter("nonce", nonce)
                        .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", OAuthRules.challenge(verifier))
                    if (existing == null) url.addQueryParameter("agent_name_hint", "Roundlight")
                    else {
                        if (existing.idToken.isNotBlank()) url.addQueryParameter("id_token_hint", existing.idToken)
                        if (existing.email.isNotBlank()) url.addQueryParameter("login_hint", existing.email)
                        if (!existing.canUsePlan) url.addQueryParameter("prompt", "consent")
                    }
                    withContext(Dispatchers.Main) { openBrowser(url.build().toString()) }
                    var callback: Pair<String, String>? = null
                    while (callback == null) {
                        ensureActive()
                        val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                        socket.use { client ->
                            client.soTimeout = 1500
                            val input = client.getInputStream().bufferedReader()
                            val line = buildString {
                                while (length < 8192) { val next = input.read(); if (next == -1 || next == 10) break; append(next.toChar()) }
                            }.trim()
                            val parts = line.split(' ')
                            val uri = runCatching { URI(parts.getOrElse(1) { "" }) }.getOrNull()
                            if (parts.firstOrNull() == "GET" && uri?.path == "/auth/callback") {
                                callback = OAuthRules.callback(uri.rawQuery.orEmpty(), state, existing?.clientId)
                                val html = "<!doctype html><meta name='viewport' content='width=device-width'><title>Roundlight</title><body style='font-family:system-ui;padding:40px;background:#f7f4ed;color:#183e37'><h1>Return to Roundlight</h1><p>Sign-in received. Return to Roundlight to finish connecting your account.</p></body>"
                                val bytes = html.toByteArray()
                                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                            } else client.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    }
                    val (code, clientId) = callback
                    // Preserve registration even if the one-time code expires before exchange.
                    if (existing == null) upsert(Account(clientId))
                    // Finish the remote exchange only after returning from the browser.
                    // Android/OEM background restrictions can otherwise surface as DNS failures.
                    awaitForeground()
                    val token = tokenRequest(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId)
                        .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirect).add("resource", resource).build())
                    val idToken = token["id_token"]?.jsonPrimitive?.content ?: error("ChatGPT did not provide an identity token.")
                    val identity = verifyIdentity(idToken, clientId, nonce)
                    require(existing == null || existing.subject.isBlank() || existing.subject == identity.first) { "Sign-in returned a different ChatGPT account." }
                    val account = tokens(token, (existing ?: Account(clientId)).copy(subject = identity.first, email = identity.second))
                    upsert(account, active = true)
                }
            }
        }
    }

    private suspend fun verifyIdentity(raw: String, clientId: String, nonce: String): Pair<String, String> {
        val jwt = SignedJWT.parse(raw)
        require(jwt.header.algorithm in setOf(JWSAlgorithm.RS256, JWSAlgorithm.ES256)) { "Unsupported identity signature." }
        val keys = requestJson(defaultHttp, Request.Builder().url("https://auth.openai.com/.well-known/jwks.json").build())
        val key = JWKSet.parse(keys.toString()).getKeyByKeyId(jwt.header.keyID) ?: error("Identity signing key was not found.")
        val valid = when (key) {
            is RSAKey -> jwt.header.algorithm == JWSAlgorithm.RS256 && jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))
            is ECKey -> jwt.header.algorithm == JWSAlgorithm.ES256 && jwt.verify(ECDSAVerifier(key.toECPublicKey()))
            else -> false
        }
        require(valid) { "ChatGPT identity signature could not be verified." }
        val claims = jwt.jwtClaimsSet
        require(claims.issuer == "https://auth.openai.com" && clientId in claims.audience) { "Identity issuer or audience mismatch." }
        require(claims.audience.size <= 1 || claims.getStringClaim("azp") == clientId) { "Identity authorized party mismatch." }
        require(claims.expirationTime != null && claims.expirationTime.time > System.currentTimeMillis()) { "Identity token expired." }
        require(claims.notBeforeTime == null || claims.notBeforeTime.time <= System.currentTimeMillis() + 30_000) { "Identity is not valid yet." }
        require(claims.getStringClaim("nonce") == nonce && !claims.subject.isNullOrBlank()) { "Identity verification failed." }
        return claims.subject to claims.getStringClaim("email").orEmpty()
    }
    private suspend fun tokenRequest(body: FormBody) = requestJson(defaultHttp, Request.Builder().url("https://auth.openai.com/api/accounts/oauth/token").post(body).build())
    private fun tokens(json: JsonObject, previous: Account): Account {
        require(json["token_type"]?.jsonPrimitive?.content.equals("Bearer", ignoreCase = true)) { "Unsupported credential type." }
        val access = json["access_token"]?.jsonPrimitive?.content.orEmpty()
        require(access.isNotBlank()) { "No access token was granted." }
        val expiry = json["expires_in"]?.jsonPrimitive?.longOrNull ?: error("Missing access-token expiry.")
        return previous.copy(accessToken = access,
            refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: previous.refreshToken,
            idToken = json["id_token"]?.jsonPrimitive?.content ?: previous.idToken,
            scopes = json["scope"]?.jsonPrimitive?.content?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: previous.scopes,
            expiresAt = System.currentTimeMillis() + expiry * 1000)
    }
    suspend fun accessToken(): String = mutex.withLock {
        var account = mutable.value.active ?: error("Connect your ChatGPT account first.")
        check(account.canUsePlan) { "Enable ChatGPT plan usage in Account before scanning." }
        if (account.expiresAt < System.currentTimeMillis() + 60_000) {
            check(account.refreshToken.isNotBlank()) { "Continue with ChatGPT to reconnect." }
            try {
                account = tokens(tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", account.clientId)
                    .add("refresh_token", account.refreshToken).add("resource", resource).build()), account)
                upsert(account)
            } catch (e: ServiceFailure) {
                if (e.code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) upsert(account.withoutTokens())
                throw e
            }
        }
        check(account.canUsePlan) { "ChatGPT plan usage permission is not enabled." }
        account.accessToken
    }
    /** Return false if remote revocation wasn't confirmed; always forget the local tokens. */
    suspend fun signOut(): Boolean = mutex.withLock {
        val account = mutable.value.active ?: return@withLock true
        var revoked = account.refreshToken.isBlank()
        try {
            if (!revoked) {
                val discovery = requestJson(defaultHttp, Request.Builder().url("https://auth.openai.com/.well-known/openid-configuration").build())
                val endpoint = discovery.getValue("revocation_endpoint").jsonPrimitive.content.toHttpUrl()
                require(endpoint.isHttps && endpoint.host == "auth.openai.com")
                val body = FormBody.Builder().add("token", account.refreshToken).add("token_type_hint", "refresh_token").add("client_id", account.clientId).build()
                for (attempt in 0..1) {
                    val status = request(defaultHttp, Request.Builder().url(endpoint).post(body).build()) { it.code }
                    if (status == 200) { revoked = true; break }
                    if (status < 500) break
                    delay(500)
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Report unconfirmed revocation, never token data. */ }
        finally { upsert(account.withoutTokens()) }
        revoked
    }
}
