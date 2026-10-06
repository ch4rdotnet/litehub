package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.Choice
import com.chardidathing.litehub.core.model.DeviceSettings
import com.chardidathing.litehub.core.model.FieldKind
import com.chardidathing.litehub.core.model.ImmichSettings
import com.chardidathing.litehub.core.model.NightMode
import com.chardidathing.litehub.core.model.NightSettings
import com.chardidathing.litehub.core.model.PhotoSettings
import com.chardidathing.litehub.core.model.SchemaField
import com.chardidathing.litehub.core.model.SettingsSection
import com.chardidathing.litehub.core.model.shownWith
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// the hub's own settings as one flat form, key to value. the settings screen on the device and
// the web ui both draw these sections and hand edits back through apply(), so they can't drift.
// settings.json keeps its nested shape, this is only how it's edited
object DeviceForm {

    private const val PHOTOS = "photos.source"
    private const val NIGHT = "night.enabled"

    val sections: List<SettingsSection> = listOf(
        SettingsSection(
            "screensaver",
            "screensaver",
            listOf(
                toggle("screensaver.enabled", "screensaver"),
                number("screensaver.idleMinutes", "minutes untouched before it starts", 1, 1440),
                SchemaField(
                    PHOTOS, "photos from", FieldKind.CHOICE,
                    options = listOf(
                        Choice("none", "none, just the clock"),
                        Choice("folder", "a folder on this device"),
                        Choice("immich", "an immich album"),
                        Choice("ha", "home assistant media"),
                    ),
                ),
                text("photos.folder", "folder", mapOf(PHOTOS to "folder")),
                text("photos.immichUrl", "immich url", mapOf(PHOTOS to "immich")),
                SchemaField("photos.immichKey", "immich api key", FieldKind.SECRET, showIf = mapOf(PHOTOS to "immich")),
                text("photos.immichAlbum", "immich album id", mapOf(PHOTOS to "immich")),
                text("photos.haMedia", "media folder (media-source://...)", mapOf(PHOTOS to "ha")),
                number("screensaver.photoSeconds", "seconds per photo", 5, 3600),
                number("screensaver.photoRefreshMinutes", "look for new photos every (minutes)", 5, 1440),
            ),
        ),
        SettingsSection(
            "night",
            "night",
            listOf(
                toggle(NIGHT, "night window"),
                SchemaField("night.start", "starts", FieldKind.TIME, showIf = mapOf(NIGHT to "true")),
                SchemaField("night.end", "ends", FieldKind.TIME, showIf = mapOf(NIGHT to "true")),
                SchemaField(
                    "night.mode", "the screen", FieldKind.CHOICE,
                    options = listOf(Choice("dim", "dims"), Choice("blank", "goes blank")),
                    showIf = mapOf(NIGHT to "true"),
                ),
                number("night.dimPercent", "dims to (percent)", 1, 100, mapOf(NIGHT to "true", "night.mode" to "dim")),
            ),
        ),
        SettingsSection(
            "wake",
            "wake",
            listOf(
                SchemaField("screensaver.wakeEntities", "ha entities that wake it when they turn on", FieldKind.ENTITIES),
                toggle("screensaver.lightWake", "wake when the room light jumps"),
                number("screensaver.lightWakeRatio", "a jump is this many times the usual light", 1.5, 20, mapOf("screensaver.lightWake" to "true")),
                number("screensaver.lightWakeLux", "and at least this many lux", 1, 1000, mapOf("screensaver.lightWake" to "true")),
                toggle("screensaver.cameraWake", "wake on camera motion"),
                number("screensaver.cameraWakePercent", "motion is this much of the picture changing (percent)", 1, 100, mapOf("screensaver.cameraWake" to "true")),
            ),
        ),
        SettingsSection(
            "notifications",
            "notifications",
            listOf(
                toggle("chime", "chime"),
                number("notifications.bannerSeconds", "seconds a banner stays up", 1, 60),
                number("notifications.maxBanners", "most banners at once", 1, 10),
                number("notifications.keep", "notifications kept in the list", 1, 500),
            ),
        ),
        SettingsSection(
            "ha",
            "home assistant",
            listOf(
                number("reporting.heartbeatMinutes", "send sensors at least every (minutes)", 1, 120),
                number("reporting.interactionSeconds", "report touches at most every (seconds)", 5, 3600),
            ),
        ),
        SettingsSection(
            "web",
            "web",
            listOf(
                toggle("web.editor", "web editor"),
                toggle("web.status", "status page"),
                number("web.port", "port", 1024, 65535),
            ),
        ),
        SettingsSection(
            "dlna",
            "dlna",
            listOf(
                toggle("dlna.enabled", "dlna renderer"),
                number("dlna.port", "port", 1024, 65535),
            ),
        ),
    )

