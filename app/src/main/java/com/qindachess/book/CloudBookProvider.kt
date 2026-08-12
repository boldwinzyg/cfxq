package com.qindachess.book

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CloudBookMove(
    val uciMove: String,
    val san: String,
    val frequency: Int,
    val score: Double,
    val wins: Int,
    val games: Int
)

data class CloudBookResponse(
    val moves: List<CloudBookMove>,
    val totalGames: Int,
    val whiteWinRate: Double,
    val source: String
)

interface CloudBookProvider {
    suspend fun queryMoves(fen: String, moveCountInGame: Int): CloudBookResponse?
    suspend fun fetchBookList(): List<BookInfo>
    suspend fun downloadBook(bookInfo: BookInfo, targetPath: String): Boolean
}

/**
 * 云库：调用 chessdb.cn HTTP RESTful API 拉取走法列表。
 * 文档：http://www.chessdb.cn/chessdb.php?action=[ACTION]&[OPTION]=[VALUE]
 *  - action=queryall &board=<FEN>  列出当前局面的所有候选招法
 *  - action=query    &board=<FEN>  列出最优招法
 *  - action=store    &board=<FEN>&move=<UCI> 提交学习数据
 */
class CloudBookManager : CloudBookProvider {

    companion object {
        private const val TAG = "CloudBookManager"
        private const val CHESSDB_API = "http://www.chessdb.cn/chessdb.php"
    }

    override suspend fun queryMoves(fen: String, moveCountInGame: Int): CloudBookResponse? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$CHESSDB_API?action=queryall&board=${urlEncode(fen)}"
                val response = httpGet(url) ?: return@withContext null
                val moves = parseQueryAll(response)
                if (moves.isEmpty()) return@withContext null
                CloudBookResponse(
                    moves = moves,
                    totalGames = moves.sumOf { it.games },
                    whiteWinRate = 0.5,
                    source = "chessdb.cn 象棋云库"
                )
            } catch (e: Exception) {
                Log.e(TAG, "chessdb query failed", e)
                null
            }
        }

    /**
     * 解析 chessdb.cn queryall 返回的文本。
     * 格式：
     *   move h2e2;score 12;win 100;los 12;draw 5;
     *   move b0c2;score 8;win 80;los 15;draw 4;
     *   |无效局面或终局
     */
    private fun parseQueryAll(text: String): List<CloudBookMove> {
        val result = mutableListOf<CloudBookMove>()
        val lineRegex = Regex(
            """move\s+([a-i][0-9][a-i][0-9])(?:;score\s+(-?\d+))?(?:;win\s+(\d+))?(?:;los\s+(\d+))?(?:;draw\s+(\d+))?""",
            RegexOption.IGNORE_CASE
        )
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("|") || line.startsWith("#")) continue
            val m = lineRegex.find(line) ?: continue
            val uci = m.groupValues[1]
            val score = m.groupValues[2]?.toIntOrNull() ?: 0
            val win = m.groupValues[3]?.toIntOrNull() ?: 0
            val los = m.groupValues[4]?.toIntOrNull() ?: 0
            val draw = m.groupValues[5]?.toIntOrNull() ?: 0
            val games = win + los + draw
            result.add(
                CloudBookMove(
                    uciMove = uci,
                    san = uci,                       // 后续在 MainActivity 转中文记谱
                    frequency = games,
                    score = score.toDouble(),
                    wins = win,
                    games = games
                )
            )
        }
        return result
    }

    override suspend fun fetchBookList(): List<BookInfo> = withContext(Dispatchers.IO) {
        listOf(
            BookInfo(
                id = "cloud_huashan",
                name = "华山狂刀（云）",
                description = "经典开局库，覆盖主流布局变化",
                path = "",
                entryCount = 50000,
                isDefault = false,
                isBuiltIn = false,
                sourceUrl = "http://www.chessdb.cn/chessdb.php?action=download&bookid=huashan",
                version = "2.1",
                author = "chessdb.cn"
            ),
            BookInfo(
                id = "cloud_xingda",
                name = "兴达开局库（云）",
                description = "侧重中局战术的丰富库",
                path = "",
                entryCount = 80000,
                isDefault = false,
                isBuiltIn = false,
                sourceUrl = "http://www.chessdb.cn/chessdb.php?action=download&bookid=xingda",
                version = "1.8",
                author = "chessdb.cn"
            ),
            BookInfo(
                id = "cloud_wanxiang",
                name = "万象开局库（云）",
                description = "全面覆盖各布局的大库",
                path = "",
                entryCount = 200000,
                isDefault = false,
                isBuiltIn = false,
                sourceUrl = "http://www.chessdb.cn/chessdb.php?action=download&bookid=wanxiang",
                version = "3.0",
                author = "chessdb.cn"
            )
        )
    }

    override suspend fun downloadBook(bookInfo: BookInfo, targetPath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = bookInfo.sourceUrl ?: return@withContext false
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                if (conn.responseCode != 200) {
                    Log.e(TAG, "Failed to download book: HTTP ${conn.responseCode}")
                    return@withContext false
                }
                val input = conn.inputStream
                val output = java.io.FileOutputStream(targetPath)
                input.use { inputStream ->
                    output.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.i(TAG, "Downloaded book to $targetPath")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Book download failed", e)
                false
            }
        }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            if (conn.responseCode != 200) return null
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            reader.useLines { lines -> lines.forEach { sb.append(it).append('\n') } }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "HTTP GET failed: $urlStr", e)
            null
        }
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8")
}
