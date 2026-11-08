package com.jarvis.ai.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys encrypted with an AES-256-GCM key that lives in the Android Keystore.
 *
 * What this protects against: keys sitting in plain text in app storage, backups and
 * adb pulls of the data directory. The encryption key is non-exportable and bound to this
 * app. What it does NOT protect against: malware that already runs as this app or on a
 * rooted device can ask the Keystore to decrypt, and a key typed into a phone can still be
 * read by anyone holding the unlocked phone. Client-side secrets are never fully safe;
 * for a team or production product, call the providers through your own gateway instead.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = ConcurrentHashMap<String, String>()

    /** @return true if the value was stored. A blank value removes the entry. */
    fun put(name: String, value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            remove(name)
            return true
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(name, packed).apply()
            cache[name] = trimmed
            true
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Could not encrypt '$name': ${e.javaClass.simpleName}")
            false
        }
    }

    fun get(name: String): String? {
        cache[name]?.let { return it }
        val packed = prefs.getString(name, null) ?: return null
        val parts = packed.split(SEPARATOR)
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val value = String(cipher.doFinal(data), Charsets.UTF_8)
            cache[name] = value
            value
        } catch (e: GeneralSecurityException) {
            // Typically the Keystore key was invalidated (for example the lock screen was removed).
            Log.w(TAG, "Could not decrypt '$name' (${e.javaClass.simpleName}); treating it as not set")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Stored value for '$name' is corrupt; treating it as not set")
            null
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    fun remove(name: String) {
        cache.remove(name)
        prefs.edit().remove(name).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecureKeyStore"
        const val PREFS_NAME = "jarvis_secure_keys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "jarvis_provider_keys_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
