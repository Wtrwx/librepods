package me.kavishdevar.librepods.utils

import org.json.JSONObject

/** HyperOS 3 template library: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131 */
internal object XiaomiBatteryIslandPayload {
    const val PICTURE_KEY = "miui.focus.pic_librepods_battery"
    const val ISLAND_TIMEOUT_SECONDS = 5
    const val NOTIFICATION_TIMEOUT_MS = 5_500L

    fun build(earName: String, battery: Int): String {
        require(battery in 0..100)
        val picture = picture(1)
        val left = JSONObject()
            .put("type", 1)
            .put("picInfo", picture)
        val right = JSONObject()
            .put("type", 2)
            .put("textInfo", JSONObject()
                .put("title", "$battery%")
                .put("showHighlightColor", true))
        val small = JSONObject()
            .put("combinePicInfo", JSONObject()
                .put("picInfo", picture(1))
                .put("progressInfo", JSONObject()
                    .put("progress", battery)
                    .put("colorReach", "#34C759")
                    .put("colorUnReach", "#40FFFFFF")
                    .put("isCCW", false)))
        return JSONObject().put("param_v2", JSONObject()
            .put("protocol", 3)
            .put("business", "librepods_battery")
            .put("ticker", "$battery%")
            // These control the expanded card, not whether the compact island appears.
            // With firstFloat=true, HyperOS spends our entire 5-second lifetime in the
            // expanded card when the app is in the background (AddEventCoordinator).
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("isShowNotification", false)
            .put("updatable", false)
            .put("notifyId", "me.kavishdevar.librepods$NOTIFICATION_ID")
            .put("param_island", JSONObject()
                .put("appContentDescription", "$earName 电量 $battery%")
                .put("islandProperty", 1)
                .put("islandPriority", 2)
                .put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
                .put("islandOrder", false)
                .put("dismissIsland", false)
                .put("maxSize", false)
                .put("needCloseAnimation", true)
                .put("bigIslandArea", JSONObject()
                    .put("imageTextInfoLeft", left)
                    .put("imageTextInfoRight", right))
                .put("smallIslandArea", small))
            // No baseInfo: TemplateFactoryV3 then skips creating focus/expanded views
            // while still accepting param_island and rendering the large/small island.
        ).toString()
    }

    private fun picture(type: Int) = JSONObject().put("type", type).put("pic", PICTURE_KEY)

    private const val NOTIFICATION_ID = 9527
}
