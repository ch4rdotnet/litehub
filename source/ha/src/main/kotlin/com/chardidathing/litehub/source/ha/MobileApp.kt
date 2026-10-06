package com.chardidathing.litehub.source.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// the mobile_app integration's rest side: registering this hub as a device, and its sensors
// through the webhook ha hands back. the push side rides the websocket (HaClient.setPushChannel)
class MobileApp(private val credentials: HaCredentials, private val http: OkHttpClient) {

    class Device(
        val deviceId: String,
        val name: String,
        val appVersion: String,
        val manufacturer: String,
        val model: String,
        val osVersion: String,
    )

    // type is "sensor" or "binary_sensor"
    class Sensor(
        val uniqueId: String,
        val name: String,
        val type: String,
        val icon: String,
        val deviceClass: String? = null,
        val unit: String? = null,
        val diagnostic: Boolean = false,
    )

    class State(val uniqueId: String, val type: String, val icon: String, val state: JsonPrimitive)

    // ha forgot the device (deleted from its integrations page), it has to register again
    class Gone : IOException("home assistant doesn't know this hub any more")

    suspend fun register(device: Device): Result<String> = post(
        credentials.url.trimEnd('/') + "/api/mobile_app/registrations",
        buildJsonObject {
            put("device_id", device.deviceId)
            put("app_id", APP_ID)
            put("app_name", "litehub")
            put("app_version", device.appVersion)
            put("device_name", device.name)
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("os_name", "Android")
            put("os_version", device.osVersion)
            put("supports_encryption", false)
            putJsonObject("app_data") { put("push_websocket_channel", true) }
        },
        bearer = true,
    ).mapCatching { body ->
        (body?.get("webhook_id") as? JsonPrimitive)?.contentOrNull ?: throw IOException("home assistant didn't hand back a webhook")
    }

    suspend fun registerSensor(webhookId: String, sensor: Sensor): Result<Unit> = webhook(
        webhookId,
        buildJsonObject {
            put("type", "register_sensor")
            putJsonObject("data") {
                put("unique_id", sensor.uniqueId)
                put("name", sensor.name)
                put("type", sensor.type)
                put("icon", sensor.icon)
                put("state", JsonPrimitive(null as String?))
                sensor.deviceClass?.let { put("device_class", it) }
                sensor.unit?.let { put("unit_of_measurement", it) }
                if (sensor.diagnostic) put("entity_category", "diagnostic")
            }
        },
    )

    suspend fun update(webhookId: String, states: List<State>): Result<Unit> = webhook(
        webhookId,
        buildJsonObject {
            put("type", "update_sensor_states")
            put("data", buildJsonArray {
                for (s in states) add(buildJsonObject {
                    put("unique_id", s.uniqueId)
                    put("type", s.type)
                    put("icon", s.icon)
                    put("state", s.state)
                })
            })
        },
    )

    private suspend fun webhook(id: String, body: JsonObject): Result<Unit> =
        post(credentials.url.trimEnd('/') + "/api/webhook/$id", body, bearer = false).map { }

    private suspend fun post(url: String, body: JsonObject, bearer: Boolean): Result<JsonObject?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url)
                .post(body.toString().toRequestBody(JSON))
                .apply { if (bearer) header("Authorization", "Bearer ${credentials.token}") }
                .build()
            http.newCall(request).execute().use { r ->
                when {
                    r.code == GONE -> Result.failure(Gone())
                    !r.isSuccessful -> Result.failure(IOException("home assistant refused (${r.code})"))
                    else -> {
                        val text = r.body.string()
                        Result.success(if (text.isBlank()) null else runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull())
                    }
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("the home assistant url isn't valid", e))
        }
    }

    private companion object {
        const val APP_ID = "com.chardidathing.litehub"
        const val GONE = 410
        val JSON = "application/json".toMediaType()
    }
}
