package com.qwen.voiceinput.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AsrStatus { IDLE, CONNECTING, LISTENING, FINALIZING, ERROR }

data class AsrState(
    val status: AsrStatus = AsrStatus.IDLE,
    /** 实时增量文本（text + stash） */
    val interim: String = "",
    /** 已确认的多句结果列表 */
    val finals: List<String> = emptyList(),
    /** finals 拼接后的完整文本（用于填入输入框） */
    val recognizedText: String = "",
    val errorMessage: String? = null,
    /** RMS 音量，约 0 ~ 12000 */
    val audioLevel: Float = 0f,
)

/**
 * 识别会话控制器：串联 [QwenAsrClient] 与 [PcmAudioRecorder]，
 * 以 [StateFlow] 暴露完整状态，供悬浮面板 / 主界面测试区共用。
 */
class AsrController(
    private val scope: CoroutineScope,
    private val config: Config,
) {
    data class Config(
        val apiKey: String,
        val model: String,
        val endpointBase: String,
        val language: String,
        val vadThreshold: Float,
        val vadSilenceMs: Int,
    )

    private val _state = MutableStateFlow(AsrState())
    val state: StateFlow<AsrState> = _state.asStateFlow()

    private var client: QwenAsrClient? = null
    private val recorder = PcmAudioRecorder { base64, rms ->
        client?.appendAudio(base64)
        _state.update { it.copy(audioLevel = rms) }
    }
    private var connectTimeoutJob: Job? = null
    private var finishTimeoutJob: Job? = null
    private var pendingResult: ((String) -> Unit)? = null

    private val isCjk: Boolean = config.language in setOf("zh", "yue", "ja")

    fun start() {
        val current = _state.value.status
        if (current != AsrStatus.IDLE && current != AsrStatus.ERROR) return
        _state.value = AsrState(status = AsrStatus.CONNECTING)

        val c = QwenAsrClient(
            endpointBase = config.endpointBase,
            apiKey = config.apiKey,
            model = config.model,
            sessionConfig = QwenAsrClient.SessionConfig(
                language = config.language,
                threshold = config.vadThreshold,
                silenceDurationMs = config.vadSilenceMs,
            ),
            listener = object : QwenAsrClient.Listener {
                override fun onSessionReady() {
                    if (_state.value.status != AsrStatus.CONNECTING) return
                    connectTimeoutJob?.cancel()
                    _state.update { it.copy(status = AsrStatus.LISTENING) }
                    recorder.start(scope)
                }

                override fun onInterimText(text: String, stash: String) {
                    _state.update { it.copy(interim = text + stash) }
                }

                override fun onFinalTranscript(transcript: String) {
                    _state.update { s ->
                        val finals = s.finals + transcript
                        s.copy(
                            interim = "",
                            finals = finals,
                            recognizedText = finals.joinToString(if (isCjk) "" else " ")
                        )
                    }
                }

                override fun onSessionFinished() {
                    if (_state.value.status == AsrStatus.FINALIZING) {
                        completeStop()
                    }
                }

                override fun onError(code: String?, message: String) {
                    if (_state.value.status == AsrStatus.CONNECTING ||
                        _state.value.status == AsrStatus.LISTENING
                    ) {
                        finishTimeoutJob?.cancel()
                        connectTimeoutJob?.cancel()
                        recorder.stop()
                        client?.close()
                        client = null
                        pendingResult = null
                        _state.update {
                            it.copy(status = AsrStatus.ERROR, errorMessage = message)
                        }
                    }
                }

                override fun onClosed() {
                    recorder.stop()
                }
            }
        )
        client = c
        c.connect()

        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(15_000)
            if (_state.value.status == AsrStatus.CONNECTING) {
                _state.update {
                    it.copy(
                        status = AsrStatus.ERROR,
                        errorMessage = "连接超时，请检查网络与 API Key"
                    )
                }
                stopInternal()
            }
        }
    }

    /**
     * 停止识别并取回本次完整文本。
     * 先发送 session.finish，等待最后一句 completed 事件（或 3.5s 超时兜底）。
     */
    fun stop(onResult: (String) -> Unit) {
        val status = _state.value.status
        if (status == AsrStatus.IDLE || status == AsrStatus.ERROR) {
            onResult(_state.value.recognizedText)
            return
        }
        pendingResult = onResult
        _state.update { it.copy(status = AsrStatus.FINALIZING) }
        recorder.stop()
        client?.finish()
        finishTimeoutJob?.cancel()
        finishTimeoutJob = scope.launch {
            delay(3_500)
            if (_state.value.status == AsrStatus.FINALIZING) completeStop()
        }
    }

    /** 放弃本次识别，直接重置 */
    fun cancel() {
        finishTimeoutJob?.cancel()
        connectTimeoutJob?.cancel()
        stopInternal()
        pendingResult = null
        _state.value = AsrState()
    }

    private fun completeStop() {
        finishTimeoutJob?.cancel()
        stopInternal()
        val text = _state.value.recognizedText
        _state.value = AsrState()
        pendingResult?.invoke(text)
        pendingResult = null
    }

    private fun stopInternal() {
        recorder.stop()
        client?.close()
        client = null
    }
}
