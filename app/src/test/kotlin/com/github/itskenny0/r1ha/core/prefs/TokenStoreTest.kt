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
    private fun newStore(keyProvider: TokenStore.KeyProvider = SoftwareKeyProvider()) =
        TokenStore(
            context = ApplicationProvider.getApplicationContext(),
            datastoreName = "test_tokens_${System.nanoTime()}",
            keyAlias = "test_alias_${System.nanoTime()}",
            keystoreProvider = keyProvider,
        )

    @Test fun roundtripStoresAndRetrievesTokens() = runTest {
        val keyProvider = SoftwareKeyProvider()
        val store = newStore(keyProvider)
        store.save(Tokens(accessToken = "A", refreshToken = "R", expiresAtMillis = 1_700_000_000_000L))
        val read = store.load()
        assertThat(read).isNotNull()
        assertThat(read!!.accessToken).isEqualTo("A")
        assertThat(read.refreshToken).isEqualTo("R")
        assertThat(read.expiresAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test fun clearRemovesTokens() = runTest {
        val keyProvider = SoftwareKeyProvider()
        val store = newStore(keyProvider)
        store.save(Tokens("A", "R", 0))
        store.clear()
        assertThat(store.load()).isNull()
    }

    /**
     * Round-trip save → clear → save → load: confirms a fresh save after clear works and the
     * second load returns the NEW tokens (not the cleared previous ones leaking back through
     * a stale fallback path).
     */
    @Test fun signOutThenSignInReturnsFreshTokens() = runTest {
        val keyProvider = SoftwareKeyProvider()
        val store = newStore(keyProvider)
        store.save(Tokens("OLD_A", "OLD_R", 1L))
        store.clear()
        assertThat(store.load()).isNull()
        store.save(Tokens("NEW_A", "NEW_R", 2L))
        val read = store.load()
        assertThat(read).isNotNull()
        assertThat(read!!.accessToken).isEqualTo("NEW_A")
        assertThat(read.refreshToken).isEqualTo("NEW_R")
    }
}
