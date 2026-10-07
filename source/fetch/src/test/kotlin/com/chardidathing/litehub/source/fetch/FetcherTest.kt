package com.chardidathing.litehub.source.fetch

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FetcherTest {

    private val server = MockWebServer().apply { start() }
    private val cacheDir = Files.createTempDirectory("fetch").toFile()
    private val fetcher = Fetcher(OkHttpClient.Builder().cache(Cache(cacheDir, 1L shl 20)).build())

    @After
    fun tearDown() {
        server.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `an unchanged feed comes back as a 304 with the cached body`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("one").addHeader("ETag", "\"v1\"").build())
        server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"v1\"").build())
        val url = server.url("/feed").toString()
        val first = fetcher.get(url) { r, changed -> changed to r.readText() }.getOrThrow()
        assertEquals(true to "one", first)
        val second = fetcher.get(url) { r, changed -> changed to r.readText() }.getOrThrow()
        assertFalse(second.first)
        assertEquals("one", second.second)
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun `http failures read as short reasons`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        val result = fetcher.get(server.url("/gone").toString()) { r, _ -> r.readText() }
        assertEquals("not found (404)", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an unreachable host is a failure, not an empty body`() = runBlocking {
        val url = server.url("/x").toString()
        server.close()
        val result = fetcher.get(url) { r, _ -> r.readText() }
        assertTrue(result.isFailure)
        assertEquals("can't reach the host", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a parser tripping over the content is a failed source, not a crash`() {
        val file = java.io.File.createTempFile("feed", ".xml").apply { writeText("anything"); deleteOnExit() }
        val result = runBlocking { Fetcher(OkHttpClient()).get(file.absolutePath) { _, _ -> throw ArithmeticException("long overflow") } }
        assertTrue(result.isFailure)
        assertEquals("couldn't read what came back", result.exceptionOrNull()?.message)
    }
}
