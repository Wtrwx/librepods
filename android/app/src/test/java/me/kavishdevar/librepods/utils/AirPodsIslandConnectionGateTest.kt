package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class AirPodsIslandConnectionGateTest {
    private var time = 0L
    private val gate = AirPodsIslandConnectionGate { time }

    @Test fun waitsForConnectionToSettleAndShowsOnlyOnce() {
        assertTrue(gate.connected("A"))
        gate.battery("A", 83)
        assertNull(gate.takeReadyBattery("A"))
        time = 999
        assertNull(gate.takeReadyBattery("A"))
        time = 1_000
        assertEquals(83, gate.takeReadyBattery("A"))
        time = 60_000
        gate.battery("A", 82)
        assertNull(gate.takeReadyBattery("A"))
    }

    @Test fun batteryBroadcastAloneCannotOpenAnIsland() {
        gate.battery("A", 83)
        time = 1_000
        assertNull(gate.takeReadyBattery("A"))
        gate.connected("A")
        time = 2_000
        assertNull(gate.takeReadyBattery("A"))
    }

    @Test fun duplicateConnectionDoesNotRestartDelayOrSession() {
        gate.connected("A")
        gate.battery("A", 50)
        time = 900
        assertFalse(gate.connected("A"))
        time = 1_000
        assertEquals(50, gate.takeReadyBattery("A"))
        assertFalse(gate.connected("A"))
        assertNull(gate.takeReadyBattery("A"))
    }

    @Test fun acceptsDelayedBatteryButDoesNotInventUnknownBattery() {
        gate.connected("A")
        time = 1_000
        for (invalid in listOf(-100, -1, 101, 255)) {
            gate.battery("A", invalid)
            assertNull(gate.takeReadyBattery("A"))
        }
        time = 14_000
        gate.battery("A", 0)
        assertEquals(0, gate.takeReadyBattery("A"))
    }

    @Test fun lateBatteryDoesNotCauseAnUnrelatedPopup() {
        gate.connected("A")
        time = 15_001
        gate.battery("A", 100)
        assertNull(gate.takeReadyBattery("A"))
    }

    @Test fun disconnectInvalidatesPendingBattery() {
        gate.connected("A")
        gate.battery("A", 83)
        gate.disconnected("A")
        time = 1_000
        assertNull(gate.takeReadyBattery("A"))
        gate.battery("A", 82)
        gate.connected("A")
        time = 2_000
        assertNull(gate.takeReadyBattery("A"))
        gate.battery("A", 80)
        assertEquals(80, gate.takeReadyBattery("A"))
    }

    @Test fun reconnectCooldownIsPerDeviceAndNotGlobal() {
        gate.connected("A")
        gate.battery("A", 83)
        time = 1_000
        assertEquals(83, gate.takeReadyBattery("A"))
        gate.disconnected("A")
        gate.connected("A")
        gate.battery("A", 82)
        gate.connected("B")
        gate.battery("B", 100)
        time = 2_000
        assertNull(gate.takeReadyBattery("A"))
        assertEquals(100, gate.takeReadyBattery("B"))
        gate.disconnected("A")
        time = 31_000
        gate.connected("A")
        gate.battery("A", 81)
        time = 32_000
        assertEquals(81, gate.takeReadyBattery("A"))
    }

    @Test fun bluetoothOffInvalidatesEveryPendingSession() {
        gate.connected("A")
        gate.connected("B")
        gate.battery("A", 10)
        gate.battery("B", 20)
        gate.clear()
        time = 1_000
        assertNull(gate.takeReadyBattery("A"))
        assertNull(gate.takeReadyBattery("B"))
    }

    @Test fun unknownUpdateDoesNotEraseValidBattery() {
        gate.connected("A")
        gate.battery("A", 83)
        gate.battery("A", -1)
        time = 1_000
        assertEquals(83, gate.takeReadyBattery("A"))
    }
}
