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
            assertThat(s.wheel.stepPercent).isEqualTo(5)
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
}
