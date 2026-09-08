package me.kavishdevar.librepods.utils

/** Battery values are overlays, never replacements for Xiaomi's device metadata. */
internal data class XiaomiExactBattery(
    val address: String,
    val connected: Boolean,
    val left: Int,
    val right: Int,
    val case: Int,
) {
    fun matches(address: String?): Boolean = connected && !address.isNullOrBlank() &&
        this.address.equals(address, ignoreCase = true)

    fun fields(): Map<String, String> = buildMap {
        if (left in 0..100) put("leftBattery", left.toString())
        if (right in 0..100) put("rightBattery", right.toString())
        if (case in 0..100) put("boxBattery", case.toString())
    }

    fun overlay(original: Array<String?>): Array<String?> {
        // y0.j#h has 10 fields. Preserve unknown versions rather than guessing offsets.
        if (original.size != 10) return original
        return original.copyOf().apply {
            if (left in 0..100) this[1] = left.toString()
            if (right in 0..100) this[3] = right.toString()
            if (case in 0..100) this[4] = case.toString()
        }
    }
}
