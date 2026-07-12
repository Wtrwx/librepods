/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.AudioSourceType
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ConnectedDevice
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.TipiScore
import me.kavishdevar.librepods.utils.RouteArbitration.DisconnectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the event chain from the 2026-07-12 capture
 * (xiaomi_interrupt_mac_auto_takeover): Android hijacks a streaming Mac,
 * plays for a while, then the AirPods firmware demotes and evicts the link
 * (HCI 0x13) with no remote takeover signal, and the Mac auto-resumes.
 */
class RouteArbitrationTest {

    private val localMac = "A4:C7:88:6C:C4:73"
    private val macMac = "D0:11:E5:E1:2D:12"

    private fun device(mac: String, info1: Byte, info2: Byte = 0) =
        ConnectedDevice(mac, info1, info2, null)

    // ------------------------------------------------------------------
    // Scenario 1: Android takes over and keeps playing for over a minute.
    // Every keepalive tick (8 s apart) must keep asserting HIGH + streaming
    // for as long as we are the audio source, i.e. the assertion never decays
    // with time, only with state.
    // ------------------------------------------------------------------
    @Test
    fun `sustained local playback keeps asserting high score and streaming state`() {
        val ticksInNinetySeconds = 90_000L / 8_000L
        repeat(ticksInNinetySeconds.toInt() + 1) {
            val action = RouteArbitration.keepaliveAction(
                audioSourceMac = localMac,
                audioSourceType = AudioSourceType.MEDIA,
                localMac = localMac,
                musicActive = true,
                ownsConnection = true
            )
            assertEquals(TipiScore.HIGH, action.score)
            assertTrue("tick $it must keep the streaming assertion fresh", action.assertStreaming)
        }
    }

    @Test
    fun `audio source mac comparison is case insensitive`() {
        val action = RouteArbitration.keepaliveAction(
            audioSourceMac = localMac.lowercase(),
            audioSourceType = AudioSourceType.MEDIA,
            localMac = localMac,
            musicActive = true,
            ownsConnection = true
        )
        assertEquals(TipiScore.HIGH, action.score)
        assertTrue(action.assertStreaming)
    }

    @Test
    fun `firmware demotion while owning and streaming triggers reassertion`() {
        // The exact table from 12:52:22.227: self entry info1=0, Mac stays 2.
        val devices = listOf(
            device(localMac, info1 = 0, info2 = 6),
            device(macMac, info1 = 2, info2 = 0)
        )
        assertTrue(RouteArbitration.isSelfDemoted(devices, localMac))
        assertTrue(
            RouteArbitration.shouldReassertOwnership(
                selfDemoted = true,
                ownsConnection = true,
                musicActive = true,
                localIsAudioSource = true
            )
        )
    }

    @Test
    fun `healthy connected devices table does not trigger reassertion`() {
        // Table from 12:51:56.570 while we streamed happily.
        val devices = listOf(
            device(localMac, info1 = 2, info2 = 2),
            device(macMac, info1 = 2, info2 = 4)
        )
        assertFalse(RouteArbitration.isSelfDemoted(devices, localMac))
    }

    @Test
    fun `absent self entry is not treated as demotion`() {
        val devices = listOf(device(macMac, info1 = 2))
        assertFalse(RouteArbitration.isSelfDemoted(devices, localMac))
    }

    // ------------------------------------------------------------------
    // Scenario 2: the remote (Mac/iPhone) legitimately re-takes within a
    // minute. We must yield: no HIGH score, no reassertion, and a following
    // link loss must not be classified as an eviction (no reconnect war).
    // ------------------------------------------------------------------
    @Test
    fun `after remote takeover keepalive converges to low and never asserts`() {
        val action = RouteArbitration.keepaliveAction(
            audioSourceMac = macMac,
            audioSourceType = AudioSourceType.MEDIA,
            localMac = localMac,
            musicActive = false,
            ownsConnection = false
        )
        assertEquals(TipiScore.LOW, action.score)
        assertFalse(action.assertStreaming)
    }

    @Test
    fun `no reassertion once ownership is gone even if self entry is demoted`() {
        assertFalse(
            RouteArbitration.shouldReassertOwnership(
                selfDemoted = true,
                ownsConnection = false,
                musicActive = false,
                localIsAudioSource = false
            )
        )
    }

    @Test
    fun `link loss right after a remote takeover signal is not an eviction`() {
        val kind = RouteArbitration.classifyDisconnect(
            reason = "eof",
            ownedAtClose = true,
            musicActiveAtClose = true,
            msSinceRemoteTakeoverSignal = 1_200L
        )
        assertEquals(DisconnectKind.REMOTE_TAKEOVER, kind)
    }

    // ------------------------------------------------------------------
    // Scenario 3: genuine AACP EOF / ACL loss.
    // ------------------------------------------------------------------
    @Test
    fun `eof while owning and streaming with no remote signal is an eviction`() {
        // The 12:52:22.388 event: EOF, OWNS_CONNECTION=1, A2DP playing, and
        // the last remote signal (OWNS=0 push) was ~28 s earlier.
        val kind = RouteArbitration.classifyDisconnect(
            reason = "eof",
            ownedAtClose = true,
            musicActiveAtClose = true,
            msSinceRemoteTakeoverSignal = 28_000L
        )
        assertEquals(DisconnectKind.REMOTE_EVICTION_WHILE_STREAMING, kind)
    }

