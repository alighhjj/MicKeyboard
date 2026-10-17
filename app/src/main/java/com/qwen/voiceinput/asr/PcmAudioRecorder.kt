package com.qwen.voiceinput.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 16kHz / 单声道 / PCM16 麦克风采集。
 * 每 100ms 回调一帧，帧内含 Base64 文本与 RMS 音量（用于悬浮面板音量条）。
 */
class PcmAudioRecorder(
    private val sampleRate: Int = 16_000,
    private val onChunk: (base64Pcm: String, rms: Float) -> Unit,
) {
    private var record: AudioRecord? = null
    private var job: Job? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    /** @return false 表示录音器初始化失败（无麦克风 / 权限缺失） */
    fun start(scope: CoroutineScope): Boolean {
        if (record != null) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufferSize = maxOf(minBuf * 2, sampleRate * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        record = rec
        isRecording = true
        job = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(sampleRate / 10 * 2) // 100ms
            try {
                rec.startRecording()
            } catch (_: IllegalStateException) {
                return@launch
            }
            while (isActive) {
                val n = rec.read(chunk, 0, chunk.size)
                when {
                    n > 0 -> {
                        val data = if (n == chunk.size) chunk else chunk.copyOf(n)
                        onChunk(
                            Base64.encodeToString(data, Base64.NO_WRAP),
                            computeRms(data)
                        )
                    }
                    // ERROR_INVALID_OPERATION 等：退出循环
                    else -> break
                }
            }
        }
        return true
    }

    fun stop() {
        isRecording = false
        job?.cancel()
        job = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null
    }

    private fun computeRms(pcm: ByteArray): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            sum += sample * sample
            i += 2
        }
        val count = maxOf(1, pcm.size / 2)
        return sqrt(sum / count).toFloat()
    }
}
