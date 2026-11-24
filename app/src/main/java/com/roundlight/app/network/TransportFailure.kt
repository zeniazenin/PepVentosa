package com.roundlight.app.network

import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class ConnectionStep(val description: String) {
    SIGN_IN("finishing ChatGPT sign-in"),
    VERIFY_IDENTITY("verifying your ChatGPT identity"),
    MODELS("loading your available models"),
    ANALYSIS("analyzing your photo"),
    ACCOUNT("connecting to ChatGPT")
}

enum class ConnectionProblem { DNS, TIMEOUT, TLS, CONNECT }

/** Only fixed step names and a public hostname appear in diagnostics. Never include URLs or tokens. */
class TransportFailure(val step: ConnectionStep, val problem: ConnectionProblem, val host: String, cause: IOException) :
    IOException(messageFor(step, problem, host), cause) {
    val diagnostic get() = "${step.name}/${problem.name} ($host)"

    companion object {
        private fun messageFor(step: ConnectionStep, problem: ConnectionProblem, host: String): String {
            val explanation = when (problem) {
                ConnectionProblem.DNS -> "Your phone couldn't look up $host"
                ConnectionProblem.TIMEOUT -> "The connection to $host timed out"
                ConnectionProblem.TLS -> "A secure connection to $host could not be verified"
                ConnectionProblem.CONNECT -> "Your phone couldn't connect to $host"
            }
            val action = when (problem) {
                ConnectionProblem.DNS, ConnectionProblem.CONNECT -> "Try again with LightPlay open. If it continues, check Wi-Fi/mobile data and any VPN or Private DNS settings."
                ConnectionProblem.TIMEOUT -> "Try again when your connection is stable."
                ConnectionProblem.TLS -> "Check your phone's date/time and network settings, then try again."
            }
            val saved = if (step == ConnectionStep.MODELS) " Your account remains connected; use Load available models to retry." else ""
            return "$explanation while ${step.description}. $action$saved"
        }
        fun from(url: HttpUrl, cause: IOException): IOException {
            val problem = when (cause) {
                is UnknownHostException -> ConnectionProblem.DNS
                is SocketTimeoutException -> ConnectionProblem.TIMEOUT
                is SSLException -> ConnectionProblem.TLS
                is ConnectException -> ConnectionProblem.CONNECT
                else -> return cause
            }
            val step = when (url.encodedPath) {
                "/api/accounts/oauth/token" -> ConnectionStep.SIGN_IN
                "/.well-known/jwks.json" -> ConnectionStep.VERIFY_IDENTITY
                "/v1/models" -> ConnectionStep.MODELS
                "/v1/responses" -> ConnectionStep.ANALYSIS
                else -> ConnectionStep.ACCOUNT
            }
            val host = url.host.takeIf { it in setOf("auth.openai.com", "api.openai.com") } ?: "the service"
            return TransportFailure(step, problem, host, cause)
        }
    }
}

/** A DNS failure occurs before an HTTP request is sent; don't replay timeouts, token grants, or inference failures. */
internal suspend fun <T> retryDnsOnce(pause: suspend () -> Unit = { delay(1000) }, operation: suspend () -> T): T {
    return try { operation() } catch (_: UnknownHostException) {
        pause()
        operation()
    }
}
