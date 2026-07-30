package com.eventpulse.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class CrowdDensityCalculatorTest {

    @Test
    fun `no peers returns UNKNOWN`() {
        val density = CrowdDensityCalculator.calculateVenueDensity(0, null)
        assertEquals(CrowdDensityCalculator.VenueDensity.UNKNOWN, density)
    }

    @Test
    fun `single peer weak signal returns LOW`() {
        val density = CrowdDensityCalculator.calculateVenueDensity(1, -80)
        assertEquals(CrowdDensityCalculator.VenueDensity.LOW, density)
    }

    @Test
    fun `two peers weak signal returns LOW`() {
        val density = CrowdDensityCalculator.calculateVenueDensity(2, -75)
        assertEquals(CrowdDensityCalculator.VenueDensity.LOW, density)
    }

    @Test
    fun `three peers weak signal returns MEDIUM`() {
        val density = CrowdDensityCalculator.calculateVenueDensity(3, -70)
        assertEquals(CrowdDensityCalculator.VenueDensity.MEDIUM, density)
    }

    @Test
    fun `five peers returns MEDIUM`() {
        val density = CrowdDensityCalculator.calculateVenueDensity(5, -65)
        assertEquals(CrowdDensityCalculator.VenueDensity.MEDIUM, density)
    }

    @Test
    fun `seven peers returns PACKED`() {
        val density = CrowdDensityCalculator.calculateVenueDensity(7, -60)
        assertEquals(CrowdDensityCalculator.VenueDensity.PACKED, density)
    }

    @Test
    fun `three peers with strong signal returns PACKED`() {
        val density = CrowdDensityCalculator.calculateVenueDensity(3, -45)
        assertEquals(CrowdDensityCalculator.VenueDensity.PACKED, density)
    }

    @Test
    fun `calculateAverageRssi empty map returns null`() {
        val avg = CrowdDensityCalculator.calculateAverageRssi(emptyMap())
        assertEquals(null, avg)
    }

    @Test
    fun `calculateAverageRssi returns correct average`() {
        val avg = CrowdDensityCalculator.calculateAverageRssi(mapOf(
            "peer1" to -60,
            "peer2" to -70,
            "peer3" to -80
        ))
        assertEquals(-70, avg)
    }

    @Test
    fun `pulseIntensity zero peers returns zero`() {
        val intensity = CrowdDensityCalculator.pulseIntensity(0, null)
        assertEquals(0f, intensity, 0.01f)
    }
}
