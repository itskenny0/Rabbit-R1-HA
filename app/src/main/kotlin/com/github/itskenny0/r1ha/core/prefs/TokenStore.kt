package com.github.itskenny0.r1ha.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

/**
 * Encrypted-at-rest token store. Uses DataStore as the primary persistence layer with an
 * AndroidKeystore-wrapped AES/GCM key, AND mirrors the resulting ciphertext to a parallel
 * SharedPreferences "shadow" file. If DataStore writes silently fail to land on a given
 * device (which has been observed in production on this user's hardware), `load()` falls
 * back to reading the shadow's ciphertext and decrypting via the same Keystore key.
 */
class TokenStore(
    context: Context,
    datastoreName: String = "r1ha_tokens",
    private val keyAlias: String = "r1ha_token_key",
    /** Override in tests to "PKCS12" or similar when AndroidKeyStore is unavailable. */
    private val keystoreProvider: KeyProvider = AndroidKeyProvider,
) {
    interface KeyProvider {
        fun getOrCreateKey(alias: String): SecretKey
    }

    object AndroidKeyProvider : KeyProvider {
        override fun getOrCreateKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.getKey(alias, null)?.let { return it as SecretKey }
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return kg.generateKey()
        }
    }

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(datastoreName) },
    )

    private val shadow: SharedPreferences =
        context.applicationContext.getSharedPreferences("${datastoreName}_shadow", Context.MODE_PRIVATE)

    private object K {
        val accessCipher = stringPreferencesKey("access.cipher")
        val accessIv = stringPreferencesKey("access.iv")
        val refreshCipher = stringPreferencesKey("refresh.cipher")
        val refreshIv = stringPreferencesKey("refresh.iv")
        val expiresAt = longPreferencesKey("expires_at")
    }

    private object S {
        const val accessCipher = "access.cipher"
        const val accessIv = "access.iv"
        const val refreshCipher = "refresh.cipher"
        const val refreshIv = "refresh.iv"
        const val expiresAt = "expires_at"

        /**
         * Marker set the first time any write reaches the shadow; once set, the shadow is the
         * authoritative source on load() — including reporting "no tokens" after a sign-out,
         * which is critical to avoid leaking the previous session's tokens if the DataStore
         * clear silently failed.
         */
        const val initialized = "_initialized"
    }

    suspend fun save(tokens: Tokens): Unit = withContext(Dispatchers.IO) {
        val key = keystoreProvider.getOrCreateKey(keyAlias)
        val (aCipher, aIv) = encrypt(key, tokens.accessToken)
        val (rCipher, rIv) = encrypt(key, tokens.refreshToken)
        R1Log.i("TokenStore.save", "encrypting + persisting (expires=${tokens.expiresAtMillis})")

        // Write the shadow synchronously first so we have at least one durable copy.
        val shadowOk = shadow.edit()
            .putString(S.accessCipher, aCipher)
            .putString(S.accessIv, aIv)
            .putString(S.refreshCipher, rCipher)
            .putString(S.refreshIv, rIv)
            .putLong(S.expiresAt, tokens.expiresAtMillis)
            .putBoolean(S.initialized, true)
            .commit()
        R1Log.i("TokenStore.save", "shadow commit=$shadowOk")
        if (!shadowOk) {
            Toaster.error("Tokens couldn't save to fallback storage")
        }

        try {
            store.edit { p ->
                p[K.accessCipher] = aCipher; p[K.accessIv] = aIv
                p[K.refreshCipher] = rCipher; p[K.refreshIv] = rIv
                p[K.expiresAt] = tokens.expiresAtMillis
            }
            R1Log.i("TokenStore.save", "DataStore commit OK")
        } catch (t: Throwable) {
            R1Log.e("TokenStore.save", "DataStore edit threw; shadow has the value", t)
            if (!shadowOk) {
                Toaster.error("Tokens couldn't save anywhere")
            }
        }
    }

    suspend fun load(): Tokens? = withContext(Dispatchers.IO) {
        val p = store.data.first()
        // Once the shadow has been written even once it becomes the authoritative store — the
        // shadow's absence-of-tokens then correctly represents "signed out" instead of falling
        // back to a stale DataStore copy that the clear() might have silently failed to wipe.
        val shadowInit = shadow.getBoolean(S.initialized, false)
        val aCipher: String?
        val aIv: String?
        val rCipher: String?
        val rIv: String?
        val expiresAt: Long?
        if (shadowInit) {
            aCipher = shadow.getString(S.accessCipher, null)
            aIv = shadow.getString(S.accessIv, null)
            rCipher = shadow.getString(S.refreshCipher, null)
            rIv = shadow.getString(S.refreshIv, null)
            expiresAt = shadow.getLong(S.expiresAt, -1L).takeIf { it >= 0L }
        } else {
            // Pre-marker / migration path — fall back to DataStore, then shadow.
            aCipher = p[K.accessCipher] ?: shadow.getString(S.accessCipher, null)
            aIv = p[K.accessIv] ?: shadow.getString(S.accessIv, null)
            rCipher = p[K.refreshCipher] ?: shadow.getString(S.refreshCipher, null)
            rIv = p[K.refreshIv] ?: shadow.getString(S.refreshIv, null)
            expiresAt = p[K.expiresAt] ?: shadow.getLong(S.expiresAt, -1L).takeIf { it >= 0L }
        }
        R1Log.i(
            "TokenStore.load",
            "from store: accessCipher=${p[K.accessCipher] != null} from shadow fallback used=${p[K.accessCipher] == null && aCipher != null}"
        )
        if (aCipher == null || aIv == null || rCipher == null || rIv == null || expiresAt == null) {
            R1Log.w("TokenStore.load", "missing fields: aCipher=${aCipher != null} aIv=${aIv != null} rCipher=${rCipher != null} rIv=${rIv != null} expiresAt=${expiresAt != null}")
            return@withContext null
        }
        val key = keystoreProvider.getOrCreateKey(keyAlias)
        try {
            Tokens(
                accessToken = decrypt(key, aCipher, aIv),
                refreshToken = decrypt(key, rCipher, rIv),
                expiresAtMillis = expiresAt,
            )
        } catch (t: Throwable) {
            R1Log.e("TokenStore.load", "decrypt failed; key likely lost. Returning null to force re-auth.", t)
            Toaster.error("Token decrypt failed — re-authenticate")
            null
        }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        // commit() blocks on disk I/O; run on the IO dispatcher to avoid jank when sign-out is
        // triggered from a Compose handler on the main thread.
        // Clear shadow but keep the initialized marker set — load() must then return null even
        // if the DataStore clear() below silently fails (in which case stale tokens would still
        // be present in the DataStore copy).
        shadow.edit().clear().putBoolean(S.initialized, true).commit()
        store.edit { it.clear() }
    }

    private fun encrypt(key: SecretKey, plaintext: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ct, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKey, ciphertextB64: String, ivB64: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val pt = cipher.doFinal(Base64.decode(ciphertextB64, Base64.NO_WRAP))
        return String(pt, Charsets.UTF_8)
    }
}
