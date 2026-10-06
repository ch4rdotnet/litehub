package com.chardidathing.litehub.core.model

import kotlinx.serialization.Serializable

// one notification ha sent to the hub. tag is ha companion's, a later clear_notification with
// the same tag takes it back
@Serializable
data class HubNotification(
    val id: String,
    val title: String? = null,
    val message: String,
    val timeMs: Long,
    val tag: String? = null,
)
