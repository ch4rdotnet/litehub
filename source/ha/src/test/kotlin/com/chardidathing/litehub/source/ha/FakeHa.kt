package com.chardidathing.litehub.source.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

// just enough of the ha websocket api to drive the client. call accept() once per connection
class FakeHa(private val token: String) : AutoCloseable {

    private val server = MockWebServer().apply { start() }
    private val states = ConcurrentHashMap<String, String>()

    val received = LinkedBlockingQueue<JsonObject>()

    @Volatile var failServices = false

    // sent back as result.response when a call asks for return_response
    @Volatile var serviceResponse: String? = null

    @Volatile private var socket: WebSocket? = null

    @Volatile private var subscription: Int? = null

    @Volatile private var pushSubscription: Int? = null

    fun push(message: String) {
        val sub = pushSubscription ?: return
        socket?.send(event(sub, """{"message":"$message","title":"hub"}"""))
    }

    val url: String get() = server.url("/").toString()

    fun accept() = server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())

    fun set(id: String, state: String) {
        states[id] = state
    }

    fun change(id: String, state: String) {
        states[id] = state
        val sub = subscription ?: return
        socket?.send(event(sub, """{"c":{"$id":{"+":{"s":"$state","lc":2.0}}}}"""))
    }

    // what ha does when it restarts
    fun restart() {
        socket?.close(1001, "going away")
    }

    override fun close() = server.close()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = Json.parseToJsonElement(text).jsonObject
            received += msg
            val id = msg["id"]?.jsonPrimitive?.int
            when (msg["type"]?.jsonPrimitive?.contentOrNull) {
                "auth" -> if (msg["access_token"]?.jsonPrimitive?.contentOrNull == token) {
                    webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
                } else {
                    webSocket.send("""{"type":"auth_invalid","message":"Invalid access token or password"}""")
                    webSocket.close(1000, null)
                }
                "subscribe_entities" -> {
                    webSocket.send(result(id!!, success = true))
                    subscription = id
                    val requested = (msg["entity_ids"] as JsonArray).map { it.jsonPrimitive.content }
                    val added = requested.filter { states.containsKey(it) }
                        .joinToString(",") { """"$it":{"s":"${states[it]}","a":{"friendly_name":"$it"},"lc":1.0}""" }
                    webSocket.send(event(id, """{"a":{$added}}"""))
                }
                "unsubscribe_events" -> webSocket.send(result(id!!, success = true))
                "mobile_app/push_notification_channel" -> {
                    val known = msg["webhook_id"]?.jsonPrimitive?.contentOrNull != "deleted"
                    webSocket.send(result(id!!, success = known))
                    if (known) pushSubscription = id
                }
                "call_service" -> {
                    val response = serviceResponse.takeIf { msg["return_response"]?.jsonPrimitive?.content == "true" }
                    if (response != null && !failServices) {
                        webSocket.send("""{"id":$id,"type":"result","success":true,"result":{"context":{},"response":$response}}""")
                    } else {
                        webSocket.send(result(id!!, success = !failServices))
                    }
                }
            }
        }
    }

    private fun event(id: Int, body: String) = """{"id":$id,"type":"event","event":$body}"""

    private fun result(id: Int, success: Boolean) = buildJsonObject {
        put("id", id)
        put("type", "result")
        put("success", success)
        if (!success) putJsonObject("error") {
            put("code", "not_found")
            put("message", "Service not found.")
        }
    }.toString()

    companion object {
        fun JsonObject.type() = (this["type"] as? JsonPrimitive)?.contentOrNull
    }
}
