package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.ImmichSettings
import com.chardidathing.litehub.core.model.NightMode
import com.chardidathing.litehub.core.model.PhotoSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFormTest {

    private val base = SettingsCodec.defaults
    private fun edits(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    @Test
    fun `every field has a value and nothing changes without edits`() {
        val values = DeviceForm.values(base)
        for (field in DeviceForm.sections.flatMap { it.fields }) assertTrue(field.key, values.containsKey(field.key))
        assertEquals(base, DeviceForm.apply(base, JsonObject(emptyMap())))
    }

    @Test
    fun `edits land in the nested settings`() {
        val s = DeviceForm.apply(
            base,
            edits(
                "screensaver.idleMinutes" to 3, "night.enabled" to true, "night.start" to "21:30", "night.mode" to "blank",
                "notifications.bannerSeconds" to 9, "screensaver.lightWakeRatio" to 2.5, "screensaver.wakeEntities" to listOf("binary_sensor.door"),
            ),
        )
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
        val e = assertThrows(ConfigException::class.java) { DeviceForm.apply(base, edits("notifications.maxBanners" to 40)) }
        assertEquals("most banners at once is 1 to 10", e.message)
    }

    @Test
    fun `hidden fields aren't checked`() {
        val s = DeviceForm.apply(base, edits("night.enabled" to false, "night.start" to "whenever"))
        assertNull(s.screensaver.night)
        assertThrows(ConfigException::class.java) { DeviceForm.apply(base, edits("night.enabled" to true, "night.start" to "whenever")) }
    }

    @Test
    fun `a blank secret keeps the stored one`() {
        val withImmich = base.copy(screensaver = base.screensaver.copy(photos = PhotoSettings(immich = ImmichSettings("http://i", "key", "album"))))
        assertEquals("", DeviceForm.values(withImmich)["photos.immichKey"].let { (it as JsonPrimitive).content })
        val s = DeviceForm.apply(withImmich, edits("photos.immichAlbum" to "other", "photos.immichKey" to ""))
        assertEquals("key", s.screensaver.photos!!.immich!!.apiKey)
        assertEquals("other", s.screensaver.photos!!.immich!!.albumId)
        assertThrows(ConfigException::class.java) { DeviceForm.apply(base, edits("photos.source" to "immich", "photos.immichUrl" to "http://i", "photos.immichAlbum" to "a")) }
    }

    @Test
    fun `turning dlna on makes a uuid once`() {
        val on = DeviceForm.apply(base, edits("dlna.enabled" to true))
        assertNotNull(on.dlna.uuid)
        assertEquals(on.dlna.uuid, DeviceForm.apply(on, edits("dlna.enabled" to false)).dlna.uuid)
    }

    @Test
    fun `unknown keys and clashing ports are refused`() {
        assertThrows(ConfigException::class.java) { DeviceForm.apply(base, edits("pin" to "1234")) }
        assertThrows(ConfigException::class.java) { DeviceForm.apply(base, edits("dlna.port" to 8080)) }
    }

    @Test
    fun `a hand edited file is checked the same way`() {
        assertThrows(ConfigException::class.java) { SettingsCodec.decode("""{"version":1,"screensaver":{"photos":{"folder":"/a","haMedia":"media-source://x"}}}""") }
        assertThrows(ConfigException::class.java) { SettingsCodec.decode("""{"version":1,"screensaver":{"night":{"start":"late","end":"06:30"}}}""") }
        assertThrows(ConfigException::class.java) { SettingsCodec.decode("""{"version":1,"screensaver":{"idleMinutes":0}}""") }
    }
}
