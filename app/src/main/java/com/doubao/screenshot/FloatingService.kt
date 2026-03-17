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
import androidx.core.app.NotificationCompat
import java.io.OutputStream

/**
 * 核心前台服务：
 *  1. 显示可拖动悬浮球
 *  2. 接收截图广播（来自悬浮球点击 或 TapAccessibilityService）
 *  3. 使用 MediaProjection 截图
 *  4. 通过 Android Share Intent 发送给豆包
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_START = "com.doubao.screenshot.START"
        private const val CHANNEL_ID = "doubao_screenshot_channel"
        private const val NOTIF_ID = 1
        private const val VIRTUAL_DISPLAY_NAME = "DoubaoCapture"
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
        registerReceiver(
            triggerReceiver,
            IntentFilter(TapAccessibilityService.ACTION_TRIGGER_SCREENSHOT),
            RECEIVER_NOT_EXPORTED
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

    private fun showFloatingBall() {
        val btn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setBackgroundResource(android.R.drawable.btn_default)
            contentDescription = "截图发豆包"
            setOnClickListener { requestScreenshot() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 300
        }

        // 拖动支持
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(v, params)
                    true
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
            // 首次需要用户授权
            val intent = Intent(this, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } else {
            doScreenshot()
        }
    }

    private fun doScreenshot() {
        // 隐藏悬浮球，等一帧渲染完再截图
        setFloatVisible(false)
        handler.postDelayed({
            captureScreen()
        }, 300)
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
            }
            setFloatVisible(true)
        }, 500)
    }

    private fun saveAndShare(bitmap: Bitmap) {
        val uri = saveBitmapToMediaStore(bitmap) ?: return
        bitmap.recycle()
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

    private fun shareToDoubao(uri: Uri) {
        // 优先直接打开豆包并传入图片；若豆包未安装则弹出系统分享菜单
        val doubaoPackage = "com.larus.nova" // 豆包包名

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = packageManager
        val doubaoInstalled = try {
            pm.getPackageInfo(doubaoPackage, 0)
            true
        } catch (e: Exception) { false }

        if (doubaoInstalled) {
            shareIntent.setPackage(doubaoPackage)
            startActivity(shareIntent)
        } else {
            // 豆包未安装，弹出系统分享选择器
            val chooser = Intent.createChooser(shareIntent, "发送给...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
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
