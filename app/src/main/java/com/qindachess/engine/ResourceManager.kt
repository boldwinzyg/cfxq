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
        File(context.filesDir, "engines").apply { mkdirs() }
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

            val engineFiles = assetManager.list("engines") ?: emptyArray()
            Log.i(TAG, "Found engine assets: ${engineFiles.toList()}")
            val engineFile = engineFiles.firstOrNull { it.endsWith(".so") || it.endsWith("pikafish") || it.endsWith("yukfish") }
            if (engineFile != null) {
                val dest = File(targetDir, engineFile)
                assetManager.open("engines/$engineFile").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setExecutable(true, false)
                enginePath = dest.absolutePath
                Log.i(TAG, "Engine deployed to: $enginePath")
            }

            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val abiPath = "engines/$abi"
            val abiFiles = try { assetManager.list(abiPath) } catch (_: Exception) { null }
            if (abiFiles != null && abiFiles.isNotEmpty()) {
                val engineSo = abiFiles.first()
                val dest = File(targetDir, engineSo)
                assetManager.open("$abiPath/$engineSo").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setExecutable(true, false)
                enginePath = dest.absolutePath
                Log.i(TAG, "ABI-specific engine deployed to: $enginePath")
            }

            val nnueFiles = assetManager.list("nnue") ?: emptyArray()
            val nnueFile = nnueFiles.firstOrNull { it.endsWith(".nnue") || it.endsWith(".bin") }
            if (nnueFile != null) {
                val dest = File(nnueDir, nnueFile)
                assetManager.open("nnue/$nnueFile").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                nnuePath = dest.absolutePath
                Log.i(TAG, "NNUE deployed to: $nnuePath")
            }

            val bookFiles = assetManager.list("book") ?: emptyArray()
            val bookFile = bookFiles.firstOrNull { it.endsWith(".bin") }
            if (bookFile != null) {
                val dest = File(bookDir, bookFile)
                assetManager.open("book/$bookFile").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                bookPath = dest.absolutePath
                Log.i(TAG, "Book deployed to: $bookPath")
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
