package com.qindachess.ui
import com.qindachess.R

import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.qindachess.QinDaApp
import com.qindachess.engine.EngineConfig
import com.qindachess.engine.EngineState
import com.qindachess.engine.SearchOptions
import com.qindachess.utils.AppPreferences
import com.qindachess.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EngineSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var scope: CoroutineScope

    private lateinit var seekDepth: SeekBar
    private lateinit var textDepthValue: TextView
    private lateinit var seekTime: SeekBar
    private lateinit var textTimeValue: TextView
    private lateinit var seekThreads: SeekBar
    private lateinit var textThreadsValue: TextView
    private lateinit var seekHash: SeekBar
    private lateinit var textHashValue: TextView
    private lateinit var seekMultiPv: SeekBar
    private lateinit var textMultiPvValue: TextView
    private lateinit var checkUseNnue: CheckBox
    private lateinit var checkPonder: CheckBox

    private lateinit var textEnginePath: TextView
    private lateinit var textNnuePath: TextView
    private lateinit var textEngineStatus: TextView
    private lateinit var btnChooseEngine: Button
    private lateinit var btnClearEngine: Button
    private lateinit var btnChooseNnue: Button
    private lateinit var btnClearNnue: Button
    private lateinit var btnReloadEngine: Button
    private lateinit var btnApplyEngine: Button
    private lateinit var btnBackEngine: ImageButton

    /**
     * 引擎文件选择器：
     * - 通过 OpenDocument 让用户选 .so / pikafish / yukfish 等可执行
     * - 复制到 app 私有目录（绕过 SAF 权限），由 engineManager 读取
     */
    private val enginePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importEngineFile(it) }
    }

    private val nnuePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importNnueFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_settings)

        prefs = AppPreferences(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        bindViews()
        setupSeekBars()
        setupCheckboxes()
        setupButtons()
        loadCurrentValues()
    }

    private fun bindViews() {
        btnBackEngine = findViewById(R.id.btnBackEngine)

        seekDepth = findViewById(R.id.seekDepth)
        textDepthValue = findViewById(R.id.textDepthValue)
        seekTime = findViewById(R.id.seekTime)
        textTimeValue = findViewById(R.id.textTimeValue)
        seekThreads = findViewById(R.id.seekThreads)
        textThreadsValue = findViewById(R.id.textThreadsValue)
        seekHash = findViewById(R.id.seekHash)
        textHashValue = findViewById(R.id.textHashValue)
        seekMultiPv = findViewById(R.id.seekMultiPv)
        textMultiPvValue = findViewById(R.id.textMultiPvValue)
        checkUseNnue = findViewById(R.id.checkUseNnue)
        checkPonder = findViewById(R.id.checkPonder)

        textEnginePath = findViewById(R.id.textEnginePath)
        textNnuePath = findViewById(R.id.textNnuePath)
        textEngineStatus = findViewById(R.id.textEngineStatus)
        btnChooseEngine = findViewById(R.id.btnChooseEngine)
        btnClearEngine = findViewById(R.id.btnClearEngine)
        btnChooseNnue = findViewById(R.id.btnChooseNnue)
        btnClearNnue = findViewById(R.id.btnClearNnue)
        btnReloadEngine = findViewById(R.id.btnReloadEngine)
        btnApplyEngine = findViewById(R.id.btnApplyEngine)
    }

    private fun setupSeekBars() {
        seekDepth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textDepthValue.text = "深度: $progress"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.searchDepth = sb?.progress ?: 12
            }
        })

        seekTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val timeMs = (progress + 1) * 500L
                textTimeValue.text = "时间: ${timeMs}ms (${timeMs / 1000.0}s)"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.searchTimeMs = (sb?.progress ?: 6 + 1) * 500L
            }
        })

        seekThreads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textThreadsValue.text = "$progress 线程"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.threadCount = sb?.progress ?: 2
            }
        })

        seekHash.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val mb = (progress + 1) * 64
                textHashValue.text = "$mb MB"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.hashSizeMb = (sb?.progress ?: 3 + 1) * 64
            }
        })

        seekMultiPv.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textMultiPvValue.text = "$progress 种候选"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.multiPv = sb?.progress ?: 3
            }
        })
    }

    private fun setupCheckboxes() {
        checkUseNnue.setOnCheckedChangeListener { _, isChecked ->
            prefs.useNnue = isChecked
        }
        checkPonder.setOnCheckedChangeListener { _, isChecked ->
            prefs.ponderEnabled = isChecked
        }
    }

    private fun setupButtons() {
        btnBackEngine.setOnClickListener {
            finish()
        }

        // === 引擎路径相关 ===
        // 路径文本本身可点击，等价于"选择"按钮
        textEnginePath.setOnClickListener {
            enginePicker.launch(arrayOf("*/*", "application/octet-stream"))
        }
        textNnuePath.setOnClickListener {
            nnuePicker.launch(arrayOf("*/*", "application/octet-stream"))
        }

        btnChooseEngine.setOnClickListener {
            // 接受所有文件类型（.so / pikafish / yukfish / elf 等）
            enginePicker.launch(arrayOf("*/*", "application/octet-stream"))
        }

        btnClearEngine.setOnClickListener {
            prefs.enginePath = ""
            loadCurrentValues()
            Toast.makeText(this, "已清除引擎路径，将使用内置引擎", Toast.LENGTH_SHORT).show()
        }

        btnChooseNnue.setOnClickListener {
            nnuePicker.launch(arrayOf("*/*", "application/octet-stream"))
        }

        btnClearNnue.setOnClickListener {
            prefs.nnuePath = ""
            loadCurrentValues()
            Toast.makeText(this, "已清除 NNUE 路径", Toast.LENGTH_SHORT).show()
        }

        btnReloadEngine.setOnClickListener {
            reloadEngine()
        }

        btnApplyEngine.setOnClickListener {
            applyEngineSettings()
            Toast.makeText(this, "引擎设置已应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCurrentValues() {
        seekDepth.progress = prefs.searchDepth.coerceIn(6, 25)
        textDepthValue.text = "深度: ${seekDepth.progress}"

        seekTime.progress = (prefs.searchTimeMs / 500 - 1).toInt().coerceIn(0, 30)
        textTimeValue.text = "时间: ${prefs.searchTimeMs}ms (${prefs.searchTimeMs / 1000.0}s)"

        seekThreads.progress = prefs.threadCount.coerceIn(1, 8)
        textThreadsValue.text = "${seekThreads.progress} 线程"

        seekHash.progress = (prefs.hashSizeMb / 64 - 1).coerceIn(0, 15)
        textHashValue.text = "${prefs.hashSizeMb} MB"

        seekMultiPv.progress = prefs.multiPv.coerceIn(1, 10)
        textMultiPvValue.text = "${seekMultiPv.progress} 种候选"

        checkUseNnue.isChecked = prefs.useNnue
        checkPonder.isChecked = prefs.ponderEnabled

        // 引擎路径显示（加可点击的视觉提示）
        val enginePathDisplay = if (prefs.enginePath.isNotBlank()) {
            "📁 ${prefs.enginePath}"
        } else {
            "（未设置，将使用 assets 内置引擎）"
        }
        textEnginePath.text = enginePathDisplay
        val nnuePathDisplay = if (prefs.nnuePath.isNotBlank()) {
            "📁 ${prefs.nnuePath}"
        } else {
            "（未设置，将使用 assets 内置 NNUE）"
        }
        textNnuePath.text = nnuePathDisplay
        updateEngineStatus()
    }

    /**
     * 应用引擎搜索参数到引擎管理器
     */
    private fun applyEngineSettings() {
        val app = application as QinDaApp
        val engineConfig = EngineConfig(
            depth = prefs.searchDepth,
            timeMs = prefs.searchTimeMs,
            threads = prefs.threadCount,
            hashSize = prefs.hashSizeMb,
            useNnue = prefs.useNnue,
            multiPv = prefs.multiPv,
            ponderEnabled = prefs.ponderEnabled
        )
        app.engineManager.applySearchOptions(engineConfig.toSearchOptions())
    }

    /**
     * 显示当前引擎的实时状态（IDLE / READY / ERROR 等）
     */
    private fun updateEngineStatus() {
        val app = application as QinDaApp
        val state = app.engineManager.engineState.value
        val ready = app.engineManager.isReady()
        val label = when {
            ready -> "引擎就绪 ✓"
            state == EngineState.STARTING -> "引擎启动中..."
            state == EngineState.SEARCHING -> "引擎思考中..."
            state == EngineState.ERROR -> "引擎错误 ✗（请检查路径/文件/可执行权限）"
            else -> "引擎未加载（请设置引擎路径或点重新加载）"
        }
        textEngineStatus.text = label
    }

    /**
     * 导入用户选择的引擎文件。
     * 关键点：
     * - 复制到 filesDir/engines/，避开 SAF 权限，让 engineManager 用 ProcessBuilder 直接启动
     * - 赋可执行权限
     * - 保存路径到 AppPreferences
     */
    private fun importEngineFile(uri: Uri) {
        scope.launch {
            textEngineStatus.text = "正在导入引擎..."
            try {
                val name = FileUtils.getFileName(this@EngineSettingsActivity, uri) ?: "user_engine.so"
                val dir = java.io.File(codeCacheDir, "engines").apply { mkdirs() }
                val dest = java.io.File(dir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                dest.setExecutable(true, false)
                prefs.enginePath = dest.absolutePath
                loadCurrentValues()
                Toast.makeText(this@EngineSettingsActivity, "引擎已保存: ${dest.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                textEngineStatus.text = "导入失败: ${e.message}"
                Toast.makeText(this@EngineSettingsActivity, "导入引擎失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importNnueFile(uri: Uri) {
        scope.launch {
            textEngineStatus.text = "正在导入 NNUE..."
            try {
                val name = FileUtils.getFileName(this@EngineSettingsActivity, uri) ?: "user_nnue.nnue"
                val dir = java.io.File(filesDir, "nnue").apply { mkdirs() }
                val dest = java.io.File(dir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                prefs.nnuePath = dest.absolutePath
                loadCurrentValues()
                Toast.makeText(this@EngineSettingsActivity, "NNUE 已保存: ${dest.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                textEngineStatus.text = "导入 NNUE 失败: ${e.message}"
                Toast.makeText(this@EngineSettingsActivity, "导入 NNUE 失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 重新加载引擎：优先用 AppPreferences 中已保存的路径，否则用 assets 中部署的。
     */
    private fun reloadEngine() {
        textEngineStatus.text = "正在重新加载引擎..."
        val app = application as QinDaApp
        scope.launch {
            try {
                val enginePath = if (prefs.enginePath.isNotBlank() && java.io.File(prefs.enginePath).exists()) {
                    prefs.enginePath
                } else {
                    // 退回 assets 中部署的
                    app.resourceManager.deployAssets().enginePath
                        ?: run {
                            textEngineStatus.text = "没有可用引擎（assets 也未打包）"
                            Toast.makeText(this@EngineSettingsActivity, "未找到任何引擎，请先选择引擎文件", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                }
                val nnuePath = if (prefs.nnuePath.isNotBlank() && java.io.File(prefs.nnuePath).exists()) {
                    prefs.nnuePath
                } else {
                    app.resourceManager.deployAssets().nnuePath
                }
                val ok = app.engineManager.loadEngine(enginePath, nnuePath)
                if (ok) {
                    app.engineManager.applySearchOptions(
                        SearchOptions(
                            depth = prefs.searchDepth,
                            timeMs = prefs.searchTimeMs,
                            threads = prefs.threadCount,
                            hashSize = prefs.hashSizeMb,
                            multiPv = prefs.multiPv,
                            useNnue = prefs.useNnue
                        )
                    )
                    // 记住本次成功路径，避免下次重新查 assets
                    prefs.enginePath = enginePath
                    if (nnuePath != null) prefs.nnuePath = nnuePath
                    Toast.makeText(this@EngineSettingsActivity, "引擎已加载", Toast.LENGTH_SHORT).show()
                } else {
                    val err = app.engineManager.lastError ?: "未知错误"
                    textEngineStatus.text = "加载失败: $err"
                    Toast.makeText(this@EngineSettingsActivity, err, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                val cause = e.cause?.message ?: e.message
                textEngineStatus.text = "加载异常: ${e.javaClass.simpleName} - $cause"
                Toast.makeText(this@EngineSettingsActivity, "加载失败: $cause", Toast.LENGTH_LONG).show()
            } finally {
                updateEngineStatus()
            }
        }
    }
}
