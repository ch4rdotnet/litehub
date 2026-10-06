package com.chardidathing.litehub.source.photos

// somewhere photos come from. refs are opaque to everyone but the source
interface PhotoSource {
    suspend fun list(): Result<List<String>>

    suspend fun bytes(ref: String): Result<ByteArray>
}
