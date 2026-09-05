package me.kavishdevar.librepods.utils

import org.json.JSONObject

/** HyperOS 3 template library: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131 */
internal object XiaomiBatteryIslandPayload {
    const val PICTURE_KEY = "miui.focus.pic_librepods_battery"
    const val ISLAND_TIMEOUT_SECONDS = 5
    const val NOTIFICATION_TIMEOUT_MS = 5_500L

    fun build(earName: String, battery: Int): String {
        require(battery in 0..100)
        val text = JSONObject()
            .put("frontTitle", "耳机")
            .put("title", "$battery%")
            .put("content", earName)
            .put("showHighlightColor", false)
        val large = JSONObject()
            .put("type", 1)
            .put("picInfo", picture(1))
            .put("textInfo", text)
            // The web guide uses this older name; the 2026 template PDF uses textInfo.
            .put("miui.focus.paramtextInfo", JSONObject(text.toString()).put("useHighLight", false))
        val small = JSONObject()
            .put("type", 6)
            .put("picInfo", picture(4))
            .put("textInfo", JSONObject().put("title", battery.toString()).put("showHighlightColor", false))
        return JSONObject().put("param_v2", JSONObject()
            .put("protocol", 1)
            .put("business", "bluetooth")
            .put("islandFirstFloat", true)
            .put("enableFloat", true)
            .put("updatable", false)
            .put("filterWhenNoPermission", true)
            .put("param_island", JSONObject()
                .put("islandProperty", 1)
                .put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
                .put("bigIslandArea", JSONObject().put("imageTextInfoLeft", large))
                .put("smallIslandArea", JSONObject().put("imageTextInfoRight", small)))
            .put("baseInfo", JSONObject()
                .put("title", earName)
                .put("content", "剩余电量 $battery%")
                .put("type", 2))).toString()
    }

    private fun picture(type: Int) = JSONObject().put("type", type).put("pic", PICTURE_KEY)
}
