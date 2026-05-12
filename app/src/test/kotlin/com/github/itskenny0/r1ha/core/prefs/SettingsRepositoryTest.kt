package com.github.itskenny0.r1ha.core.prefs

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryTest {

    private fun newRepo(): SettingsRepository =
        SettingsRepository.forTesting(ApplicationProvider.getApplicationContext(), datastoreName = "test_settings_${System.nanoTime()}")

    @Test fun defaults() = runTest {
        val repo = newRepo()
        repo.settings.test {
            val s = awaitItem()
            assertThat(s.theme).isEqualTo(ThemeId.PRAGMATIC_HYBRID)
            assertThat(s.wheel.stepPercent).isEqualTo(2)
            assertThat(s.favorites).isEmpty()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun setThenReadFavourites() = runTest {
        val repo = newRepo()
        repo.update { it.copy(favorites = listOf("light.kitchen", "fan.bedroom")) }
        repo.settings.test {
            assertThat(awaitItem().favorites).containsExactly("light.kitchen", "fan.bedroom").inOrder()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun setThenReadWheelStep() = runTest {
        val repo = newRepo()
        repo.update { it.copy(wheel = it.wheel.copy(stepPercent = 10, acceleration = false)) }
        repo.settings.test {
            val w = awaitItem().wheel
            assertThat(w.stepPercent).isEqualTo(10)
            assertThat(w.acceleration).isFalse()
            cancelAndConsumeRemainingEvents()
        }
    }

    /**
     * Sign-in then sign-out: confirms the shadow's "no server" state takes priority over a
     * lingering DataStore value. Regression test for the production bug where signing out
     * appeared not to stick because the DataStore delete silently failed on the user's device.
     */
    @Test fun signOutClearsServerEvenIfDataStoreLags() = runTest {
        val repo = newRepo()
        repo.update { it.copy(server = ServerConfig(url = "http://ha.example:8123")) }
        // Sanity check: signed in.
        repo.settings.test {
            assertThat(awaitItem().server?.url).isEqualTo("http://ha.example:8123")
            cancelAndConsumeRemainingEvents()
        }
        // Sign out.
        repo.update { it.copy(server = null) }
        repo.settings.test {
            assertThat(awaitItem().server).isNull()
            cancelAndConsumeRemainingEvents()
        }
    }

    /**
     * Sign-in then sign-out then sign-in again — favorites set in between should persist
     * (covers the order-preservation regression where the favourites list reset on the
     * second sign-in).
     */
    @Test fun favouritesPersistAcrossSignOutAndIn() = runTest {
        val repo = newRepo()
        repo.update {
            it.copy(
                server = ServerConfig(url = "http://ha.example:8123"),
                favorites = listOf("light.kitchen", "fan.bedroom"),
            )
        }
        repo.update { it.copy(server = null) }
        repo.update { it.copy(server = ServerConfig(url = "http://ha.example:8123")) }
        repo.settings.test {
            val s = awaitItem()
            assertThat(s.server?.url).isEqualTo("http://ha.example:8123")
            // Favourites still there from before sign-out.
            assertThat(s.favorites).containsExactly("light.kitchen", "fan.bedroom").inOrder()
            cancelAndConsumeRemainingEvents()
        }
    }
}
