package com.chardidathing.litehub.source.ha

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.time.toJavaDuration

// one websocket to ha, reconnecting forever. scope must be single threaded, all state here
// is touched only from it, and the listener is called on it too
class HaClient(
    private val credentials: HaCredentials,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val timing: HaTiming,
    private val listener: Listener,
) {

    sealed interface Status {
        data object Connecting : Status

        data object Connected : Status

        data class Failed(val reason: String) : Status
    }

    // initial is the first event of a subscription, it lists every requested entity that exists
    class EntityEvent(val requested: Set<String>, val initial: Boolean, val body: JsonObject)

    interface Listener {
        fun onStatus(status: Status)

        fun onEntities(event: EntityEvent)
    }

    private class AuthRejected(message: String) : IOException(message)

    private class Subscription(val id: Int, val requested: Set<String>) {
        var initial = true
    }

    private val http = http.newBuilder().pingInterval(timing.ping.toJavaDuration()).build()
    private val json = Json { ignoreUnknownKeys = true }
    private var wanted: Set<String> = emptySet()
    private var socket: WebSocket? = null
    private var subscription: Subscription? = null
    private var nextId = 1
    private val pending = HashMap<Int, CompletableDeferred<JsonObject>>()

    fun start(): Job = scope.launch {
        var attempt = 0
        while (true) {
            listener.onStatus(Status.Connecting)
            var connected = false
            val reason = try {
                session { connected = true }
                "home assistant closed the connection"
            } catch (e: TimeoutCancellationException) {
                "home assistant didn't answer"
            } catch (e: IOException) {
                describe(e)
            } catch (e: SerializationException) {
                "home assistant sent something unreadable"
            } catch (e: IllegalArgumentException) {
                // a json value that isn't an object
                "home assistant sent something unreadable"
            }
            ensureActive()
            if (connected) attempt = 0
            listener.onStatus(Status.Failed(reason))
            delay(timing.backoff(attempt++))
        }
    }

    // only the entities on screen get subscribed, so ha only sends what we draw
    fun setEntityIds(ids: Set<String>) {
        if (ids == wanted) return
        wanted = ids
        if (socket != null) resubscribe()
    }

    // the service's response when asked for (calendar.get_events and the like), else null
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: JsonObject? = null,
        returnResponse: Boolean = false,
    ): Result<JsonObject?> = request("call_service") {
        put("domain", domain)
        put("service", service)
        putJsonObject("target") { put("entity_id", entityId) }
        if (data != null) put("service_data", data)
        if (returnResponse) put("return_response", true)
    }.map { (it as? JsonObject)?.get("response") as? JsonObject }

    // any one shot websocket command (get_states, the registries), answers with its result
    suspend fun command(type: String): Result<JsonElement?> = request(type) {}

    private suspend fun request(type: String, fields: JsonObjectBuilder.() -> Unit): Result<JsonElement?> {
        val ws = socket ?: return Result.failure(IOException("not connected to home assistant"))
        val id = nextId++
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        ws.send(
            buildJsonObject {
                put("id", id)
                put("type", type)
                fields()
            }.toString(),
        )
        return try {
            val result = withTimeout(timing.commandTimeout) { reply.await() }
            if ((result["success"] as? JsonPrimitive)?.booleanOrNull == true) {
                Result.success(result["result"])
            } else {
                val message = (result["error"] as? JsonObject)?.string("message")
                Result.failure(IOException(message?.replaceFirstChar { it.lowercase() } ?: "home assistant refused"))
            }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            Result.failure(IOException("home assistant didn't answer"))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private suspend fun session(onConnected: () -> Unit) {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val request = Request.Builder().url(credentials.url.trimEnd('/') + "/api/websocket").build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close(IOException("home assistant closed the connection"))
                webSocket.close(code, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(t as? IOException ?: IOException(t))
            }
        })
        try {
            withTimeout(timing.handshakeTimeout) {
                if (parse(incoming.receive()).type != "auth_required") throw IOException("not a home assistant websocket")
                ws.send(
                    buildJsonObject {
                        put("type", "auth")
                        put("access_token", credentials.token)
                    }.toString(),
                )
                val reply = parse(incoming.receive())
                when (reply.type) {
                    "auth_ok" -> Unit
                    "auth_invalid" -> throw AuthRejected(reply.string("message") ?: "")
                    else -> throw IOException("not a home assistant websocket")
                }
            }
            socket = ws
            nextId = 1
            subscription = null
            onConnected()
            listener.onStatus(Status.Connected)
            resubscribe()
            for (text in incoming) handle(parse(text))
        } finally {
            ws.cancel()
            socket = null
            subscription = null
            pending.values.forEach { it.completeExceptionally(IOException("connection to home assistant dropped")) }
            pending.clear()
        }
    }

    private fun handle(message: JsonObject) {
        val id = (message["id"] as? JsonPrimitive)?.intOrNull ?: return
        when (message.type) {
            "event" -> {
                val sub = subscription?.takeIf { it.id == id } ?: return
                val body = message["event"] as? JsonObject ?: return
                listener.onEntities(EntityEvent(sub.requested, sub.initial, body))
                sub.initial = false
            }
            "result" -> pending.remove(id)?.complete(message)
        }
    }

    // subscribe the new set before dropping the old one, so there's no gap in updates
    private fun resubscribe() {
        val ws = socket ?: return
        val old = subscription
        subscription = null
        if (wanted.isNotEmpty()) {
            val sub = Subscription(nextId++, wanted)
            subscription = sub
            ws.send(
                buildJsonObject {
                    put("id", sub.id)
                    put("type", "subscribe_entities")
                    putJsonArray("entity_ids") { sub.requested.forEach { add(JsonPrimitive(it)) } }
                }.toString(),
            )
        }
        if (old != null) {
            ws.send(
                buildJsonObject {
                    put("id", nextId++)
                    put("type", "unsubscribe_events")
                    put("subscription", old.id)
                }.toString(),
            )
        }
    }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private val JsonObject.type get() = string("type")

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun describe(e: IOException): String = when (e) {
        is AuthRejected -> "token rejected"
        is UnknownHostException -> "can't find home assistant"
        is ConnectException, is SocketTimeoutException -> "can't reach home assistant"
        is SSLException -> "home assistant's certificate isn't trusted"
        else -> e.message?.takeIf { it.isNotBlank() && it.first().isLowerCase() } ?: "connection to home assistant dropped"
    }
}