    @Test
    fun `eof with no remote signal ever seen is an eviction when streaming`() {
        val kind = RouteArbitration.classifyDisconnect(
            reason = "eof",
            ownedAtClose = true,
            musicActiveAtClose = true,
            msSinceRemoteTakeoverSignal = null
        )
        assertEquals(DisconnectKind.REMOTE_EVICTION_WHILE_STREAMING, kind)
    }

    @Test
    fun `eof while idle is not an eviction`() {
        val kind = RouteArbitration.classifyDisconnect(
            reason = "eof",
            ownedAtClose = false,
            musicActiveAtClose = false,
            msSinceRemoteTakeoverSignal = null
        )
        assertEquals(DisconnectKind.LINK_LOST_IDLE, kind)
    }

    @Test
    fun `read errors are treated like link loss`() {
        val kind = RouteArbitration.classifyDisconnect(
            reason = "read_error:IOException:bt socket closed",
            ownedAtClose = true,
            musicActiveAtClose = true,
            msSinceRemoteTakeoverSignal = null
        )
        assertEquals(DisconnectKind.REMOTE_EVICTION_WHILE_STREAMING, kind)
    }

    @Test
    fun `expected teardowns are never classified as eviction`() {
        for (reason in listOf("user_disconnect", "moved_to_remote", "service_destroyed")) {
            assertEquals(
                DisconnectKind.LOCAL_OR_EXPECTED,
                RouteArbitration.classifyDisconnect(
                    reason = reason,
                    ownedAtClose = true,
                    musicActiveAtClose = true,
                    msSinceRemoteTakeoverSignal = null
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Scenario 4: playback pauses locally — ownership/score must converge
    // instead of camping on HIGH.
    // ------------------------------------------------------------------
    @Test
    fun `paused local media drops score to low even while still audio source`() {
        val action = RouteArbitration.keepaliveAction(
            audioSourceMac = localMac,
            audioSourceType = AudioSourceType.MEDIA,
            localMac = localMac,
            musicActive = false,
            ownsConnection = true
        )
        assertEquals(TipiScore.LOW, action.score)
        assertFalse(action.assertStreaming)
    }

    @Test
    fun `audio source none never yields high score`() {
        val action = RouteArbitration.keepaliveAction(
            audioSourceMac = localMac,
            audioSourceType = AudioSourceType.NONE,
            localMac = localMac,
            musicActive = true,
            ownsConnection = true
        )
        assertEquals(TipiScore.LOW, action.score)
        assertFalse(action.assertStreaming)
    }

    @Test
    fun `no reassertion while paused`() {
        assertFalse(
            RouteArbitration.shouldReassertOwnership(
                selfDemoted = true,
                ownsConnection = true,
                musicActive = false,
                localIsAudioSource = true
            )
        )
    }

    // ------------------------------------------------------------------
    // Scenario 5: delayed tasks of an old session/claim must not touch the
    // state of a newer one.
    // ------------------------------------------------------------------
    @Test
    fun `stale generation task does not run after epoch advances`() {
        val tracker = RouteGenerationTracker()
        val oldGeneration = tracker.current
        var oldRan = false

        tracker.advance() // e.g. remote hijack or session close happens

        val ran = tracker.runIfCurrent(oldGeneration) { oldRan = true }
        assertFalse(ran)
        assertFalse(oldRan)
    }

    @Test
    fun `current generation task runs and new session tasks are unaffected by old epochs`() {
        val tracker = RouteGenerationTracker()
        tracker.advance() // old session dies
        val newGeneration = tracker.advance() // new claim
        var newRan = false

        assertTrue(tracker.runIfCurrent(newGeneration) { newRan = true })
        assertTrue(newRan)
    }

    @Test
    fun `each advance invalidates all earlier generations`() {
        val tracker = RouteGenerationTracker()
        val g1 = tracker.advance()
        val g2 = tracker.advance()
        assertFalse(tracker.isCurrent(g1))
        assertTrue(tracker.isCurrent(g2))
        val g3 = tracker.advance()
        assertFalse(tracker.isCurrent(g2))
        assertTrue(tracker.isCurrent(g3))
    }

    // ------------------------------------------------------------------
    // Eviction recovery is single-shot per user playback episode: no
    // reconnect war when the firmware keeps evicting us.
    // ------------------------------------------------------------------
    @Test
    fun `recovery slot is single use until user plays again`() {
        val policy = EvictionRecoveryPolicy()
        assertTrue(policy.tryClaimRecovery())
        assertFalse("second eviction must not recover again", policy.tryClaimRecovery())
        assertFalse(policy.tryClaimRecovery())

        policy.onUserPlayback()
        assertTrue(policy.tryClaimRecovery())
        assertFalse(policy.tryClaimRecovery())
    }
}
