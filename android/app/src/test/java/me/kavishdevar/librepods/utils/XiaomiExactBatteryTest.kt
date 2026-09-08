package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class XiaomiExactBatteryTest {
    @Test fun preservesModelWearChargingAndConnectionFields() {
        val original = arrayOf<String?>("true", "90", "false", "90", "50", "false", "true", "false", "2720", "true")
        val result = XiaomiExactBattery("AA", true, 93, 94, 47).overlay(original)
        assertArrayEquals(arrayOf("true", "93", "false", "94", "47", "false", "true", "false", "2720", "true"), result)
        assertEquals("90", original[1])
        assertNotSame(original, result)
    }

    @Test fun unknownBatteryPreservesNativeValueButZeroIsValid() {
        val original = Array<String?>(10) { "native-$it" }
        val battery = XiaomiExactBattery("AA", true, -1, 255, 0)
        val result = battery.overlay(original)
        assertEquals("native-1", result[1])
        assertEquals("native-3", result[3])
        assertEquals("0", result[4])
        assertEquals(mapOf("boxBattery" to "0"), battery.fields())
    }

    @Test fun neverOverlaysAnUnidentifiedDifferentOrDisconnectedDevice() {
        val battery = XiaomiExactBattery("AA:BB", true, 93, 94, 47)
        assertTrue(battery.matches("aa:bb"))
        assertFalse(battery.matches(null))
        assertFalse(battery.matches(""))
        assertFalse(battery.matches("AA:CC"))
        assertFalse(battery.copy(connected = false).matches("AA:BB"))
    }

    @Test fun preservesUnknownArraySchemas() {
        for (size in listOf(0, 9, 11)) {
            val original = Array<String?>(size) { "native-$it" }
            assertSame(original, XiaomiExactBattery("AA", true, 1, 2, 3).overlay(original))
        }
    }
}
