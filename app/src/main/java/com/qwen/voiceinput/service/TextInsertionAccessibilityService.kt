package com.qwen.voiceinput.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 文本填入辅助服务。
 *
 * 通过 AccessibilityNodeInfo 定位当前获得输入焦点的可编辑控件，按优先级尝试：
 *   1. ACTION_PASTE（粘贴，天然保留光标位置）
 *   2. ACTION_SET_TEXT（在光标处合并后整段设置）
 *   3. ACTION_SET_TEXT（追加到末尾）
 *
 * 全部失败时调用方回退为「复制到剪贴板」。
 */
class TextInsertionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceInputA11y"

        @Volatile
        var instance: TextInsertionAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, TextInsertionAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                it.equals(expected.flattenToString(), ignoreCase = true) ||
                    it.equals(expected.flattenToShortString(), ignoreCase = true)
            }
        }

        /** @return true 已填入输入框；false 表示未找到输入框或填入失败 */
        fun insert(context: Context, text: String): Boolean {
            if (text.isEmpty()) return false
            val svc = instance ?: return false
            return try {
                svc.insertText(text)
            } catch (e: Exception) {
                Log.w(TAG, "insert failed", e)
                false
            }
        }

        fun copyToClipboard(context: Context, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qwen-voice-input", text))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅需在用户主动填入时操作节点，无需监听事件
    }

    override fun onInterrupt() {
        // 无反馈中断处理
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun insertText(text: String): Boolean {
        val node = findFocusedEditable()
        if (node == null) {
            runOnMain { toast("未找到输入框，请先点击一个输入位置") }
            return false
        }
        // 1) 粘贴：保留光标位置，兼容富文本/自定义输入框
        if (paste(node, text)) {
            runOnMain { toast("已填入输入框") }
            return true
        }
        // 2) 光标处合并
        val merged = mergeAtCursor(node, text)
        if (merged != null && setText(node, merged)) {
            runOnMain { toast("已填入输入框") }
            return true
        }
        // 3) 追加到末尾
        val existing = node.text?.toString() ?: ""
        if (setText(node, existing + text)) {
            runOnMain { toast("已填入输入框") }
            return true
        }
        runOnMain { toast("填入失败，已复制到剪贴板") }
        return false
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focused = try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        if (focused != null && focused.isEditable) return focused
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return focused?.takeIf { it.isEditable }
        return findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            findEditable(child)?.let { return it }
        }
        return null
    }

    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qwen-voice-input", text))
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }

    private fun mergeAtCursor(node: AccessibilityNodeInfo, text: String): String? {
        val current = node.text?.toString() ?: return null
        val selStart = try {
            node.textSelectionStart
        } catch (_: Exception) {
            -1
        }
        if (selStart < 0 || selStart > current.length) return null
        return current.substring(0, selStart) + text + current.substring(selStart)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(mainLooper).post { block() }
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
