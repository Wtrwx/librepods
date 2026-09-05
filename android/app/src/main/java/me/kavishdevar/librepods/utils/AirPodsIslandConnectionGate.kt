package me.kavishdevar.librepods.utils

/** Main-thread, per-device connection sessions. Battery updates alone never start a session. */
internal class AirPodsIslandConnectionGate(private val now: () -> Long) {
    private data class Session(val connectedAt: Long, var level: Int? = null, var consumed: Boolean = false)

    private val sessions = mutableMapOf<String, Session>()
    private val lastShown = mutableMapOf<String, Long>()

    fun connected(address: String): Boolean {
        if (address in sessions) return false
        val time = now()
        lastShown.entries.removeAll { time - it.value >= RECONNECT_COOLDOWN_MS }
        // A rapid disconnect/reconnect must not turn battery updates into repeated popups.
        sessions[address] = Session(time, consumed = address in lastShown)
        return true
    }

    fun battery(address: String, level: Int) {
        if (level in 0..100) sessions[address]?.level = level
    }

    fun takeReadyBattery(address: String): Int? {
        val session = sessions[address] ?: return null
        val elapsed = now() - session.connectedAt
        if (session.consumed || elapsed !in INITIAL_DELAY_MS..BATTERY_WAIT_MS) return null
        val level = session.level ?: return null
        session.consumed = true
        lastShown[address] = now()
        return level
    }

    fun disconnected(address: String) {
        sessions.remove(address)
    }

    fun clear() {
        sessions.clear()
    }

    companion object {
        const val INITIAL_DELAY_MS = 1_000L
        const val BATTERY_WAIT_MS = 15_000L
        const val RECONNECT_COOLDOWN_MS = 30_000L
    }
}
