package com.qindachess.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qindachess.R
import com.qindachess.auto.AutoPlayService
import com.qindachess.utils.AppPreferences

class ConnectSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences

    private lateinit var textStatus: TextView
    private lateinit var textDelayValue: TextView
    private lateinit var seekDelay: SeekBar
    private lateinit var textDepthValue: TextView
    private lateinit var seekDepth: SeekBar
    private lateinit var checkAutoMode: CheckBox
    private lateinit var checkShowMiniBoard: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect_settings)

        prefs = AppPreferences(this)

        textStatus = findViewById(R.id.textStatus)
        textDelayValue = findViewById(R.id.textDelayValue)
        seekDelay = findViewById(R.id.seekDelay)
        textDepthValue = findViewById(R.id.textDepthValue)
        seekDepth = findViewById(R.id.seekDepth)
        checkAutoMode = findViewById(R.id.checkAutoMode)
        checkShowMiniBoard = findViewById(R.id.checkShowMiniBoard)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            if (!hasOverlayPermission()) return@setOnClickListener
            AutoPlayService.start(this)
            Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            AutoPlayService.stop(this)
            Toast.makeText(this, "已请求停止", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }

        // 出招延时: 0.1s ~ 8.0s, 100ms 一档，共 80 档
        seekDelay.max = 79
        val initialDelayMs = prefs.autoMoveDelay.coerceIn(100L, 8000L)
        seekDelay.progress = ((initialDelayMs - 100L) / 100L).toInt()
        updateDelayLabel(initialDelayMs)
        seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ms = 100L + progress * 100L
                updateDelayLabel(ms)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val ms = 100L + (sb?.progress ?: 4) * 100L
                prefs.autoMoveDelay = ms
            }
        })

        // 思考层数: 8 ~ 50
        seekDepth.max = 42
        val initialDepth = prefs.searchDepth.coerceIn(8, 50)
        seekDepth.progress = (initialDepth - 8)
        updateDepthLabel(initialDepth)
        seekDepth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateDepthLabel(progress + 8)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.searchDepth = (sb?.progress ?: 4) + 8
            }
        })

        checkAutoMode.isChecked = prefs.cloudEnabled  // 复用开关位作为自动模式默认
        checkAutoMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.cloudEnabled = isChecked
        }
        checkShowMiniBoard.isChecked = prefs.autoDetectBoard
        checkShowMiniBoard.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoDetectBoard = isChecked
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun updateDelayLabel(ms: Long) {
        val seconds = ms / 1000.0
        textDelayValue.text = if (seconds < 1.0) "${ms}ms" else String.format("%.1fs", seconds)
    }

    private fun updateDepthLabel(depth: Int) {
        textDepthValue.text = "$depth 层"
    }

    private fun refreshStatus() {
        textStatus.text = if (AutoPlayService.isRunning) {
            "● 悬浮窗运行中"
        } else {
            "○ 未启动"
        }
    }

    private fun hasOverlayPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return false
        }
        return true
    }
}
