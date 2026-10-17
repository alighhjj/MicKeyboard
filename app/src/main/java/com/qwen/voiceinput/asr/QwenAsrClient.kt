package com.qwen.voiceinput.asr

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * DashScope Qwen-ASR-Realtime WebSocket 客户端。
 *
 * 与原 Web Demo 的区别：浏览器 WebSocket 无法自定义握手请求头，才需要 Node 代理；
 * Android 端 OkHttp 原生支持请求头，因此直连 DashScope，无需任何代理：
 *
 *   wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime
 *   Header: Authorization: Bearer <apiKey>
 *
 * 协议（与已调通的 Demo 完全一致）：
 *   -> session.update        （会话配置：pcm/16k/language/server_vad）
 *   -> input_audio_buffer.append （Base64 PCM16 音频块）
 *   -> session.finish        （结束会话）
 *   <- session.created / session.updated
 *   <- input_audio_buffer.speech_started / speech_stopped
 *   <- conversation.item.input_audio_transcription.text        （增量 text + stash）
 *   <- conversation.item.input_audio_transcription.completed   （最终 transcript）
 *   <- session.finished
 */
class QwenAsrClient(
    private val endpointBase: String,
    private val apiKey: String,
    private val model: String,
    private val sessionConfig: SessionConfig,
    private val listener: Listener,
) {
    data class SessionConfig(
        val language: String = "zh",
        val sampleRate: Int = 16_000,
        val threshold: Float = 0.2f,
        val silenceDurationMs: Int = 800,
    )

    interface Listener {
        fun onOpen() {}
        fun onSessionCreated(sessionId: String?) {}
        fun onSessionReady() {}
        fun onSpeechStarted() {}
        fun onSpeechStopped() {}
        /** 增量结果：text 为已确认部分，stash 为暂存部分，拼接后为当前实时全文 */
        fun onInterimText(text: String, stash: String) {}
        /** 一句话识别完成（VAD 断句触发），一次会话可能多次回调 */
        fun onFinalTranscript(transcript: String) {}
        fun onSessionFinished() {}
        fun onError(code: String?, message: String) {}
        fun onClosed() {}
    }

    private var webSocket: WebSocket? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect() {
        val url = "$endpointBase?model=${URLEncoder.encode(model, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()
        webSocket = sharedClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                sendSessionUpdate()
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                val hint = when (response?.code) {
                    401, 403 -> "鉴权失败（HTTP ${response.code}），请检查 API Key 或模型权限"
                    404 -> "模型不存在（HTTP 404）：$model"
                    else -> t.message ?: "连接失败"
                }
                listener.onError("ws_failure", hint)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                listener.onClosed()
            }
        })
    }

    private fun sendSessionUpdate() {
        val session = JSONObject().apply {
            put("input_audio_format", "pcm")
            put("sample_rate", sessionConfig.sampleRate)
            put(
                "input_audio_transcription",
                JSONObject().put("language", sessionConfig.language)
            )
            put(
                "turn_detection",
                JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", sessionConfig.threshold.toDouble())
                    put("silence_duration_ms", sessionConfig.silenceDurationMs)
                }
            )
        }
        send(type = "session.update", session = session)
    }

    /** 追加一帧 Base64(PCM16) 音频 */
    fun appendAudio(base64Pcm: String) {
        if (!isConnected) return
        send(type = "input_audio_buffer.append", "audio" to base64Pcm)
    }

    /** 结束会话：VAD 模式下必须先发送 finish 再断开，否则最后一句可能丢失 */
    fun finish() {
        if (!isConnected) return
        send(type = "session.finish")
    }

    fun close() {
        isConnected = false
        try {
            webSocket?.close(1000, "client close")
        } catch (_: Exception) {
        }
        webSocket = null
    }

    private fun send(type: String, vararg fields: Pair<String, Any?>, session: JSONObject? = null) {
        val json = JSONObject().apply {
            put("type", type)
            put("event_id", "ev_" + Random.Default.nextLong().toString(36))
            fields.forEach { (k, v) -> v?.let { put(k, it) } }
            session?.let { put("session", it) }
        }
        try {
            webSocket?.send(json.toString())
        } catch (_: IllegalStateException) {
        }
    }

    private fun handleEvent(text: String) {
        val ev = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (ev.optString("type")) {
            "session.created" ->
                listener.onSessionCreated(ev.optJSONObject("session")?.optString("id"))

            "session.updated" -> listener.onSessionReady()

            "input_audio_buffer.speech_started" -> listener.onSpeechStarted()
            "input_audio_buffer.speech_stopped" -> listener.onSpeechStopped()
            "input_audio_buffer.committed" -> Unit

            "conversation.item.input_audio_transcription.text" ->
                listener.onInterimText(ev.optString("text"), ev.optString("stash"))

            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = ev.optString("transcript").ifEmpty { ev.optString("stash") }
                if (transcript.isNotEmpty()) listener.onFinalTranscript(transcript)
            }

            "session.finished" -> listener.onSessionFinished()

            "error" -> {
                val err = ev.optJSONObject("error")
                listener.onError(
                    err?.optString("code"),
                    err?.optString("message") ?: "服务端返回错误"
                )
            }
        }
    }

    companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // WebSocket 长连接不设读超时，靠 ping 保活
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
