package com.chardidathing.litehub.source.ha

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

// lives in its own file so the token never ends up in an exported dashboard
@Serializable
data class HaCredentials(val url: String, val token: String) {

    companion object {
        fun load(file: File): Result<HaCredentials> {
            if (!file.exists()) return Result.failure(IOException("home assistant isn't set up"))
            return try {
                Result.success(Json.decodeFromString(serializer(), file.readText()))
            } catch (e: SerializationException) {
                Result.failure(IOException("ha.json isn't valid", e))
            } catch (e: IllegalArgumentException) {
                Result.failure(IOException("ha.json isn't valid", e))
            } catch (e: IOException) {
                Result.failure(IOException("couldn't read ha.json", e))
            }
        }
    }
}
