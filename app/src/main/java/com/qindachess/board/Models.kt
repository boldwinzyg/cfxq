package com.qindachess.board

data class Position(val row: Int, val col: Int) {
    companion object {
        fun fromFenSquare(sq: String): Position? {
            if (sq.length != 2) return null
            // 中国象棋 UCI 约定（ePic/UCCI 主流，棋盘内部 row 0 = 红方底线）：
            //   UCI 'a0' = 黑方底线 col 0 → Position(row 9, col 0)
            //   UCI 'a9' = 红方底线 col 0 → Position(row 0, col 0)
            val file = sq[0] - 'a'
            val rank = 9 - (sq[1] - '0')
            if (file !in 0..8 || rank !in 0..9) return null
            return Position(rank, file)
        }
    }

    fun toFenSquare(): String {
        val file = 'a' + col
        val rank = 9 - row
        return "$file$rank"
    }
}

enum class PieceColor { RED, BLACK }

enum class PieceType {
    KING, ADVISOR, BISHOP, KNIGHT, ROOK, CANNON, PAWN;

    fun toChar(color: PieceColor): Char {
        val chars = when (this) {
            KING -> "Kk"
            ADVISOR -> "Aa"
            BISHOP -> "Bb"
            KNIGHT -> "Nn"
            ROOK -> "Rr"
            CANNON -> "Cc"
            PAWN -> "Pp"
        }
        return if (color == PieceColor.RED) chars[0] else chars[1]
    }

    companion object {
        fun fromChar(c: Char): Pair<PieceType, PieceColor>? {
            return when (c) {
                'K' -> KING to PieceColor.RED
                'k' -> KING to PieceColor.BLACK
                'A' -> ADVISOR to PieceColor.RED
                'a' -> ADVISOR to PieceColor.BLACK
                'B' -> BISHOP to PieceColor.RED
                'b' -> BISHOP to PieceColor.BLACK
                'N' -> KNIGHT to PieceColor.RED
                'n' -> KNIGHT to PieceColor.BLACK
                'R' -> ROOK to PieceColor.RED
                'r' -> ROOK to PieceColor.BLACK
                'C' -> CANNON to PieceColor.RED
                'c' -> CANNON to PieceColor.BLACK
                'P' -> PAWN to PieceColor.RED
                'p' -> PAWN to PieceColor.BLACK
                else -> null
            }
        }
    }
}

data class Piece(
    val type: PieceType,
    val color: PieceColor,
    val position: Position
)

data class Move(
    val from: Position,
    val to: Position,
    val promotion: Char? = null
) {
    fun toUci(): String {
        val promo = promotion?.let { it.toString() } ?: ""
        return "${from.toFenSquare()}${to.toFenSquare()}$promo"
    }

    companion object {
        fun fromUci(uci: String): Move? {
            if (uci.length < 4) return null
            val from = Position.fromFenSquare(uci.substring(0, 2)) ?: return null
            val to = Position.fromFenSquare(uci.substring(2, 4)) ?: return null
            val promo = if (uci.length > 4) uci[4] else null
            return Move(from, to, promo)
        }
    }
}