    private val fields = sections.flatMap { it.fields }.associateBy { it.key }

    // what a night window starts as before one's been set
    private val NEW_NIGHT = NightSettings("22:00", "07:00")

    fun values(s: DeviceSettings): JsonObject {
        val saver = s.screensaver
        val photos = saver.photos
        val night = saver.night ?: NEW_NIGHT
        return JsonObject(
            mapOf(
                "screensaver.enabled" to JsonPrimitive(saver.enabled),
                "screensaver.idleMinutes" to JsonPrimitive(saver.idleMinutes),
                PHOTOS to JsonPrimitive(
                    when {
                        photos?.folder != null -> "folder"
                        photos?.immich != null -> "immich"
                        photos?.haMedia != null -> "ha"
                        else -> "none"
                    },
                ),
                "photos.folder" to JsonPrimitive(photos?.folder.orEmpty()),
                "photos.immichUrl" to JsonPrimitive(photos?.immich?.url.orEmpty()),
                "photos.immichKey" to JsonPrimitive(""),
                "photos.immichAlbum" to JsonPrimitive(photos?.immich?.albumId.orEmpty()),
                "photos.haMedia" to JsonPrimitive(photos?.haMedia.orEmpty()),
                "screensaver.photoSeconds" to JsonPrimitive(saver.photoSeconds),
                "screensaver.photoRefreshMinutes" to JsonPrimitive(saver.photoRefreshMinutes),
                NIGHT to JsonPrimitive(saver.night != null),
                "night.start" to JsonPrimitive(night.start),
                "night.end" to JsonPrimitive(night.end),
                "night.mode" to JsonPrimitive(if (night.mode == NightMode.BLANK) "blank" else "dim"),
                "night.dimPercent" to JsonPrimitive(night.dimPercent),
                "screensaver.wakeEntities" to JsonArray(saver.wakeEntities.map(::JsonPrimitive)),
                "screensaver.lightWake" to JsonPrimitive(saver.lightWake),
                "screensaver.lightWakeRatio" to JsonPrimitive(saver.lightWakeRatio),
                "screensaver.lightWakeLux" to JsonPrimitive(saver.lightWakeLux),
                "screensaver.cameraWake" to JsonPrimitive(saver.cameraWake),
                "screensaver.cameraWakePercent" to JsonPrimitive(saver.cameraWakePercent),
                "chime" to JsonPrimitive(s.chime),
                "notifications.bannerSeconds" to JsonPrimitive(s.notifications.bannerSeconds),
                "notifications.maxBanners" to JsonPrimitive(s.notifications.maxBanners),
                "notifications.keep" to JsonPrimitive(s.notifications.keep),
                "reporting.heartbeatMinutes" to JsonPrimitive(s.reporting.heartbeatMinutes),
                "reporting.interactionSeconds" to JsonPrimitive(s.reporting.interactionSeconds),
                "web.editor" to JsonPrimitive(s.web.editor),
                "web.status" to JsonPrimitive(s.web.status),
                "web.port" to JsonPrimitive(s.web.port),
                "dlna.enabled" to JsonPrimitive(s.dlna.enabled),
                "dlna.port" to JsonPrimitive(s.dlna.port),
            ),
        )
    }

