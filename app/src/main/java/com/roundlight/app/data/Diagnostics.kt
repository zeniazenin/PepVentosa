package com.roundlight.app.data

import android.content.Context
import android.os.Build
import com.roundlight.app.BuildConfig
import java.io.File
import java.time.Instant

/** Fixed event names and numeric/device metadata only; no tokens, URLs, photo data or exception messages. */
class Diagnostics(context: Context) {
    private val dir=File(context.filesDir,"diagnostics").apply { mkdirs() }
    private val current=File(dir,"events.log")
    @Synchronized fun event(name: String, values: Map<String,Number> = emptyMap()) {
        require(name.matches(Regex("[a-z0-9_.-]{1,80}")))
        if (current.length()>512_000) { val old=File(dir,"previous.log"); old.delete(); current.renameTo(old) }
        current.appendText("${Instant.now()} $name ${values.entries.joinToString(" ") { "${it.key.filter(Char::isLetterOrDigit)}=${it.value}" }}\n")
    }
    @Synchronized fun failure(stage: String, error: Throwable) {
        event("${stage}.failed"); event("error.${error.javaClass.simpleName.lowercase().filter(Char::isLetterOrDigit).take(45)}")
        error.stackTrace.filter { it.className.startsWith("com.roundlight.") }.take(12).forEach { frame ->
            current.appendText("  at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})\n")
        }
    }
    @Synchronized fun report(): String = buildString {
        appendLine("Roundlight ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("No credentials, photographs, account identifiers, or authorization URLs are included.")
        listOf(File(dir,"previous.log"),current).filter { it.exists() }.forEach { append(it.readText()) }
    }
    @Synchronized fun service(status:Int,code:String,requestId:String){
        event("openai.http_error",mapOf("status" to status))
        val safeCode=code.takeIf{it.matches(Regex("[a-zA-Z0-9_-]{1,100}"))} ?: "unknown"
        val safeId=requestId.takeIf{it.matches(Regex("[a-zA-Z0-9_-]{1,100}"))} ?: "unavailable"
        current.appendText("  code=$safeCode requestId=$safeId\n")
    }
}
