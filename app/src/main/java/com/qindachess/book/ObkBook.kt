package com.qindachess.book

import android.util.Log
import com.qindachess.board.ChessBoard
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OBK v1 —— 中国象棋最主流的商业开局库格式。
 *
 * 文件结构：
 *   Header 16 bytes:
 *     magic      : "OBK\x01\x00\x00\x00"
 *     entryCount : int32  条目数
 *     reserved   : 8 bytes
 *
 *   Entry 20 bytes:
 *     hash   : 8 bytes  Zobrist 键（象棋专用）
 *     from   : byte      起始格 0-89
 *     to     : byte      目标格 0-89
 *     piece  : byte      移动的棋子类型编码
 *     weight : uint16    胜率权重
 *     learn  : int32     学习值（负表示差着）
 *     score  : int16     局面评分（厘子，100 = 1子）
 *     pad    : 2 bytes   保留
 */
class ObkBook : IOpeningBook {

    data class ObkEntry(
        val hash: Long,
        val from: Int,        // 0-89
        val to: Int,          // 0-89
        val piece: Int,       // OBK 棋子编码
        val weight: Int,
        val learn: Int,
        val score: Int
    ) {
        /** 转换成 UCI 走法字符串，比如 "h2e2" */
        fun toUciMove(): String {
            val fromRow = from / 9
            val fromCol = from % 9
            val toRow = to / 9
            val toCol = to % 9
            val ffc = 'a' + fromCol
            val frc = '9' - fromRow
            val tfc = 'a' + toCol
            val trc = '9' - toRow
            return "$ffc$frc$tfc$trc"
        }
    }

    private val entries = mutableListOf<ObkEntry>()
    private var isLoaded = false
    private var version = 1

    override fun isLoaded(): Boolean = isLoaded
    override fun entryCount(): Int = entries.size

    fun loadFromFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "OBK file not found: $path")
                return false
            }
            if (file.length() < HEADER_SIZE) {
                Log.e(TAG, "OBK file too small: ${file.length()}")
                return false
            }
            loadFromBytes(FileInputStream(file).use { it.readBytes() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load OBK", e)
            false
        }
    }

    fun loadFromBytes(data: ByteArray): Boolean {
        return try {
            entries.clear()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // 校验 magic
            val magic = ByteArray(4)
            buf.get(magic)
            val expected = byteArrayOf('O'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 1)
            val isObkV1 = magic.contentEquals(expected)
            val isObkV1Alt = magic[0] == 'O'.code.toByte() && magic[1] == 'B'.code.toByte() && magic[2] == 'K'.code.toByte()
            if (!isObkV1 && !isObkV1Alt) {
                Log.e(TAG, "Not an OBK file (magic=${magic.contentToString()})")
                return false
            }
            version = if (magic[3].toInt() == 1) 1 else magic[3].toInt()

            buf.short       // 跳过 version flag
            val entryCount = buf.int
            buf.position(buf.position() + 8)  // 跳过 reserved

            val entrySize = 20
            val actualEntries = (buf.remaining() / entrySize).coerceAtMost(entryCount)

            for (i in 0 until actualEntries) {
                val hash = buf.long
                val from = buf.get().toInt() and 0xFF
                val to = buf.get().toInt() and 0xFF
                val piece = buf.get().toInt() and 0xFF
                val weight = buf.short.toInt() and 0xFFFF
                val learn = buf.int
                val score = buf.short.toInt()
                buf.short  // pad

                entries.add(ObkEntry(hash, from, to, piece, weight, learn, score))
            }

            entries.sortBy { it.hash }
            isLoaded = true
            Log.i(TAG, "✅ OBK loaded: ${entries.size} entries (v$version)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "OBK parse failed", e)
            false
        }
    }

    /** 查找当前局面的所有开局走法，返回 [UCI 走法 → weight]，按 weight 降序 */
    override fun findMovesForPosition(fen: String): List<Pair<String, Int>> {
        if (!isLoaded) return emptyList()
        val board = ChessBoard().apply { parseFen(fen) }
        val targetHash = XiangqiZobrist.compute(board)

        val result = mutableMapOf<String, Int>()

        // 二分定位 hash 起点
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val h = entries[mid].hash
            when {
                h == targetHash -> {
                    // 找到，向两端扩展
                    var start = mid
                    while (start > 0 && entries[start - 1].hash == targetHash) start--
                    var end = mid
                    while (end < entries.size - 1 && entries[end + 1].hash == targetHash) end++
                    for (i in start..end) {
                        val e = entries[i]
                        val uci = e.toUciMove()
                        result[uci] = (result[uci] ?: 0) + e.weight
                    }
                    break
                }
                h < targetHash -> lo = mid + 1
                else -> hi = mid - 1
            }
        }

        return result.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** 根据 weight 加权随机选一步 */
    override fun findBestMove(fen: String): String? {
        val moves = findMovesForPosition(fen)
        if (moves.isEmpty()) return null
        val total = moves.sumOf { it.second }.coerceAtLeast(1)
        var r = (Math.random() * total).toInt()
        for ((m, w) in moves) {
            r -= w
            if (r <= 0) return m
        }
        return moves.first().first
    }

    companion object {
        private const val TAG = "ObkBook"
        private const val HEADER_SIZE = 16
    }
}