    // edits laid over what's there now. keys left out keep their value, a blank secret keeps the
    // old one. throws ConfigException naming the field when something's off
    fun apply(s: DeviceSettings, edits: JsonObject): DeviceSettings {
        val v = values(s).toMutableMap()
        for ((key, value) in edits) {
            val field = fields[key] ?: throw ConfigException("there's no setting called $key")
            if (field.kind == FieldKind.SECRET && (value as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) continue
            v[key] = value
        }
        checkFields(v)
        val next = build(s, Values(v))
        check(next)
        return next
    }

    // the same checks settings.json gets when it's read from disk
    fun check(s: DeviceSettings) {
        checkFields(values(s))
        s.screensaver.photos?.let { p ->
            if (listOfNotNull(p.folder, p.immich, p.haMedia).size != 1) throw ConfigException("photos needs exactly one of folder, immich or haMedia")
        }
        if (s.dlna.port == s.web.port) throw ConfigException("dlna and the web editor can't share a port")
    }

    // hidden fields aren't checked, a night window that's off can hold anything
    private fun checkFields(v: Map<String, JsonElement>) {
        for (field in fields.values) {
            if (!field.shownWith(v)) continue
            val value = v[field.key] as? JsonPrimitive
            val ok = when (field.kind) {
                FieldKind.TOGGLE -> value?.booleanOrNull != null
                FieldKind.NUMBER -> value?.doubleOrNull?.let { n -> (field.min == null || n >= field.min!!) && (field.max == null || n <= field.max!!) } == true
                FieldKind.CHOICE -> field.options.any { it.value == value?.contentOrNull }
                FieldKind.TIME -> value?.contentOrNull?.let(SettingsCodec::time) != null
                FieldKind.ENTITIES -> (v[field.key] as? JsonArray)?.all { (it as? JsonPrimitive)?.isString == true } == true
                else -> value?.isString == true
            }
            if (!ok) throw ConfigException(problem(field))
        }
    }

    private fun problem(field: SchemaField) = when (field.kind) {
        FieldKind.NUMBER -> "${field.label} is ${plain(field.min)} to ${plain(field.max)}"
        FieldKind.TIME -> "${field.label} is a time like 22:00"
        FieldKind.CHOICE -> "${field.label} is one of ${field.options.joinToString { it.value }}"
        FieldKind.TOGGLE -> "${field.label} is on or off"
        else -> "${field.label} isn't valid"
    }

    private fun plain(n: Double?) = n?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "any"

    private fun build(s: DeviceSettings, v: Values): DeviceSettings {
        val existingKey = s.screensaver.photos?.immich?.apiKey
        val photos = when (v.text(PHOTOS)) {
            "folder" -> PhotoSettings(folder = v.needed("photos.folder"))
            "immich" -> PhotoSettings(
                immich = ImmichSettings(
                    url = v.needed("photos.immichUrl"),
                    apiKey = v.text("photos.immichKey").ifBlank { existingKey ?: throw ConfigException("immich api key is needed") },
                    albumId = v.needed("photos.immichAlbum"),
                ),
            )
            "ha" -> PhotoSettings(haMedia = v.needed("photos.haMedia"))
            else -> null
        }
        val night = if (!v.bool(NIGHT)) null else NightSettings(
            start = v.text("night.start"),
            end = v.text("night.end"),
            mode = if (v.text("night.mode") == "blank") NightMode.BLANK else NightMode.DIM,
            dimPercent = v.int("night.dimPercent"),
        )
        return s.copy(
            screensaver = s.screensaver.copy(
                enabled = v.bool("screensaver.enabled"),
                idleMinutes = v.int("screensaver.idleMinutes"),
                photos = photos,
                photoSeconds = v.int("screensaver.photoSeconds"),
                photoRefreshMinutes = v.int("screensaver.photoRefreshMinutes"),
                night = night,
                wakeEntities = v.list("screensaver.wakeEntities"),
                lightWake = v.bool("screensaver.lightWake"),
                lightWakeRatio = v.float("screensaver.lightWakeRatio"),
                lightWakeLux = v.int("screensaver.lightWakeLux"),
                cameraWake = v.bool("screensaver.cameraWake"),
                cameraWakePercent = v.int("screensaver.cameraWakePercent"),
            ),
            chime = v.bool("chime"),
            notifications = s.notifications.copy(
                bannerSeconds = v.int("notifications.bannerSeconds"),
                maxBanners = v.int("notifications.maxBanners"),
                keep = v.int("notifications.keep"),
            ),
            reporting = s.reporting.copy(
                heartbeatMinutes = v.int("reporting.heartbeatMinutes"),
                interactionSeconds = v.int("reporting.interactionSeconds"),
            ),
            web = s.web.copy(editor = v.bool("web.editor"), status = v.bool("web.status"), port = v.int("web.port")),
            // the uuid is made the first time it's turned on and kept, ha knows the renderer by it
            dlna = s.dlna.copy(
                enabled = v.bool("dlna.enabled"),
                port = v.int("dlna.port"),
                uuid = s.dlna.uuid ?: if (v.bool("dlna.enabled")) java.util.UUID.randomUUID().toString() else null,
            ),
        )
    }

    private class Values(private val v: Map<String, JsonElement>) {
        fun text(key: String) = (v[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        fun needed(key: String) = text(key).ifEmpty { throw ConfigException("${fields.getValue(key).label} is needed") }
        fun bool(key: String) = (v[key] as? JsonPrimitive)?.booleanOrNull ?: false
        fun int(key: String) = (v[key] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        fun float(key: String) = (v[key] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f
        fun list(key: String) = (v[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }.orEmpty()
    }

    private fun toggle(key: String, label: String) = SchemaField(key, label, FieldKind.TOGGLE)

    private fun text(key: String, label: String, showIf: Map<String, String> = emptyMap()) = SchemaField(key, label, FieldKind.TEXT, showIf = showIf)

    private fun number(key: String, label: String, min: Number, max: Number, showIf: Map<String, String> = emptyMap()) =
        SchemaField(key, label, FieldKind.NUMBER, min = min.toDouble(), max = max.toDouble(), showIf = showIf)
}
