package com.example.chesshelper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var whiteBtn: RadioButton
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, CaptureService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                    .putExtra("apiKey", keyInput.text.toString().trim())
                    .putExtra("white", whiteBtn.isChecked)
                ContextCompat.startForegroundService(this, svc)
                Toast.makeText(this, "เริ่มแล้ว เปิดเกม CHESS แล้วกดปุ่มลอยได้เลย", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "ไม่ได้อนุญาตให้จับภาพหน้าจอ", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Chess Helper"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "ใส่ Anthropic API key (เก็บไว้ในเครื่องนี้เท่านั้น)"
            setPadding(0, pad, 0, 0)
        })
        keyInput = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("apiKey", ""))
        }
        root.addView(keyInput)

        root.addView(TextView(this).apply {
            text = "คุณเล่นฝั่งไหน"
            setPadding(0, pad, 0, 0)
        })
        whiteBtn = RadioButton(this).apply { text = "ขาว"; id = 1; isChecked = prefs.getBoolean("white", true) }
        val blackBtn = RadioButton(this).apply { text = "ดำ"; id = 2 }
        RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(whiteBtn)
            addView(blackBtn)
            root.addView(this)
        }
        if (!whiteBtn.isChecked) blackBtn.isChecked = true

        root.addView(Button(this).apply {
            text = "เริ่มใช้งาน"
            setOnClickListener { start() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        root.addView(TextView(this).apply {
            text = "ใช้เพื่อฝึกซ้อม เล่นกับบอท หรือวิเคราะห์เกมย้อนหลัง " +
                "การใช้ช่วยตอนแข่งกับคนจริงออนไลน์ถือเป็นการโกง"
            textSize = 12f
            gravity = Gravity.START
            setPadding(0, pad, 0, 0)
        })

        setContentView(root)
    }

    private fun start() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "กรุณาใส่ API key ก่อน", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("apiKey", key).putBoolean("white", whiteBtn.isChecked).apply()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "เปิดสิทธิ์ \"แสดงทับแอปอื่น\" แล้วกลับมากดเริ่มใหม่", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
