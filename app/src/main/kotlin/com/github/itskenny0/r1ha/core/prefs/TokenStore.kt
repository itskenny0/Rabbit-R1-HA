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
import kotlinx.coroutines.flow.first
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
    }

    suspend fun save(tokens: Tokens) {
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
            .commit()
        R1Log.i("TokenStore.save", "shadow commit=$shadowOk")
        if (shadowOk) Toaster.show("Tokens shadow-saved")
        else Toaster.show("Tokens shadow save FAILED", long = true)

        try {
            store.edit { p ->
                p[K.accessCipher] = aCipher; p[K.accessIv] = aIv
                p[K.refreshCipher] = rCipher; p[K.refreshIv] = rIv
                p[K.expiresAt] = tokens.expiresAtMillis
            }
            R1Log.i("TokenStore.save", "DataStore commit OK")
            Toaster.show("Tokens DataStore-saved")
        } catch (t: Throwable) {
            R1Log.e("TokenStore.save", "DataStore edit threw; shadow has the value", t)
            Toaster.show("Tokens DataStore FAILED — using shadow", long = true)
        }
    }

    suspend fun load(): Tokens? {
        val p = store.data.first()
        // Try DataStore first; if any field is missing, look in the shadow.
        val aCipher = p[K.accessCipher] ?: shadow.getString(S.accessCipher, null)
        val aIv = p[K.accessIv] ?: shadow.getString(S.accessIv, null)
        val rCipher = p[K.refreshCipher] ?: shadow.getString(S.refreshCipher, null)
        val rIv = p[K.refreshIv] ?: shadow.getString(S.refreshIv, null)
        val expiresAt = p[K.expiresAt] ?: shadow.getLong(S.expiresAt, -1L).takeIf { it >= 0L }
        R1Log.i(
            "TokenStore.load",
            "from store: accessCipher=${p[K.accessCipher] != null} from shadow fallback used=${p[K.accessCipher] == null && aCipher != null}"
        )
        if (aCipher == null || aIv == null || rCipher == null || rIv == null || expiresAt == null) {
            R1Log.w("TokenStore.load", "missing fields: aCipher=${aCipher != null} aIv=${aIv != null} rCipher=${rCipher != null} rIv=${rIv != null} expiresAt=${expiresAt != null}")
            return null
        }
        val key = keystoreProvider.getOrCreateKey(keyAlias)
        return try {
            Tokens(
                accessToken = decrypt(key, aCipher, aIv),
                refreshToken = decrypt(key, rCipher, rIv),
                expiresAtMillis = expiresAt,
            )
        } catch (t: Throwable) {
            R1Log.e("TokenStore.load", "decrypt failed; key likely lost. Returning null to force re-auth.", t)
            Toaster.show("Token decrypt failed — re-authenticate", long = true)
            null
        }
    }

    suspend fun clear() {
        shadow.edit().clear().commit()
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
