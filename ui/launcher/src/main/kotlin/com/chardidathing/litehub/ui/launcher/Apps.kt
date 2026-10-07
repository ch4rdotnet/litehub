package com.chardidathing.litehub.ui.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import java.io.IOException
import java.util.Locale

// one app android will launch. key is the package, with the profile's serial after an @ when
// it isn't this profile (work apps), so the same app in two profiles stays two apps
data class AppEntry(val key: String, val label: String)

// launchable apps through LauncherApps, so work profile apps show up too. everything here but
// launch blocks on the package manager, keep it off the main thread. icons are drawn at the
// size asked for and kept in a small lru, nothing is held for apps nobody looked at
class Apps(private val context: Context) {

    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val icons = LruCache<String, Bitmap>(ICON_CACHE)

    // a to z, without litehub itself
    fun list(): List<AppEntry> = launcher.profiles.flatMap { user ->
        launcher.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .map { AppEntry(key(it.componentName.packageName, user), it.label.toString()) }
    }.distinctBy { it.key }.sortedBy { it.label.lowercase(Locale.ROOT) }

    // null when it isn't installed (any more)
    fun label(key: String): String? = activity(key)?.label?.toString()

    fun icon(key: String, size: Int): Bitmap? {
        val cacheKey = "$key:$size"
        icons.get(cacheKey)?.let { return it }
        val drawable = activity(key)?.getBadgedIcon(0) ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        icons.put(cacheKey, bitmap)
        return bitmap
    }

    fun launch(key: String): Result<Unit> {
        val info = activity(key) ?: return Result.failure(IOException("it isn't installed on this device"))
        return try {
            launcher.startMainActivity(info.componentName, info.user, null, null)
            Result.success(Unit)
        } catch (e: ActivityNotFoundException) {
            Result.failure(IOException("android couldn't find it to open"))
        } catch (e: SecurityException) {
            Result.failure(IOException("android won't let litehub open it"))
        }
    }

    private fun activity(key: String): LauncherActivityInfo? {
        val pkg = key.substringBefore('@')
        val serial = key.substringAfter('@', "")
        val user = if (serial.isEmpty()) Process.myUserHandle() else serial.toLongOrNull()?.let(users::getUserForSerialNumber) ?: return null
        return launcher.getActivityList(pkg, user).firstOrNull()
    }

    private fun key(pkg: String, user: UserHandle) =
        if (user == Process.myUserHandle()) pkg else "$pkg@${users.getSerialNumberForUser(user)}"

    private companion object {
        // a drawer's worth at one size, a few kb each
        const val ICON_CACHE = 200
    }
}
