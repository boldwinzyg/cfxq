package com.qindachess.engine

import android.content.Context
import android.util.Log
import com.qindachess.book.PolyGlotBook
import java.io.File

class ResourceManager(private val context: Context) {

    enum class ResourceStatus {
        OK, MISSING, UNREADABLE, TOO_SMALL, MD5_MISMATCH
    }

    data class ResourceResult(
        val enginePath: String? = null,
        val nnuePath: String? = null,
        val bookPath: String? = null,
        val engineStatus: ResourceStatus = ResourceStatus.MISSING,
        val nnueStatus: ResourceStatus = ResourceStatus.MISSING,
        val bookStatus: ResourceStatus = ResourceStatus.MISSING
    )

    private val targetDir: File by lazy {
        File(context.codeCacheDir, "engines").apply { mkdirs() }
    }

    private val nnueDir: File by lazy {
        File(context.filesDir, "nnue").apply { mkdirs() }
    }

    private val bookDir: File by lazy {
        File(context.filesDir, "book").apply { mkdirs() }
    }

    fun deployAssets(): ResourceResult {
        val result = ResourceResult()
        var enginePath: String? = null
        var nnuePath: String? = null
        var bookPath: String? = null

        try {
            val assetManager = context.assets

            // 引擎 / NNUE / 开局库的"最低有效大小"，低于此值视为占位符或损坏，不予部署
            val minEngineSize = 10 * 1024L        // 10 KB
            val minNnueSize   = 100 * 1024L       // 100 KB
            val minBookSize   = 1 * 1024L         //  1 KB （48 字节的占位符会被拒绝）

            val engineFiles = assetManager.list("engines") ?: emptyArray()
            Log.i(TAG, "Found engine assets: ${engineFiles.toList()}")
            val engineFile = engineFiles.firstOrNull {
                it.endsWith(".so") || it.endsWith("pikafish") || it.endsWith("yukfish")
            }
            if (engineFile != null) {
                val assetSize = try { assetManager.openFd("engines/$engineFile").use { it.length } } catch (_: Exception) { 0L }
                if (assetSize < minEngineSize) {
                    Log.w(TAG, "Engine asset too small (${assetSize}B), skip: $engineFile")
                } else {
                    val dest = File(targetDir, engineFile)
                    assetManager.open("engines/$engineFile").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.setExecutable(true, false)
                    enginePath = dest.absolutePath
                    Log.i(TAG, "Engine deployed to: $enginePath (${dest.length()}B)")
                }
            }

            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val abiPath = "engines/$abi"
            val abiFiles = try { assetManager.list(abiPath) } catch (_: Exception) { null }
            if (abiFiles != null && abiFiles.isNotEmpty()) {
                val engineSo = abiFiles.first()
                val assetSize = try { assetManager.openFd("$abiPath/$engineSo").use { it.length } } catch (_: Exception) { 0L }
                if (assetSize < minEngineSize) {
                    Log.w(TAG, "ABI-specific engine asset too small (${assetSize}B), skip: $engineSo")
                } else {
                    val dest = File(targetDir, engineSo)
                    assetManager.open("$abiPath/$engineSo").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.setExecutable(true, false)
                    enginePath = dest.absolutePath
                    Log.i(TAG, "ABI-specific engine deployed to: $enginePath (${dest.length()}B)")
                }
            }

            val nnueFiles = assetManager.list("nnue") ?: emptyArray()
            val nnueFile = nnueFiles.firstOrNull { it.endsWith(".nnue") || it.endsWith(".bin") }
            if (nnueFile != null) {
                val assetSize = try { assetManager.openFd("nnue/$nnueFile").use { it.length } } catch (_: Exception) { 0L }
                if (assetSize < minNnueSize) {
                    Log.w(TAG, "NNUE asset too small (${assetSize}B), skip: $nnueFile")
                } else {
                    val dest = File(nnueDir, nnueFile)
                    assetManager.open("nnue/$nnueFile").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    nnuePath = dest.absolutePath
                    Log.i(TAG, "NNUE deployed to: $nnuePath (${dest.length()}B)")
                }
            }

            val bookFiles = assetManager.list("book") ?: emptyArray()
            val bookFile = bookFiles.firstOrNull { it.endsWith(".bin") }
            if (bookFile != null) {
                val assetSize = try { assetManager.openFd("book/$bookFile").use { it.length } } catch (_: Exception) { 0L }
                if (assetSize < minBookSize) {
                    Log.w(TAG, "Book asset too small (${assetSize}B), skip and use BuiltInBook: $bookFile")
                } else {
                    val dest = File(bookDir, bookFile)
                    assetManager.open("book/$bookFile").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    bookPath = dest.absolutePath
                    Log.i(TAG, "Book deployed to: $bookPath (${dest.length()}B)")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy assets", e)
        }

        return ResourceResult(
            enginePath = enginePath,
            nnuePath = nnuePath,
            bookPath = bookPath
        )
    }

    fun validateResource(path: String?, minSize: Long = 0): ResourceStatus {
        if (path == null) return ResourceStatus.MISSING
        val file = File(path)
        if (!file.exists()) return ResourceStatus.MISSING
        if (!file.canRead()) return ResourceStatus.UNREADABLE
        if (file.length() < minSize) return ResourceStatus.TOO_SMALL
        return ResourceStatus.OK
    }

    companion object {
        private const val TAG = "ResourceManager"
    }
}
