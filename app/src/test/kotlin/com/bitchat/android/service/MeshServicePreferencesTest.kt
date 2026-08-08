package com.bitchat.android.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the mesh security preferences. The app's security posture is "post-quantum by
 * default": both the hybrid ML-KEM toggle and the classic-downgrade block must default on so a
 * fresh install never negotiates a quantum-readable session.
 */
@RunWith(RobolectricTestRunner::class)
class MeshServicePreferencesTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `post-quantum handshakes default to enabled`() {
        MeshServicePreferences.init(context)
        assertTrue(MeshServicePreferences.isPostQuantumEnabled(true))
    }

    @Test
    fun `classic handshake block defaults to enabled`() {
        MeshServicePreferences.init(context)
        assertTrue(MeshServicePreferences.isClassicHandshakeBlocked(true))
    }

    @Test
    fun `preferences persist updates`() {
        MeshServicePreferences.init(context)
        MeshServicePreferences.setClassicHandshakeBlocked(false)
        assertFalse(MeshServicePreferences.isClassicHandshakeBlocked(true))
        MeshServicePreferences.setClassicHandshakeBlocked(true)
        assertTrue(MeshServicePreferences.isClassicHandshakeBlocked(true))
    }
}
