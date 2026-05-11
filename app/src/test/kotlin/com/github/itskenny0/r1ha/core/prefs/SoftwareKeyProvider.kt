package com.github.itskenny0.r1ha.core.prefs

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Software-backed key provider for environments where AndroidKeyStore is unavailable (e.g. unit tests). */
class SoftwareKeyProvider : TokenStore.KeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()
    override fun getOrCreateKey(alias: String): SecretKey =
        keys.getOrPut(alias) {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(256)
            kg.generateKey()
        }
}
