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
import com.qwen.voiceinput.databinding.ViewWaveBarBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/**
 * 悬浮图标前台服务。
 *
 * 功能（对齐主流语音输入工具的悬浮球交互）：
 *  - 常驻屏幕边缘的可拖动悬浮球（麦克风图标），聆听时有脉冲动画
 *  - 点击悬浮球 → 弹出**横向声波输入框**（音量波形 + 实时识别文字 + 停止按钮），
 *    同时识别文字实时写入当前焦点输入框
 *  - 再次点击悬浮球（或声波框上的停止按钮）→ 结束识别并收
 *    起声波框；文字已在识别过程中实时填入输入框
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

    private var waveView: View? = null
    private var waveParams: WindowManager.LayoutParams? = null
    private var waveBinding: ViewWaveBarBinding? = null

    private var asrController: AsrController? = null
    private var stateCollectJob: Job? = null
    private var pulseAnimator: AnimatorSet? = null

    /** 声波框空闲呼吸动画（连接中/等待时播放） */
    private var waveIdleAnimator: ValueAnimator? = null

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
            applyWaveBarStyle()
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
        removeWaveBar()
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
                        // 拖动时声波框跟随悬浮球水平位置
                        if (waveView != null) positionWaveBar()
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
                if (waveView != null) positionWaveBar()
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

    // ---------------- 横向声波输入框 ----------------

    private fun addWaveBar() {
        if (waveView != null) return
        val binding = ViewWaveBarBinding.inflate(LayoutInflater.from(this))
        waveBinding = binding
        binding.btnWaveStop.setOnClickListener { finishRecognition() }
        binding.root.setOnTouchListener(WaveBarTouchListener())

        val params = WindowManager.LayoutParams(
            screenWidth - dp(24),
            dp(72),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(binding.root, params)
        } catch (e: Exception) {
            Log.e(TAG, "addWaveBar failed", e)
            waveBinding = null
            return
        }
        waveView = binding.root
        waveParams = params
        applyWaveBarStyle()
        positionWaveBar()
        startWaveIdle()
    }

    private fun removeWaveBar() {
        stopWaveIdle()
        waveView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        waveView = null
        waveParams = null
        waveBinding = null
    }

    /** 声波框定位：水平居中于悬浮球，垂直方向贴在悬浮球上方（空间不足则放下方） */
    private fun positionWaveBar() {
        val p = waveParams ?: return
        val bp = bubbleParams ?: return
        val waveW = screenWidth - dp(24)
        val waveH = dp(72)
        val gap = dp(10)
        val bubbleW = dp(56)
        p.x = (bp.x + bubbleW / 2 - waveW / 2).coerceIn(dp(12), screenWidth - waveW - dp(12))
        p.y = if (bp.y > waveH + gap + dp(24)) {
            bp.y - waveH - gap // 悬浮球靠下 → 框在上方
        } else {
            (bp.y + bubbleW + gap).coerceAtMost(screenHeight - waveH - dp(24)) // 框在下方
        }
        updateWaveBar()
    }

    private fun updateWaveBar() {
        val v = waveView ?: return
        val p = waveParams ?: return
        try {
            windowManager.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    /** 声波框支持拖动（按住条身非按钮区域移动） */
    private inner class WaveBarTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false
        private val slop = dp(6)

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = waveParams?.x ?: 0
                    initialY = waveParams?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    return false // 不拦截，保留停止按钮点击
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        waveParams?.x = initialX + dx
                        waveParams?.y = initialY + dy
                        updateWaveBar()
                    }
                    return false
                }

                else -> return false
            }
        }
    }

    /** 空闲呼吸：未识别时波形条缓慢起伏，提示框处于活动状态 */
    private fun startWaveIdle() {
        stopWaveIdle()
        val bars = waveBars() ?: return
        bars.forEach { it.pivotY = it.height.toFloat().coerceAtLeast(1f) }
        waveIdleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val t = anim.animatedFraction * 2 * Math.PI
                bars.forEachIndexed { i, bar ->
                    val s = 0.35f + 0.25f * sin(t + i * 0.9).toFloat()
                    bar.scaleY = s
                }
            }
            start()
        }
    }

    private fun stopWaveIdle() {
        waveIdleAnimator?.cancel()
        waveIdleAnimator = null
        waveBars()?.forEach { it.scaleY = 1f }
    }

    private fun waveBars(): List<View>? {
        val b = waveBinding ?: return null
        return listOf(b.wave1, b.wave2, b.wave3, b.wave4, b.wave5)
    }

    // ---------------- 识别流程 ----------------

    /** 直接输入模式的流式写入状态（识别文字实时写入焦点输入框） */
    private var streamWriter: TextInsertionAccessibilityService.StreamTextWriter? = null
    private var lastInterim: String = ""
    private var committedFinals: Int = 0

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

        val controller = AsrController(scope, settings.toAsrConfig())
        asrController = controller
        stateCollectJob = scope.launch {
            controller.state.collect { s -> renderWaveBar(s) }
        }

        // 先锁定当前焦点输入框，识别文字将实时写入其中
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
        addWaveBar()
        controller.start()
        startPulse()
        updateNotification(getString(R.string.notification_listening))
    }

    /** 声波框渲染 + 直接输入（流式写入当前焦点输入框） */
    private fun renderWaveBar(s: AsrState) {
        val b = waveBinding
        // 声波框：波形与文字
        if (b != null) {
            when (s.status) {
                AsrStatus.CONNECTING -> {
                    b.tvWaveText.text = getString(R.string.wave_connecting)
                }

                AsrStatus.LISTENING -> {
                    stopWaveIdle()
                    b.tvWaveText.text =
                        s.interim.ifBlank { getString(R.string.wave_listening) }
                    val level = (s.audioLevel / 12000f).coerceIn(0f, 1f)
                    val t = SystemClock.elapsedRealtime() * 0.012f
                    waveBars()?.forEachIndexed { i, bar ->
                        bar.pivotY = bar.height.toFloat().coerceAtLeast(1f)
                        val wobble = 0.6f + 0.4f * sin(t + i * 0.9f)
                        bar.scaleY = (0.15f + level * 0.85f * wobble).coerceIn(0.12f, 1f)
                    }
                }

                AsrStatus.FINALIZING -> {
                    b.tvWaveText.text = getString(R.string.wave_finalizing)
                }

                AsrStatus.ERROR -> {
                    b.tvWaveText.text = s.errorMessage ?: getString(R.string.wave_error)
                }

                AsrStatus.IDLE -> Unit
            }
        }

        // 直接输入：把增量/确认文本实时写入焦点输入框。
        // IDLE 为 completeStop 重置态：此时 stop 回调尚未取消 collect（竞态），
        // 绝不能因空 interim 触发清理而删掉输入框内已识别的文字
        val writer = streamWriter
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
            removeWaveBar()
            updateNotification(getString(R.string.notification_idle))
            toast(s.errorMessage ?: getString(R.string.wave_error))
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
            removeWaveBar()
            updateNotification(getString(R.string.notification_idle))
            toast(
                if (text.isNotBlank()) getString(R.string.direct_inserted)
                else getString(R.string.direct_empty)
            )
        }
    }

    private fun cancelRecognition() {
        stopPulse()
        stateCollectJob?.cancel()
        stateCollectJob = null
        asrController?.cancel()
        asrController = null
        resetDirectState()
        removeWaveBar()
        updateNotification(getString(R.string.notification_idle))
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
        val alpha = (settings.bubbleAlpha * 255).toInt().coerceIn(0, 255)
        val isBrand = base == SettingsRepository.DEFAULT_BUBBLE_COLOR
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isBrand) {
                // 默认品牌色：黑色渐变（与图标一致的品牌视觉）
                colors = intArrayOf(
                    Color.argb(alpha, 0x2E, 0x32, 0x38),
                    Color.argb(alpha, 0x0B, 0x0D, 0x10)
                )
                orientation = GradientDrawable.Orientation.TL_BR
            } else {
                setColor(
                    Color.argb(
                        alpha,
                        Color.red(base),
                        Color.green(base),
                        Color.blue(base)
                    )
                )
            }
            setStroke(dp(1), 0x66FFFFFF)
        }
    }

    /** 应用声波框背景透明度 */
    private fun applyWaveBarStyle() {
        val v = waveView ?: return
        val alpha = (settings.bubbleAlpha * 255).toInt().coerceIn(0, 255)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(36).toFloat()
            setColor(Color.argb(alpha, 0x10, 0x13, 0x18))
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
