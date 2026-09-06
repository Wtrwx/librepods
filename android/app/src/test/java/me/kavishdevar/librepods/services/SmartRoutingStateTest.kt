package me.kavishdevar.librepods.services

import org.junit.Assert.*
import org.junit.Test

class SmartRoutingStateTest {
    @Test fun remoteHandoffInvalidatesOldLocalWorkWithoutATimeoutReclaim() {
        var now = 0L
        val state = SmartRoutingState { now }
        val oldClaim = state.requestLocal(resumeMedia = true)
        state.confirmLocal()
        val handoff = state.remote()!!
        now = 60_000
        assertFalse(state.allowsLocal(oldClaim))
        assertTrue(state.allowsRemote(handoff))
        assertFalse(state.confirmLocal()) // delayed ownership ACK cannot reclaim by itself
        assertFalse(state.consumeResume(oldClaim, true))
    }

    @Test fun manualTakeoverSurvivesUnchangedMacTelemetryButNotANewHijack() {
        val state = SmartRoutingState { 0L }
        state.remote()
        val manual = state.requestLocal(resumeMedia = true)
        assertNull(state.remote(explicitHandoff = false))
        assertTrue(state.confirmLocal())
        assertTrue(state.allowsLocal(manual))
        state.remote(explicitHandoff = true)
        assertFalse(state.allowsLocal(manual))
        assertFalse(state.consumeResume(manual, true))
    }

    @Test fun playbackResumesOnceOnlyWhenBothOwnershipAndActiveProfileAreReady() {
        val state = SmartRoutingState { 0L }
        val claim = state.requestLocal(resumeMedia = true)
        assertFalse(state.consumeResume(claim, true)) // A2DP connected before ownership
        state.confirmLocal()
        assertFalse(state.consumeResume(claim, false)) // ownership before the active profile
        assertTrue(state.consumeResume(claim, true))
        assertFalse(state.consumeResume(claim, true)) // repeated source/profile/ACK notifications
    }

    @Test fun nativeSystemPickerSourceInvalidatesPendingRemoteRelease() {
        val state = SmartRoutingState()
        val remote = state.remote()!!
        state.confirmLocalSource()
        assertFalse(state.allowsRemote(remote))
        assertTrue(state.allowsLocal(state.generation))
        assertFalse(state.consumeResume(state.generation, true)) // no unsolicited PLAY
    }

    @Test fun coldConnectAndLateAckCanResumeButExpiredIntentCannot() {
        var now = 0L
        val state = SmartRoutingState { now }
        val claim = state.requestLocal(resumeMedia = true)
        now = 2_000
        state.confirmLocal()
        assertTrue(state.consumeResume(claim, true))
        val next = state.requestLocal(resumeMedia = true)
        now += SmartRoutingState.CLAIM_TIMEOUT_MS
        state.confirmLocal()
        assertFalse(state.consumeResume(next, true))
    }

    @Test fun failedClaimAndDisconnectInvalidateResumeAndProfileCallbacks() {
        var now = 0L
        val state = SmartRoutingState { now }
        val claim = state.requestLocal(resumeMedia = true)
        now = SmartRoutingState.CLAIM_TIMEOUT_MS
        assertNotNull(state.remote(explicitHandoff = false))
        assertFalse(state.allowsLocal(claim))
        val reconnect = state.requestLocal(resumeMedia = true)
        state.reset()
        assertFalse(state.allowsLocal(reconnect, allowUnknown = true))
        assertFalse(state.consumeResume(reconnect, true))
        assertFalse(state.allowsLocal(state.generation))
        assertTrue(state.allowsLocal(state.generation, allowUnknown = true))
    }
}
