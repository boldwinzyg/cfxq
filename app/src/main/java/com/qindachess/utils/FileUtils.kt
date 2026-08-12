package com.qindachess.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    private const val TAG = "FileUtils"

    fun copyToInternalStorage(context: Context, uri: Uri, targetDir: String): String? {
        return try {
            val dir = File(context.filesDir, targetDir)
            if (!dir.exists()) dir.mkdirs()

            val fileName = getFileName(context, uri) ?: "file.bin"
            val destFile = File(dir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Copied to ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file", e)
            null
        }
    }

    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = it.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast("/")
        }
        return name
    }

    fun getFileExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot >= 0) fileName.substring(dot + 1) else ""
    }

    fun isValidEngineFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return false
        if (file.length() < 1024) return false
        return true
    }

    fun isValidBookFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.canRead()) return false
        if (file.length() < 16) return false
        return true
    }
}
