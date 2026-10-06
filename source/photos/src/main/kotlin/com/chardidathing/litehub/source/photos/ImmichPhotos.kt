package com.chardidathing.litehub.source.photos

import com.chardidathing.litehub.core.model.ImmichSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// an immich album. photos come as immich's own preview size, already close to a panel's
class ImmichPhotos(private val settings: ImmichSettings, private val http: OkHttpClient) : PhotoSource {

    private val base = settings.url.trimEnd('/')

    override suspend fun list(): Result<List<String>> = get("$base/api/albums/${settings.albumId}").mapCatching { body ->
        parseAlbum(String(body))
    }

    override suspend fun bytes(ref: String): Result<ByteArray> = get("$base/api/assets/$ref/thumbnail?size=preview")

    private suspend fun get(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("x-api-key", settings.apiKey).header("Accept", "*/*").build()
            http.newCall(request).execute().use { r ->
                when {
                    r.code == UNAUTHORISED -> Result.failure(IOException("immich didn't accept the api key"))
                    r.code == NOT_FOUND -> Result.failure(IOException("immich has no album ${settings.albumId}"))
                    !r.isSuccessful -> Result.failure(IOException("immich refused (${r.code})"))
                    else -> Result.success(r.body.bytes())
                }
            }
        } catch (e: IOException) {
            Result.failure(IOException("can't reach immich, ${e.message}", e))
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("the immich url isn't valid", e))
        }
    }

    companion object {
        private const val UNAUTHORISED = 401
        private const val NOT_FOUND = 404

        // images only, videos in the album are skipped
        fun parseAlbum(json: String): List<String> {
            val assets = (Json.parseToJsonElement(json) as? JsonObject)?.get("assets") as? JsonArray ?: return emptyList()
            return assets.mapNotNull { a ->
                val o = a as? JsonObject ?: return@mapNotNull null
                if ((o["type"] as? JsonPrimitive)?.contentOrNull != "IMAGE") return@mapNotNull null
                (o["id"] as? JsonPrimitive)?.contentOrNull
            }
        }
    }
}
