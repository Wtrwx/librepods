/* LibrePods contributors, GPL-3.0-or-later. */
package me.kavishdevar.librepods.services

/** Invalidates asynchronous route work when another host takes over or the socket closes. */
internal class SmartRoutingState(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object { const val CLAIM_TIMEOUT_MS = 5_000L }
    enum class Owner { UNKNOWN, LOCAL_PENDING, LOCAL, REMOTE }

    @Volatile var owner = Owner.UNKNOWN
        private set
    @Volatile var generation = 0L
        private set
    private var claimDeadline = 0L
    private var resumeMedia = false

    @Synchronized fun requestLocal(resumeMedia: Boolean = false): Long {
        generation++
        owner = Owner.LOCAL_PENDING
        claimDeadline = now() + CLAIM_TIMEOUT_MS
        this.resumeMedia = resumeMedia
        return generation
    }

    @Synchronized fun remote(explicitHandoff: Boolean = true): Long? {
        // An unchanged Mac playback report can arrive while our hijack is still in flight.
        if (!explicitHandoff && owner == Owner.LOCAL_PENDING && now() < claimDeadline) return null
        if (owner != Owner.REMOTE) generation++
        owner = Owner.REMOTE
        resumeMedia = false
        return generation
    }

    /** A late local source/ownership packet must not undo a newer remote handoff. */
    @Synchronized fun confirmLocal(): Boolean {
        if (owner == Owner.REMOTE) return false
        owner = Owner.LOCAL
        return true
    }

    /** The actual active audio source also covers routing through Android's system picker. */
    @Synchronized fun confirmLocalSource() {
        if (owner == Owner.REMOTE) generation++
        owner = Owner.LOCAL
    }

    @Synchronized fun hasResumeRequest(): Boolean = resumeMedia && now() < claimDeadline

    @Synchronized fun consumeResume(token: Long, profileActive: Boolean): Boolean {
        if (token != generation || owner != Owner.LOCAL || !profileActive || !hasResumeRequest()) return false
        resumeMedia = false
        return true
    }

    @Synchronized fun isPending(token: Long): Boolean = token == generation && owner == Owner.LOCAL_PENDING

    @Synchronized fun reset() {
        generation++
        owner = Owner.UNKNOWN
        resumeMedia = false
    }

    @Synchronized fun allowsLocal(token: Long, allowUnknown: Boolean = false): Boolean =
        token == generation && (owner == Owner.LOCAL || owner == Owner.LOCAL_PENDING ||
            (allowUnknown && owner == Owner.UNKNOWN))

    @Synchronized fun allowsRemote(token: Long): Boolean =
        token == generation && owner == Owner.REMOTE
}
