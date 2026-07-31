package com.eventpulse.mesh

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventPulseRateLimiterTest {

    @Before
    fun setUp() {
        // Isolate tests: the rate limiter is a singleton, so state must be cleared
        // before each test or messages from previous tests bleed in.
        EventPulseRateLimiter.clear()
    }

    @Test
    fun `message over 280 chars is truncated`() {
        val longBody = "A".repeat(300)
        val result = EventPulseRateLimiter.validateOutboundMessage(longBody)
        assertEquals(280, result.length)
    }

    @Test
    fun `short message passes through unchanged`() {
        val result = EventPulseRateLimiter.validateOutboundMessage("Hello!")
        assertEquals("Hello!", result)
    }

    @Test
    fun `duplicate message from same sender is rejected`() {
        val first = EventPulseRateLimiter.shouldAcceptIncoming("peer1", "Hello")
        assertTrue("First message should be accepted", first)

        val second = EventPulseRateLimiter.shouldAcceptIncoming("peer1", "Hello")
        assertFalse("Duplicate message should be rejected", second)
    }

    @Test
    fun `second message from same sender within rate-limit window is rejected`() {
        val first = EventPulseRateLimiter.shouldAcceptIncoming("peer1", "First message")
        assertTrue("First message should be accepted", first)

        // Spec: max 1 message per 2 seconds per sender ID.
        val second = EventPulseRateLimiter.shouldAcceptIncoming("peer1", "Second message")
        assertFalse("Second message within 2s window should be rate-limited", second)
    }

    @Test
    fun `different sender can send same content`() {
        EventPulseRateLimiter.shouldAcceptIncoming("peer1", "Same text")
        val fromPeer2 = EventPulseRateLimiter.shouldAcceptIncoming("peer2", "Same text")
        assertTrue("Different sender with same content should be accepted", fromPeer2)
    }

    @Test
    fun `clear resets all state`() {
        EventPulseRateLimiter.shouldAcceptIncoming("peer1", "Test")
        EventPulseRateLimiter.clear()
        val afterClear = EventPulseRateLimiter.shouldAcceptIncoming("peer1", "Test")
        assertTrue("After clear, duplicate should be accepted", afterClear)
    }
}
