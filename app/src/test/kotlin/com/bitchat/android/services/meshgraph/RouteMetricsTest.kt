package com.bitchat.android.services.meshgraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RouteMetricsTest {

    @Before
    fun setUp() {
        RouteMetrics.reset()
    }

    @Test
    fun `relay outcomes are tracked per transport`() {
        RouteMetrics.recordRelay("BLE", true)
        RouteMetrics.recordRelay("BLE", true)
        RouteMetrics.recordRelay("BLE", false)
        RouteMetrics.recordRelay("WIFI", true)

        val snapshot = RouteMetrics.flush()
        assertEquals(2, snapshot.relaySuccessByTransport["BLE"])
        assertEquals(1, snapshot.relayFailureByTransport["BLE"])
        assertEquals(1, snapshot.relaySuccessByTransport["WIFI"])
        assertNull(snapshot.relayFailureByTransport["WIFI"])
    }

    @Test
    fun `hop histogram and path changes only advance on real changes`() {
        // First computation records the 2-hop profile (A→B→C = two edges).
        RouteMetrics.recordPath("A", "C", listOf("A", "B", "C"))
        // Repeated recomputation of the same path must not pollute counters.
        RouteMetrics.recordPath("A", "C", listOf("A", "B", "C"))
        // A genuinely different path advances the change counter and 1-hop profile.
        RouteMetrics.recordPath("A", "C", listOf("A", "C"))

        val snapshot = RouteMetrics.flush()
        assertEquals(1, snapshot.pathChanges)
        assertEquals(1, snapshot.hopCountHistogram[2])
        assertEquals(1, snapshot.hopCountHistogram[1])
        assertEquals(1, snapshot.routesKnown)
    }

    @Test
    fun `route loss increments changes and drops routesKnown without histogram`() {
        RouteMetrics.recordPath("A", "C", listOf("A", "B", "C"))
        RouteMetrics.recordPath("A", "C", null)

        val snapshot = RouteMetrics.flush()
        assertEquals(1, snapshot.pathChanges)
        assertEquals(0, snapshot.routesKnown)
        assertEquals(1, snapshot.hopCountHistogram[2])
    }

    @Test
    fun `drops and fallback floods accumulate`() {
        RouteMetrics.recordRoutedDrop()
        RouteMetrics.recordRoutedDrop()
        RouteMetrics.recordFallbackFlood()

        val snapshot = RouteMetrics.flush()
        assertEquals(2, snapshot.routedDrops)
        assertEquals(1, snapshot.fallbackFloods)
    }

    @Test
    fun `reset clears all counters`() {
        RouteMetrics.recordRelay("BLE", true)
        RouteMetrics.recordPath("A", "C", listOf("A", "B", "C"))
        RouteMetrics.recordRoutedDrop()
        RouteMetrics.recordFallbackFlood()

        RouteMetrics.reset()

        val snapshot = RouteMetrics.metrics.value
        assertEquals(emptyMap<String, Int>(), snapshot.relaySuccessByTransport)
        assertEquals(emptyMap<String, Int>(), snapshot.relayFailureByTransport)
        assertEquals(0, snapshot.routedDrops)
        assertEquals(0, snapshot.fallbackFloods)
        assertEquals(0, snapshot.pathChanges)
        assertEquals(emptyMap<Int, Int>(), snapshot.hopCountHistogram)
        assertEquals(0, snapshot.routesKnown)
    }

    @Test
    fun `flush reflects a burst of coalesced events`() {
        // A rapid burst of relay events all land on flush (coalescing loses nothing).
        repeat(50) { i ->
            RouteMetrics.recordRelay(if (i % 2 == 0) "BLE" else "WIFI", i % 3 != 0)
        }
        val snapshot = RouteMetrics.flush()
        assertEquals(25, (snapshot.relaySuccessByTransport["BLE"] ?: 0) + (snapshot.relayFailureByTransport["BLE"] ?: 0))
        assertEquals(25, (snapshot.relaySuccessByTransport["WIFI"] ?: 0) + (snapshot.relayFailureByTransport["WIFI"] ?: 0))
        // Even i -> BLE, odd i -> WIFI; success is i % 3 != 0.
        // BLE (25 evens): 9 multiples of 3 -> 16 success, 9 failure.
        // WIFI (25 odds): 8 multiples of 3 -> 17 success, 8 failure.
        assertEquals(16, snapshot.relaySuccessByTransport["BLE"])
        assertEquals(9, snapshot.relayFailureByTransport["BLE"])
        assertEquals(17, snapshot.relaySuccessByTransport["WIFI"])
        assertEquals(8, snapshot.relayFailureByTransport["WIFI"])
    }

    @Test
    fun `debug summary includes transport rows and counters`() {
        RouteMetrics.recordRelay("BLE", true)
        RouteMetrics.recordPath("A", "C", listOf("A", "B", "C"))
        RouteMetrics.recordRoutedDrop()
        RouteMetrics.flush()

        val summary = RouteMetrics.debugSummary()
        assertTrue("expected BLE row", summary.contains("BLE: success=1 failure=0"))
        assertTrue("expected hop profile", summary.contains("2-hop x 1"))
        assertTrue("expected routes planned", summary.contains("Routes planned: 1"))
        assertTrue("expected drops", summary.contains("Routed drops: 1"))
    }
}
