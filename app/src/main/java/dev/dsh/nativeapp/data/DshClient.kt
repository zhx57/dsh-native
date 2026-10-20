package dev.dsh.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A DSH endpoint failure carried in a `server-response` envelope. */
class DshRemoteException(
    val code: String,
    override val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
) : Exception(message)

/** One logical stream opened over `/api/remote.mux`. */
interface DshStream {
    fun cancel()
}

/**
 * Minimal client for the DeepSeek Harness host API.
 *
 * Wire contract (verified against dsh 0.2.0-rc.2):
 *  - unary RPC: `POST /api/<endpoint>` with a `client-request` envelope
 *  - streaming: `WS /api/remote.mux` with `open`/`item`/`end`/`error`/`cancel` frames
 *  - authentication: a signed `dsh-auth-<authority-hash>` cookie
 */
class DshClient(
    @Volatile var baseUrl: String,
    @Volatile var cookieHeader: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Absolute HTTP base without a trailing slash, e.g. `http://127.0.0.1:3080`. */
    private fun origin(): String = baseUrl.trimEnd('/')

    /** `ws(s)://host/api/remote.mux`. */
    private fun muxUrl(): String {
        val origin = origin()
        val wsOrigin = when {
            origin.startsWith("https://") -> "wss://" + origin.removePrefix("https://")
            origin.startsWith("http://") -> "ws://" + origin.removePrefix("http://")
            else -> "ws://$origin"
        }
        return "$wsOrigin/api/remote.mux"
    }

    /** Host header value the server binds cookies to. */
    fun authority(): String = origin()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')

    fun originHeader(): String = origin()

    // ---------------------------------------------------------------- unary RPC

    /**
     * Call one Remote endpoint.
     *
     * @param method endpoint id such as `session/list`.
     * @param args descriptor-shaped argument object; DSH rejects missing or extra fields.
     */
    fun call(method: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        val rpcId = UUID.randomUUID().toString()
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", method)
            put("payload", buildJsonObject { put("args", args) })
        }
        val request = Request.Builder()
            .url("${origin()}/api/$method")
            .header("content-type", "application/json")
            .header("cookie", cookieHeader)
            .header("origin", originHeader())
            .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DshRemoteException(
                    code = "transport/http-${response.code}",
                    message = "HTTP ${response.code} calling $method",
                )
            }
            val body = response.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(body).jsonObject
            val result = parsed["result"] as? JsonObject
                ?: throw DshRemoteException("transport/envelope", "missing result envelope for $method")
            if (JsonText.bool(result["ok"]) == true) {
                return result["value"] ?: JsonNull
            }
            val error = result["error"] as? JsonObject
            throw DshRemoteException(
                code = JsonText.string(error?.get("code")) ?: "gateway/internal",
                message = JsonText.string(error?.get("message")) ?: "call failed: $method",
                details = error?.get("details") as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }

    // ----------------------------------------------------------------- streams

    /**
     * Open one logical stream and deliver every `item` value to [onItem].
     *
     * The carrier multiplexes all streams over one socket, so each call owns its
     * own socket here: DSH streams are long-lived and rarely concurrent, and an
     * isolated socket keeps reconnect and cancellation semantics simple.
     */
    fun stream(
        endpoint: String,
        args: JsonObject,
        onItem: (JsonElement) -> Unit,
        onError: (Throwable) -> Unit = {},
        onClosed: () -> Unit = {},
    ): DshStream {
        val streamId = UUID.randomUUID().toString()
        var socket: WebSocket? = null
        var cancelled = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                val frame = buildJsonObject {
                    put("type", "open")
                    put("streamId", streamId)
                    put("endpoint", endpoint)
                    put("payload", buildJsonObject { put("args", args) })
                }
                webSocket.send(frame.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                if (JsonText.string(frame["streamId"]) != streamId) return
                when (JsonText.string(frame["type"])) {
                    "item" -> frame["value"]?.let(onItem)
                    "end" -> {
                        onClosed()
                        webSocket.close(1000, null)
                    }
                    "error" -> {
                        val error = frame["error"] as? JsonObject
                        onError(
                            DshRemoteException(
                                code = JsonText.string(error?.get("code")) ?: "gateway/internal",
                                message = JsonText.string(error?.get("message")) ?: "stream failed: $endpoint",
                            ),
                        )
                        webSocket.close(1000, null)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!cancelled) onError(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!cancelled) onClosed()
            }
        }

        val request = Request.Builder()
            .url(muxUrl())
            .header("cookie", cookieHeader)
            .header("origin", originHeader())
            .build()
        socket = http.newWebSocket(request, listener)

        return object : DshStream {
            override fun cancel() {
                cancelled = true
                val frame = buildJsonObject {
                    put("type", "cancel")
                    put("streamId", streamId)
                }
                socket?.send(frame.toString())
                socket?.close(1000, null)
            }
        }
    }

    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
