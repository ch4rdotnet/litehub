package com.chardidathing.litehub.source.fetch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// the newest stable release of the app on github, for the updater. /releases/latest already
// leaves out drafts and pre-releases, so a beta tag never reaches a hub
class ReleaseCheck(private val http: OkHttpClient, private val api: HttpUrl = GITHUB) {

    data class Release(val version: String, val notes: String, val page: String, val apkUrl: String, val apkBytes: Long)

    // null when the repository has no releases yet
    suspend fun latest(repo: String): Result<Release?> = withContext(Dispatchers.IO) {
        try {
            val url = api.newBuilder().addPathSegments("repos/$repo/releases/latest").build()
            val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
            http.newCall(request).execute().use { r ->
                when {
                    r.code == NOT_FOUND -> Result.success(null)
                    !r.isSuccessful -> Result.failure(IOException("github answered ${r.code}"))
                    else -> Result.success(parse(r.body.string()))
                }
            }
        } catch (e: IOException) {
            Result.failure(IOException(Fetcher.describe(e), e))
        } catch (e: SerializationException) {
            Result.failure(IOException("github's answer couldn't be read", e))
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("github's answer couldn't be read", e))
        }
    }

    companion object {
        private val GITHUB = "https://api.github.com/".toHttpUrl()
        private const val NOT_FOUND = 404
        private val VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?$""")

        // a release without an apk attached isn't one the hub can install
        fun parse(json: String): Release? {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: return null
            fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            val tag = text("tag_name") ?: return null
            val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true } ?: return null
            return Release(
                version = tag.removePrefix("v"),
                notes = text("body").orEmpty(),
                page = text("html_url").orEmpty(),
                apkUrl = (apk["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: return null,
                apkBytes = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }

        // semver order: numbers first, then a version with a pre-release part is older than
        // the same version without one. anything that isn't a version is never newer
        fun newer(candidate: String, current: String): Boolean {
            val a = VERSION.find(candidate) ?: return false
            val b = VERSION.find(current) ?: return true
            for (i in 1..3) {
                val x = a.groupValues[i].toLong()
                val y = b.groupValues[i].toLong()
                if (x != y) return x > y
            }
            val pa = a.groupValues[4]
            val pb = b.groupValues[4]
            return pa.isEmpty() && pb.isNotEmpty()
        }
    }
}
