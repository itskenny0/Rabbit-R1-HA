package com.github.itskenny0.r1ha.core.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
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

    private object K {
        val accessCipher = stringPreferencesKey("access.cipher")
        val accessIv = stringPreferencesKey("access.iv")
        val refreshCipher = stringPreferencesKey("refresh.cipher")
        val refreshIv = stringPreferencesKey("refresh.iv")
        val expiresAt = longPreferencesKey("expires_at")
    }

    suspend fun save(tokens: Tokens) {
        val key = keystoreProvider.getOrCreateKey(keyAlias)
        val (aCipher, aIv) = encrypt(key, tokens.accessToken)
        val (rCipher, rIv) = encrypt(key, tokens.refreshToken)
        store.edit { p ->
            p[K.accessCipher] = aCipher; p[K.accessIv] = aIv
            p[K.refreshCipher] = rCipher; p[K.refreshIv] = rIv
            p[K.expiresAt] = tokens.expiresAtMillis
        }
    }

    suspend fun load(): Tokens? {
        val p = store.data.first()
        val aCipher = p[K.accessCipher] ?: return null
        val aIv = p[K.accessIv] ?: return null
        val rCipher = p[K.refreshCipher] ?: return null
        val rIv = p[K.refreshIv] ?: return null
        val expiresAt = p[K.expiresAt] ?: return null
        val key = keystoreProvider.getOrCreateKey(keyAlias)
        return Tokens(
            accessToken = decrypt(key, aCipher, aIv),
            refreshToken = decrypt(key, rCipher, rIv),
            expiresAtMillis = expiresAt,
        )
    }

    suspend fun clear() {
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
