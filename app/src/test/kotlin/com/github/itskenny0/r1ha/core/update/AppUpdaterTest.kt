package com.github.itskenny0.r1ha.core.update

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the tag → versionCode derivation against the release workflow's bash math.
 * Any drift here breaks the self-update check (it'd either fire on an already-
 * installed build or refuse to update to a strictly-newer one), so the math is
 * worth a couple of small regression tests.
 */
class AppUpdaterTest {

    @Test fun `date-only tag derives versionCode for midnight UTC`() {
        // Workflow path for legacy r1ha-YYYYMMDD tags — defaults HHmm = 0000.
        // 2026-05-13 00:00 UTC is exactly 2324 days × 1440 min past 2020-01-01
        // (2020 + 2024 are leap years, 2026 itself is not).
        val days = java.time.Duration.between(
            java.time.LocalDateTime.of(2020, 1, 1, 0, 0),
            java.time.LocalDateTime.of(2026, 5, 13, 0, 0),
        ).toMinutes() / 1440L
        val expected = 100_000_000L + days * 1440L
        assertThat(AppUpdater.versionCodeFromTag("r1ha-20260513")).isEqualTo(expected)
    }

    @Test fun `date-plus-time tag derives strictly larger versionCode than midnight`() {
        val midnight = AppUpdater.versionCodeFromTag("r1ha-20260513")!!
        val later = AppUpdater.versionCodeFromTag("r1ha-20260513-1409")!!
        assertThat(later).isGreaterThan(midnight)
        // 14:09 UTC = 849 minutes past midnight.
        assertThat(later - midnight).isEqualTo(849L)
    }

    @Test fun `malformed tag returns null instead of throwing`() {
        assertThat(AppUpdater.versionCodeFromTag("not-an-r1ha-tag")).isNull()
        assertThat(AppUpdater.versionCodeFromTag("r1ha-NOPE")).isNull()
        assertThat(AppUpdater.versionCodeFromTag("r1ha-20260513-XXYY")).isNull()
    }

    @Test fun `versionCode floor matches workflow constant`() {
        // 100M floor is the contract between defaultVersionCode(), the workflow,
        // and this updater. Document it as a test so a future change has to also
        // update the documented floor.
        val anyTag = AppUpdater.versionCodeFromTag("r1ha-20200101")!!
        assertThat(anyTag).isEqualTo(100_000_000L)
    }
}
