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
    val dlna: DlnaSettings = DlnaSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val reporting: ReportingSettings = ReportingSettings(),
)

// the banners in the top right and the list behind the shade
@Serializable
data class NotificationSettings(val bannerSeconds: Int = 5, val maxBanners: Int = 7, val keep: Int = 50)

// how often the companion sensors go to ha even when nothing changed, and how often a touch
// updates last interaction
@Serializable
data class ReportingSettings(val heartbeatMinutes: Int = 15, val interactionSeconds: Int = 60)

// the hub as a dlna media renderer ha (or anything else on the lan) can play to. uuid is made
// the first time it's turned on and kept, so ha sees the same device across restarts
@Serializable
data class DlnaSettings(val enabled: Boolean = false, val port: Int = DEFAULT_DLNA_PORT, val uuid: String? = null)

// the start of the dynamic range, where upnp devices usually sit
const val DEFAULT_DLNA_PORT = 49152

// the photo frame, idle and night behaviour, and what wakes the screen
@Serializable
data class ScreensaverSettings(
    val enabled: Boolean = false,
    val idleMinutes: Int = 10,
    val photos: PhotoSettings? = null,
    val photoSeconds: Int = 30,
    // the backlight turned down while the screensaver shows, to this percent of full
    val dimWhileShowing: Boolean = false,
    val showingDimPercent: Int = 30,
    // the photo list is read again this often so new photos turn up
    val photoRefreshMinutes: Int = 60,
    val night: NightSettings? = null,
    // ha entities that wake the screen when they turn on (a pir, a door)
    val wakeEntities: List<String> = emptyList(),
    // a sudden change in room light, a lamp going on
    val lightWake: Boolean = true,
    // the jump that counts, this many times the room's usual light and at least this many lux
    val lightWakeRatio: Float = 3f,
    val lightWakeLux: Int = 15,
    // low resolution motion detection, off unless asked for and never on a low ram device
    val cameraWake: Boolean = false,
    // how much of the picture has to change to count as someone there
    val cameraWakePercent: Int = 6,
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
