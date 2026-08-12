package com.qindachess.ui
import com.qindachess.R

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.qindachess.QinDaApp
import com.qindachess.book.BookManager
import com.qindachess.book.CloudBookManager
import com.qindachess.engine.EngineConfig
import com.qindachess.engine.GameConfig
import com.qindachess.engine.GameManagerV2
import com.qindachess.engine.BookConfig
import com.qindachess.utils.AppPreferences
import com.qindachess.utils.FileUtils
import com.qindachess.ui.theme.BoardSkins
import com.qindachess.ui.theme.PieceStyles
import com.qindachess.ui.theme.ThemeManager
import com.qindachess.ui.theme.AppThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SECTION = "extra_section"
        const val SECTION_ENGINE = "engine"
        const val SECTION_BOOK = "book"
    }

    private lateinit var prefs: AppPreferences
    private lateinit var themeManager: ThemeManager
    private lateinit var scope: CoroutineScope

    private lateinit var spinnerTheme: Spinner
    private lateinit var spinnerBoardSkin: Spinner
    private lateinit var spinnerPieceStyle: Spinner
    private lateinit var checkShowCoordinates: CheckBox
    private lateinit var checkFlipDefault: CheckBox

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

    private lateinit var checkUseBook: CheckBox
    private lateinit var checkCloudEnabled: CheckBox
    private lateinit var seekMaxBookMoves: SeekBar
    private lateinit var textMaxBookMovesValue: TextView
    private lateinit var seekMaxCloudMoves: SeekBar
    private lateinit var textMaxCloudMovesValue: TextView
    private lateinit var seekMinBookWeight: SeekBar
    private lateinit var textMinBookWeightValue: TextView
    private lateinit var textBookStatus: TextView

    private lateinit var btnImportBook: Button
    private lateinit var btnDownloadCloudBook: Button
    private lateinit var btnApplyAll: Button
    private lateinit var btnResetAll: Button

    private val bookPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importBookFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = AppPreferences(this)
        themeManager = ThemeManager.getInstance(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        bindViews()
        setupSpinners()
        setupSeekBars()
        setupCheckboxes()
        setupButtons()
        loadCurrentValues()
    }

    private fun bindViews() {
        spinnerTheme = findViewById(R.id.spinnerTheme)
        spinnerBoardSkin = findViewById(R.id.spinnerBoardSkin)
        spinnerPieceStyle = findViewById(R.id.spinnerPieceStyle)
        checkShowCoordinates = findViewById(R.id.checkShowCoordinates)
        checkFlipDefault = findViewById(R.id.checkFlipDefault)

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

        checkUseBook = findViewById(R.id.checkUseBook)
        checkCloudEnabled = findViewById(R.id.checkCloudEnabled)
        seekMaxBookMoves = findViewById(R.id.seekMaxBookMoves)
        textMaxBookMovesValue = findViewById(R.id.textMaxBookMovesValue)
        seekMaxCloudMoves = findViewById(R.id.seekMaxCloudMoves)
        textMaxCloudMovesValue = findViewById(R.id.textMaxCloudMovesValue)
        seekMinBookWeight = findViewById(R.id.seekMinBookWeight)
        textMinBookWeightValue = findViewById(R.id.textMinBookWeightValue)
        textBookStatus = findViewById(R.id.textBookStatus)

        btnImportBook = findViewById(R.id.btnImportBook)
        btnDownloadCloudBook = findViewById(R.id.btnDownloadCloudBook)
        btnApplyAll = findViewById(R.id.btnApplyAll)
        btnResetAll = findViewById(R.id.btnResetAll)
    }

    private fun setupSpinners() {
        val themeEntries = AppThemes.ALL.map { it.name }
        val themeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themeEntries)
        spinnerTheme.adapter = themeAdapter
        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.theme = AppThemes.ALL[position].id
                themeManager.setTheme(AppThemes.ALL[position].id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val skinEntries = BoardSkins.ALL.map { it.name }
        val skinAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, skinEntries)
        spinnerBoardSkin.adapter = skinAdapter
        spinnerBoardSkin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.boardSkinId = BoardSkins.ALL[position].id
                themeManager.setSkin(BoardSkins.ALL[position].id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val pieceEntries = PieceStyles.ALL.map { it.name }
        val pieceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pieceEntries)
        spinnerPieceStyle.adapter = pieceAdapter
        spinnerPieceStyle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.pieceStyleId = PieceStyles.ALL[position].id
                themeManager.setPieceStyle(PieceStyles.ALL[position].id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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

        seekMaxBookMoves.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textMaxBookMovesValue.text = "$progress 步后脱谱"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.maxBookMoves = sb?.progress ?: 20
            }
        })

        seekMaxCloudMoves.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textMaxCloudMovesValue.text = "$progress 步后停止云库查询"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.maxCloudMoves = sb?.progress ?: 30
            }
        })

        seekMinBookWeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textMinBookWeightValue.text = "≥ $progress 权重"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.minBookWeight = sb?.progress ?: 10
            }
        })
    }

    private fun setupCheckboxes() {
        checkShowCoordinates.setOnCheckedChangeListener { _, isChecked ->
            prefs.showCoordinates = isChecked
        }
        checkFlipDefault.setOnCheckedChangeListener { _, isChecked ->
            prefs.flipBoardByDefault = isChecked
        }
        checkUseNnue.setOnCheckedChangeListener { _, isChecked ->
            prefs.useNnue = isChecked
        }
        checkPonder.setOnCheckedChangeListener { _, isChecked ->
            prefs.ponderEnabled = isChecked
        }
        checkUseBook.setOnCheckedChangeListener { _, isChecked ->
            prefs.useBook = isChecked
        }
        checkCloudEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.cloudEnabled = isChecked
        }
    }

    private fun setupButtons() {
        btnImportBook.setOnClickListener {
            bookPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }

        btnDownloadCloudBook.setOnClickListener {
            downloadCloudBooks()
        }

        btnApplyAll.setOnClickListener {
            applyAllSettings()
            Toast.makeText(this, "设置已应用", Toast.LENGTH_SHORT).show()
        }

        btnResetAll.setOnClickListener {
            prefs.reset()
            loadCurrentValues()
            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCurrentValues() {
        spinnerTheme.setSelection(AppThemes.ALL.indexOfFirst { it.id == prefs.theme }.coerceAtLeast(0))
        spinnerBoardSkin.setSelection(BoardSkins.ALL.indexOfFirst { it.id == prefs.boardSkinId }.coerceAtLeast(0))
        spinnerPieceStyle.setSelection(PieceStyles.ALL.indexOfFirst { it.id == prefs.pieceStyleId }.coerceAtLeast(0))

        checkShowCoordinates.isChecked = prefs.showCoordinates
        checkFlipDefault.isChecked = prefs.flipBoardByDefault

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

        checkUseBook.isChecked = prefs.useBook
        checkCloudEnabled.isChecked = prefs.cloudEnabled

        seekMaxBookMoves.progress = prefs.maxBookMoves.coerceIn(0, 50)
        textMaxBookMovesValue.text = "${seekMaxBookMoves.progress} 步后脱谱"

        seekMaxCloudMoves.progress = prefs.maxCloudMoves.coerceIn(0, 60)
        textMaxCloudMovesValue.text = "${seekMaxCloudMoves.progress} 步后停止云库查询"

        seekMinBookWeight.progress = prefs.minBookWeight.coerceIn(0, 100)
        textMinBookWeightValue.text = "≥ ${seekMinBookWeight.progress} 权重"

        updateBookStatus()
    }

    private fun updateBookStatus() {
        val mgr = BookManager.getInstance()
        val books = mgr.listAllBooks()
        textBookStatus.text = if (books.isNotEmpty()) {
            val active = mgr.activeBookInfo
            val sb = StringBuilder()
            sb.append("已加载开局库 (${books.size} 个):\n")
            books.forEach { book ->
                val marker = if (active?.id == book.id) " ✓" else ""
                val builtIn = if (book.isBuiltIn) " [内置]" else " [用户]"
                sb.append("• ${book.name}$marker$builtIn\n")
            }
            sb.toString().trimEnd()
        } else {
            "当前未加载任何开局库。可以导入本地 .bin 文件或下载云端库。"
        }
    }

    private fun importBookFile(uri: Uri) {
        scope.launch {
            textBookStatus.text = "正在导入..."
            val path = FileUtils.copyToInternalStorage(this@SettingsActivity, uri, "books")
            if (path != null) {
                val book = BookManager.getInstance().importLocalBook("导入的开局库", path)
                if (book != null) {
                    BookManager.getInstance().selectBook(book.id)
                    updateBookStatus()
                    Toast.makeText(this@SettingsActivity, "导入成功: ${book.name}", Toast.LENGTH_SHORT).show()
                } else {
                    textBookStatus.text = "导入失败：无法解析开局库"
                    Toast.makeText(this@SettingsActivity, "导入失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                textBookStatus.text = "导入失败：文件复制错误"
            }
        }
    }

    private fun downloadCloudBooks() {
        scope.launch {
            textBookStatus.text = "正在获取云库列表..."
            val cloudProvider = CloudBookManager()
            val books = cloudProvider.fetchBookList()

            if (books.isEmpty()) {
                textBookStatus.text = "无法获取云端开局库列表，请检查网络"
                return@launch
            }

            val book = books.first()
            val targetFile = FileUtils.copyToInternalStorage(
                this@SettingsActivity,
                Uri.parse("content://dummy/${book.id}.bin"),
                "books"
            ) ?: run {
                val dir = java.io.File(filesDir, "books")
                dir.mkdirs()
                java.io.File(dir, "${book.id}.bin").absolutePath
            }

            textBookStatus.text = "正在下载 ${book.name}..."
            val success = cloudProvider.downloadBook(book, targetFile)

            if (success) {
                val registered = BookManager.getInstance().importLocalBook(book.name, targetFile)
                if (registered != null) {
                    BookManager.getInstance().selectBook(registered.id)
                    updateBookStatus()
                    Toast.makeText(this@SettingsActivity, "下载成功: ${book.name}", Toast.LENGTH_SHORT).show()
                }
            } else {
                textBookStatus.text = "下载失败"
                Toast.makeText(this@SettingsActivity, "下载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyAllSettings() {
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
        val bookConfig = BookConfig(
            enabled = prefs.useBook,
            maxBookMoves = prefs.maxBookMoves,
            cloudEnabled = prefs.cloudEnabled,
            maxCloudMoves = prefs.maxCloudMoves,
            minBookWeight = prefs.minBookWeight
        )

        themeManager.setTheme(prefs.theme)
        themeManager.setSkin(prefs.boardSkinId)
        themeManager.setPieceStyle(prefs.pieceStyleId)

        app.engineManager.applySearchOptions(engineConfig.toSearchOptions())
    }
}
