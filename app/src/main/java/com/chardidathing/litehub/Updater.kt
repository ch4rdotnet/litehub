package com.chardidathing.litehub

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.chardidathing.litehub.source.fetch.Fetcher
import com.chardidathing.litehub.source.fetch.ReleaseCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

// checks github for a newer release when asked, downloads it and hands it to android's
// installer. android asks on screen before anything installs, the hub never does it quietly
class Updater(private val app: LitehubApp) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data object NoReleases : State
        data class Available(val release: ReleaseCheck.Release) : State
        data class Downloading(val release: ReleaseCheck.Release) : State
        data class Installing(val release: ReleaseCheck.Release) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    // big downloads don't go through the shared http cache, they'd push every feed out of it
    private val http by lazy { app.http.newBuilder().cache(null).build() }
    private val check by lazy { ReleaseCheck(http) }
    private val dir get() = File(app.cacheDir, "updates")

    val current: String = BuildConfig.VERSION_NAME

    // android's own switch for letting this app install others, the tab links to it
    fun allowed(): Boolean = app.packageManager.canRequestPackageInstalls()

    suspend fun check() {
        _state.value = State.Checking
        _state.value = check.latest(BuildConfig.UPDATE_REPO).fold(
            { r ->
                when {
                    r == null -> State.NoReleases
                    ReleaseCheck.newer(r.version, current) -> State.Available(r)
                    else -> State.UpToDate
                }
            },
            { State.Failed("couldn't check, ${it.message}") },
        )
    }

    suspend fun install(release: ReleaseCheck.Release) {
        _state.value = State.Downloading(release)
        val apk = try {
            withContext(Dispatchers.IO) { download(release) }
        } catch (e: IOException) {
            _state.value = State.Failed("couldn't download it, ${Fetcher.describe(e)}")
            return
        }
        val problem = withContext(Dispatchers.IO) { problem(apk) }
        if (problem != null) {
            apk.delete()
            _state.value = State.Failed(problem)
            return
        }
        _state.value = State.Installing(release)
        try {
            withContext(Dispatchers.IO) { commit(apk) }
            AppLog.add("update to ${release.version} handed to android's installer")
        } catch (e: IOException) {
            _state.value = State.Failed("android's installer wouldn't take it, ${e.message}")
        }
    }

    // from the installer's answer, android has its own screen for the prompt
    fun onResult(status: Int, message: String?) {
        val release = (_state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> AppLog.add("updated to ${release?.version}")
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.add("update cancelled on the install prompt")
                _state.value = release?.let { State.Available(it) } ?: State.Idle
            }
            else -> {
                AppLog.add("update failed, ${message ?: "android didn't say why"}")
                _state.value = State.Failed("android didn't install it, ${message ?: "it didn't say why"}")
            }
        }
    }

    private fun download(release: ReleaseCheck.Release): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "litehub-${release.version}.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("github answered ${r.code}")
            val limit = if (release.apkBytes > 0) release.apkBytes else MAX_APK_BYTES
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw IOException("it was bigger than the release said")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
        return file
    }

    // the installer would refuse these too, but with an error nobody can read on a wall
    private fun problem(apk: File): String? {
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val incoming = pm.getPackageArchiveInfo(apk.path, flags) ?: return "the download isn't an app android can read"
        if (incoming.packageName != app.packageName) return "the download is a different app"
        val installed = pm.getPackageInfo(app.packageName, flags)
        if (code(incoming) <= code(installed)) return "the download isn't newer than what's installed"
        val theirs = signers(incoming) ?: return null
        val ours = signers(installed) ?: return null
        if (theirs != ours) {
            return "the download is signed with a different key, this hub is running a build that wasn't made by the release workflow"
        }
        return null
    }

    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    // null when android didn't read them from the archive, the installer still checks
    private fun signers(p: PackageInfo): Set<String>? =
        p.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()?.ifEmpty { null }

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("litehub.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(app, UpdateResult::class.java)
            // mutable, android fills in the outcome
            val pending = PendingIntent.getBroadcast(app, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    private companion object {
        // when github doesn't give the size, an app this small is never bigger than this
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
    }
}
