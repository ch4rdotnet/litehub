package com.chardidathing.litehub.source.ha

import com.chardidathing.litehub.core.model.Entity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuessTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    private val light = Entity("light.desk", "off", obj("""{"friendly_name":"desk","color_mode":"color_temp"}"""))

    @Test
    fun `brightness turns a light on and scales to 255`() {
        val g = Guess.after(light, "turn_on", obj("""{"brightness_pct":50}"""))!!
        assertEquals("on", g.state)
        assertEquals("128", g.attribute("brightness"))
        assertEquals("desk", g.attribute("friendly_name"))
    }

    @Test
    fun `colour and warmth switch the colour mode`() {
        assertEquals("hs", Guess.after(light, "turn_on", obj("""{"hs_color":[120,80]}"""))!!.attribute("color_mode"))
        val warm = Guess.after(light, "turn_on", obj("""{"color_temp_kelvin":2700}"""))!!
        assertEquals("color_temp", warm.attribute("color_mode"))
        assertEquals("2700", warm.attribute("color_temp_kelvin"))
    }

    @Test
    fun `off is off, anything else isn't guessed`() {
        assertEquals("off", Guess.after(light.copy(state = "on"), "turn_off", null)!!.state)
        assertNull(Guess.after(light, "flash", null))
        assertNull(Guess.after(Entity("switch.fan", "off"), "turn_on", null))
    }
}
