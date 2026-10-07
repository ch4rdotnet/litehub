package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.ThemeMode
import com.chardidathing.litehub.core.model.ThemeSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {

    private val base = Fixtures.base

    @Test
    fun `override replaces only what it names`() {
        val themes = Themes(listOf(base), listOf(Fixtures.obj("""{"id":"mine","extends":"base","colors":{"primary":"#ff9800"}}""")))
        val mine = themes["mine"]
        assertNotEquals(base.colors.primary, mine.colors.primary)
        assertEquals(base.colors.copy(primary = mine.colors.primary), mine.colors)
        assertEquals(base.type, mine.type)
        assertEquals("mine", mine.name)
    }

    @Test
    fun `user themes can extend ones declared later`() {
        val themes = Themes(
            listOf(base),
            listOf(
                Fixtures.obj("""{"id":"b","extends":"a","radii":{"small":2}}"""),
                Fixtures.obj("""{"id":"a","extends":"base","font":"system"}"""),
            ),
        )
        assertEquals("system", themes["b"].font)
        assertEquals(base.radii.medium, themes["b"].radii.medium)
    }

    @Test
    fun `rejects a cycle`() {
        val e = failure {
            Themes(listOf(base), listOf(
                Fixtures.obj("""{"id":"a","extends":"b"}"""),
                Fixtures.obj("""{"id":"b","extends":"a"}"""),
            ))
        }
        assertTrue(e.message!!.contains("extends itself"))
    }

    @Test
    fun `rejects an unknown parent`() {
        failure { Themes(listOf(base), listOf(Fixtures.obj("""{"id":"a","extends":"nope"}"""))) }
    }

    @Test
    fun `rejects a bad colour`() {
        val e = failure { Themes(listOf(base), listOf(Fixtures.obj("""{"id":"a","extends":"base","colors":{"primary":"purple"}}"""))) }
        assertTrue(e.message!!.contains("purple"))
    }

    @Test
    fun `selects by mode`() {
        val themes = Themes(listOf(base), listOf(Fixtures.obj("""{"id":"night","extends":"base"}""")))
        val system = ThemeSelection(ThemeMode.SYSTEM, light = "base", dark = "night")
        assertEquals("night", themes.select(system, systemDark = true).id)
        assertEquals("base", themes.select(system, systemDark = false).id)
        assertEquals("base", themes.select(system.copy(mode = ThemeMode.LIGHT), systemDark = true).id)
        assertEquals("night", themes.select(system.copy(mode = ThemeMode.DARK), systemDark = false).id)
    }

    private fun failure(block: () -> Unit): ConfigException =
        runCatching(block).exceptionOrNull() as? ConfigException
            ?: throw AssertionError("expected a ConfigException")

    @Test
    fun `a chain of themes built to overflow is refused`() {
        val chain = (0 until 30).map { i -> Fixtures.obj("""{"id":"t$i","extends":"${if (i == 29) "base" else "t${i + 1}"}"}""") }
        val e = org.junit.Assert.assertThrows(ConfigException::class.java) { Themes(listOf(base), chain) }
        assertTrue(e.message!!.contains("builds on more than"))
    }
}
