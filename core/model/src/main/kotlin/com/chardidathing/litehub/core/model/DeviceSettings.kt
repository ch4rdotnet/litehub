package com.chardidathing.litehub.core.model

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
)

// the browser editor and the read only status page, both off until turned on from the menu.
// the editor asks for the device pin when there is one
@Serializable
data class WebSettings(val editor: Boolean = false, val status: Boolean = false, val port: Int = DEFAULT_WEB_PORT)

const val DEFAULT_WEB_PORT = 8080

// name is what ha calls the device, notify.mobile_app_<name> sends to it
@Serializable
data class CompanionRegistration(val deviceId: String, val webhookId: String, val name: String)
