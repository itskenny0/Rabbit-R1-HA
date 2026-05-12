package com.github.itskenny0.r1ha.core.input

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Subscription gap test: emits a handful of events with no subscriber present, then attaches
     * a subscriber and verifies it sees only future emissions — not the events emitted in the gap.
     * This is the contract that prevents wheel spins from off-screen replays clobbering CardStack
     * brightness when the user navigates back. If a future change inadvertently switches
     * SharedFlow to a replay-style configuration this test will catch it.
     */
    @Test fun `events emitted while no subscriber are not replayed`() = runTest {
        val wheel = WheelInput()
        // Emit 4 events with no subscriber.
        wheel.emit(WheelEvent.Direction.UP, now = 1)
        wheel.emit(WheelEvent.Direction.UP, now = 2)
        wheel.emit(WheelEvent.Direction.UP, now = 3)
        wheel.emit(WheelEvent.Direction.UP, now = 4)
        // Subscribe with a short timeout to fence against the replay-bug case (where these
        // would be redelivered).
        val replayed = withTimeoutOrNull(50) { wheel.events.first() }
        assertThat(replayed).isNull()
    }

    /** With a subscriber actively collecting, emit produces events the subscriber sees in order. */
    @Test fun `subscribed collector receives emitted events in order`() = runTest {
        val wheel = WheelInput()
        val collector = async {
            wheel.events.take(3).toList()
        }
        // Tiny delay so the collector has time to attach.
        delay(10)
        wheel.emit(WheelEvent.Direction.UP, now = 1)
        wheel.emit(WheelEvent.Direction.DOWN, now = 2)
        wheel.emit(WheelEvent.Direction.UP, now = 3)
        val seen = collector.await()
        assertThat(seen.map { it.direction }).containsExactly(
            WheelEvent.Direction.UP, WheelEvent.Direction.DOWN, WheelEvent.Direction.UP,
        ).inOrder()
    }
}
