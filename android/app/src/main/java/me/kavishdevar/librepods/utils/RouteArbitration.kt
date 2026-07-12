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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.kavishdevar.librepods.bluetooth.AACPManager

/**
 * Pure (Android-free) decision logic for AirPods audio-route arbitration.
 *
 * Evidence base (capture xiaomi_interrupt_mac_auto_takeover_20260712_125121):
 * the AirPods firmware terminated Android's ACL (HCI reason 0x13) ~26 s after a
 * successful local takeover, while Android still held OWNS_CONNECTION=1 and an
 * active A2DP stream. The only advance warning on the Android side was a
 * CONNECTED_DEVICES update in which our own entry's connection status byte
 * (info1) dropped to 0, ~160 ms before the link was cut. No hijack /
 * SetOwnershipToFalse / OWNS_CONNECTION=0 arrived from any peer.
 *
 * This module centralizes:
 *  - what the periodic keepalive tick must send (score + streaming assertion),
 *  - when a CONNECTED_DEVICES update is a demotion that warrants an immediate,
 *    event-driven ownership reassertion,
 *  - how to classify an AACP session loss (remote takeover vs firmware
 *    eviction vs expected/idle) so recovery never fights a legitimate
 *    Mac/iPhone takeover,
 * plus small primitives for generation-scoped async work.
 */
object RouteArbitration {

    /** Remote-takeover signals older than this no longer explain a link loss. */
    const val REMOTE_TAKEOVER_SIGNAL_WINDOW_MS = 10_000L

    data class KeepaliveAction(
        val score: AACPManager.Companion.TipiScore,
        /**
         * When true the caller must also refresh the arbitration-level
         * streaming state (Media Information / HostStreamingState=YES) so that
         * neither the firmware nor the interrupted Apple host ever sees our
         * ownership as stale while we are genuinely the active audio source.
         */
        val assertStreaming: Boolean
    )

    fun isLocalAudioSource(
        audioSourceMac: String?,
        audioSourceType: AACPManager.Companion.AudioSourceType?,
        localMac: String
    ): Boolean {
        if (audioSourceMac == null || audioSourceType == null) return false
        if (localMac.isBlank()) return false
        return audioSourceType != AACPManager.Companion.AudioSourceType.NONE &&
                audioSourceMac.equals(localMac, ignoreCase = true)
    }

    /**
     * One keepalive tick. HIGH is only ever sent while this device is the
     * confirmed audio source AND local media is actually audible; everything
     * else converges to LOW so a paused phone never blocks a remote takeover.
     */
    fun keepaliveAction(
        audioSourceMac: String?,
        audioSourceType: AACPManager.Companion.AudioSourceType?,
        localMac: String,
        musicActive: Boolean,
        ownsConnection: Boolean
    ): KeepaliveAction {
        val localIsSource = isLocalAudioSource(audioSourceMac, audioSourceType, localMac)
        val streaming = localIsSource && musicActive
        return KeepaliveAction(
            score = if (streaming) {
                AACPManager.Companion.TipiScore.HIGH
            } else {
                AACPManager.Companion.TipiScore.LOW
            },
            assertStreaming = streaming && ownsConnection
        )
    }

    /**
     * True when a CONNECTED_DEVICES update reports our own entry with
     * connection-status byte 0 — the firmware-side demotion observed ~160 ms
     * before the remote-initiated ACL drop. An absent entry is NOT treated as
     * a demotion (partial lists must not trigger reassertion).
     */
    fun isSelfDemoted(
        devices: List<AACPManager.Companion.ConnectedDevice>,
        selfMac: String
    ): Boolean {
        if (selfMac.isBlank()) return false
        val self = devices.find { it.mac.equals(selfMac, ignoreCase = true) } ?: return false
        return self.info1 == 0.toByte()
    }

    /**
     * Event-driven defense: reassert ownership only when we are the rightful,
     * actively-streaming owner and the firmware just demoted us. Never fires
     * after a legitimate remote takeover (ownsConnection is false by then), so
     * it cannot create a takeover war or split-brain.
     */
    fun shouldReassertOwnership(
        selfDemoted: Boolean,
        ownsConnection: Boolean,
        musicActive: Boolean,
        localIsAudioSource: Boolean
    ): Boolean {
        return selfDemoted && ownsConnection && musicActive && localIsAudioSource
    }

    enum class DisconnectKind {
        /** Firmware/remote cut the link while we owned it and were streaming. */
        REMOTE_EVICTION_WHILE_STREAMING,

        /** Link loss shortly after an explicit remote takeover signal. */
        REMOTE_TAKEOVER,

        /** Link loss while idle / not owning — nothing to defend. */
        LINK_LOST_IDLE,

        /** Locally requested or expected teardown (user, moved-to-remote, ...). */
        LOCAL_OR_EXPECTED
    }

    /**
     * Classify how an AACP session ended.
     *
     * @param reason cleanup reason string ("eof", "read_error:...", "user_disconnect", ...)
     * @param ownedAtClose OWNS_CONNECTION state captured before state was cleared
     * @param musicActiveAtClose whether local media was audible at close time
     * @param msSinceRemoteTakeoverSignal elapsed ms since the last hijack /
     *        SetOwnershipToFalse / OWNS=0 / remote-audio-source signal, or null
     *        if none was ever received this session.
     */
    fun classifyDisconnect(
        reason: String,
        ownedAtClose: Boolean,
        musicActiveAtClose: Boolean,
        msSinceRemoteTakeoverSignal: Long?
    ): DisconnectKind {
        val linkLoss = reason == "eof" || reason.startsWith("read_error")
        if (!linkLoss) return DisconnectKind.LOCAL_OR_EXPECTED
        val remoteExplains = msSinceRemoteTakeoverSignal != null &&
                msSinceRemoteTakeoverSignal in 0 until REMOTE_TAKEOVER_SIGNAL_WINDOW_MS
        if (remoteExplains) return DisconnectKind.REMOTE_TAKEOVER
        return if (ownedAtClose && musicActiveAtClose) {
            DisconnectKind.REMOTE_EVICTION_WHILE_STREAMING
        } else {
            DisconnectKind.LINK_LOST_IDLE
        }
    }
}

/**
 * Monotonic generation counter for ownership/route epochs. Every delayed or
 * periodic route action must capture the generation at schedule time and
 * no-op if it is no longer current, so tasks from an old session or an old
 * ownership claim can never mutate the state of a newer one.
 */
class RouteGenerationTracker {
    private val counter = AtomicLong(0L)

    val current: Long
        get() = counter.get()

    fun advance(): Long = counter.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = counter.get() == generation

    /** Runs [block] only if [generation] is still current. Returns whether it ran. */
    inline fun runIfCurrent(generation: Long, block: () -> Unit): Boolean {
        if (!isCurrent(generation)) return false
        block()
        return true
    }
}

/**
 * Allows exactly one automatic recovery attempt per user-initiated playback
 * episode. Re-armed only by a genuine user playback action, so a firmware that
 * keeps evicting us can never drag the phone into an endless reconnect war
 * with another host.
 */
class EvictionRecoveryPolicy {
    private val armed = AtomicBoolean(true)

    fun onUserPlayback() {
        armed.set(true)
    }

    /** Claims the single recovery slot; false when already used. */
    fun tryClaimRecovery(): Boolean = armed.compareAndSet(true, false)

    val isArmed: Boolean
        get() = armed.get()
}
