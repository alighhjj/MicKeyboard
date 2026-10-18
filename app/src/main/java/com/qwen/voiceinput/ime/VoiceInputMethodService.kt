package com.qwen.voiceinput.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlinx.coroutines.delay
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

    /** inflate 失败时的纯代码兜底控件，保证键盘一定能显示 */
    private var fallbackButton: Button? = null
    private var fallbackStatus: TextView? = null

    private var controller: AsrController? = null
    private var collectJob: Job? = null

    /** stopAndCommit 的 UI 兜底计时：防止回调未到达时按钮卡在「识别中」 */
    private var stopSafetyJob: Job? = null

    /** 已写入输入框但尚未确认的增量文本长度 */
    private var pendingInterimLen = 0
    private var lastInterim: String = ""
    private var committedFinals: Int = 0

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
    }

    override fun onCreateInputView(): View {
        return try {
            val b = ViewImeBinding.inflate(layoutInflater)
            binding = b
            b.btnVoice.setOnClickListener { toggle() }
            b.btnSwitchIme.setOnClickListener { switchToOtherIme() }
            resetUi()
            b.root
        } catch (e: Exception) {
            // 布局 inflate 异常（如主题不兼容）时不能抛出，否则系统直接跳过整个键盘
            Log.e(TAG, "inflate input view failed, using fallback", e)
            binding = null
            buildFallbackView()
        }
    }

    /** 纯代码构造的兜底键盘视图：只含框架控件，与服务上下文主题无关 */
    private fun buildFallbackView(): View {
        fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1F2430.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        fallbackStatus = TextView(this).apply {
            text = getString(R.string.ime_idle_hint)
            setTextColor(0xFF8A8F99.toInt())
            textSize = 12f
        }
        fallbackButton = Button(this).apply {
            text = getString(R.string.ime_start)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_ime_voice_button)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setOnClickListener { toggle() }
        }
        root.addView(fallbackStatus)
        root.addView(
            fallbackButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
                topMargin = dp(8)
            }
        )
        Log.w(TAG, "fallback input view in use")
        return root
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
        fallbackButton = null
        fallbackStatus = null
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
        // UI 兜底：即使 stop 回调因任何原因未到达，也保证按钮不会卡在「识别中」
        stopSafetyJob?.cancel()
        stopSafetyJob = scope.launch {
            delay(5_000)
            if (controller != null) {
                controller?.cancel()
                controller = null
                collectJob?.cancel()
                collectJob = null
                resetUi()
                toast(getString(R.string.direct_empty))
            }
        }
        c.stop { text ->
            stopSafetyJob?.cancel()
            controller = null
            collectJob?.cancel()
            collectJob = null
            if (settings.imeAutoSend) {
                performSend()
            } else if (text.isNotBlank()) {
                toast(getString(R.string.direct_inserted))
            }
            resetUi()
        }
        updateButton(R.string.ime_finalizing, enabled = false)
    }

    /**
     * 执行「发送」：
     *  - 输入框声明了 EditorAction（发送/搜索/前往等）→ sendDefaultEditorAction
     *  - 无 action 的输入框（浏览器地址栏、搜索框、多数聊天输入框）→ 发送真实
     *    回车键事件（ACTION_DOWN + ACTION_UP），走按键通路让输入框/App 自行处理提交。
     * 注意：微信/QQ 的发送按钮是 App 内部控件，软键盘无法直接触发（回车在其
     * 多行输入框中表现为换行），此类 App 需点其自带发送按钮。
     */
    private fun performSend() {
        val ei = currentInputEditorInfo ?: return
        val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (action != EditorInfo.IME_ACTION_NONE && !noEnterAction) {
            try {
                sendDefaultEditorAction(true)
            } catch (_: Exception) {
            }
        } else {
            try {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } catch (_: Exception) {
            }
        }
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
                setStatus(getString(R.string.panel_listening))
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
        val text = getString(textRes)
        binding?.btnVoice?.let {
            it.isEnabled = enabled
            it.text = text
        }
        fallbackButton?.let {
            it.isEnabled = enabled
            it.text = text
        }
    }

    private fun setStatus(text: String) {
        binding?.tvStatus?.text = text
        fallbackStatus?.text = text
    }

    private fun resetUi() {
        updateButton(R.string.ime_start, enabled = true)
        setStatus(getString(R.string.ime_idle_hint))
    }

    // ---------------- 工具 ----------------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MicKeyboardIME"

        /** 输入法是否已在系统中启用 */
        fun isEnabled(context: android.content.Context): Boolean {
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
            return imm.enabledInputMethodList.any { it.packageName == context.packageName }
        }
    }
}
