package com.qindachess.book

import android.util.Log
import java.io.File
import java.nio.charset.Charset

/**
 * UCCI 开局库文本格式。
 *
 * 最通用的人类可读开局库格式，网上能直接下载到：
 *   - 华山狂刀
 *   - 炮镇中权
 *   - 梅花谱
 *   - 飞燕抄底
 *
 * 每行格式之一：
 *   1. fen|move|weight|score|comment
 *      rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1|h2e2|100|123|炮二平五
 *
 *   2. UCI "book moves" 风格：
 *      book h2e2 weight 100
 *      book h2e7 weight 80
 *
 *   3. 纯走法列表（用于固定开局，无 Fen）：
 *      h2e2  b7c7
 */
class UcciTextBook : IOpeningBook {

    data class TextEntry(
        val fen: String?,
        val move: String,
        val weight: Int,
        val score: Int,
        val comment: String?
    )

    private val byFen = mutableMapOf<String, MutableList<TextEntry>>()
    private var globalEntries = mutableListOf<TextEntry>()
    private var isLoaded = false

    override fun isLoaded(): Boolean = isLoaded
    override fun entryCount(): Int = byFen.values.sumOf { it.size } + globalEntries.size

    fun loadFromFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() < 2) return false
            // 尝试多种编码（华山、梅花谱等常见 UCCI 文件为 GBK/GB18030）
            val rawBytes = file.readBytes()
            val candidates = listOf(Charsets.UTF_8, Charset.forName("GBK"), Charset.forName("GB18030"), Charset.forName("UTF-16LE"), Charset.forName("UTF-16BE"))
            var text: String? = null
            for (enc in candidates) {
                try {
                    val t = rawBytes.toString(enc)
                    // 中文等非 ASCII 字符占 2~3 字节且解码无错；UTF-8 严格不允许 0xFFFE
                    if (!t.contains('\uFFFD')) { text = t; break }
                } catch (_: Exception) {}
            }
            if (text == null) text = rawBytes.toString(Charsets.UTF_8)
            loadFromString(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load UCCI text", e)
            false
        }
    }

    fun loadFromString(text: String): Boolean {
        return try {
            byFen.clear()
            globalEntries.clear()

            val lines = text.lineSequence().map { it.trim() }
            for (raw in lines) {
                if (raw.isBlank() || raw.startsWith('#') || raw.startsWith(';')) continue

                val entry = parseLine(raw) ?: continue
                val fen = entry.fen
                if (fen != null) {
                    byFen.getOrPut(normalizeFen(fen)) { mutableListOf() }.add(entry)
                } else {
                    globalEntries.add(entry)
                }
            }

            isLoaded = byFen.isNotEmpty() || globalEntries.isNotEmpty()
            Log.i(TAG, "✅ UCCI text loaded: fenGroups=${byFen.size}, global=${globalEntries.size}")
            isLoaded
        } catch (e: Exception) {
            Log.e(TAG, "UCCI text parse failed", e)
            false
        }
    }

    /**
     * 把一段"开局母串"在开局局面下展开。
     * 母串格式：fen|move1|weight1|score1|comment1
     *          fen|move2|weight2|score2|comment2
     */
    fun appendEntries(entries: List<TextEntry>) {
        for (e in entries) {
            if (e.fen != null) {
                byFen.getOrPut(normalizeFen(e.fen)) { mutableListOf() }.add(e)
            } else {
                globalEntries.add(e)
            }
        }
        isLoaded = byFen.isNotEmpty() || globalEntries.isNotEmpty()
    }

    private fun parseLine(line: String): TextEntry? {
        // 格式 1: fen|move|weight|score|comment
        if ('|' in line) {
            val parts = line.split('|', limit = 5).map { it.trim() }
            if (parts.size < 2) return null
            val fen = parts[0].takeIf { it.contains('/') && it.isNotBlank() }
            val move = parts.getOrNull(1).orEmpty()
            if (move.isBlank()) return null
            val weight = parts.getOrNull(2)?.toIntOrNull() ?: 100
            val score = parts.getOrNull(3)?.toIntOrNull() ?: 0
            val comment = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            return TextEntry(fen, move, weight.coerceAtLeast(1), score, comment)
        }

        // 格式 2: "book h2e2 weight 100"  —— 中国象棋 UCI: 列 a-i 行 0-9
        val bookRegex = Regex("""book\s+([a-i][0-9][a-i][0-9])(?:\s+weight\s+(\d+))?(?:\s+score\s+(-?\d+))?""")
        bookRegex.find(line)?.let { m ->
            val move = m.groupValues[1]
            val weight = m.groupValues[2]?.toIntOrNull() ?: 100
            val score = m.groupValues[3]?.toIntOrNull() ?: 0
            return TextEntry(null, move, weight, score, null)
        }

        // 格式 3: 纯走法 "h2e2 b7c7 h7h3"
        val moves = line.split(Regex("\\s+")).filter { it.matches(Regex("""^[a-i][0-9][a-i][0-9][rnbqcp]?$""")) }
        if (moves.isNotEmpty()) {
            // 只取第一条走法，无 fen 上下文的话这个文件整体没用，跳过
            return null
        }

        return null
    }

    private fun normalizeFen(fen: String): String {
        val trimmed = fen.trim()
        // 只保留棋盘部分 + 走子方，去掉 castling/enpassant/时钟
        val parts = trimmed.split(' ')
        return if (parts.size >= 2) "${parts[0]} ${parts[1]}" else trimmed
    }

    override fun findMovesForPosition(fen: String): List<Pair<String, Int>> {
        if (!isLoaded) return emptyList()
        val normalized = normalizeFen(fen)
        val entries = byFen[normalized] ?: byFen[fen.trim()]
        if (entries != null && entries.isNotEmpty()) {
            return entries.sortedByDescending { it.weight }.map { it.move to it.weight }
        }
        // 匹配 fen 的棋盘部分（忽略 sideToMove 差异）
        val boardOnly = normalized.substringBefore(' ')
        for ((key, list) in byFen) {
            if (key.startsWith(boardOnly)) {
                return list.sortedByDescending { it.weight }.map { it.move to it.weight }
            }
        }
        // 全局兜底
        if (globalEntries.isNotEmpty()) {
            return globalEntries.sortedByDescending { it.weight }.map { it.move to it.weight }
        }
        return emptyList()
    }

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
        private const val TAG = "UcciTextBook"
    }
}
