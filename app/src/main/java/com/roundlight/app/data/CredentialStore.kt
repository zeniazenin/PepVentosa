package com.roundlight.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.roundlight.app.model.appJson
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Account(
    val clientId: String,
    val subject: String = "",
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAt: Long = 0,
    val scopes: Set<String> = emptySet(),
    val welcomed: Boolean = false,
    val selectedModel: String = "",
    val selectedModelLabel: String = ""
) {
    val connected get() = accessToken.isNotBlank()
    val canUsePlan get() = connected && "chatgpt.tokens.use.direct" in scopes
    val label get() = "${email.ifBlank { "ChatGPT account" }} · ${clientId.takeLast(6)}"
    fun withoutTokens() = copy(accessToken = "", refreshToken = "", idToken = "", expiresAt = 0, scopes = emptySet())
}

@Serializable
data class Connections(val hostId: String = "urn:uuid:${UUID.randomUUID()}", val accounts: List<Account> = emptyList(), val activeClientId: String? = null) {
    val active get() = accounts.find { it.clientId == activeClientId }
}

/** Only ciphertext reaches disk. Backup is disabled in the Android manifest. */
class CredentialStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt.vault"))
    private val alias = "roundlight.chatgpt.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): Connections {
        val bytes = try { file.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) { return Connections().also { write(it) } }
        require(bytes.size > 28) { "Account storage is damaged. Reinstall to reset protected storage." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return appJson.decodeFromString(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun write(value: Connections) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(appJson.encodeToString(value).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
