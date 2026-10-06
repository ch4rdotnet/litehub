package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.CalendarSource
import com.chardidathing.litehub.core.model.ImmichSettings
import com.chardidathing.litehub.core.model.NightMode
import com.chardidathing.litehub.core.model.PhotoSettings
import com.chardidathing.litehub.core.model.Sources
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFormTest {

    private val device = SettingsCodec.defaults
    private val sources = Sources(SourcesCodec.VERSION, calendars = listOf(CalendarSource("family", "Family", url = "https://cal/x.ics")))
    private val hub = HubSettings(device, sources, "http://ha:8123", haTokenSet = true)

    private fun json(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { json(it!!) })
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to json(x!!) })
        else -> JsonPrimitive(v.toString())
    }
    private fun edits(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to json(v) })
    private fun apply(h: HubSettings, vararg pairs: Pair<String, Any>) = SettingsForm.apply(h, edits(*pairs))

    @Test
    fun `every field has a value and nothing changes without edits`() {
        val values = SettingsForm.values(hub)
        for (section in SettingsForm.sections) {
            assertTrue(section.id, section.items == null || values.containsKey(section.id))
            for (field in section.fields) assertTrue(field.key, values.containsKey(field.key))
        }
        val saved = SettingsForm.apply(hub, JsonObject(emptyMap()))
        assertEquals(device, saved.device)
        assertEquals(sources, saved.sources)
        assertNull(saved.ha)
    }

    @Test
    fun `edits land in the nested settings`() {
        val s = apply(
            hub,
            "screensaver.idleMinutes" to 3, "night.enabled" to true, "night.start" to "21:30", "night.mode" to "blank",
            "notifications.bannerSeconds" to 9, "screensaver.lightWakeRatio" to 2.5, "screensaver.wakeEntities" to listOf("binary_sensor.door"),
        ).device
        assertEquals(3, s.screensaver.idleMinutes)
        assertEquals("21:30", s.screensaver.night!!.start)
        assertEquals(NightMode.BLANK, s.screensaver.night!!.mode)
        assertEquals(9, s.notifications.bannerSeconds)
        assertEquals(2.5f, s.screensaver.lightWakeRatio)
        assertEquals(listOf("binary_sensor.door"), s.screensaver.wakeEntities)
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
    }

    @Test
    fun `numbers outside their range name the field`() {
        val e = assertThrows(ConfigException::class.java) { apply(hub, "notifications.maxBanners" to 40) }
        assertEquals("most banners at once is 1 to 10", e.message)
    }

    @Test
    fun `hidden fields aren't checked`() {
        assertNull(apply(hub, "night.enabled" to false, "night.start" to "whenever").device.screensaver.night)
        assertThrows(ConfigException::class.java) { apply(hub, "night.enabled" to true, "night.start" to "whenever") }
    }

    @Test
    fun `a blank secret keeps the stored one`() {
        val withImmich = hub.copy(device = device.copy(screensaver = device.screensaver.copy(photos = PhotoSettings(immich = ImmichSettings("http://i", "key", "album")))))
        assertEquals(JsonPrimitive(""), SettingsForm.values(withImmich)["photos.immichKey"])
        val s = apply(withImmich, "photos.immichAlbum" to "other", "photos.immichKey" to "").device
        assertEquals("key", s.screensaver.photos!!.immich!!.apiKey)
        assertEquals("other", s.screensaver.photos!!.immich!!.albumId)
        assertThrows(ConfigException::class.java) { apply(hub, "photos.source" to "immich", "photos.immichUrl" to "http://i", "photos.immichAlbum" to "a") }
    }

    @Test
    fun `turning dlna on makes a uuid once`() {
        val on = apply(hub, "dlna.enabled" to true).device
        assertNotNull(on.dlna.uuid)
        assertEquals(on.dlna.uuid, apply(hub.copy(device = on), "dlna.enabled" to false).device.dlna.uuid)
    }

    @Test
    fun `unknown keys and clashing ports are refused`() {
        assertThrows(ConfigException::class.java) { apply(hub, "pin" to "1234") }
        assertThrows(ConfigException::class.java) { apply(hub, "dlna.port" to 8080) }
    }

    @Test
    fun `calendars keep their ids and new ones get one from the name`() {
        val existing = SettingsForm.values(hub)[SettingsForm.CALENDARS] as JsonArray
        val renamed = JsonObject((existing[0] as JsonObject) + ("name" to JsonPrimitive("Our family")) + ("color" to JsonPrimitive("#6200EE")))
        val added = mapOf("name" to "Bin night", "source" to "entity", "entity" to "calendar.bins", "url" to "", "color" to "", "refreshMinutes" to 15)
        val clash = mapOf("name" to "family", "source" to "url", "url" to "https://other.ics", "color" to "", "refreshMinutes" to 30)
        val s = apply(hub, SettingsForm.CALENDARS to listOf(renamed, added, clash)).sources
        assertEquals(listOf("family", "bin-night", "family-2"), s.calendars.map { it.id })
        assertEquals("Our family", s.calendars[0].name)
        assertEquals(0xFF6200EE.toInt(), s.calendars[0].color)
        assertEquals("calendar.bins", s.calendars[1].entity)
        assertNull(s.calendars[1].url)
    }

    @Test
    fun `a calendar item missing its link names itself`() {
        val bad = mapOf("name" to "Work", "source" to "url", "url" to "", "color" to "", "refreshMinutes" to 30)
        val e = assertThrows(ConfigException::class.java) { apply(hub, SettingsForm.CALENDARS to listOf(bad)) }
        assertEquals("Work, link is needed", e.message)
        val badColour = bad + ("url" to "https://w.ics") + ("color" to "purple")
        assertThrows(ConfigException::class.java) { apply(hub, SettingsForm.CALENDARS to listOf(badColour)) }
    }

    @Test
    fun `feeds and location`() {
        val s = apply(
            hub,
            SettingsForm.FEEDS to listOf(mapOf("name" to "ABC News", "url" to "https://abc/rss", "refreshMinutes" to 60)),
            "location.set" to true, "location.latitude" to -34.93, "location.longitude" to 138.6,
        ).sources
        assertEquals("abc-news", s.feeds.single().id)
        assertEquals(-34.93, s.location!!.latitude, 0.0)
        assertThrows(ConfigException::class.java) { apply(hub, "location.set" to true, "location.latitude" to 120) }
    }

    @Test
    fun `ha changes only when touched and needs a token the first time`() {
        assertEquals(HaEdit("http://ha2:8123", null), apply(hub, "ha.url" to "http://ha2:8123").ha)
        assertEquals(HaEdit("http://ha:8123", "tok"), apply(hub, "ha.token" to "tok").ha)
        val fresh = hub.copy(haUrl = null, haTokenSet = false)
        assertThrows(ConfigException::class.java) { apply(fresh, "ha.url" to "http://ha:8123") }
        assertEquals(HaEdit("http://ha:8123", "tok"), apply(fresh, "ha.url" to "http://ha:8123", "ha.token" to "tok").ha)
    }

    @Test
    fun `a hand edited file is checked the same way`() {
        assertThrows(ConfigException::class.java) { SettingsCodec.decode("""{"version":1,"screensaver":{"photos":{"folder":"/a","haMedia":"media-source://x"}}}""") }
        assertThrows(ConfigException::class.java) { SettingsCodec.decode("""{"version":1,"screensaver":{"night":{"start":"late","end":"06:30"}}}""") }
        assertThrows(ConfigException::class.java) { SettingsCodec.decode("""{"version":1,"screensaver":{"idleMinutes":0}}""") }
    }
}
