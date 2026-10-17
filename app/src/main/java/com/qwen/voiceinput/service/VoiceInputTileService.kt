package com.qwen.voiceinput.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.qwen.voiceinput.R

/**
 * 快捷设置磁贴：一键开关悬浮图标服务。
 * 添加方式：下拉通知栏 → 编辑快捷设置 → 添加「Qwen语音输入」。
 */
class VoiceInputTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, FloatingBubbleService::class.java)
        if (FloatingBubbleService.isRunning) {
            intent.action = FloatingBubbleService.ACTION_STOP
            stopService(intent)
        } else {
            intent.action = FloatingBubbleService.ACTION_START
            // 磁贴由系统绑定，属于后台启动 FGS 的豁免场景，可安全使用 startForegroundService
            ContextCompat.startForegroundService(this, intent)
        }
        refreshTile()
    }

    private fun refreshTile() {
        qsTile?.apply {
            state = if (FloatingBubbleService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }
}
