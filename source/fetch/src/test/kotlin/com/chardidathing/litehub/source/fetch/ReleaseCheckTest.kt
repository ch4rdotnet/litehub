package com.chardidathing.litehub.source.fetch

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReleaseCheckTest {

    private val server = MockWebServer()

    @Before
    fun up() = server.start()

    @After
    fun down() = server.close()

    private fun check() = ReleaseCheck(OkHttpClient(), server.url("/"))

    @Test
    fun `the latest release and its apk`() {
        server.enqueue(
            MockResponse.Builder().body(
                """{"tag_name":"v1.2.3","html_url":"https://github.com/o/r/releases/tag/v1.2.3","body":"fixes",
                   "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
                             {"name":"litehub-v1.2.3.apk","browser_download_url":"https://x/litehub.apk","size":1507705}]}""",
            ).build(),
        )
        val r = runBlocking { check().latest("o/r") }.getOrThrow()!!
        assertEquals("1.2.3", r.version)
        assertEquals("https://x/litehub.apk", r.apkUrl)
        assertEquals(1507705L, r.apkBytes)
        assertEquals("/repos/o/r/releases/latest", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `no releases yet is no release, not a failure`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(runBlocking { check().latest("o/r") }.getOrThrow())
    }

    @Test
    fun `github refusing is a failure that says so`() {
        server.enqueue(MockResponse.Builder().code(403).build())
        assertEquals("github answered 403", runBlocking { check().latest("o/r") }.exceptionOrNull()?.message)
    }

    @Test
    fun `a release without an apk is skipped`() {
        assertNull(ReleaseCheck.parse("""{"tag_name":"v1.0.0","assets":[]}"""))
    }

    @Test
    fun `versions compare like semver`() {
        assertTrue(ReleaseCheck.newer("1.2.4", "1.2.3"))
        assertTrue(ReleaseCheck.newer("1.10.0", "1.9.9"))
        assertTrue(ReleaseCheck.newer("1.0.0", "1.0.0-beta.1"))
        assertTrue(ReleaseCheck.newer("0.1.0", "0.0.0-dev"))
        assertFalse(ReleaseCheck.newer("1.2.3", "1.2.3"))
        assertFalse(ReleaseCheck.newer("1.2.2", "1.2.3"))
        assertFalse(ReleaseCheck.newer("1.0.0-beta.1", "1.0.0"))
        assertFalse(ReleaseCheck.newer("not-a-version", "1.0.0"))
    }
}
