package com.qindachess.book

import android.util.Log
import com.qindachess.board.ChessBoard
import com.qindachess.board.Move
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BookEntry(
    val key: Long,
    val move: Int,
    val weight: Int,
    val learn: Int
) {
    fun toUciMove(): String {
        val toFile = move and 7
        val toRank = (move shr 3) and 7
        val fromFile = (move shr 6) and 7
        val fromRank = (move shr 9) and 7
        val promotion = (move shr 12) and 7

        val fromFileChar = 'a' + fromFile
        val fromRankChar = '1' + fromRank
        val toFileChar = 'a' + toFile
        val toRankChar = '1' + toRank

        val promoChar = when (promotion) {
            1 -> "r"
            2 -> "n"
            3 -> "b"
            4 -> "q"
            else -> ""
        }

        return "$fromFileChar$fromRankChar$toFileChar$toRankChar$promoChar"
    }
}

class PolyGlotBook : IOpeningBook {
    private val entries = mutableListOf<BookEntry>()
    private var isLoaded = false

    override fun isLoaded(): Boolean = isLoaded
    override fun entryCount(): Int = entries.size

    fun loadFromFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "Book file not found: $path")
                return false
            }
            if (file.length() < 16) {
                Log.e(TAG, "Book file too small: ${file.length()}")
                return false
            }
            loadFromBytes(FileInputStream(file).use { it.readBytes() })
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load book file", e)
            false
        }
    }

    fun loadFromBytes(data: ByteArray): Boolean {
        return try {
            entries.clear()
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val entryCount = data.size / 16

            for (i in 0 until entryCount) {
                val key = buffer.long
                val move = buffer.short.toInt() and 0xFFFF
                val weight = buffer.short.toInt() and 0xFFFF
                val learn = buffer.int
                entries.add(BookEntry(key, move, weight, learn))
            }

            entries.sortBy { it.key }
            isLoaded = true
            Log.i(TAG, "Loaded ${entries.size} book entries")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse book data", e)
            false
        }
    }

    override fun findMovesForPosition(fen: String): List<Pair<String, Int>> {
        if (!isLoaded) return emptyList()

        val board = ChessBoard().apply { parseFen(fen) }
        val key = computePolyGlotKey(board)

        val result = mutableListOf<Pair<String, Int>>()
        var lo = 0
        var hi = entries.size - 1

        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entries[mid]
            when {
                e.key == key -> {
                    var start = mid
                    while (start > 0 && entries[start - 1].key == key) start--
                    var end = mid
                    while (end < entries.size - 1 && entries[end + 1].key == key) end++
                    for (i in start..end) {
                        result.add(entries[i].toUciMove() to entries[i].weight)
                    }
                    break
                }
                e.key < key -> lo = mid + 1
                else -> hi = mid - 1
            }
        }

        result.sortByDescending { it.second }
        return result
    }

    override fun findBestMove(fen: String): String? {
        val moves = findMovesForPosition(fen)
        if (moves.isEmpty()) return null
        val totalWeight = moves.sumOf { it.second }.coerceAtLeast(1)
        var rand = (Math.random() * totalWeight).toInt()
        for ((move, weight) in moves) {
            rand -= weight
            if (rand <= 0) return move
        }
        return moves.first().first
    }

    companion object {
        internal const val TAG = "PolyGlotBook"

        private val ZobristSideToMove = longArrayOf(-7117617611634414271L)

        private val ZobristCastle = longArrayOf(
            0x2A18345068BB1289L,
            0x5C47F929DF12660CL,
            0x0AFABD29F210C253L,
            0x66F6B59B459A63DFL
        )

        private val ZobristEnPassant = longArrayOf(
            0x0000000000000000L,
            0x245660FB63918802L,
            0x4B4192C59B8E3657L,
            0x70DE8F21AE2A9C80L,
            0x18734230797D19F5L,
            -7532566595147251613L,
            -4105155946449855902L,
            -8622830838764488590L
        )

        private val ZobristPiece = Array(12) { longArrayOf() }

        init {
            val seeds = longArrayOf(
                -7117617611634414271L, 0x2A18345068BB1289L,
                0x14B2682E96459FABL, 0x5C47F929DF12660CL,
                0x4B4192C59B8E3657L, 0x0AFABD29F210C253L,
                0x70DE8F21AE2A9C80L, 0x18734230797D19F5L,
                -7532566595147251613L, -4105155946449855902L,
                0x66F6B59B459A63DFL, -8622830838764488590L,
                0x4ADF487625473FF4L, -479584026620530191L,
                0x3656498761F09455L, 0x22C777A714633933L,
                0x140B07631C425CC0L, -7626057384842527247L,
                0x761878F2E4490148L, 0x09669072D0B66979L,
                0x3246F0F1E0162037L, 0x66111099E790087BL,
                0x785B76D57156264DL, -9206963129696609062L,
                -7914950097422232783L, 0x694219E374C1C69AL,
                0x6317A98AF5B19737L, 0x5A5F52F6B40981FBL,
                0x7AB8B3E81A157BBAL, 0x6F33755DD03E1239L,
                0x52364F3478D8B32CL, 0x1CE1603D6AFBC21DL,
                0x71D3A59341754D89L, 0x30A2E29F8E22D738L,
                0x15825329D0A7BB96L, 0x41F91B386B000977L,
                -8707793111256659695L, 0x36E948E9F058BC41L,
                0x49520F79F0D39B1FL, 0x4897513A2F2EC971L,
                0x1D7346B02156A6AEL, 0x7F457B635EE55083L,
                0x489C02858E6F80BAL, -8730943507067641322L,
                0x24F3656279065057L, -3255307777713450286L,
                0x7AB8B3E81A157BBAL, 0x6F33755DD03E1239L,
                0x52364F3478D8B32CL, 0x1CE1603D6AFBC21DL,
                0x71D3A59341754D89L, 0x30A2E29F8E22D738L,
                0x15825329D0A7BB96L, 0x41F91B386B000977L,
                -8707793111256659695L, 0x36E948E9F058BC41L,
                0x49520F79F0D39B1FL, 0x4897513A2F2EC971L
            )
            var idx = 0
            for (pieceType in 0 until 12) {
                ZobristPiece[pieceType] = LongArray(64)
                for (sq in 0 until 64) {
                    ZobristPiece[pieceType][sq] = seeds[idx++ % seeds.size]
                }
            }
        }

        private fun computePolyGlotKey(board: ChessBoard): Long {
            var key = 0L
            val pieceMapping = mapOf(
                'P' to 0, 'N' to 1, 'B' to 2, 'R' to 3, 'Q' to 4, 'K' to 5,
                'p' to 6, 'n' to 7, 'b' to 8, 'r' to 9, 'q' to 10, 'k' to 11
            )
            for (rank in 0 until 10) {
                for (file in 0 until 9) {
                    val piece = board.getPiece(rank, file) ?: continue
                    val ch = piece.type.toChar(piece.color)
                    val pieceIdx = pieceMapping[ch] ?: continue
                    val sq = file * 8 + rank
                    key = key xor ZobristPiece[pieceIdx][sq]
                }
            }
            if (board.sideToMove == com.qindachess.board.PieceColor.BLACK) {
                key = key xor ZobristSideToMove[0]
            }
            return key
        }
    }
}
