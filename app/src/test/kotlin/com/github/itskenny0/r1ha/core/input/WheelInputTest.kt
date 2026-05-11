package com.github.itskenny0.r1ha.core.input

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WheelInputTest {
    @Test fun `slow scrolling uses base step`() {
        // 1 event/sec is well under threshold => multiplier == 1
        assertThat(WheelInput.effectiveStep(base = 5, ratePerSec = 1.0, accelerate = true)).isEqualTo(5)
    }
    @Test fun `fast scrolling scales up`() {
        // 16 events/sec => clamp(16-4, 0, 12) = 12; 1 + 12*0.5 = 7 * 5 = 35
        assertThat(WheelInput.effectiveStep(base = 5, ratePerSec = 16.0, accelerate = true)).isEqualTo(35)
    }
    @Test fun `acceleration disabled returns base`() {
        assertThat(WheelInput.effectiveStep(base = 5, ratePerSec = 50.0, accelerate = false)).isEqualTo(5)
    }
    @Test fun `event direction respects invert`() {
        assertThat(WheelInput.applyDirection(WheelEvent.Direction.UP, invert = false)).isEqualTo(+1)
        assertThat(WheelInput.applyDirection(WheelEvent.Direction.DOWN, invert = false)).isEqualTo(-1)
        assertThat(WheelInput.applyDirection(WheelEvent.Direction.UP, invert = true)).isEqualTo(-1)
    }
}
