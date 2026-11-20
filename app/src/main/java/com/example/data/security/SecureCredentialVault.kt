package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts sensitive RTMP/RTMPS stream keys locally using Android Keystore AES-256-GCM
 * so raw stream keys are never written in plaintext to SharedPreferences or readable Firestore docs.
 */
class SecureCredentialVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun storeStreamKey(destinationId: String, rawStreamKey: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(rawStreamKey.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        prefs.edit().putString(keyPrefName(destinationId), payload).apply()
        return "keystore:$destinationId"
    }

    fun readStreamKey(destinationId: String): String? {
        val encoded = prefs.getString(keyPrefName(destinationId), null) ?: return null
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= 12) return null
            val iv = raw.copyOfRange(0, 12)
            val ciphertext = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun removeStreamKey(destinationId: String) {
        prefs.edit().remove(keyPrefName(destinationId)).apply()
    }

    fun maskStreamKey(rawKey: String): String {
        val trimmed = rawKey.trim()
        if (trimmed.length <= 4) return "••••"
        return "••••-${trimmed.takeLast(4)}"
    }

    private fun keyPrefName(id: String) = "sk_$id"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "techlive_stream_key_aes256"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "techlive_encrypted_vault"
    }
}
