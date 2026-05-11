package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BackoffPolicyTest {
    @Test fun `doubles and caps at 30 seconds`() {
        val p = BackoffPolicy(baseMillis = 1_000, capMillis = 30_000, jitter = 0.0, rng = Random(0))
        assertThat(p.delayForAttempt(0)).isEqualTo(1_000)
        assertThat(p.delayForAttempt(1)).isEqualTo(2_000)
        assertThat(p.delayForAttempt(2)).isEqualTo(4_000)
        assertThat(p.delayForAttempt(3)).isEqualTo(8_000)
        assertThat(p.delayForAttempt(4)).isEqualTo(16_000)
        assertThat(p.delayForAttempt(5)).isEqualTo(30_000)   // 32k capped to 30k
        assertThat(p.delayForAttempt(20)).isEqualTo(30_000)
    }
    @Test fun `jitter widens the window deterministically with seeded rng`() {
        val p = BackoffPolicy(baseMillis = 1_000, capMillis = 30_000, jitter = 0.25, rng = Random(42))
        val d = p.delayForAttempt(0)
        assertThat(d).isAtLeast(750)
        assertThat(d).isAtMost(1_250)
    }
}
