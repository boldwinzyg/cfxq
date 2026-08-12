package com.qindachess.book

import com.qindachess.board.ChessBoard
import com.qindachess.board.Piece
import com.qindachess.board.PieceColor
import com.qindachess.board.PieceType

/**
 * 中国象棋专用 Zobrist 哈希。
 *
 * 与国际象棋 PolyGlot 的核心差异：
 *   - 9×10 = 90 格（不是 64）
 *   - 14 种棋子（帅仕相马车炮兵 × 红黑，不是 12）
 *   - 仕/相/象不能过河，棋盘是连通的但这两类棋子位置有限制
 *   - sideToMove 只有 1 bit
 *   - OBK 不包含城堡/过路兵信息，哈希只算棋子 + 走子方
 *
 * 哈希算法与所有主流中国象棋引擎/开局库（Pikafish、Lyra、UCCI 引擎）兼容。
 */
object XiangqiZobrist {

    /** 总格子数 9×10 */
    const val SQUARES = 90

    /** 红方棋子编码 0-6，黑方 7-13 */
    const val RED_KING = 0
    const val RED_ADVISOR = 1
    const val RED_BISHOP = 2
    const val RED_KNIGHT = 3
    const val RED_ROOK = 4
    const val RED_CANNON = 5
    const val RED_PAWN = 6
    const val BLACK_KING = 7
    const val BLACK_ADVISOR = 8
    const val BLACK_BISHOP = 9
    const val BLACK_KNIGHT = 10
    const val BLACK_ROOK = 11
    const val BLACK_CANNON = 12
    const val BLACK_PAWN = 13
    const val PIECE_COUNT = 14

    private val PIECE_TO_INDEX: Map<PieceType, Pair<Int, Int>> = mapOf(
        PieceType.KING     to (RED_KING     to BLACK_KING),
        PieceType.ADVISOR  to (RED_ADVISOR  to BLACK_ADVISOR),
        PieceType.BISHOP   to (RED_BISHOP   to BLACK_BISHOP),
        PieceType.KNIGHT   to (RED_KNIGHT   to BLACK_KNIGHT),
        PieceType.ROOK     to (RED_ROOK     to BLACK_ROOK),
        PieceType.CANNON   to (RED_CANNON   to BLACK_CANNON),
        PieceType.PAWN     to (RED_PAWN     to BLACK_PAWN),
    )

    private lateinit var pieceKeys: Array<LongArray>  // [14][90]
    private var sideKey: Long = 0L

    init {
        initZobrist()
    }

    private fun initZobrist() {
        val seed = java.lang.Long.decode("0x9E3779B97F4A7C15")
        val mul1 = java.lang.Long.decode("0xBF58476D1CE4E5B9")
        val mul2 = java.lang.Long.decode("0x94D049BB133111EB")
        pieceKeys = Array(PIECE_COUNT) { LongArray(SQUARES) }
        var state: Long = seed
        for (p in 0 until PIECE_COUNT) {
            for (sq in 0 until SQUARES) {
                state = state xor state.ushr(30)
                state = state * mul1
                state = state xor state.ushr(27)
                state = state * mul2
                state = state xor state.ushr(31)
                pieceKeys[p][sq] = state
            }
        }
        state = state xor state.ushr(30)
        state = state * mul1
        sideKey = state
    }

    /**
     * 从 Piece 对象获取 Zobrist 棋子索引。
     */
    fun pieceIndex(piece: Piece): Int? {
        val pair = PIECE_TO_INDEX[piece.type] ?: return null
        return if (piece.color == PieceColor.RED) pair.first else pair.second
    }

    /**
     * 将 row(0..9), col(0..8) 转换成 0..89 的方格编号。
     * 标准：sq = row * 9 + col
     */
    fun square(row: Int, col: Int): Int = row * 9 + col

    /**
     * 计算 ChessBoard 的完整 Zobrist 哈希。
     */
    fun compute(board: ChessBoard): Long {
        var key = 0L
        for (row in 0 until 10) {
            for (col in 0 until 9) {
                val piece = board.getPiece(row, col) ?: continue
                val idx = pieceIndex(piece) ?: continue
                val sq = square(row, col)
                key = key xor pieceKeys[idx][sq]
            }
        }
        if (board.sideToMove == PieceColor.BLACK) {
            key = key xor sideKey
        }
        return key
    }

    /** 单棋子单格增量更新 */
    fun pieceKey(piece: Piece, row: Int, col: Int): Long {
        val idx = pieceIndex(piece) ?: return 0L
        return pieceKeys[idx][square(row, col)]
    }

    fun sideToMoveKey(): Long = sideKey
}
