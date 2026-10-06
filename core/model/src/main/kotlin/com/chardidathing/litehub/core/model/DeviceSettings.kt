package com.chardidathing.litehub.core.model

import kotlinx.serialization.Serializable

// settings that belong to this device rather than a dashboard, never exported.
// pin is a salted hash (see Pin), null means the admin menu opens without one
@Serializable
data class DeviceSettings(
    val version: Int,
    val pin: String? = null,
)
