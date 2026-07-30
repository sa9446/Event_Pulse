package com.eventpulse.mesh

import org.junit.Assert.*
import org.junit.Test

class EventPulsePacketTest {

    @Test
    fun `round-trip serialization preserves all fields`() {
        val original = EventPulsePayload(
            sender = "Attendee42",
            channel = "announcements",
            type = "CHAT",
            timestamp = 1722384000L,
            body = "Hello from EventPulse!"
        )
        val bytes = EventPulsePayload.toByteArray(original)
        assertNotNull("Serialization should produce bytes", bytes)
        assertTrue("Bytes should be valid JSON", EventPulsePayload.isEventPulsePayload(bytes!!))

        val parsed = EventPulsePayload.fromByteArray(bytes)
        assertNotNull("Deserialization should succeed", parsed)
        assertEquals("Sender should match", original.sender, parsed!!.sender)
        assertEquals("Channel should match", original.channel, parsed.channel)
        assertEquals("Type should match", original.type, parsed.type)
        assertEquals("Body should match", original.body, parsed.body)
    }

    @Test
    fun `truncation reduces oversized payloads`() {
        val longBody = "A".repeat(1000)
        val payload = EventPulsePayload(
            sender = "Test",
            channel = "general",
            type = "CHAT",
            body = longBody
        )
        val bytes = EventPulsePayload.toByteArray(payload)
        assertNotNull("Even long messages should serialize (truncated)", bytes)
        assertTrue("Serialized should be under 512 bytes", bytes!!.size <= 512)
    }

    @Test
    fun `invalid JSON returns null`() {
        val result = EventPulsePayload.fromByteArray("not json".toByteArray())
        assertNull("Invalid JSON should return null", result)
    }

    @Test
    fun `isEventPulsePayload detects valid JSON`() {
        val json = """{"sender":"Test","channel":"qa","type":"CHAT","timestamp":100,"body":"hello"}"""
        assertTrue(EventPulsePayload.isEventPulsePayload(json.toByteArray()))
    }

    @Test
    fun `isEventPulsePayload rejects plain text`() {
        assertFalse(EventPulsePayload.isEventPulsePayload("just a plain message".toByteArray()))
    }
}
