package com.qindachess.ui
import com.qindachess.R

import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.qindachess.book.BookManager
import com.qindachess.book.CloudBookManager
import com.qindachess.utils.AppPreferences
import com.qindachess.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BookSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var scope: CoroutineScope

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
    private lateinit var btnApplyBook: Button
    private lateinit var btnBackBook: ImageButton

    private val bookPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importBookFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_settings)

        prefs = AppPreferences(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        bindViews()
        setupSeekBars()
        setupCheckboxes()
        setupButtons()
        loadCurrentValues()
    }

    private fun bindViews() {
        btnBackBook = findViewById(R.id.btnBackBook)

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
        btnApplyBook = findViewById(R.id.btnApplyBook)
    }

    private fun setupSeekBars() {
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
        checkUseBook.setOnCheckedChangeListener { _, isChecked ->
            prefs.useBook = isChecked
        }
        checkCloudEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.cloudEnabled = isChecked
        }
    }

    private fun setupButtons() {
        btnBackBook.setOnClickListener {
            finish()
        }

        btnImportBook.setOnClickListener {
            bookPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }

        btnDownloadCloudBook.setOnClickListener {
            downloadCloudBooks()
        }

        btnApplyBook.setOnClickListener {
            Toast.makeText(this, "开局库设置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCurrentValues() {
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
            val path = FileUtils.copyToInternalStorage(this@BookSettingsActivity, uri, "books")
            if (path != null) {
                val book = BookManager.getInstance().importLocalBook("导入的开局库", path)
                if (book != null) {
                    BookManager.getInstance().selectBook(book.id)
                    updateBookStatus()
                    Toast.makeText(this@BookSettingsActivity, "导入成功: ${book.name}", Toast.LENGTH_SHORT).show()
                } else {
                    textBookStatus.text = "导入失败：无法解析开局库"
                    Toast.makeText(this@BookSettingsActivity, "导入失败", Toast.LENGTH_SHORT).show()
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
                this@BookSettingsActivity,
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
                    Toast.makeText(this@BookSettingsActivity, "下载成功: ${book.name}", Toast.LENGTH_SHORT).show()
                }
            } else {
                textBookStatus.text = "下载失败"
                Toast.makeText(this@BookSettingsActivity, "下载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
