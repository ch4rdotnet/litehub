package com.chardidathing.litehub.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// settings that belong to this device rather than a dashboard, never exported.
// pin is a salted hash (see Pin), null means the admin menu opens without one.
// companion is set once the hub has registered itself with ha as a mobile_app device
@Serializable
data class DeviceSettings(
    val version: Int,
    val pin: String? = null,
    val companion: CompanionRegistration? = null,
    val web: WebSettings = WebSettings(),
    val screensaver: ScreensaverSettings = ScreensaverSettings(),
    // notification banners play a short chime, a message can still ask for silence
    val chime: Boolean = true,
)

// the photo frame, idle and night behaviour, and what wakes the screen
@Serializable
data class ScreensaverSettings(
    val enabled: Boolean = false,
    val idleMinutes: Int = 10,
    val photos: PhotoSettings? = null,
    val photoSeconds: Int = 30,
    val night: NightSettings? = null,
    // ha entities that wake the screen when they turn on (a pir, a door)
    val wakeEntities: List<String> = emptyList(),
    // a sudden change in room light, a lamp going on
    val lightWake: Boolean = true,
    // low resolution motion detection, off unless asked for and never on a low ram device
    val cameraWake: Boolean = false,
)

// exactly one source. immich's key stays in this device only file, it's never exported
@Serializable
data class PhotoSettings(val folder: String? = null, val immich: ImmichSettings? = null, val haMedia: String? = null)

@Serializable
data class ImmichSettings(val url: String, val apiKey: String, val albumId: String)

// start and end are "HH:mm" local, the window can cross midnight
@Serializable
data class NightSettings(val start: String, val end: String, val mode: NightMode = NightMode.DIM, val dimPercent: Int = 10)

@Serializable
enum class NightMode {
    @SerialName("dim") DIM,
    @SerialName("blank") BLANK,
}

// the browser editor and the read only status page, both off until turned on from the menu.
// the editor asks for the device pin when there is one
@Serializable
data class WebSettings(val editor: Boolean = false, val status: Boolean = false, val port: Int = DEFAULT_WEB_PORT)

const val DEFAULT_WEB_PORT = 8080

// name is what ha calls the device, notify.mobile_app_<name> sends to it
@Serializable
data class CompanionRegistration(val deviceId: String, val webhookId: String, val name: String)
