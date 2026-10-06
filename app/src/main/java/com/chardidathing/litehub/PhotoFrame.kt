package com.chardidathing.litehub

import android.graphics.Bitmap
import com.chardidathing.litehub.core.model.PhotoSettings
import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.source.photos.FolderPhotos
import com.chardidathing.litehub.source.photos.HaMediaPhotos
import com.chardidathing.litehub.source.photos.ImmichPhotos
import com.chardidathing.litehub.source.photos.PhotoDecoder
import com.chardidathing.litehub.source.photos.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

// the next photo for the screensaver, from whichever source is set, shuffled, the list
// refreshed now and then so new photos turn up
class PhotoFrame(settings: PhotoSettings, ha: EntityRepository, http: OkHttpClient) {

    private val source: PhotoSource = when {
        settings.folder != null -> FolderPhotos(File(settings.folder))
        settings.immich != null -> ImmichPhotos(settings.immich!!, http)
        else -> HaMediaPhotos(ha, settings.haMedia!!, http)
    }
    private var queue = ArrayDeque<String>()
    private var listedAt = 0L

    suspend fun next(width: Int, height: Int): Result<Bitmap> {
        if (queue.isEmpty() || System.currentTimeMillis() - listedAt > RELIST_MS) {
            val all = source.list().getOrElse { return Result.failure(it) }
            if (all.isEmpty()) return Result.failure(IOException("there are no photos there yet"))
            queue = ArrayDeque(all.shuffled())
            listedAt = System.currentTimeMillis()
        }
        // a photo that won't load is skipped rather than ending the slideshow
        repeat(queue.size) {
            val ref = queue.removeFirst()
            val bytes = source.bytes(ref).getOrNull() ?: return@repeat
            val bitmap = withContext(Dispatchers.Default) { runCatching { PhotoDecoder.decode(bytes, width, height) }.getOrNull() }
            if (bitmap != null) return Result.success(bitmap)
        }
        return Result.failure(IOException("none of the photos would load"))
    }

    private companion object {
        const val RELIST_MS = 60 * 60_000L
    }
}
