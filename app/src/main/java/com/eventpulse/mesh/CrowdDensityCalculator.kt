package com.eventpulse.mesh

/**
 * Feature A: Local Crowd Density Indicator (RSSI-Based "Pulse")
 *
 * Calculates venue crowd density from BLE peer discovery data and RSSI values.
 * Exposes the density state for the live "Crowd Pulse Ring" UI indicator.
 */
object CrowdDensityCalculator {

    /**
     * Density levels returned by the venue crowd pulse calculation.
     */
    enum class VenueDensity(val label: String, val emoji: String) {
        LOW("Low", "🟢"),        // < 3 peers or weak signal
        MEDIUM("Medium", "🟡"),  // 3–6 peers
        PACKED("Packed", "🔴"),  // > 6 peers or strong signals
        UNKNOWN("—", "⚪")       // No data available
    }

    /**
     * RSSI threshold for considering a signal "strong."
     * RSSI values closer to 0 dBm indicate stronger signals.
     * -50 dBm or better is considered strong (close proximity).
     */
    private const val STRONG_RSSI_THRESHOLD = -50

    /**
     * Calculate the current venue density based on peer count and average RSSI.
     *
     * @param peerCount The number of active BLE peers discovered.
     * @param avgRssi The average RSSI across all active peers, or null if no data.
     * @return VenueDensity level with label and emoji indicator.
     */
    fun calculateVenueDensity(peerCount: Int, avgRssi: Int?): VenueDensity {
        if (peerCount == 0 || avgRssi == null) {
            return VenueDensity.UNKNOWN
        }

        return when {
            // PACKED: > 6 peers OR strong signals with at least 3 peers
            peerCount > 6 -> VenueDensity.PACKED
            avgRssi >= STRONG_RSSI_THRESHOLD && peerCount >= 3 -> VenueDensity.PACKED

            // MEDIUM: 3–6 peers
            peerCount in 3..6 -> VenueDensity.MEDIUM

            // LOW: < 3 peers or weak signal
            else -> VenueDensity.LOW
        }
    }

    /**
     * Compute the average RSSI from a map of peer ID to RSSI values.
     *
     * @param peerRssiMap Map of peer IDs to their latest RSSI values.
     * @return The average RSSI value, or null if the map is empty.
     */
    fun calculateAverageRssi(peerRssiMap: Map<String, Int>): Int? {
        if (peerRssiMap.isEmpty()) return null
        val sum = peerRssiMap.values.sum()
        return sum / peerRssiMap.size
    }

    /**
     * Get a confidence-weighted "pulse" color intensity (0.0 to 1.0)
     * based on how reliable the density reading is.
     * More peers and stronger signals = higher confidence.
     */
    fun pulseIntensity(peerCount: Int, avgRssi: Int?): Float {
        if (peerCount == 0 || avgRssi == null) return 0f
        val peerFactor = (peerCount.toFloat() / 10f).coerceIn(0f, 1f)
        val signalFactor = ((avgRssi + 100) / 50f).coerceIn(0f, 1f)
        return (peerFactor * 0.6f + signalFactor * 0.4f).coerceIn(0f, 1f)
    }
}
