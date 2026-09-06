package me.kavishdevar.librepods.utils

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XiaomiBatteryIslandPayloadTest {
    @Test fun payloadUsesDocumentedLargeAndSmallIslandComponents() {
        val params = JSONObject(XiaomiBatteryIslandPayload.build("AirPods Pro", 83)).getJSONObject("param_v2")
        assertEquals(3, params.getInt("protocol"))
        assertEquals("librepods_battery", params.getString("business"))
        assertEquals("83%", params.getString("ticker"))
        // An expanded card consumes the island's whole lifetime for background apps.
        assertFalse(params.getBoolean("islandFirstFloat"))
        assertFalse(params.getBoolean("enableFloat"))
        assertFalse(params.getBoolean("isShowNotification"))
        assertFalse(params.has("baseInfo"))
        assertFalse(params.getBoolean("updatable"))
        val island = params.getJSONObject("param_island")
        assertEquals(5, island.getInt("islandTimeout"))
        assertEquals(5_500L, XiaomiBatteryIslandPayload.NOTIFICATION_TIMEOUT_MS)
        val big = island.getJSONObject("bigIslandArea")
        val left = big.getJSONObject("imageTextInfoLeft")
        val right = big.getJSONObject("imageTextInfoRight")
        assertFalse(left.has("textInfo"))
        assertEquals("83%", right.getJSONObject("textInfo").getString("title"))
        assertEquals(XiaomiBatteryIslandPayload.PICTURE_KEY, left.getJSONObject("picInfo").getString("pic"))
        val small = island.getJSONObject("smallIslandArea").getJSONObject("combinePicInfo")
        assertEquals(XiaomiBatteryIslandPayload.PICTURE_KEY, small.getJSONObject("picInfo").getString("pic"))
        assertEquals(83, small.getJSONObject("progressInfo").getInt("progress"))
    }

    @Test fun deviceNamesCannotBreakJsonOrInjectParameters() {
        val name = "小明的 \"AirPods\" \\ Pro\n🎧 {\"cancel\":true}"
        val params = JSONObject(XiaomiBatteryIslandPayload.build(name, 100)).getJSONObject("param_v2")
        assertEquals("$name 电量 100%", params.getJSONObject("param_island").getString("appContentDescription"))
        assertFalse(params.has("cancel"))
    }

    @Test fun zeroBatteryRemainsZero() {
        val params = JSONObject(XiaomiBatteryIslandPayload.build("AirPods", 0)).getJSONObject("param_v2")
        assertEquals("0%", params.getString("ticker"))
        assertEquals(0, params.getJSONObject("param_island").getJSONObject("smallIslandArea")
            .getJSONObject("combinePicInfo").getJSONObject("progressInfo").getInt("progress"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownBattery() {
        XiaomiBatteryIslandPayload.build("AirPods", -1)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOverchargedBattery() {
        XiaomiBatteryIslandPayload.build("AirPods", 101)
    }
}
