package com.qindachess.board

import android.util.Log

/**
 * UCI 招法 → 中文象棋记谱法转换器。
 *
 * 中国象棋中文记谱规则：
 *   - 棋子名：帅/将 仕/士 相/象 马 车 炮 兵/卒
 *   - 红方用汉字数字： 一二三四五六七八九
 *   - 黑方用阿拉伯数字： 123456789
 *   - 方向：进/退/平
 *   - 移动：进N 表示前进 N 个交叉点（横/竖时），或跳到第N列（斜走时，如马走日）
 *   - 平：左右同列移动，写"平 目的地数字"
 *
 * 起始位置的"前/后"约定：
 *   - 同列多子：前指离对方底线最近者（最上面）
 *   - 同列两子：写"前"/"后"区分
 *
 * UCI 坐标约定（红方在下）：
 *   - row 0 = 黑方底线（rank "9"），row 9 = 红方底线（rank "0"）
 *   - col 0 = a, col 8 = i
 */
object ChineseNotation {

    private const val TAG = "ChineseNotation"

    private val RED_NAME = mapOf(
        PieceType.KING to "帅", PieceType.ADVISOR to "仕",
        PieceType.BISHOP to "相", PieceType.KNIGHT to "马",
        PieceType.ROOK to "车", PieceType.CANNON to "炮",
        PieceType.PAWN to "兵"
    )
    private val BLACK_NAME = mapOf(
        PieceType.KING to "将", PieceType.ADVISOR to "士",
        PieceType.BISHOP to "象", PieceType.KNIGHT to "马",
        PieceType.ROOK to "车", PieceType.CANNON to "炮",
        PieceType.PAWN to "卒"
    )
    private val RED_DIGIT = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val BLACK_DIGIT = listOf("", "1", "2", "3", "4", "5", "6", "7", "8", "9")

    /**
     * 把一个 UCI 走法转成中文记谱。
     * @param board 走子前的局面（用于确定"前/后"以及多子判定）
     * @param uci   "h2e2" 格式
     */
    fun toChinese(board: ChessBoard, uci: String): String {
        if (uci.length < 4) return uci
        val from = Position.fromFenSquare(uci.substring(0, 2)) ?: return uci
        val to = Position.fromFenSquare(uci.substring(2, 4)) ?: return uci
        return toChinese(board, Move(from, to))
    }

    fun toChinese(board: ChessBoard, move: Move): String {
        val piece = board.getPiece(move.from.row, move.from.col) ?: return move.toUci()
        val name = if (piece.color == PieceColor.RED) RED_NAME[piece.type]
            else BLACK_NAME[piece.type] ?: return move.toUci()

        // 决定前/后标记，或直接用列号
        val prefix = positionPrefix(board, move.from, piece)

        // 方向：红方向"进"是 row 增大（向黑方底线），黑方相反
        val color = piece.color
        val dr = move.to.row - move.from.row
        val dc = move.to.col - move.from.col
        val isForward = if (color == PieceColor.RED) dr > 0 else dr < 0
        val isBackward = if (color == PieceColor.RED) dr < 0 else dr > 0
        val isSameRow = dr == 0
        val isSameCol = dc == 0

        val direction: String
        val target: String
        when {
            isSameRow -> {
                // 平：写"平 目的地列号"
                direction = "平"
                target = if (color == PieceColor.RED) RED_DIGIT[move.to.col + 1]
                    else BLACK_DIGIT[move.to.col + 1]
            }
            isSameCol -> {
                // 前进/后退 N 步
                val steps = Math.abs(dr)
                if (isForward) {
                    direction = "进"
                    target = if (color == PieceColor.RED) RED_DIGIT[steps]
                        else BLACK_DIGIT[steps]
                } else {
                    direction = "退"
                    target = if (color == PieceColor.RED) RED_DIGIT[steps]
                        else BLACK_DIGIT[steps]
                }
            }
            else -> {
                // 斜走：马/相/士，目标写"到达列号"
                direction = if (isForward) "进" else "退"
                target = if (color == PieceColor.RED) RED_DIGIT[move.to.col + 1]
                    else BLACK_DIGIT[move.to.col + 1]
            }
        }

        return "$prefix$name$direction$target"
    }

    /**
     * 同列多子 → 写"前/后"；否则写起始列号。
     * 注意：红方"前"是离黑方底线近的（row 小）；黑方"前"是离红方底线近的（row 大）。
     */
    private fun positionPrefix(
        board: ChessBoard,
        from: Position,
        piece: Piece
    ): String {
        var sameCount = 0
        var aheadIndex = -1
        var selfIndex = -1
        for (r in 0 until 10) {
            if (r == from.row) continue
            val p = board.getPiece(r, from.col) ?: continue
            if (p.type == piece.type && p.color == piece.color) {
                sameCount++
                if (piece.color == PieceColor.RED) {
                    // 离黑方底线近 = row 小
                    if (r < from.row) {
                        aheadIndex = 0 // "前"
                    } else {
                        aheadIndex = 1 // "后"
                    }
                } else {
                    // 黑方"前"= 离红方底线近 = row 大
                    if (r > from.row) {
                        aheadIndex = 0
                    } else {
                        aheadIndex = 1
                    }
                }
                if (selfIndex < 0) {
                    // 自身相对位置
                    selfIndex = if (piece.color == PieceColor.RED) {
                        if (r < from.row) 0 else 1
                    } else {
                        if (r > from.row) 0 else 1
                    }
                }
            }
        }

        return if (sameCount >= 1) {
            // 同列还有别的同类子，用前/后区分
            // 自己的位置（离己方底线近的为"后"，远的为"前"）
            val selfIsForward = if (piece.color == PieceColor.RED) from.row < 5
                else from.row >= 5
            if (selfIsForward) "前" else "后"
        } else {
            if (piece.color == PieceColor.RED) RED_DIGIT[from.col + 1]
                else BLACK_DIGIT[from.col + 1]
        }
    }

    /**
     * 把 UCI 列表转成中文招法列表
     */
    fun pvToChinese(startBoard: ChessBoard, uciList: List<String>): List<String> {
        val b = startBoard.copy()
        val result = mutableListOf<String>()
        for (u in uciList) {
            val m = Move.fromUci(u) ?: break
            val cn = toChinese(b, m)
            result.add(cn)
            try {
                b.applyMove(m)
            } catch (e: Exception) {
                Log.w(TAG, "applyMove failed: $u", e)
                break
            }
        }
        return result
    }
}
