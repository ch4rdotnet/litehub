package com.chardidathing.litehub.source.ha

import com.chardidathing.litehub.core.model.EntityChoice
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityCatalogueTest {

    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `names come from states, areas from the entity or its device`() {
        val choices = EntityCatalogue.build(
            states = j("""[{"entity_id":"light.lamp","attributes":{"friendly_name":"Lamp"}},
                           {"entity_id":"sensor.temp","attributes":{"friendly_name":"Temp"}},
                           {"entity_id":"sun.sun","attributes":{}}]"""),
            entities = j("""{"entities":[{"ei":"light.lamp","ai":"lounge"},{"ei":"sensor.temp","di":"d1"}]}"""),
            devices = j("""[{"id":"d1","area_id":"kitchen"}]"""),
            areas = j("""[{"area_id":"lounge","name":"Lounge"},{"area_id":"kitchen","name":"Kitchen"}]"""),
        )
        assertEquals(
            listOf(EntityChoice("sensor.temp", "Temp", "Kitchen"), EntityChoice("light.lamp", "Lamp", "Lounge"), EntityChoice("sun.sun", "sun.sun", null)),
            choices,
        )
    }

    @Test
    fun `missing registries still give a flat list`() {
        val choices = EntityCatalogue.build(j("""[{"entity_id":"light.a","attributes":{"friendly_name":"A"}}]"""), null, null, null)
        assertEquals(listOf(EntityChoice("light.a", "A", null)), choices)
    }
}
