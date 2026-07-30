package com.eventpulse.mesh

import org.junit.Assert.*
import org.junit.Test

class EventPulseRateLimiterTest {

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
    fun `different message from same sender is accepted`() {
        EventPulseRateLimiter.shouldAcceptIncoming("peer1", "First message")
        val second = EventPulseRateLimiter.shouldAcceptIncoming("peer1", "Second message")
        assertTrue("Different message should be accepted", second)
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
