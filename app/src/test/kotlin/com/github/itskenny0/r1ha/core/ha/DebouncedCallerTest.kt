package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedCallerTest {
    @Test fun `coalesces rapid submissions to last value`() = runTest {
        val seen = mutableListOf<Int>()
        val invocations = AtomicInteger()
        val caller = DebouncedCaller<String, Int>(
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            debounceMillis = 120,
        ) { key, value ->
            invocations.incrementAndGet()
            seen += value
        }
        caller.submit("light.k", 10)
        caller.submit("light.k", 20)
        caller.submit("light.k", 30)
        advanceTimeBy(50)
        caller.submit("light.k", 40)
        advanceTimeBy(200)
        assertThat(invocations.get()).isEqualTo(1)
        assertThat(seen).containsExactly(40)
    }
    @Test fun `different keys debounce independently`() = runTest {
        val seen = mutableListOf<Pair<String, Int>>()
        val caller = DebouncedCaller<String, Int>(
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            debounceMillis = 100,
        ) { key, v -> seen += key to v }
        caller.submit("a", 1)
        caller.submit("b", 2)
        advanceTimeBy(150)
        assertThat(seen).containsExactly("a" to 1, "b" to 2)
    }
}
