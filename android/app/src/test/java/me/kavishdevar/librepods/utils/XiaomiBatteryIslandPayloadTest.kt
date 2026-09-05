package me.kavishdevar.librepods.utils

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XiaomiBatteryIslandPayloadTest {
    @Test fun payloadUsesDocumentedLargeAndSmallIslandComponents() {
        val params = JSONObject(XiaomiBatteryIslandPayload.build("AirPods Pro", 83)).getJSONObject("param_v2")
        assertEquals("bluetooth", params.getString("business"))
        assertTrue(params.getBoolean("islandFirstFloat"))
        assertFalse(params.getBoolean("updatable"))
        val island = params.getJSONObject("param_island")
        assertEquals(5, island.getInt("islandTimeout"))
        assertEquals(5_500L, XiaomiBatteryIslandPayload.NOTIFICATION_TIMEOUT_MS)
        val large = island.getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft")
        assertEquals("83%", large.getJSONObject("textInfo").getString("title"))
        assertEquals("AirPods Pro", large.getJSONObject("textInfo").getString("content"))
        assertEquals("83%", large.getJSONObject("miui.focus.paramtextInfo").getString("title"))
        val small = island.getJSONObject("smallIslandArea").getJSONObject("imageTextInfoRight")
        assertEquals(6, small.getInt("type"))
        assertEquals(4, small.getJSONObject("picInfo").getInt("type"))
        assertEquals("83", small.getJSONObject("textInfo").getString("title"))
        assertEquals(XiaomiBatteryIslandPayload.PICTURE_KEY, small.getJSONObject("picInfo").getString("pic"))
        assertEquals(XiaomiBatteryIslandPayload.PICTURE_KEY, large.getJSONObject("picInfo").getString("pic"))
    }

    @Test fun deviceNamesCannotBreakJsonOrInjectParameters() {
        val name = "小明的 \"AirPods\" \\ Pro\n🎧 {\"cancel\":true}"
        val params = JSONObject(XiaomiBatteryIslandPayload.build(name, 100)).getJSONObject("param_v2")
        assertEquals(name, params.getJSONObject("baseInfo").getString("title"))
        assertFalse(params.has("cancel"))
    }

    @Test fun zeroBatteryRemainsZero() {
        val params = JSONObject(XiaomiBatteryIslandPayload.build("AirPods", 0)).getJSONObject("param_v2")
        assertEquals("剩余电量 0%", params.getJSONObject("baseInfo").getString("content"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownBattery() {
        XiaomiBatteryIslandPayload.build("AirPods", -1)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOverchargedBattery() {
        XiaomiBatteryIslandPayload.build("AirPods", 101)
    }
}
