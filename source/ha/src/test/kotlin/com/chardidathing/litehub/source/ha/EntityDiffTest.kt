package com.chardidathing.litehub.source.ha

import com.chardidathing.litehub.core.model.Entity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityDiffTest {

    private fun body(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `adds, changes and removes`() {
        val entities = HashMap<String, Entity>()
        EntityDiff.apply(entities, body("""{"a":{"light.a":{"s":"on","a":{"brightness":255,"friendly_name":"A"},"lc":1.0},"light.b":{"s":"off","lc":1.0}}}"""))
        val result = EntityDiff.apply(
            entities,
            body("""{"c":{"light.a":{"+":{"s":"on","a":{"brightness":128}},"-":{"a":["friendly_name"]}}},"r":["light.b"]}"""),
        )
        val a = entities.getValue("light.a")
        assertEquals("128", a.attribute("brightness"))
        assertNull(a.attribute("friendly_name"))
        assertEquals(1.0, a.lastChanged, 0.0)
        assertNull(entities["light.b"])
        assertEquals(setOf("light.a"), result.changed)
        assertEquals(setOf("light.b"), result.removed)
    }

    @Test
    fun `ignores changes for entities it never saw`() {
        val entities = HashMap<String, Entity>()
        val result = EntityDiff.apply(entities, body("""{"c":{"light.x":{"+":{"s":"on"}}}}"""))
        assertEquals(emptySet<String>(), result.changed)
    }
}
