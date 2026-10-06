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
)

// name is what ha calls the device, notify.mobile_app_<name> sends to it
@Serializable
data class CompanionRegistration(val deviceId: String, val webhookId: String, val name: String)
