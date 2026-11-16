package com.example.chesshelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.IntentCompat
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class CaptureService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: View? = null
    private lateinit var status: TextView
    private lateinit var analyzeBtn: Button

    private var apiKey = ""
    private var iAmWhite = true
    private var busy = false
    private var captured = false
    private var w = 0
    private var h = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        apiKey = intent.getStringExtra("apiKey") ?: ""
        iAmWhite = intent.getBooleanExtra("white", true)

        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        // Android 14+ ต้อง register callback ก่อนสร้าง VirtualDisplay
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, main)

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupCapture(p)
        showOverlay()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("chess", "Chess Helper", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "chess")
            .setContentTitle("Chess Helper กำลังทำงาน")
            .setContentText("กดปุ่มลอยเพื่อวิเคราะห์กระดาน")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    private fun setupCapture(p: MediaProjection) {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        w = dm.widthPixels
        h = dm.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay(
            "chess-capture", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun showOverlay() {
        val d = resources.displayMetrics.density
        val pad = (10 * d).toInt()

        status = TextView(this).apply {
            text = "พร้อมแล้ว เปิดกระดานแล้วกดวิเคราะห์"
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = (260 * d).toInt()
        }
        analyzeBtn = Button(this).apply {
            text = "วิเคราะห์"
            setOnClickListener { analyze() }
        }
        val stopBtn = Button(this).apply {
            text = "หยุด"
            setOnClickListener { stopSelf() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(analyzeBtn)
            addView(stopBtn)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 20, 20, 20))
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(row)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * d).toInt()
            y = (120 * d).toInt()
        }

        // ลากกล่องไปวางตำแหน่งอื่นได้โดยลากที่ข้อความ
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        status.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(box, lp)
                }
            }
            true
        }

        overlay = box
        wm.addView(box, lp)
    }

    private fun setStatus(s: String) {
        main.post { status.text = s }
    }

    private fun analyze() {
        if (busy) return
        busy = true
        captured = false
        setStatus("กำลังจับภาพ...")
        overlay?.visibility = View.INVISIBLE // ซ่อนกล่องเพื่อไม่ให้ติดในภาพ

        main.postDelayed({
            val r = reader
            if (r == null) {
                finish("ระบบจับภาพยังไม่พร้อม")
                return@postDelayed
            }
            val img = r.acquireLatestImage()
            if (img != null) {
                handleImage(img)
            } else {
                r.setOnImageAvailableListener({ rd ->
                    rd.setOnImageAvailableListener(null, null)
                    rd.acquireLatestImage()?.let { handleImage(it) }
                }, main)
                main.postDelayed({
                    if (!captured) {
                        reader?.setOnImageAvailableListener(null, null)
                        finish("จับภาพไม่สำเร็จ ลองใหม่อีกครั้ง")
                    }
                }, 3000)
            }
        }, 400)
    }

    private fun handleImage(img: Image) {
        if (captured) {
            img.close()
            return
        }
        captured = true
        val jpeg: ByteArray
        try {
            val plane = img.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * w
            val full = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(full, 0, 0, w, h)

            val maxSide = 1568f
            val scale = minOf(1f, maxSide / maxOf(w, h))
            val scaled = if (scale < 1f)
                Bitmap.createScaledBitmap(cropped, (w * scale).toInt(), (h * scale).toInt(), true)
            else cropped
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            jpeg = out.toByteArray()
        } finally {
            img.close()
        }

        overlay?.visibility = View.VISIBLE
        setStatus("กำลังให้ AI อ่านกระดานและคิดตาเดิน...")

        thread {
            val msg = try {
                val res = ChessAnalyzer.analyze(apiKey, jpeg, iAmWhite)
                "${res.text}\n\nFEN: ${res.fen}"
            } catch (e: Exception) {
                "ผิดพลาด: ${e.message}"
            }
            finish(msg)
        }
    }

    private fun finish(msg: String) {
        main.post {
            overlay?.visibility = View.VISIBLE
            status.text = msg
            busy = false
        }
    }

    override fun onDestroy() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
