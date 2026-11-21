package com.roundlight.app.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ServiceFailure(val status: Int, val code: String, val requestId: String = "", val parameter: String = "") : IOException(messageFor(status, code)) {
    val usageLimit get() = code == "subscription_sharing_usage_limit_exceeded"
    companion object {
        fun messageFor(status: Int, code: String): String = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> "Your plan or Roundlight usage limit was reached. Open Manage usage to review it."
            "subscription_sharing_user_not_eligible" -> "This ChatGPT account or workspace is not eligible for plan usage. Check your account's eligibility."
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> "ChatGPT plan usage is temporarily unavailable. Your connection is saved; try later."
            "subscription_sharing_unsupported_capability" -> "This model or request feature is not supported by your ChatGPT plan. Select another image-capable model."
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused" -> "Your connection expired. Continue with ChatGPT to reconnect."
            else -> when (status) {
                401 -> "ChatGPT could not authorize this request. Reconnect your account."
                403 -> "OpenAI restricted this request for this account, workspace, or integration."
                429 -> "OpenAI is limiting requests. Wait before trying again."
                in 500..599 -> "OpenAI is temporarily unavailable. Your photos are saved; try later."
                else -> "The request could not be completed${if (code.isNotBlank()) " ($code)" else ""}."
            }
        }
        fun from(status: Int, body: JsonObject?, requestId: String = ""): ServiceFailure {
            val error = body?.get("error")
            val code = if (error is JsonObject) error["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                else (error as? JsonPrimitive)?.contentOrNull ?: body?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
            return ServiceFailure(status, code, requestId, (error as? JsonObject)?.get("param")?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}

val defaultHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false)
    .followRedirects(false).followSslRedirects(false).build()

suspend fun <T> request(client: OkHttpClient, request: Request, parse: (Response) -> T): T {
    return try { retryDnsOnce { requestOnce(client, request, parse) } }
    catch (error: IOException) { throw TransportFailure.from(request.url, error) }
}

private suspend fun <T> requestOnce(client: OkHttpClient, request: Request, parse: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use(parse)
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    })
}

suspend fun requestJson(http: OkHttpClient, req: Request): JsonObject = request(http, req) { response ->
    val body = response.body?.string().orEmpty()
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    if (!response.isSuccessful) throw ServiceFailure.from(response.code, json, response.header("x-request-id").orEmpty())
    json ?: throw IOException("OpenAI returned an unreadable response.")
}

/** SSE text is untrusted until the completion event arrives. Never expose partial compositions. */
object ResponseStream {
    fun read(reader: java.io.BufferedReader, requestId: String = ""): String {
        var completed = false
        var output = ""
        val eventData = StringBuilder()
        val deltas = StringBuilder()
        var totalChars = 0
        fun dispatch() {
            if (eventData.isEmpty()) return
            val data = eventData.toString().trimEnd('\n'); eventData.clear()
            if (data == "[DONE]") return
            val event = Json.parseToJsonElement(data).jsonObject
            when (event["type"]?.jsonPrimitive?.content) {
                "response.output_text.delta" -> deltas.append(event["delta"]?.jsonPrimitive?.content.orEmpty())
                "response.refusal.delta", "response.refusal.done" -> throw IOException("The model declined this image. Try a different photo.")
                "response.failed", "error" -> throw ServiceFailure.from(400, (event["response"] as? JsonObject) ?: event, requestId)
                "response.incomplete" -> throw IOException("Analysis stopped before it finished. No composition was saved; try again.")
                "response.completed" -> {
                    val response = event["response"]?.jsonObject ?: throw IOException("Missing completed response.")
                    require(response["status"]?.jsonPrimitive?.content == "completed") { "Response did not complete." }
                    val parts = response["output"]?.jsonArray.orEmpty().flatMap { (it as? JsonObject)?.get("content")?.jsonArray.orEmpty() }
                    if (parts.any { it.jsonObject["type"]?.jsonPrimitive?.content == "refusal" }) throw IOException("The model declined this photo.")
                    output = parts.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
                        .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.ifBlank { deltas.toString() }
                    completed = true
                }
            }
        }
        while (true) {
            val line = reader.readLine() ?: break
            totalChars += line.length
            if (totalChars > 4_000_000) throw IOException("Analysis exceeded the supported response size.")
            if (line.isEmpty()) {
                dispatch()
                if (completed) break
            } else if (line.startsWith("data:")) eventData.append(line.substring(5).removePrefix(" ")).append('\n')
        }
        if (!completed) dispatch()
        if (!completed || output.isBlank()) throw IOException("Connection ended before the analysis was complete. Please try again.")
        return output
    }
}
