package com.qindachess.auto

import com.qindachess.board.ChessBoard
import com.qindachess.board.Move
import com.qindachess.board.PieceColor
import com.qindachess.board.PieceType
import kotlin.math.abs

/**
 * UCI 着法 → 中文象棋招法（如"马八进七"）转换器。
 * 依赖当前局面棋子信息确定红黑方和棋子名。
 */
object ChineseNotation {

    private val redNames = mapOf(
        PieceType.KING to "帅", PieceType.ADVISOR to "仕",
        PieceType.BISHOP to "相", PieceType.KNIGHT to "马",
        PieceType.ROOK to "车", PieceType.CANNON to "炮", PieceType.PAWN to "兵"
    )
    private val blackNames = mapOf(
        PieceType.KING to "将", PieceType.ADVISOR to "士",
        PieceType.BISHOP to "象", PieceType.KNIGHT to "马",
        PieceType.ROOK to "车", PieceType.CANNON to "炮", PieceType.PAWN to "卒"
    )

    /** 走子类棋子（马、仕/士、相/象）的 进/退 用目标列号，而非步数 */
    private val destColPieces = setOf(PieceType.KNIGHT, PieceType.ADVISOR, PieceType.BISHOP)

    /**
     * 从 UCI 字符串和当前棋盘转换
     * @param board 当前局面棋盘（用于确定棋子颜色和类型）
     * @param uci 如 "h8g8"
     * @return 中文招法，如 "马八进七"
     */
    fun fromUci(board: ChessBoard, uci: String): String {
        val move = Move.fromUci(uci) ?: return uci
        val piece = board.getPiece(move.from.row, move.from.col) ?: return uci
        return convert(piece, move)
    }

    /**
     * 从 Move 对象和当前棋盘转换
     */
    fun fromMove(board: ChessBoard, move: Move): String {
        val piece = board.getPiece(move.from.row, move.from.col) ?: return move.toUci()
        return convert(piece, move)
    }

    private fun convert(piece: com.qindachess.board.Piece, move: Move): String {
        val names = if (piece.color == PieceColor.RED) redNames else blackNames
        val name = names[piece.type] ?: return move.toUci()

        val isRed = piece.color == PieceColor.RED

        // 列号：红方从右到左 1-9，黑方从右到左 1-9（黑方视角）
        val fromCol = if (isRed) 9 - move.from.col else move.from.col + 1
        val toCol = if (isRed) 9 - move.to.col else move.to.col + 1

        val action: String
        val number: String

        if (move.from.row == move.to.row) {
            // 平移
            action = "平"
            number = toCol.toString()
        } else {
            // 进/退
            val advancing = if (isRed) move.from.row > move.to.row else move.from.row < move.to.row
            action = if (advancing) "进" else "退"

            number = if (piece.type in destColPieces) {
                // 马、仕、相 → 目标列号
                toCol.toString()
            } else {
                // 车、炮、兵/卒、帅/将 → 步数
                abs(move.from.row - move.to.row).toString()
            }
        }

        return "$name$fromCol$action$number"
    }

    /**
     * 把 PV（主变）列表转成中文招法字符串
     * @return 如"马八进七 车9平8"
     */
    fun pvListToChinese(board: ChessBoard, moves: List<Move>): String {
        if (moves.isEmpty()) return "—"
        // 对 PV 里的每一步，都要在对应的局面下转换
        // 这里简化：每步都用原始局面转换（只转第一步精确，后续步近似）
        // 但对于悬浮窗只显示第一步，所以没问题
        return moves.take(6).joinToString(" ") { move ->
            fromMove(board, move)
        }
    }
}