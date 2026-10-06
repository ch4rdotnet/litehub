package com.chardidathing.litehub.source.photos

import com.chardidathing.litehub.source.ha.EntityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// a folder in ha's media browser ("media-source://media_source/local/frame"), and the folders
// directly inside it. each photo is resolved to a short lived signed url right before loading
class HaMediaPhotos(private val ha: EntityRepository, private val folder: String, private val http: OkHttpClient) : PhotoSource {

    override suspend fun list(): Result<List<String>> {
        val top = browse(folder).getOrElse { return Result.failure(it) }
        val photos = images(top).toMutableList()
        for (dir in folders(top)) photos += browse(dir).map(::images).getOrDefault(emptyList())
        return Result.success(photos)
    }

    override suspend fun bytes(ref: String): Result<ByteArray> {
        val resolved = ha.command("media_source/resolve_media", buildJsonObject { put("media_content_id", ref) })
            .getOrElse { return Result.failure(it) }
        val path = ((resolved as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: return Result.failure(IOException("home assistant didn't give a url for that photo"))
        val url = if (path.startsWith("http")) path else (ha.baseUrl ?: return Result.failure(IOException("home assistant isn't set up"))) + path
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) Result.success(r.body.bytes()) else Result.failure(IOException("home assistant refused the photo (${r.code})"))
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    private suspend fun browse(id: String) = ha.command("media_source/browse_media", buildJsonObject { put("media_content_id", id) })

    companion object {
        private fun children(result: JsonElement?) = ((result as? JsonObject)?.get("children") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        fun images(result: JsonElement?): List<String> =
            children(result).filter { it.str("media_class") == "image" }.mapNotNull { it.str("media_content_id") }

        fun folders(result: JsonElement?): List<String> =
            children(result).filter { it.str("media_class") == "directory" }.mapNotNull { it.str("media_content_id") }
    }
}
