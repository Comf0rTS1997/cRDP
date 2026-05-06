package com.crdp.rdp.gateway

import com.crdp.core.rdp.RdpSessionPort
import com.crdp.core.rdp.input.KeyEventPayload
import com.crdp.core.rdp.input.PointerEvent
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import com.crdp.core.rdp.model.SessionState
import com.crdp.rdp.gateway.api.CreateSessionRequest
import com.crdp.rdp.gateway.api.CreateSessionResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GatewayRdpSession @Inject constructor(
    @GatewayIo private val io: CoroutineDispatcher,
) : RdpSessionPort {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var bytesSent = 0L
    private var bytesReceived = 0L

    override suspend fun connect(profile: ConnectionProfile): Result<Unit> = withContext(io) {
        if (profile !is GatewayConnectionProfile) {
            return@withContext Result.failure(IllegalArgumentException("Gateway profile required"))
        }
        _sessionState.value = SessionState.Connecting
        runCatching {
            val base = profile.gatewayBaseUrl.trimEnd('/')
            val body = CreateSessionRequest(
                targetHost = profile.targetHost,
                targetPort = profile.targetPort,
                displayName = profile.displayName,
            )
            val req = Request.Builder()
                .url("$base/v1/sessions")
                .post(json.encodeToString(CreateSessionRequest.serializer(), body).toRequestBody(JSON_MEDIA))
                .apply {
                    profile.bearerToken?.let { header("Authorization", "Bearer $it") }
                }
                .build()

            val parsed = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Create session failed: HTTP ${resp.code} ${resp.message}")
                }
                val text = resp.body?.string().orEmpty()
                json.decodeFromString(CreateSessionResponse.serializer(), text)
            }

            bytesSent = 0L
            bytesReceived = 0L

            openRelay(parsed.relayUrl, profile.bearerToken)
            _sessionState.value = SessionState.Connected(
                detail = "Gateway · session ${parsed.sessionId} · relay ${parsed.relayUrl}",
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
            )
        }.onFailure { e ->
            _sessionState.value = SessionState.Error(e.message ?: "gateway connect failed")
        }.map { }
    }

    private fun openRelay(relayUrl: String, bearerToken: String?) {
        webSocket?.close(1000, null)
        val b = Request.Builder().url(relayUrl)
        bearerToken?.let { b.header("Authorization", "Bearer $it") }
        val request = b.build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch {
                        refreshConnectedFromWs()
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    bytesReceived += bytes.size
                    scope.launch { refreshConnectedFromWs() }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    scope.launch {
                        _sessionState.value = SessionState.Idle
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scope.launch {
                        _sessionState.value = SessionState.Error(t.message ?: "relay failure")
                    }
                }
            },
        )
    }

    private suspend fun refreshConnectedFromWs() {
        val cur = _sessionState.value
        if (cur is SessionState.Connected) {
            _sessionState.value = cur.copy(bytesSent = bytesSent, bytesReceived = bytesReceived)
        }
    }

    override suspend fun disconnect() = withContext(io) {
        _sessionState.value = SessionState.Disconnecting
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
        _sessionState.value = SessionState.Idle
    }

    override fun onPointerEvent(event: PointerEvent) {
        val ws = webSocket ?: return
        val payload = buildString {
            append("P:")
            append(event.x).append(',')
            append(event.y).append(',')
            append(event.buttons).append(',')
            append(event.wheelDelta)
        }
        val bb = payload.encodeToByteArray()
        bytesSent += bb.size
        ws.send(bb.toByteString())
        scope.launch { refreshConnectedFromWs() }
    }

    override fun onKeyEvent(event: KeyEventPayload) {
        val ws = webSocket ?: return
        // K: keyCode, metaState, actionOrdinal (0=Down,1=Up), scanCode — first two fields match legacy clients.
        val payload = "K:${event.keyCode},${event.metaState},${event.action.ordinal},${event.scanCode}"
        val bb = payload.encodeToByteArray()
        bytesSent += bb.size
        ws.send(bb.toByteString())
        scope.launch { refreshConnectedFromWs() }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
