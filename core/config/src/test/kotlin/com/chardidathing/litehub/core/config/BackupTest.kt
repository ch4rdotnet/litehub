package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.CompanionRegistration
import com.chardidathing.litehub.core.model.DlnaSettings
import com.chardidathing.litehub.core.model.ImmichSettings
import com.chardidathing.litehub.core.model.PhotoSettings
import com.chardidathing.litehub.core.model.ScreensaverSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val companion = CompanionRegistration("device", "webhook", "litehub test")

    private val hub = Backup.Hub(
        config = Fixtures.config,
        sources = """{ "version": 1, "calendars": [], "feeds": [] }""",
        settings = SettingsCodec.defaults.copy(
            pin = "pbkdf2\$1\$c2FsdA==\$aGFzaA==",
            companion = companion,
            dlna = DlnaSettings(enabled = true, uuid = "uuid-1"),
            screensaver = ScreensaverSettings(photos = PhotoSettings(immich = ImmichSettings("https://immich.example", "immich-key", "album"))),
        ),
        haUrl = "http://ha.local:8123",
        haToken = "ha-token",
    )

    // a fresh hub, nothing set up
    private val blank = Backup.Hub(null, null, SettingsCodec.defaults, null, null)

    private fun zip(passphrase: String?): ByteArray =
        ByteArrayOutputStream().also { Backup.write(hub, "1.0.0", "2026-10-07T12:00:00", passphrase, it) }.toByteArray()

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun `no secret is anywhere in the zip in the clear`() {
        for (passphrase in listOf(null, "correct horse")) {
            val all = entries(zip(passphrase)).values.joinToString("\n")
            for (secret in listOf("ha-token", "immich-key", "webhook", "aGFzaA==", "uuid-1")) {
                assertFalse("$secret leaked", all.contains(secret))
            }
        }
    }

    @Test
    fun `secrets come back with the passphrase`() {
        val read = Backup.read(ByteArrayInputStream(zip("correct horse")))
        assertTrue(read.hasSecrets)
        val restored = Backup.restore(read, read.secrets("correct horse"), blank)
        assertEquals(hub, restored)
    }

    @Test
    fun `a wrong passphrase says so`() {
        val read = Backup.read(ByteArrayInputStream(zip("correct horse")))
        val e = assertThrows(ConfigException::class.java) { read.secrets("wrong horse") }
        assertEquals("that passphrase doesn't open this backup's secrets", e.message)
    }

    @Test
    fun `a short passphrase is refused`() {
        assertThrows(ConfigException::class.java) { zip("short") }
    }

    @Test
    fun `without secrets this hub keeps its own`() {
        val here = hub.copy(settings = hub.settings.copy(pin = "mine", companion = null, dlna = DlnaSettings(uuid = "uuid-here")))
        val read = Backup.read(ByteArrayInputStream(zip(null)))
        assertFalse(read.hasSecrets)
        val restored = Backup.restore(read, null, here)
        assertEquals("mine", restored.settings.pin)
        assertNull(restored.settings.companion)
        assertEquals("uuid-here", restored.settings.dlna.uuid)
        assertEquals("ha-token", restored.haToken)
        assertEquals("immich-key", restored.settings.screensaver.photos?.immich?.apiKey)
    }

    @Test
    fun `a token isn't kept for a different server`() {
        val here = hub.copy(haUrl = "http://other:8123", settings = hub.settings.copy(
            screensaver = ScreensaverSettings(photos = PhotoSettings(immich = ImmichSettings("https://other", "other-key", "album"))),
        ))
        val restored = Backup.restore(Backup.read(ByteArrayInputStream(zip(null))), null, here)
        assertEquals("http://ha.local:8123", restored.haUrl)
        assertEquals("", restored.haToken)
        assertEquals("", restored.settings.screensaver.photos?.immich?.apiKey)
    }

    @Test
    fun `something that isn't a backup is refused`() {
        val other = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("photo.jpg"))
                z.write(ByteArray(4))
                z.closeEntry()
            }
        }.toByteArray()
        assertEquals("this isn't a litehub backup", assertThrows(ConfigException::class.java) { Backup.read(ByteArrayInputStream(other)) }.message)
        assertThrows(ConfigException::class.java) { Backup.read(ByteArrayInputStream("not a zip".toByteArray())) }
    }

    @Test
    fun `a backup from a newer litehub is refused`() {
        val newer = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("backup.json"))
                z.write("""{"format": 99}""".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        assertEquals("this backup is from a newer litehub, update this one first", assertThrows(ConfigException::class.java) { Backup.read(ByteArrayInputStream(newer)) }.message)
    }
}
