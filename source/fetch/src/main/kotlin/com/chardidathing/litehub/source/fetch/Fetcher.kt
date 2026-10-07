package com.chardidathing.litehub.source.fetch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.Reader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// gets a url through okhttp's disk cache, so an unchanged feed is a 304. parse gets the body as
// a reader plus whether it changed since last time, and can skip reading when it didn't.
// webcal:// is https, file:// and bare paths read from disk
class Fetcher(private val http: OkHttpClient) {

    suspend fun <T> get(url: String, parse: (body: Reader, changed: Boolean) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            if (url.startsWith("file://") || url.startsWith("/")) {
                val file = File(url.removePrefix("file://"))
                return@withContext Result.success(file.reader().use { parse(it, true) })
            }
            val request = Request.Builder().url(normalise(url)).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException(status(response.code)))
                // no network response means served fresh from cache, a 304 means validated
                val network = response.networkResponse
                val changed = network != null && network.code != 304
                Result.success(parse(response.body.charStream(), changed))
            }
        } catch (e: IOException) {
            Result.failure(IOException(describe(e), e))
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("the url isn't valid", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // a parser tripping over what someone else's server sent is that source failing,
            // never the hub crashing
            Result.failure(IOException("couldn't read what came back", e))
        }
    }

    private fun normalise(url: String) = when {
        url.startsWith("webcal://", ignoreCase = true) -> "https://" + url.substring(9)
        url.startsWith("webcals://", ignoreCase = true) -> "https://" + url.substring(10)
        else -> url
    }

    private fun status(code: Int) = when (code) {
        401, 403 -> "not allowed ($code)"
        404, 410 -> "not found ($code)"
        in 500..599 -> "the server failed ($code)"
        else -> "unexpected response ($code)"
    }

    companion object {
        fun describe(e: IOException): String = when (e) {
            is UnknownHostException -> "can't find the host"
            is ConnectException -> "can't reach the host"
            is SocketTimeoutException -> "timed out"
            is SSLException -> "the certificate isn't trusted"
            is java.io.FileNotFoundException -> "file not found"
            else -> e.message?.takeIf { it.isNotBlank() && it.first().isLowerCase() } ?: "couldn't fetch"
        }
    }
}
