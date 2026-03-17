package com.doubao.screenshot

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.OutputStream

class FloatingService : Service() {

    companion object {
        const val ACTION_START = "com.doubao.screenshot.START"
        private const val CHANNEL_ID = "doubao_screenshot_channel"
        private const val NOTIF_ID = 1
        private const val VIRTUAL_DISPLAY_NAME = "DoubaoCapture"
        private const val DOUBAO_PACKAGE = "com.larus.nova"

        // 悬浮球尺寸：72dp，足够大又不遮挡内容
        private const val BALL_SIZE_DP = 72
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private val projectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val resultCode = intent.getIntExtra(PermissionActivity.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(PermissionActivity.EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(PermissionActivity.EXTRA_RESULT_DATA)

            if (resultCode == Activity.RESULT_OK && data != null) {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, data)
                doScreenshot()
            }
        }
    }

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            requestScreenshot()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        registerReceiver(
            projectionReceiver,
            IntentFilter(PermissionActivity.ACTION_PROJECTION_RESULT)
        )
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(
            triggerReceiver,
            IntentFilter(TapAccessibilityService.ACTION_TRIGGER_SCREENSHOT),
            receiverFlags
        )

        showFloatingBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(projectionReceiver)
        unregisterReceiver(triggerReceiver)
        floatView?.let { windowManager.removeView(it) }
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 悬浮球 ──────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun showFloatingBall() {
        val sizePx = dpToPx(BALL_SIZE_DP)

        val btn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            // 半透明紫色圆形背景，醒目但不突兀
            setBackgroundColor(0xCC6200EE.toInt())
            contentDescription = "截图发豆包"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            setOnClickListener { requestScreenshot() }
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 右下角，距底部 120dp（避开导航栏），距右边 16dp
            gravity = Gravity.BOTTOM or Gravity.END
            x = dpToPx(16)
            y = dpToPx(120)
        }

        // 拖动支持
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var isDragging = false

        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true
                        params.x = initialX - dx.toInt()
                        params.y = initialY - dy.toInt()
                        windowManager.updateViewLayout(v, params)
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) true else false
                }
                else -> false
            }
        }

        windowManager.addView(btn, params)
        floatView = btn
    }

    private fun setFloatVisible(visible: Boolean) {
        handler.post { floatView?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    // ── 截图流程 ─────────────────────────────────────────────────────────────

    private fun requestScreenshot() {
        if (mediaProjection == null) {
            val intent = Intent(this, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } else {
            doScreenshot()
        }
    }

    private fun doScreenshot() {
        setFloatVisible(false)
        handler.postDelayed({ captureScreen() }, 300)
    }

    private fun captureScreen() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader?.close()
        virtualDisplay?.release()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME, width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        handler.postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                saveAndShare(cropped)
            } else {
                setFloatVisible(true)
                showToast("截图失败，请重试")
            }
        }, 500)
    }

    private fun saveAndShare(bitmap: Bitmap) {
        val uri = saveBitmapToMediaStore(bitmap)
        bitmap.recycle()
        if (uri == null) {
            setFloatVisible(true)
            showToast("保存截图失败，请检查存储权限")
            return
        }
        shareToDoubao(uri)
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap): Uri? {
        val filename = "doubao_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DoubaoScreenshot")
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        var out: OutputStream? = null
        return try {
            out = resolver.openOutputStream(uri)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out!!)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        } finally {
            out?.close()
        }
    }

    // ── 发送给豆包（核心修复）────────────────────────────────────────────────

    private fun shareToDoubao(uri: Uri) {
        // 先恢复悬浮球显示
        setFloatVisible(true)

        val pm = packageManager

        // 检查豆包是否安装
        val doubaoInstalled = try {
            pm.getPackageInfo(DOUBAO_PACKAGE, 0)
            true
        } catch (e: Exception) { false }

        if (!doubaoInstalled) {
            showToast("未检测到豆包 App，请先安装豆包")
            return
        }

        // 构建 Share Intent
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // 用 PackageManager 查询豆包能接收图片分享的 Activity
        val resolveInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(shareIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(shareIntent, 0)
        }

        val doubaoActivity = resolveInfoList.firstOrNull {
            it.activityInfo.packageName == DOUBAO_PACKAGE
        }

        if (doubaoActivity != null) {
            // 精确启动豆包的分享接收 Activity
            val targetIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                setClassName(
                    doubaoActivity.activityInfo.packageName,
                    doubaoActivity.activityInfo.name
                )
            }
            try {
                startActivity(targetIntent)
                // 成功：不弹任何提示，静默完成
            } catch (e: Exception) {
                showToast("调用豆包失败：${e.message}")
            }
        } else {
            // 豆包已安装但未注册图片分享接收，降级弹出系统分享菜单
            showToast("豆包不支持直接接收分享，已打开系统分享菜单")
            val chooser = Intent.createChooser(shareIntent, "发送图片给...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(chooser)
            } catch (e: Exception) {
                showToast("打开分享菜单失败：${e.message}")
            }
        }
    }

    private fun showToast(msg: String) {
        handler.post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ── 通知 ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "截图服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "豆包截图助手运行中" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("豆包截图助手")
            .setContentText("点击悬浮球或三指三击截图")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
