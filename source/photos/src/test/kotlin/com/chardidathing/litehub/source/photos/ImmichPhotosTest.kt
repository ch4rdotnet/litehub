package com.chardidathing.litehub.source.photos

import com.chardidathing.litehub.core.model.ImmichSettings
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImmichPhotosTest {

    private val server = MockWebServer()

    @Before
    fun up() = server.start()

    @After
    fun down() = server.close()

    private fun photos() = ImmichPhotos(ImmichSettings(server.url("/").toString(), "key", "album1"), OkHttpClient())

    @Test
    fun `the album is searched a page at a time`() {
        server.enqueue(MockResponse.Builder().body("""{"assets":{"items":[{"id":"a1","type":"IMAGE"}],"nextPage":"2"}}""").build())
        server.enqueue(MockResponse.Builder().body("""{"assets":{"items":[{"id":"a2","type":"IMAGE"}],"nextPage":null}}""").build())
        assertEquals(listOf("a1", "a2"), runBlocking { photos().list() }.getOrThrow())
        val first = server.takeRequest()
        assertEquals("/api/search/metadata", first.url.encodedPath)
        assertEquals("key", first.headers["x-api-key"])
        val body = first.body!!.utf8()
        assertTrue(body, body.contains("\"albumIds\":[\"album1\"]") && body.contains("\"page\":1"))
        assertTrue(server.takeRequest().body!!.utf8().contains("\"page\":2"))
    }

    @Test
    fun `a key without the permissions says which`() {
        server.enqueue(MockResponse.Builder().code(403).build())
        val e = runBlocking { photos().list() }.exceptionOrNull()
        assertEquals("the immich api key needs the asset.read and asset.view permissions", e?.message)
    }
}
