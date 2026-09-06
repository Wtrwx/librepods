/* Adapted from beidaoc/librepods, GPL-3.0-or-later. */
package me.kavishdevar.librepods.bluetooth

internal data class SmartRoutingPlaybackState(
    val hostStreaming: Boolean?,
    val otherDeviceAudioCategory: Int?,
    val playingAppActive: Boolean?
) {
    val hasRemotePlaybackIntent: Boolean?
        get() = when {
            hostStreaming == true -> true
            otherDeviceAudioCategory != null -> otherDeviceAudioCategory > 0
            playingAppActive != null -> playingAppActive
            hostStreaming != null -> false
            else -> null
        }
}

internal fun parseSmartRoutingPlaybackState(packetString: String): SmartRoutingPlaybackState {
    val hostStreaming = HOST_STREAMING_STATE_REGEX.find(packetString)
        ?.groupValues
        ?.getOrNull(1)
        ?.equals("YES", ignoreCase = true)
    val audioCategory = OTHER_DEVICE_AUDIO_CATEGORY_REGEX.find(packetString)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val playingAppActive = PLAYING_APP_REGEX.find(packetString)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { value -> value.isNotBlank() && !value.equals("NA", ignoreCase = true) }
    return SmartRoutingPlaybackState(hostStreaming, audioCategory, playingAppActive)
}

private val HOST_STREAMING_STATE_REGEX =
    Regex("hostStreamingState[A-Z]?(YES|NO)", RegexOption.IGNORE_CASE)
private val OTHER_DEVICE_AUDIO_CATEGORY_REGEX =
    Regex("otherDeviceAudioCategory([0-9])", RegexOption.IGNORE_CASE)
private val PLAYING_APP_REGEX =
    Regex("playingApp.(.*?).hostStreamingState", RegexOption.IGNORE_CASE)

