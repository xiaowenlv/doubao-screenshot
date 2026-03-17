package com.doubao.screenshot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updateStatus()

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            startService(Intent(this, FloatingService::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.tv_overlay_status).text =
            "悬浮窗权限：${if (overlayOk) "✅ 已授权" else "❌ 未授权（点击下方按钮）"}"

        val accOk = isAccessibilityEnabled()
        findViewById<TextView>(R.id.tv_acc_status).text =
            "无障碍服务：${if (accOk) "✅ 已开启" else "❌ 未开启（点击下方按钮）"}"

        findViewById<Button>(R.id.btn_start).isEnabled = overlayOk
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${TapAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(service)
    }
}
