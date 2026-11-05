package dev.rohit.labelaudit

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class FatSecretKeys(val clientId: String, val clientSecret: String) {
    override fun toString() = "FatSecretKeys(redacted)"
}

interface CredentialsCipher {
    fun encrypt(bytes: ByteArray): ByteArray

    fun decrypt(bytes: ByteArray): ByteArray
}

private class KeystoreCredentialsCipher : CredentialsCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("fatsecret-local-credentials", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "fatsecret-local-credentials",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    override fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(bytes)
    }

    override fun decrypt(bytes: ByteArray): ByteArray {
        val size = bytes.firstOrNull()?.toInt() ?: error("No credentials")
        require(size in 12..16 && bytes.size > size + 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, bytes.copyOfRange(1, size + 1)),
        )
        return cipher.doFinal(bytes.copyOfRange(size + 1, bytes.size))
    }
}

/** Keystore-encrypted, device-only file. Neither SQLite exports nor Android backup include it. */
class FatSecretCredentials(
    context: Context,
    private val cipher: CredentialsCipher = KeystoreCredentialsCipher(),
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "fatsecret-credentials"))
    val configured: Boolean
        get() = file.baseFile.exists()

    @Synchronized
    fun read(): FatSecretKeys? {
        if (!configured) return null
        try {
            val data = JSONObject(String(cipher.decrypt(file.readFully()), Charsets.UTF_8))
            return FatSecretKeys(data.getString("clientId"), data.getString("clientSecret"))
        } catch (e: Exception) {
            throw IllegalStateException(
                "Couldn't read FatSecret credentials. Enter them again in Settings."
            )
        }
    }

    @Synchronized
    fun save(keys: FatSecretKeys) {
        require(keys.clientId.isNotBlank() && keys.clientSecret.isNotBlank()) {
            "Enter both credentials"
        }
        val encrypted =
            cipher.encrypt(
                JSONObject()
                    .put("clientId", keys.clientId.trim())
                    .put("clientSecret", keys.clientSecret.trim())
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            )
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw IllegalStateException("Couldn't save FatSecret credentials")
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
