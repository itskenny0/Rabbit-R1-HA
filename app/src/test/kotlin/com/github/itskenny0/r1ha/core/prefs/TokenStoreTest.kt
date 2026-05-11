package com.github.itskenny0.r1ha.core.prefs

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenStoreTest {
    // Each test gets its own shared SoftwareKeyProvider so save/load use the same key instance.
    private fun newStore(keyProvider: TokenStore.KeyProvider = TokenStore.SoftwareKeyProvider()) =
        TokenStore(
            context = ApplicationProvider.getApplicationContext(),
            datastoreName = "test_tokens_${System.nanoTime()}",
            keyAlias = "test_alias_${System.nanoTime()}",
            keystoreProvider = keyProvider,
        )

    @Test fun roundtripStoresAndRetrievesTokens() = runTest {
        val keyProvider = TokenStore.SoftwareKeyProvider()
        val store = newStore(keyProvider)
        store.save(Tokens(accessToken = "A", refreshToken = "R", expiresAtMillis = 1_700_000_000_000L))
        val read = store.load()
        assertThat(read).isNotNull()
        assertThat(read!!.accessToken).isEqualTo("A")
        assertThat(read.refreshToken).isEqualTo("R")
        assertThat(read.expiresAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test fun clearRemovesTokens() = runTest {
        val keyProvider = TokenStore.SoftwareKeyProvider()
        val store = newStore(keyProvider)
        store.save(Tokens("A", "R", 0))
        store.clear()
        assertThat(store.load()).isNull()
    }
}
