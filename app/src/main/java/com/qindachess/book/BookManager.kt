package com.qindachess.book

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

enum class BookFormat { OBK, UCCI_TEXT, POLYGLOT, UNKNOWN }

/**
 * 统一开局库接口 —— 不同格式（OBK / UCCI 文本 / PolyGlot）共享同一个抽象。
 */
interface IOpeningBook {
    fun isLoaded(): Boolean
    fun entryCount(): Int
    fun findMovesForPosition(fen: String): List<Pair<String, Int>>
    fun findBestMove(fen: String): String?
}

data class BookInfo(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val entryCount: Int,
    val isDefault: Boolean,
    val isBuiltIn: Boolean,
    val sourceUrl: String? = null,
    val version: String = "1.0",
    val author: String = "Unknown",
    val sha256: String? = null,
    val fileSizeBytes: Long = 0,
    val format: BookFormat = BookFormat.UNKNOWN
)

object BookFormatDetector {

    fun detect(file: File): BookFormat {
        if (!file.exists() || file.length() < 4) return BookFormat.UNKNOWN
        return try {
            val head = ByteArray(4)
            FileInputStream(file).use { it.read(head) }
            detectFromMagic(head, file.name)
        } catch (e: Exception) {
            detectByExtension(file.name)
        }
    }

    fun detectFromBytes(data: ByteArray, fileName: String = ""): BookFormat {
        if (data.size < 4) return detectByExtension(fileName)
        val head = data.copyOfRange(0, 4)
        return detectFromMagic(head, fileName)
    }

    private fun detectFromMagic(head: ByteArray, fileName: String): BookFormat {
        // OBK magic: "OBK" + version byte
        if (head.size >= 3 &&
            head[0] == 'O'.code.toByte() &&
            head[1] == 'B'.code.toByte() &&
            head[2] == 'K'.code.toByte()) {
            return BookFormat.OBK
        }
        // PolyGlot magic: 前 4 字节看起来像排序好的 entry 里的 key（但不是严格 magic）
        // 文本格式：检查前几个字节是否可读 ASCII
        val printable = head.all { it in 0x20..0x7E || it == '\n'.code.toByte() || it == '\r'.code.toByte() || it == '\t'.code.toByte() }
        if (printable) return BookFormat.UCCI_TEXT
        return detectByExtension(fileName)
    }

    private fun detectByExtension(name: String): BookFormat {
        return when {
            name.endsWith(".obk", ignoreCase = true) -> BookFormat.OBK
            name.endsWith(".binbook", ignoreCase = true) -> BookFormat.POLYGLOT
            name.endsWith(".dat", ignoreCase = true) ||
            name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".uci", ignoreCase = true) -> BookFormat.UCCI_TEXT
            else -> BookFormat.UNKNOWN
        }
    }
}

class BookManager private constructor() {

    private val localBooks = mutableMapOf<String, BookInfo>()
    private val loadedBooks = mutableMapOf<String, IOpeningBook>()
    private var activeBookId: String? = null

    /**
     * activeBook 变化时通知 UI 刷新。GameManager 创建时 activeBook 可能为 null
     * （assets 异步部署中），需要等 registerBuiltInBook 后才更新。
     */
    private val _activeBookFlow = MutableStateFlow<String?>(null)
    val activeBookIdFlow: StateFlow<String?> = _activeBookFlow.asStateFlow()

    val activeBook: IOpeningBook?
        get() = activeBookId?.let { loadedBooks[it] }

    val activeBookInfo: BookInfo?
        get() = activeBookId?.let { localBooks[it] }

    fun registerBuiltInBook(name: String, description: String, path: String): BookInfo? {
        // 空路径直接走兜底（调用方主动放弃 assets 库）
        if (path.isBlank()) {
            return registerFallbackBook(name, description)
        }
        val file = File(path)
        // 48 字节的占位符 / < 1KB 的文件 / 损坏的 bin 都视为"无效"，直接走 BuiltInBook 兜底
        if (!file.exists() || file.length() < 1024) {
            Log.w(TAG, "Built-in book file too small or missing: $path (${file.length()}B), using BuiltInBook")
            return registerFallbackBook(name, description)
        }

        val book = loadBookAuto(path)
        if (book == null || !book.isLoaded() || book.entryCount() == 0) {
            Log.w(TAG, "Built-in book load failed or empty: $path, using BuiltInBook")
            return registerFallbackBook(name, description)
        }

        val info = BookInfo(
            id = "builtin_${name.hashCode().toString(16)}",
            name = name, description = description, path = path,
            entryCount = book.entryCount(), isDefault = true, isBuiltIn = true,
            fileSizeBytes = file.length(),
            format = BookFormatDetector.detect(file)
        )
        localBooks[info.id] = info
        loadedBooks[info.id] = book
        if (activeBookId == null) {
            activeBookId = info.id
            _activeBookFlow.value = info.id
            Log.i(TAG, "✅ 默认激活开局库: ${info.name} (${info.format}, ${info.entryCount} entries)")
        }
        Log.i(TAG, "Built-in book registered: $name (${info.format}, ${info.entryCount} entries)")
        return info
    }

