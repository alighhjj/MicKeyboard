package com.qwen.voiceinput.service

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qwen.voiceinput.MainActivity
import com.qwen.voiceinput.R
import com.qwen.voiceinput.SettingsRepository
import com.qwen.voiceinput.asr.AsrController
import com.qwen.voiceinput.asr.AsrState
import com.qwen.voiceinput.asr.AsrStatus
import com.qwen.voiceinput.databinding.ViewBubbleBinding
import com.qwen.voiceinput.databinding.ViewPanelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮图标前台服务。
 *
 * 功能：
 *  - 常驻屏幕边缘的可拖动悬浮球（麦克风图标），聆听时有脉冲动画
 *  - 点击悬浮球弹出识别面板，实时显示增量文本、音量条与已确认文本
 *  - 再次点击悬浮球（或面板「完成并填入」）→ 结束识别并将文本填入
 *    当前获得焦点的输入框（经 [TextInsertionAccessibilityService]）
 *  - 通知栏提供停止入口；快捷设置磁贴可一键开关本服务
 */
class FloatingBubbleService : Service() {

    companion object {
        const val ACTION_START = "com.qwen.voiceinput.action.START"
        const val ACTION_STOP = "com.qwen.voiceinput.action.STOP"
        const val ACTION_REFRESH_STYLE = "com.qwen.voiceinput.action.REFRESH_STYLE"

        private const val TAG = "FloatingBubble"
        private const val CHANNEL_ID = "voice_input_service"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelBinding: ViewPanelBinding? = null

    private var asrController: AsrController? = null
    private var stateCollectJob: Job? = null
    private var pulseAnimator: AnimatorSet? = null

    /** 手动模式下暂存最终文本，等待用户点击填入 */
    private var pendingText: String = ""

    // ---------------- 生命周期 ----------------

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        settings = SettingsRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_STYLE) {
            // 设置界面改了配色/透明度：即时刷新，不重启服务
            applyBubbleStyle()
            applyPanelStyle()
            return START_STICKY
        }
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasMic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasOverlay || !hasMic) {
            Log.w(TAG, "missing overlay=$hasOverlay mic=$hasMic, stop")
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification(getString(R.string.notification_idle)))
        if (bubbleView == null) addBubble()
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelRecognition()
        removeBubble()
        removePanel()
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    // ---------------- 悬浮球 ----------------

    private fun addBubble() {
        if (bubbleView != null) return
        val binding = ViewBubbleBinding.inflate(LayoutInflater.from(this))
        val params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeight / 3
        }
        binding.root.setOnTouchListener(BubbleTouchListener(params))
        try {
            windowManager.addView(binding.root, params)
        } catch (e: Exception) {
            Log.e(TAG, "addBubble failed", e)
            toast(getString(R.string.hint_overlay_failed))
            return
        }
        bubbleView = binding.root
        bubbleParams = params
        applyBubbleStyle()
    }

    private fun removeBubble() {
        stopPulse()
        bubbleView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        bubbleParams = null
    }

    private inner class BubbleTouchListener(
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false
        private var downAt = 0L
        private val slop = dp(8)

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    downAt = SystemClock.elapsedRealtime()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        updateBubble()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snapBubbleToEdge()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP &&
                        SystemClock.elapsedRealtime() - downAt < 600
                    ) {
                        onBubbleClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun onBubbleClick() {
        when {
            asrController != null -> finishRecognition()
            pendingText.isNotBlank() -> insertAndHide(pendingText)
            else -> startRecognition()
        }
    }

    private fun updateBubble() {
        val v = bubbleView ?: return
        val p = bubbleParams ?: return
        try {
            windowManager.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    /** 松手后吸附到左右边缘 */
    private fun snapBubbleToEdge() {
        val v = bubbleView ?: return
        val p = bubbleParams ?: return
        val width = if (v.width > 0) v.width else dp(56)
        val targetX =
            if (p.x + width / 2 < screenWidth / 2) 0 else screenWidth - width
        ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 220
            addUpdateListener { anim ->
                p.x = anim.animatedValue as Int
                updateBubble()
            }
            start()
        }
    }

    private fun startPulse() {
        stopPulse()
        val v = bubbleView ?: return
        val sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.08f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.08f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        pulseAnimator = AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 600
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        bubbleView?.scaleX = 1f
        bubbleView?.scaleY = 1f
    }

    // ---------------- 识别面板 ----------------

    private fun addPanel() {
        if (panelView != null) return
        val binding = ViewPanelBinding.inflate(LayoutInflater.from(this))
        panelBinding = binding
        binding.btnInsert.setOnClickListener { onInsertClick() }
        binding.btnCancel.setOnClickListener { cancelRecognition() }
        binding.btnRetry.setOnClickListener { startRecognition() }
        binding.panelHeader.setOnTouchListener(PanelTouchListener())

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val bubbleX = bubbleParams?.x ?: dp(16)
            val bubbleY = bubbleParams?.y ?: (screenHeight / 3)
            x = bubbleX.coerceIn(0, maxOf(0, screenWidth - dp(300)))
            y = (bubbleY - dp(380)).coerceIn(dp(24), maxOf(dp(24), screenHeight - dp(48)))
        }
        try {
            windowManager.addView(binding.root, params)
        } catch (e: Exception) {
            Log.e(TAG, "addPanel failed", e)
            panelBinding = null
            return
        }
        panelView = binding.root
        panelParams = params
        applyPanelStyle()
    }

    private fun removePanel() {
        panelView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        panelView = null
        panelParams = null
        panelBinding = null
    }

    private fun hidePanel() {
        removePanel()
    }

    private inner class PanelTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false
        private val slop = dp(6)

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = panelParams?.x ?: 0
                    initialY = panelParams?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        panelParams?.x = initialX + dx
                        panelParams?.y = initialY + dy
                        updatePanel()
                    }
                    return true
                }

                else -> return true
            }
        }
    }

    private fun updatePanel() {
        val v = panelView ?: return
        val p = panelParams ?: return
        try {
            windowManager.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    // ---------------- 识别流程 ----------------

    /** 直接输入模式（无面板）的流式写入状态 */
    private var streamWriter: TextInsertionAccessibilityService.StreamTextWriter? = null
    private var lastInterim: String = ""
    private var committedFinals: Int = 0

    private val directInputMode: Boolean get() = !settings.showPanel

    private fun resetDirectState() {
        streamWriter?.stop()
        streamWriter = null
        lastInterim = ""
        committedFinals = 0
    }

    private fun startRecognition() {
        // 清理可能存在的上一次会话（含错误态）
        asrController?.let { old ->
            stateCollectJob?.cancel()
            stateCollectJob = null
            old.cancel()
            asrController = null
        }
        resetDirectState()
        if (settings.apiKey.isBlank()) {
            toast(getString(R.string.hint_need_api_key))
            return
        }
        pendingText = ""

        val controller = AsrController(scope, settings.toAsrConfig())
        asrController = controller
        stateCollectJob = scope.launch {
            controller.state.collect { s ->
                if (directInputMode) handleDirectState(s) else renderPanel(s)
            }
        }

        if (directInputMode) {
            // 直接输入模式：先锁定当前焦点输入框，识别文字将实时写入其中，无聆听窗口
            val writer = TextInsertionAccessibilityService.createStreamWriter(this)
            if (writer == null || !writer.start()) {
                controller.cancel()
                asrController = null
                stateCollectJob?.cancel()
                stateCollectJob = null
                toast(getString(R.string.direct_need_input))
                return
            }
            streamWriter = writer
            controller.start()
            toast(getString(R.string.direct_listening))
        } else {
            addPanel()
            controller.start()
        }
        startPulse()
        updateNotification(getString(R.string.notification_listening))
    }

    /** 直接输入模式：把增量/确认文本实时写入焦点输入框 */
    private fun handleDirectState(s: AsrState) {
        val writer = streamWriter
        // IDLE 为 completeStop 重置态：此时 stop 回调尚未取消 collect（竞态），
        // 绝不能因空 interim 触发清理而删掉输入框内已识别的文字
        if (writer != null && s.status != AsrStatus.IDLE) {
            while (committedFinals < s.finals.size) {
                writer.commitFinal(s.finals[committedFinals])
                committedFinals++
            }
            if (s.interim != lastInterim) {
                writer.updateInterim(s.interim)
                lastInterim = s.interim
            }
        }
        if (s.status == AsrStatus.ERROR) {
            // 错误态自清理：释放写入器并停止脉冲，用户再次点击悬浮球即可重新开始
            resetDirectState()
            asrController?.cancel()
            asrController = null
            stateCollectJob?.cancel()
            stateCollectJob = null
            stopPulse()
            updateNotification(getString(R.string.notification_idle))
            toast(s.errorMessage ?: getString(R.string.panel_error))
        }
    }

    private fun finishRecognition() {
        val controller = asrController ?: return
        stopPulse()
        controller.stop { text ->
            asrController = null
            stateCollectJob?.cancel()
            stateCollectJob = null
            resetDirectState()
            when {
                // 直接输入模式：文本已在识别过程中实时写入输入框，无需额外操作
                directInputMode -> {
                    toast(
                        if (text.isNotBlank()) getString(R.string.direct_inserted)
                        else getString(R.string.direct_empty)
                    )
                    updateNotification(getString(R.string.notification_idle))
                }

                text.isBlank() -> {
                    hidePanel()
                    updateNotification(getString(R.string.notification_idle))
                }

                settings.autoInsert -> insertAndHide(text)

                else -> panelBinding?.let { b ->
                    b.tvStatus.text = getString(R.string.panel_stopped)
                    b.tvInterim.text = ""
                    b.tvFinal.text = text
                    b.btnInsert.isEnabled = true
                    b.levelBar.progress = 0
                    updateNotification(getString(R.string.notification_idle))
                }
            }
        }
    }

    private fun onInsertClick() {
        val text = pendingText.ifBlank {
            panelBinding?.tvFinal?.text?.toString() ?: ""
        }
        if (text.isBlank()) {
            hidePanel()
            return
        }
        insertAndHide(text)
    }

    private fun insertAndHide(text: String) {
        val inserted = TextInsertionAccessibilityService.insert(this, text)
        if (!inserted) {
            TextInsertionAccessibilityService.copyToClipboard(this, text)
            toast(getString(R.string.copied_to_clipboard))
        }
        pendingText = ""
        hidePanel()
        updateNotification(getString(R.string.notification_idle))
    }

    private fun cancelRecognition() {
        stopPulse()
        stateCollectJob?.cancel()
        stateCollectJob = null
        asrController?.cancel()
        asrController = null
        resetDirectState()
        pendingText = ""
        hidePanel()
        updateNotification(getString(R.string.notification_idle))
    }

    private fun renderPanel(s: AsrState) {
        val b = panelBinding ?: return
        when (s.status) {
            AsrStatus.CONNECTING -> {
                b.tvStatus.text = getString(R.string.panel_connecting)
                b.tvError.visibility = View.GONE
                b.btnRetry.visibility = View.GONE
                b.btnInsert.isEnabled = false
            }

            AsrStatus.LISTENING -> {
                b.tvStatus.text = getString(R.string.panel_listening)
                b.tvInterim.text = s.interim
                b.tvFinal.text = s.recognizedText
                b.btnInsert.isEnabled = true
                b.levelBar.progress =
                    (s.audioLevel / 12000f * 100).toInt().coerceIn(0, 100)
            }

            AsrStatus.FINALIZING -> {
                b.tvStatus.text = getString(R.string.panel_finalizing)
                b.btnInsert.isEnabled = false
            }

            AsrStatus.ERROR -> {
                b.tvStatus.text = getString(R.string.panel_error)
                b.tvError.text = s.errorMessage ?: ""
                b.tvError.visibility = View.VISIBLE
                b.btnRetry.visibility = View.VISIBLE
                b.btnInsert.isEnabled = false
                b.levelBar.progress = 0
            }

            AsrStatus.IDLE -> Unit
        }
    }

    // ---------------- 通知 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val stopIntent = Intent(this, FloatingBubbleService::class.java)
            .setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.notification_action_stop), stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (_: Exception) {
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------- 悬浮窗外观（配色 / 透明度） ----------------

    /** 应用悬浮球配色与透明度（设置变更时通过 ACTION_REFRESH_STYLE 触发刷新） */
    private fun applyBubbleStyle() {
        val v = bubbleView ?: return
        val base = settings.bubbleColor
        val color = Color.argb(
            (settings.bubbleAlpha * 255).toInt().coerceIn(0, 255),
            Color.red(base),
            Color.green(base),
            Color.blue(base)
        )
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), 0x66FFFFFF)
        }
    }

    /** 应用识别面板背景透明度 */
    private fun applyPanelStyle() {
        val v = panelView ?: return
        val alpha = (settings.panelAlpha * 255).toInt().coerceIn(0, 255)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(alpha, 0x1F, 0x24, 0x30))
            setStroke(dp(1), 0x33FFFFFF)
        }
    }

    // ---------------- 工具 ----------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
