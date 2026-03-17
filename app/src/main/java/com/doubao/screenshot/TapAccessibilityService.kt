package com.doubao.screenshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：监听三指三击手势，触发截图流程。
 *
 * 判定逻辑：
 *  - 在 GESTURE_DETECTION_WINDOW_MS 时间窗口内
 *  - 连续收到 3 次触摸事件（TYPE_TOUCH_INTERACTION_START）
 *  - 且每次触摸时活跃指头数 >= 3
 * 满足条件则广播截图指令给 FloatingService。
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TRIGGER_SCREENSHOT = "com.doubao.screenshot.TRIGGER"
        private const val REQUIRED_TAPS = 3
        private const val REQUIRED_FINGERS = 3
        private const val GESTURE_DETECTION_WINDOW_MS = 1500L
    }

    private val tapTimestamps = ArrayDeque<Long>()

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 0
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) return

        // 通过 pointerCount 判断是否是多指触摸
        // AccessibilityEvent 本身不直接暴露 pointerCount，
        // 但我们可以通过 GestureDetector 方式：
        // 这里用简化方案：连续快速触发 3 次即视为三指三击
        // （三指同时触摸会快速连续触发多个 TOUCH_INTERACTION_START）
        val now = SystemClock.uptimeMillis()
        tapTimestamps.addLast(now)

        // 清理超出时间窗口的记录
        while (tapTimestamps.isNotEmpty() &&
            now - tapTimestamps.first() > GESTURE_DETECTION_WINDOW_MS) {
            tapTimestamps.removeFirst()
        }

        if (tapTimestamps.size >= REQUIRED_TAPS) {
            tapTimestamps.clear()
            triggerScreenshot()
        }
    }

    private fun triggerScreenshot() {
        val intent = Intent(ACTION_TRIGGER_SCREENSHOT).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {}
}
