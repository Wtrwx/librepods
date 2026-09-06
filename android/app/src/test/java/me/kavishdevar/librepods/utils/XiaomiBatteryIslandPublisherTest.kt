package me.kavishdevar.librepods.utils

import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiBatteryIslandPublisherTest {
    @Test fun usesLowerConnectedEarLevel() {
        assertEquals(76, XiaomiBatteryIslandPublisher.unifiedLevel(listOf(
            Battery(BatteryComponent.LEFT, 83, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 76, BatteryStatus.CHARGING),
        )))
    }

    @Test fun usesOnlyConnectedEar() {
        assertEquals(83, XiaomiBatteryIslandPublisher.unifiedLevel(listOf(
            Battery(BatteryComponent.LEFT, 83, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 71, BatteryStatus.DISCONNECTED),
        )))
    }

    @Test fun rejectsMissingOrInvalidEarLevels() {
        assertNull(XiaomiBatteryIslandPublisher.unifiedLevel(listOf(
            Battery(BatteryComponent.LEFT, -1, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 101, BatteryStatus.CHARGING),
            Battery(BatteryComponent.CASE, 60, BatteryStatus.NOT_CHARGING),
        )))
    }
}
