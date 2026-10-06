package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.CalendarSource
import com.chardidathing.litehub.core.model.Choice
import com.chardidathing.litehub.core.model.DeviceSettings
import com.chardidathing.litehub.core.model.FeedSource
import com.chardidathing.litehub.core.model.FieldKind
import com.chardidathing.litehub.core.model.Hex
import com.chardidathing.litehub.core.model.ImmichSettings
import com.chardidathing.litehub.core.model.Location
import com.chardidathing.litehub.core.model.NightMode
import com.chardidathing.litehub.core.model.NightSettings
import com.chardidathing.litehub.core.model.PhotoSettings
import com.chardidathing.litehub.core.model.SchemaField
import com.chardidathing.litehub.core.model.SettingsSection
import com.chardidathing.litehub.core.model.Sources
import com.chardidathing.litehub.core.model.shownWith
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

// everything the settings screens edit, across settings.json, sources.json and ha.json. the ha
// token never comes in here, only whether one is set
data class HubSettings(val device: DeviceSettings, val sources: Sources, val haUrl: String?, val haTokenSet: Boolean)

// what a save hands back. ha is null when the connection wasn't touched, a null token keeps the old one
data class SavedSettings(val device: DeviceSettings, val sources: Sources, val ha: HaEdit?)

data class HaEdit(val url: String, val token: String?)

// the hub's settings as one form, key to value (a list section's value is an array of item
// objects). the settings screen on the device and the web ui both draw these sections and hand
// edits back through apply(), so they can't drift. the files keep their own shapes
object SettingsForm {

    private const val PHOTOS = "photos.source"
    private const val NIGHT = "night.enabled"
    private const val LOCATION = "location.set"
    const val CALENDARS = "calendars"
    const val FEEDS = "feeds"

