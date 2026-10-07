package com.chardidathing.litehub

import android.os.Build
import com.chardidathing.litehub.core.config.Backup
import com.chardidathing.litehub.source.ha.HaCredentials
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json

// the whole hub to and from a zip, for the settings screen and the web editor. everything here
// reads or writes disk and the passphrase is slow on purpose, so none of it on main
class Backups(private val app: LitehubApp) {

    // passphrase null leaves the secrets out
    fun write(passphrase: String?, out: OutputStream) {
        val created = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
        Backup.write(current(), BuildConfig.VERSION_NAME, created, passphrase, out)
    }

    // the dashboards are checked against the themes too, so a backup that reads restores whole
    fun read(input: InputStream): Backup.Contents = Backup.read(input).also { b -> b.config?.let(app::checkConfig) }

    // passphrase null restores without the backup's secrets, this hub keeps its own
    fun restore(backup: Backup.Contents, passphrase: String?) {
        val secrets = passphrase?.let(backup::secrets)
        val next = Backup.restore(backup, secrets, current())
        next.config?.let { File(app.filesDir, LitehubApp.CONFIG_FILE).writeAtomic(it) }
        next.sources?.let { File(app.filesDir, LitehubApp.SOURCES_FILE).writeAtomic(it) }
        val ha = File(app.filesDir, LitehubApp.HA_FILE)
        val url = next.haUrl
        if (url == null) ha.delete() else ha.writeAtomic(Json.encodeToString(HaCredentials.serializer(), HaCredentials(url, next.haToken.orEmpty())))
        app.updateSettings(next.settings)
        AppLog.add("restored the backup from ${backup.created}" + if (secrets != null) " with its secrets" else "")
    }

    // the drop folder, where adb or a file manager can take a backup off the hub
    fun folder(): File? = app.getExternalFilesDir(null)

    fun save(passphrase: String?): File {
        val dir = folder() ?: throw java.io.IOException("there's no storage to save to")
        val file = File(dir, "litehub-backup-${LocalDateTime.now().format(STAMP)}.zip")
        file.outputStream().use { write(passphrase, it) }
        AppLog.add("saved a backup as ${file.name}" + if (passphrase != null) " with its secrets" else "")
        return file
    }

    // newest first. before android 11 other apps could have put a file there, so they're not offered
    fun dropped(): List<File> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return folder()?.listFiles { f -> f.isFile && f.name.endsWith(".zip") }.orEmpty().sortedByDescending { it.lastModified() }
    }

    private fun current(): Backup.Hub {
        fun text(name: String) = File(app.filesDir, name).takeIf { it.exists() }?.readText()
        val ha = HaCredentials.load(File(app.filesDir, LitehubApp.HA_FILE)).getOrNull()
        return Backup.Hub(text(LitehubApp.CONFIG_FILE), text(LitehubApp.SOURCES_FILE), app.settings, ha?.url, ha?.token)
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
