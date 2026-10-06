package com.chardidathing.litehub.source.photos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// a folder on the device, jpg, png and webp, one level deep
class FolderPhotos(private val folder: File) : PhotoSource {

    override suspend fun list(): Result<List<String>> = withContext(Dispatchers.IO) {
        val files = folder.listFiles() ?: return@withContext Result.failure(IOException("can't read ${folder.path}, is the storage permission granted"))
        Result.success(files.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }.map { it.path }.sorted())
    }

    override suspend fun bytes(ref: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching { File(ref).readBytes() }.recoverCatching { throw IOException("couldn't read ${File(ref).name}", it) }
    }

    private companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
