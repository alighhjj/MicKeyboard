package com.qwen.voiceinput.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.qwen.voiceinput.R
import com.qwen.voiceinput.SettingsRepository
import com.qwen.voiceinput.asr.AsrController
import com.qwen.voiceinput.asr.AsrState
import com.qwen.voiceinput.asr.AsrStatus
import com.qwen.voiceinput.databinding.ViewImeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * MicKeyboard 键盘模式（输入法）。
 *
 * 键盘上只有一个主按钮：
 *  - 点击一次 → 开始语音识别，识别文字实时写入当前输入框（InputConnection）
 *  - 再点击一次 → 结束识别，文本已在框内，按设置执行发送动作（回车/发送键）
 *
 * 与悬浮窗模式相互独立，可同时启用或任选其一。
 */
class VoiceInputMethodService : InputMethodService() {

    private lateinit var settings: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var binding: ViewImeBinding? = null
    private var controller: AsrController? = null
    private var collectJob: Job? = null

    /** 已写入输入框但尚未确认的增量文本长度 */
    private var pendingInterimLen = 0
    private var lastInterim: String = ""
    private var committedFinals: Int = 0

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
    }

    override fun onCreateInputView(): View {
        val b = ViewImeBinding.inflate(layoutInflater)
        binding = b
        b.btnVoice.setOnClickListener { toggle() }
        b.btnSwitchIme.setOnClickListener { switchToOtherIme() }
        resetUi()
        return b.root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetUi()
    }

    override fun onFinishInput() {
        // 键盘收起时若仍在识别，先提交再结束
        if (controller != null) stopAndCommit()
        super.onFinishInput()
    }

    override fun onDestroy() {
        controller?.cancel()
        controller = null
        scope.cancel()
        binding = null
        super.onDestroy()
    }

    // ---------------- 交互 ----------------

    private fun toggle() {
        if (controller != null) stopAndCommit() else startRecognition()
    }

    private fun startRecognition() {
        if (settings.apiKey.isBlank()) {
            toast(getString(R.string.hint_need_api_key))
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.ime_need_mic))
            return
        }
        pendingInterimLen = 0
        lastInterim = ""
        committedFinals = 0

        val c = AsrController(scope, settings.toAsrConfig())
        controller = c
        collectJob = scope.launch { c.state.collect { render(it) } }
        c.start()
        updateButton(R.string.ime_connecting, enabled = false)
    }

    private fun stopAndCommit() {
        val c = controller ?: return
        c.stop { text ->
            controller = null
            collectJob?.cancel()
            collectJob = null
            if (settings.imeAutoSend) {
                try {
                    sendDefaultEditorAction(true)
                } catch (_: Exception) {
                }
            }
            resetUi()
            if (text.isBlank()) toast(getString(R.string.direct_empty))
        }
        updateButton(R.string.ime_finalizing, enabled = false)
    }

    private fun switchToOtherIme() {
        try {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        } catch (_: Exception) {
        }
    }

    // ---------------- 状态渲染与流式写入 ----------------

    private fun render(s: AsrState) {
        when (s.status) {
            AsrStatus.CONNECTING -> updateButton(R.string.ime_connecting, enabled = false)

            AsrStatus.LISTENING -> {
                updateButton(R.string.ime_stop_and_send, enabled = true)
                binding?.tvStatus?.text = getString(R.string.panel_listening)
            }

            AsrStatus.FINALIZING -> updateButton(R.string.ime_finalizing, enabled = false)

            AsrStatus.ERROR -> {
                controller?.cancel()
                controller = null
                collectJob?.cancel()
                collectJob = null
                resetUi()
                toast(s.errorMessage ?: getString(R.string.panel_error))
            }

            AsrStatus.IDLE -> Unit
        }

        val ic = currentInputConnection ?: return

        // 1) 增量文本：删除上次暂存后重新提交
        if (s.interim != lastInterim) {
            if (pendingInterimLen > 0) ic.deleteSurroundingText(pendingInterimLen, 0)
            if (s.interim.isNotEmpty()) ic.commitText(s.interim, 1)
            pendingInterimLen = s.interim.length
            lastInterim = s.interim
        }

        // 2) 确认文本：替换暂存后逐句提交
        while (committedFinals < s.finals.size) {
            if (pendingInterimLen > 0) {
                ic.deleteSurroundingText(pendingInterimLen, 0)
                pendingInterimLen = 0
            }
            ic.commitText(s.finals[committedFinals], 1)
            committedFinals++
        }
    }

    private fun updateButton(textRes: Int, enabled: Boolean) {
        binding?.btnVoice?.apply {
            isEnabled = enabled
            text = getString(textRes)
        }
    }

    private fun resetUi() {
        updateButton(R.string.ime_start, enabled = true)
        binding?.tvStatus?.text = getString(R.string.ime_idle_hint)
    }

    // ---------------- 工具 ----------------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 输入法是否已在系统中启用 */
        fun isEnabled(context: android.content.Context): Boolean {
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
            return imm.enabledInputMethodList.any { it.packageName == context.packageName }
        }
    }
}
