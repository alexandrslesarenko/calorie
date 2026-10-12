package com.calorie.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.calorie.app.logic.Provider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey as JcaKey
import javax.crypto.spec.GCMParameterSpec

/** API keys of the recognizer providers, loaded once for a request round. */
class AiKeys(private val keys: Map<Provider, String>) {
    operator fun get(p: Provider): String? = keys[p]

    fun has(p: Provider) = p in keys

    /** Some recognizer can be asked. */
    val any: Boolean get() = keys.isNotEmpty()

    companion object {
        fun load(ctx: Context) = AiKeys(Provider.entries.mapNotNull { p -> ApiKeyStore.load(ctx, p)?.let { p to it } }.toMap())
    }
}

/**
 * API keys (Claude, Gemini) on the phone: encrypted with AES-GCM using an Android Keystore key,
 * stored in a separate "secret" file that is excluded from the cloud backup (xml/backup_rules).
 * The Keystore key cannot leave the phone, so a copy of the file is useless elsewhere.
 * The Claude field keeps its old name: keys saved before Gemini stay readable.
 */
object ApiKeyStore {
    private const val ALIAS = "claude_api_key"
    private const val FILE = "secret"
    private const val IV_LEN = 12

    private fun field(p: Provider) = when (p) {
        Provider.CLAUDE -> "api_key"
        Provider.GEMINI -> "gemini_key"
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun keystoreKey(): JcaKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? JcaKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(ctx: Context, key: String, p: Provider = Provider.CLAUDE) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val blob = c.iv + c.doFinal(key.toByteArray(Charsets.UTF_8))
        sp(ctx).edit().putString(field(p), Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    /** null - no key or it cannot be decrypted (for example, the file came from another phone). */
    fun load(ctx: Context, p: Provider = Provider.CLAUDE): String? {
        val b64 = sp(ctx).getString(field(p), null) ?: return null
        return runCatching {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, IV_LEN))
            String(c.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear(ctx: Context, p: Provider = Provider.CLAUDE) {
        sp(ctx).edit().remove(field(p)).apply()
    }

    /** Key for display: start and end, the middle hidden. */
    fun masked(key: String): String = if (key.length <= 16) "..." else key.take(10) + "..." + key.takeLast(4)
}
