package com.qwen.voiceinput

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.qwen.voiceinput.asr.AsrController
import com.qwen.voiceinput.asr.AsrState
import com.qwen.voiceinput.asr.AsrStatus
import com.qwen.voiceinput.databinding.ActivityMainBinding
import com.qwen.voiceinput.service.FloatingBubbleService
import com.qwen.voiceinput.service.TextInsertionAccessibilityService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 主界面：识别设置、权限引导、悬浮图标开关、内置识别测试。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository

    private var testController: AsrController? = null
    private var testCollectJob: Job? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionStates() }

    private val micForServiceLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleService() else toast(getString(R.string.hint_mic_denied))
    }

    private val micForTestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doStartTest() else toast(getString(R.string.hint_mic_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)

        setupSpinners()
        loadSettings()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
        refreshServiceButton()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    // ---------------- 设置 ----------------

    private fun setupSpinners() {
        binding.spinnerModel.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.models,
            android.R.layout.simple_spinner_item
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerLanguage.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.language_labels,
            android.R.layout.simple_spinner_item
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun loadSettings() {
        binding.etApiKey.setText(settings.apiKey)
        binding.spinnerModel.setSelection(
            resources.getStringArray(R.array.models).indexOf(settings.model).coerceAtLeast(0)
        )
        binding.spinnerLanguage.setSelection(
            resources.getStringArray(R.array.language_codes).indexOf(settings.language).coerceAtLeast(0)
        )
        binding.etVadThreshold.setText(settings.vadThreshold.toString())
        binding.etVadSilence.setText(settings.vadSilenceMs.toString())
        binding.swAutoInsert.isChecked = settings.autoInsert
    }

    private fun saveSettings() {
        settings.apiKey = binding.etApiKey.text?.toString() ?: ""
        val modelPos = binding.spinnerModel.selectedItemPosition
        settings.model = resources.getStringArray(R.array.models)
            .getOrElse(modelPos) { SettingsRepository.DEFAULT_MODEL }
        val langPos = binding.spinnerLanguage.selectedItemPosition
        settings.language = resources.getStringArray(R.array.language_codes)
            .getOrElse(langPos) { "zh" }
        settings.vadThreshold = binding.etVadThreshold.text?.toString()?.toFloatOrNull() ?: 0.2f
        settings.vadSilenceMs = binding.etVadSilence.text?.toString()?.toIntOrNull() ?: 800
        settings.autoInsert = binding.swAutoInsert.isChecked
    }

    // ---------------- 按钮与权限 ----------------

    private fun setupButtons() {
        binding.btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        binding.btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.btnToggleService.setOnClickListener { toggleService() }
        binding.btnTestStart.setOnClickListener { toggleTest() }
    }

    private fun refreshPermissionStates() {
        val granted = getString(R.string.perm_granted)
        val missing = getString(R.string.perm_missing)

        binding.tvOverlayStatus.text =
            if (Settings.canDrawOverlays(this)) granted else missing
        binding.tvA11yStatus.text =
            if (TextInsertionAccessibilityService.isEnabled(this)) granted else missing
        val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        binding.tvNotificationStatus.text = if (notificationsOk) granted else missing
        binding.btnNotification.isEnabled = !notificationsOk
    }

    private fun refreshServiceButton() {
        binding.btnToggleService.setText(
            if (FloatingBubbleService.isRunning) R.string.btn_stop_service
            else R.string.btn_start_service
        )
    }

    private fun toggleService() {
        if (FloatingBubbleService.isRunning) {
            startService(
                Intent(this, FloatingBubbleService::class.java)
                    .setAction(FloatingBubbleService.ACTION_STOP)
            )
            refreshServiceButton()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先开启悬浮窗权限")
            return
        }
        if (!hasMicPermission()) {
            micForServiceLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        saveSettings()
        ContextCompat.startForegroundService(
            this,
            Intent(this, FloatingBubbleService::class.java)
                .setAction(FloatingBubbleService.ACTION_START)
        )
        refreshServiceButton()
        toast("悬浮图标已启动，切换到其他 App 点击输入框后使用")
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---------------- 测试识别 ----------------

    private fun toggleTest() {
        if (testController != null) {
            testController?.stop { text -> onTestStopped(text) }
            return
        }
        saveSettings()
        if (settings.apiKey.isBlank()) {
            toast(getString(R.string.hint_need_api_key))
            return
        }
        if (!hasMicPermission()) {
            micForTestLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        doStartTest()
    }

    private fun doStartTest() {
        testController?.cancel()
        testCollectJob?.cancel()
        binding.tvTestInterim.text = ""
        binding.tvTestFinal.text = ""
        binding.btnTestInsert.visibility = View.GONE
        val controller = AsrController(lifecycleScope, settings.toAsrConfig())
        testController = controller
        testCollectJob = lifecycleScope.launch {
            controller.state.collect { renderTest(it) }
        }
        controller.start()
        binding.btnTestStart.setText(R.string.stop_recognition)
    }

    private fun onTestStopped(text: String) {
        testController = null
        testCollectJob?.cancel()
        testCollectJob = null
        binding.btnTestStart.setText(R.string.start_recognition)
        binding.tvTestInterim.text = ""
        binding.tvTestFinal.text = text
        binding.testLevelBar.progress = 0
        binding.tvTestStatus.setText(
            if (text.isBlank()) R.string.test_empty else R.string.test_done
        )
        if (text.isNotBlank()) {
            binding.btnTestInsert.visibility = View.VISIBLE
            binding.btnTestInsert.setOnClickListener {
                if (!TextInsertionAccessibilityService.insert(this, text)) {
                    TextInsertionAccessibilityService.copyToClipboard(this, text)
                    toast(getString(R.string.copied_to_clipboard))
                }
                binding.btnTestInsert.visibility = View.GONE
            }
        }
    }

    private fun renderTest(s: AsrState) {
        binding.tvTestStatus.setText(
            when (s.status) {
                AsrStatus.CONNECTING -> R.string.panel_connecting
                AsrStatus.LISTENING -> R.string.panel_listening
                AsrStatus.FINALIZING -> R.string.panel_finalizing
                AsrStatus.ERROR -> R.string.panel_error
                AsrStatus.IDLE -> R.string.test_idle
            }
        )
        if (s.status == AsrStatus.ERROR && !s.errorMessage.isNullOrBlank()) {
            binding.tvTestStatus.text = s.errorMessage
        }
        binding.tvTestInterim.text = s.interim
        binding.tvTestFinal.text = s.recognizedText
        binding.testLevelBar.progress =
            (s.audioLevel / 12000f * 100).toInt().coerceIn(0, 100)
    }

    override fun onDestroy() {
        testController?.cancel()
        testController = null
        super.onDestroy()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
