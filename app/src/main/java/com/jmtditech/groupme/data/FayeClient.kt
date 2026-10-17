package com.jmtditech.groupme.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/**
 * Minimal Bayeux/Faye client over WebSocket for GroupMe's (unofficial) push service.
 * [listen] suspends until the socket closes or fails; the caller handles reconnects.
 */
class FayeClient(private val client: OkHttpClient, private val json: Json) {

    @Volatile var connected: Boolean = false
        private set

    suspend fun listen(
        userId: String,
        token: String,
        onConnected: () -> Unit,
        onData: suspend (JsonObject) -> Unit,
    ) {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val request = Request.Builder().url("wss://push.groupme.com/faye").build()
        var counter = 1
        fun nextId(): String = (counter++).toString()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    buildJsonArray {
                        addJsonObject {
                            put("channel", "/meta/handshake")
                            put("version", "1.0")
                            putJsonArray("supportedConnectionTypes") { add("websocket") }
                            put("id", nextId())
                        }
                    }.toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                incoming.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(t)
            }
        })

        var clientId: String? = null
        try {
            for (text in incoming) {
                val arr = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: continue
                for (el in arr) {
                    val o = el as? JsonObject ?: continue
                    val ok = (o["successful"] as? JsonPrimitive)?.booleanOrNull == true
                    when (o.str("channel")) {
                        "/meta/handshake" -> {
                            if (!ok) throw IOException("Faye handshake failed")
                            clientId = o.str("clientId") ?: throw IOException("No clientId")
                            ws.send(
                                buildJsonArray {
                                    addJsonObject {
                                        put("channel", "/meta/subscribe")
                                        put("clientId", clientId)
                                        put("subscription", "/user/$userId")
                                        putJsonObject("ext") {
                                            put("access_token", token)
                                            put("timestamp", System.currentTimeMillis() / 1000)
                                        }
                                        put("id", nextId())
                                    }
                                }.toString()
                            )
                        }
                        "/meta/subscribe" -> {
                            if (!ok) throw IOException("Faye subscribe failed")
                            connected = true
                            onConnected()
                            ws.send(connectMessage(clientId!!, nextId()))
                        }
                        "/meta/connect" -> {
                            if (!ok) throw IOException("Faye connect failed")
                            val interval = ((o["advice"] as? JsonObject)?.get("interval") as? JsonPrimitive)?.longOrNull ?: 0L
                            if (interval > 0) delay(interval)
                            ws.send(connectMessage(clientId!!, nextId()))
                        }
                        else -> {
                            val data = o["data"] as? JsonObject
                            if (data != null) onData(data)
                        }
                    }
                }
            }
        } finally {
            connected = false
            ws.cancel()
        }
    }

    private fun connectMessage(clientId: String, id: String): String = buildJsonArray {
        addJsonObject {
            put("channel", "/meta/connect")
            put("clientId", clientId)
            put("connectionType", "websocket")
            put("id", id)
        }
    }.toString()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
