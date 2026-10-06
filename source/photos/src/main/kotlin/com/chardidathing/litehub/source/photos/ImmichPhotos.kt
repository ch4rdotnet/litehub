package com.chardidathing.litehub.source.photos

import com.chardidathing.litehub.core.model.ImmichSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// an immich album. photos come as immich's own preview size, already close to a panel's. the key
// needs asset.read (to search the album) and asset.view (to load each photo)
class ImmichPhotos(private val settings: ImmichSettings, private val http: OkHttpClient) : PhotoSource {

    private val base = settings.url.trimEnd('/')

    // the album's images a page at a time. immich 2 and on don't list assets on the album itself
    override suspend fun list(): Result<List<String>> {
        val ids = ArrayList<String>()
        var page: String? = "1"
        while (page != null) {
            val body = buildJsonObject {
                putJsonArray("albumIds") { add(settings.albumId) }
                put("type", "IMAGE")
                put("size", PAGE_SIZE)
                put("page", page.toInt())
            }
            val result = send(Request.Builder().url("$base/api/search/metadata").post(body.toString().toRequestBody(JSON))).getOrElse { return Result.failure(it) }
            val (items, next) = parseSearch(String(result))
            ids += items
            page = next
        }
        return Result.success(ids)
    }

    override suspend fun bytes(ref: String): Result<ByteArray> = send(Request.Builder().url("$base/api/assets/$ref/thumbnail?size=preview"))

    private suspend fun send(builder: Request.Builder): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = builder.header("x-api-key", settings.apiKey).header("Accept", "*/*").build()
            http.newCall(request).execute().use { r ->
                when {
                    r.code == UNAUTHORISED -> Result.failure(IOException("immich didn't accept the api key"))
                    r.code == FORBIDDEN -> Result.failure(IOException("the immich api key needs the asset.read and asset.view permissions"))
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
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val PAGE_SIZE = 250
        private val JSON = "application/json".toMediaType()

        // a search page's image ids and the next page, null after the last. videos are skipped
        // even though the search asks for images, an older server may ignore the type
        fun parseSearch(json: String): Pair<List<String>, String?> {
            val assets = (Json.parseToJsonElement(json) as? JsonObject)?.get("assets") as? JsonObject ?: return emptyList<String>() to null
            val items = (assets["items"] as? JsonArray).orEmpty().mapNotNull { a ->
                val o = a as? JsonObject ?: return@mapNotNull null
                if ((o["type"] as? JsonPrimitive)?.contentOrNull != "IMAGE") return@mapNotNull null
                (o["id"] as? JsonPrimitive)?.contentOrNull
            }
            val next = (assets["nextPage"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.toIntOrNull() != null }
            return items to next
        }
    }
}
