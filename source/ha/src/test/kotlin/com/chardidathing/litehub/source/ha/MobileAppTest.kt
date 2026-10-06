package com.chardidathing.litehub.source.ha

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAppTest {

    private val server = MockWebServer().apply { start() }
    private val mobileApp = MobileApp(HaCredentials(server.url("/").toString(), "tok"), OkHttpClient())

    @After
    fun tearDown() = server.close()

    @Test
    fun `registers with the token and asks for the websocket push channel`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body("""{"webhook_id":"abc","cloudhook_url":null}""").build())
        val id = mobileApp.register(MobileApp.Device("dev1", "litehub", "1.0", "rockchip", "D156", "12")).getOrThrow()
        assertEquals("abc", id)
        val req = server.takeRequest()
        assertEquals("/api/mobile_app/registrations", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("true", body["app_data"]!!.jsonObject["push_websocket_channel"]!!.jsonPrimitive.content)
        assertEquals("litehub", body["device_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sensor updates go to the webhook without the token`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        mobileApp.update("abc", listOf(MobileApp.State("screen", "binary_sensor", "mdi:monitor", JsonPrimitive(true)))).getOrThrow()
        val req = server.takeRequest()
        assertEquals("/api/webhook/abc", req.url.encodedPath)
        assertEquals(null, req.headers["Authorization"])
        val data = Json.parseToJsonElement(req.body!!.utf8()).jsonObject["data"]!!.jsonArray
        assertEquals("screen", data[0].jsonObject["unique_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a deleted device comes back as gone`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(410).build())
        val result = mobileApp.update("abc", emptyList())
        assertTrue(result.exceptionOrNull() is MobileApp.Gone)
    }
}