    /**
     * 当 assets 中没有可用开局库时使用内置兜底开局库（BuiltInBook）。
     * 这样保证开局库 Tab 永远有招法可显示。
     */
    private fun registerFallbackBook(name: String, description: String): BookInfo? {
        val book = UcciTextBook().apply {
            appendEntries(BuiltInBook.getEntries())
        }
        if (!book.isLoaded()) {
            Log.e(TAG, "Even BuiltInBook is empty!")
            return null
        }
        val info = BookInfo(
            id = "builtin_fallback",
            name = name + "（兜底）",
            description = description + " - 内置兜底开局库",
            path = "",
            entryCount = book.entryCount(),
            isDefault = true,
            isBuiltIn = true,
            fileSizeBytes = 0,
            format = BookFormat.UCCI_TEXT
        )
        localBooks[info.id] = info
        loadedBooks[info.id] = book
        if (activeBookId == null) {
            activeBookId = info.id
            _activeBookFlow.value = info.id
            Log.i(TAG, "✅ 兜底开局库已激活 (${book.entryCount()} entries)")
        }
        return info
    }

    suspend fun importLocalBook(name: String, path: String): BookInfo? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists() || file.length() < 4) return@withContext null
            val book = loadBookAuto(path) ?: return@withContext null

            val info = BookInfo(
                id = "local_${file.name.hashCode().toString(16)}",
                name = name.ifBlank { file.name },
                description = "用户导入开局库 (${BookFormatDetector.detect(file)})",
                path = path,
                entryCount = book.entryCount(),
                isDefault = false, isBuiltIn = false,
                fileSizeBytes = file.length(),
                format = BookFormatDetector.detect(file)
            )
            localBooks[info.id] = info
            loadedBooks[info.id] = book
            info
        }

    fun deleteBook(bookId: String): Boolean {
        val info = localBooks.remove(bookId) ?: return false
        loadedBooks.remove(bookId)
        if (info.isBuiltIn) return false
        if (activeBookId == bookId) activeBookId = localBooks.keys.firstOrNull()
        return true
    }

    fun selectBook(bookId: String): Boolean {
        if (!loadedBooks.containsKey(bookId)) return false
        activeBookId = bookId
        _activeBookFlow.value = bookId
        return true
    }

    fun listAllBooks(): List<BookInfo> = localBooks.values.toList()
    fun getBook(bookId: String): BookInfo? = localBooks[bookId]

    fun reloadBook(bookId: String): Boolean {
        val info = localBooks[bookId] ?: return false
        val book = loadBookAuto(info.path) ?: return false
        loadedBooks[bookId] = book
        return true
    }

    fun findBestMove(fen: String, maxBookMoves: Int = 30, moveCountInGame: Int = 0): String? {
        val book = activeBook ?: return null
        if (!book.isLoaded()) return null
        if (moveCountInGame >= maxBookMoves) return null
        return book.findBestMove(fen)
    }

    fun findAllMoves(fen: String): List<Pair<String, Int>> {
        val book = activeBook ?: return emptyList()
        return book.findMovesForPosition(fen)
    }

    // ============================================================
    //  私有工具
    // ============================================================

    private fun loadBookAuto(path: String): IOpeningBook? {
        val file = File(path)
        val format = BookFormatDetector.detect(file)
        Log.i(TAG, "Detected format=$format for ${file.name}")
        val book: IOpeningBook = when (format) {
            BookFormat.OBK -> ObkBook().apply { loadFromFile(path) }
            BookFormat.UCCI_TEXT -> UcciTextBook().apply { loadFromFile(path) }
            BookFormat.POLYGLOT -> PolyGlotBook().apply { loadFromFile(path) }
            BookFormat.UNKNOWN -> ObkBook().takeIf { it.loadFromFile(path) }
                ?: UcciTextBook().takeIf { it.loadFromFile(path) }
                ?: PolyGlotBook().takeIf { it.loadFromFile(path) }
                ?: return null
        }
        return book
    }

    companion object {
        private const val TAG = "BookManager"

        @Volatile private var instance: BookManager? = null
        fun getInstance(): BookManager = instance ?: synchronized(this) {
            instance ?: BookManager().also { instance = it }
        }
    }
}

object EmptyBook : IOpeningBook {
    override fun isLoaded(): Boolean = false
    override fun entryCount(): Int = 0
    override fun findMovesForPosition(fen: String): List<Pair<String, Int>> = emptyList()
    override fun findBestMove(fen: String): String? = null
}