    // fills location in from ha's own home, the device and the web ask ha for it
    const val HA_HOME = "ha-home"

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
                text("ha.url", "url (http://homeassistant.local:8123)"),
                SchemaField("ha.token", "long lived token", FieldKind.SECRET),
                number("reporting.heartbeatMinutes", "send sensors at least every (minutes)", 1, 120),
                number("reporting.interactionSeconds", "report touches at most every (seconds)", 5, 3600),
            ),
        ),
        SettingsSection(
            CALENDARS,
            "calendars",
            itemName = "calendar",
            items = listOf(
                SchemaField("name", "name", FieldKind.TEXT, required = true),
                SchemaField("source", "from", FieldKind.CHOICE, options = listOf(Choice("url", "a link (ics or webcal)"), Choice("entity", "a home assistant calendar"))),
                SchemaField("url", "link", FieldKind.TEXT, required = true, showIf = mapOf("source" to "url")),
                SchemaField("entity", "calendar", FieldKind.ENTITY, required = true, domains = listOf("calendar"), showIf = mapOf("source" to "entity")),
                SchemaField("color", "colour", FieldKind.COLOR),
                number("refreshMinutes", "check every (minutes)", 1, 1440),
            ),
        ),
        SettingsSection(
            FEEDS,
            "feeds",
            itemName = "feed",
            items = listOf(
                SchemaField("name", "name", FieldKind.TEXT, required = true),
                SchemaField("url", "link (rss or atom)", FieldKind.TEXT, required = true),
                number("refreshMinutes", "check every (minutes)", 1, 1440),
            ),
        ),
        SettingsSection(
            "location",
            "location",
            listOf(
                toggle(LOCATION, "a location for weather without a ha entity"),
                number("location.latitude", "latitude", -90, 90, mapOf(LOCATION to "true")),
                number("location.longitude", "longitude", -180, 180, mapOf(LOCATION to "true")),
            ),
            actions = listOf(Choice(HA_HOME, "use home assistant's home")),
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
    private val lists = sections.filter { it.items != null }.associateBy { it.id }

    // what a night window starts as before one's been set
    private val NEW_NIGHT = NightSettings("22:00", "07:00")

    fun values(h: HubSettings): JsonObject {
        val s = h.device
        val saver = s.screensaver
        val photos = saver.photos
        val night = saver.night ?: NEW_NIGHT
        val location = h.sources.location
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
                "ha.url" to JsonPrimitive(h.haUrl.orEmpty()),
                "ha.token" to JsonPrimitive(""),
                "reporting.heartbeatMinutes" to JsonPrimitive(s.reporting.heartbeatMinutes),
                "reporting.interactionSeconds" to JsonPrimitive(s.reporting.interactionSeconds),
                CALENDARS to JsonArray(h.sources.calendars.map(::calendarItem)),
                FEEDS to JsonArray(h.sources.feeds.map(::feedItem)),
                LOCATION to JsonPrimitive(location != null),
                "location.latitude" to JsonPrimitive(location?.latitude ?: 0.0),
                "location.longitude" to JsonPrimitive(location?.longitude ?: 0.0),
                "web.editor" to JsonPrimitive(s.web.editor),
                "web.status" to JsonPrimitive(s.web.status),
                "web.port" to JsonPrimitive(s.web.port),
                "dlna.enabled" to JsonPrimitive(s.dlna.enabled),
                "dlna.port" to JsonPrimitive(s.dlna.port),
            ),
        )
    }

    private fun calendarItem(c: CalendarSource) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(c.id),
            "name" to JsonPrimitive(c.name),
            "source" to JsonPrimitive(if (c.entity != null) "entity" else "url"),
            "url" to JsonPrimitive(c.url.orEmpty()),
            "entity" to JsonPrimitive(c.entity.orEmpty()),
            "color" to JsonPrimitive(c.color?.let(Hex::of).orEmpty()),
            "refreshMinutes" to JsonPrimitive(c.refreshMinutes),
        ),
    )

    private fun feedItem(f: FeedSource) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(f.id),
            "name" to JsonPrimitive(f.name),
            "url" to JsonPrimitive(f.url),
            "refreshMinutes" to JsonPrimitive(f.refreshMinutes),
        ),
    )

    // what a new list item starts as, the same defaults a hand written one gets
    fun newItem(section: String): JsonObject = when (section) {
        CALENDARS -> calendarItem(CalendarSource("", "")).let { JsonObject(it - "id") }
        else -> feedItem(FeedSource("", "", "")).let { JsonObject(it - "id") }
    }

    // edits laid over what's there now. keys left out keep their value, a blank secret keeps the
    // old one, a list's value replaces the whole list. throws ConfigException naming the field
    fun apply(h: HubSettings, edits: JsonObject): SavedSettings {
        val v = values(h).toMutableMap()
        for ((key, value) in edits) {
            val field = fields[key]
            if (field == null && key !in lists) throw ConfigException("there's no setting called $key")
            if (field?.kind == FieldKind.SECRET && (value as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) continue
            v[key] = value
        }
        checkFields(fields.values, v)
        for ((id, section) in lists) {
            val items = v[id] as? JsonArray ?: throw ConfigException("${section.name} isn't a list")
            for (item in items) {
                val o = item as? JsonObject ?: throw ConfigException("${section.name} has something that isn't a ${section.itemName}")
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: section.itemName.orEmpty()
                try {
                    checkFields(section.items.orEmpty(), o)
                } catch (e: ConfigException) {
                    throw ConfigException("$name, ${e.message}")
                }
            }
        }
        val values = Values(v)
        val device = build(h.device, values)
        check(device)
        val sources = sources(h.sources, values)
        SourcesCodec.check(sources)
        return SavedSettings(device, sources, ha(h, values))
    }

    // the same checks settings.json gets when it's read from disk
    fun check(s: DeviceSettings) {
        checkFields(fields.values, values(HubSettings(s, Sources(SourcesCodec.VERSION), null, false)))
        s.screensaver.photos?.let { p ->
            if (listOfNotNull(p.folder, p.immich, p.haMedia).size != 1) throw ConfigException("photos needs exactly one of folder, immich or haMedia")
        }
        if (s.dlna.port == s.web.port) throw ConfigException("dlna and the web editor can't share a port")
    }

    // hidden fields aren't checked, a night window that's off can hold anything
    private fun checkFields(list: Collection<SchemaField>, v: Map<String, JsonElement>) {
        for (field in list) {
            if (!field.shownWith(v)) continue
            val value = v[field.key] as? JsonPrimitive
            if (field.required && value?.contentOrNull.isNullOrBlank()) throw ConfigException("${field.label} is needed")
            val ok = when (field.kind) {
                FieldKind.TOGGLE -> value?.booleanOrNull != null
                FieldKind.NUMBER -> value?.doubleOrNull?.let { n -> (field.min == null || n >= field.min!!) && (field.max == null || n <= field.max!!) } == true
                FieldKind.CHOICE -> field.options.any { it.value == value?.contentOrNull }
                FieldKind.TIME -> value?.contentOrNull?.let(SettingsCodec::time) != null
                FieldKind.ENTITIES -> (v[field.key] as? JsonArray)?.all { (it as? JsonPrimitive)?.isString == true } == true
                // a cleared box drops its key, that's automatic too
                FieldKind.COLOR -> value?.contentOrNull.orEmpty().let { it.isBlank() || Hex.parse(it) != null }
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
        FieldKind.COLOR -> "${field.label} is a colour like #6200ee, or blank"
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

    // ids are kept once made, dashboards point at them. a new item gets one from its name
    private fun sources(old: Sources, v: Values): Sources {
        // existing ids are reserved first so a new item never takes one that's in use
        val taken = (v.items(CALENDARS) + v.items(FEEDS)).map { it.text("id") }.filter { it.isNotEmpty() }.toMutableSet()
        fun id(item: Values.Item): String {
            item.text("id").ifEmpty { null }?.let { return it }
            val wanted = slug(item.text("name"))
            var candidate = wanted
            var n = 2
            while (candidate in taken) candidate = "$wanted-${n++}"
            taken += candidate
            return candidate
        }
        val calendars = v.items(CALENDARS).map { c ->
            val byUrl = c.text("source") != "entity"
            CalendarSource(
                id = id(c),
                name = c.text("name"),
                url = if (byUrl) c.text("url") else null,
                entity = if (byUrl) null else c.text("entity"),
                color = Hex.parse(c.text("color")),
                refreshMinutes = c.int("refreshMinutes"),
            )
        }
        val feeds = v.items(FEEDS).map { f -> FeedSource(id(f), f.text("name"), f.text("url"), f.int("refreshMinutes")) }
        val location = if (!v.bool(LOCATION)) null else Location(v.double("location.latitude"), v.double("location.longitude"))
        return old.copy(calendars = calendars, feeds = feeds, location = location)
    }

    private fun slug(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "source" }

    private fun ha(h: HubSettings, v: Values): HaEdit? {
        val url = v.text("ha.url")
        val token = v.text("ha.token").ifEmpty { null }
        if (url == h.haUrl.orEmpty() && token == null) return null
        if (url.isEmpty()) throw ConfigException("home assistant url is needed")
        if (token == null && !h.haTokenSet) throw ConfigException("a long lived token is needed the first time")
        return HaEdit(url, token)
    }

    private class Values(private val v: Map<String, JsonElement>) {
        fun text(key: String) = (v[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        fun needed(key: String) = text(key).ifEmpty { throw ConfigException("${fields.getValue(key).label} is needed") }
        fun bool(key: String) = (v[key] as? JsonPrimitive)?.booleanOrNull ?: false
        fun int(key: String) = (v[key] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        fun float(key: String) = double(key).toFloat()
        fun double(key: String) = (v[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun list(key: String) = (v[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }.orEmpty()
        fun items(key: String) = (v[key] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::Item) }.orEmpty()

        class Item(private val o: JsonObject) {
            fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            fun int(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        }
    }

    private fun toggle(key: String, label: String) = SchemaField(key, label, FieldKind.TOGGLE)

    private fun text(key: String, label: String, showIf: Map<String, String> = emptyMap()) = SchemaField(key, label, FieldKind.TEXT, showIf = showIf)

    private fun number(key: String, label: String, min: Number, max: Number, showIf: Map<String, String> = emptyMap()) =
        SchemaField(key, label, FieldKind.NUMBER, min = min.toDouble(), max = max.toDouble(), showIf = showIf)

}
