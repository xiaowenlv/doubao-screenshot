package com.doubao.screenshot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * 透明 Activity，专门用于弹出 MediaProjection 授权对话框。
 * 授权结果通过 LocalBroadcast 传回 FloatingService。
 */
class PermissionActivity : Activity() {

    companion object {
        const val ACTION_PROJECTION_RESULT = "com.doubao.screenshot.PROJECTION_RESULT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            val broadcast = Intent(ACTION_PROJECTION_RESULT).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            sendBroadcast(broadcast)
        }
        finish()
    }
}
