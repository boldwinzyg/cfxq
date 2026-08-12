package com.qindachess.utils

import com.qindachess.board.ChessBoard
import com.qindachess.board.PieceType
import com.qindachess.board.Position

object FenUtils {

    private val INITIAL_PIECES = mapOf(
        Position(9, 0) to PieceType.ROOK, Position(9, 1) to PieceType.KNIGHT,
        Position(9, 2) to PieceType.BISHOP, Position(9, 3) to PieceType.ADVISOR,
        Position(9, 4) to PieceType.KING, Position(9, 5) to PieceType.ADVISOR,
        Position(9, 6) to PieceType.BISHOP, Position(9, 7) to PieceType.KNIGHT,
        Position(9, 8) to PieceType.ROOK,
        Position(7, 1) to PieceType.CANNON, Position(7, 7) to PieceType.CANNON,
        Position(6, 0) to PieceType.PAWN, Position(6, 2) to PieceType.PAWN,
        Position(6, 4) to PieceType.PAWN, Position(6, 6) to PieceType.PAWN,
        Position(6, 8) to PieceType.PAWN,
        Position(0, 0) to PieceType.ROOK, Position(0, 1) to PieceType.KNIGHT,
        Position(0, 2) to PieceType.BISHOP, Position(0, 3) to PieceType.ADVISOR,
        Position(0, 4) to PieceType.KING, Position(0, 5) to PieceType.ADVISOR,
        Position(0, 6) to PieceType.BISHOP, Position(0, 7) to PieceType.KNIGHT,
        Position(0, 8) to PieceType.ROOK,
        Position(2, 1) to PieceType.CANNON, Position(2, 7) to PieceType.CANNON,
        Position(3, 0) to PieceType.PAWN, Position(3, 2) to PieceType.PAWN,
        Position(3, 4) to PieceType.PAWN, Position(3, 6) to PieceType.PAWN,
        Position(3, 8) to PieceType.PAWN
    )

    fun validateFen(fen: String): Boolean {
        val parts = fen.split(" ")
        if (parts.size < 2) return false

        val ranks = parts[0].split("/")
        if (ranks.size != 10) return false

        for (rank in ranks) {
            var count = 0
            for (ch in rank) {
                count += if (ch.isDigit()) ch.digitToInt() else 1
            }
            if (count != 9) return false
        }

        val side = parts[1]
        if (side != "w" && side != "b") return false

        return true
    }

    fun generateFen(board: ChessBoard): String = board.toFen()

    fun parseMoveHistory(moves: List<String>): List<com.qindachess.board.Move> {
        return moves.mapNotNull { com.qindachess.board.Move.fromUci(it) }
    }

    fun buildPositionCommand(fen: String, moves: List<String>): String {
        val sb = StringBuilder("position fen ")
        sb.append(fen)
        if (moves.isNotEmpty()) {
            sb.append(" moves ")
            sb.append(moves.joinToString(" "))
        }
        return sb.toString()
    }

    fun moveToChinese(move: com.qindachess.board.Move, fen: String): String {
        val board = ChessBoard().apply { parseFen(fen) }
        val piece = board.getPiece(move.from.row, move.from.col) ?: return move.toUci()
        val target = board.getPiece(move.to.row, move.to.col)

        val fromFileChar = '九' - move.from.col
        val fromRankChar = 10 - move.from.row
        val toFileChar = '九' - move.to.col
        val toRankChar = 10 - move.to.row

        val pieceName = when (piece.type) {
            PieceType.KING -> "帥"
            PieceType.ADVISOR -> "仕"
            PieceType.BISHOP -> "相"
            PieceType.KNIGHT -> "馬"
            PieceType.ROOK -> "車"
            PieceType.CANNON -> "砲"
            PieceType.PAWN -> "兵"
        }

        val isVertical = move.from.col == move.to.col
        val isForward = move.to.row < move.from.row
        val isBackward = move.to.row > move.from.row
        val isSamePosition = move.from == move.to
        val distance = kotlin.math.abs(move.to.row - move.from.row).coerceAtLeast(
            kotlin.math.abs(move.to.col - move.from.col)
        )

        val direction = when {
            isVertical && isForward -> "進"
            isVertical && isBackward -> "退"
            else -> "平"
        }

        val targetNumber = if (isVertical) {
            if (piece.color == com.qindachess.board.PieceColor.RED) {
                (10 - move.to.row).toString()
            } else {
                move.to.row.toString()
            }
        } else {
            if (piece.color == com.qindachess.board.PieceColor.RED) {
                ('〇'.code + 9 - move.to.col).toChar().toString()
            } else {
                ('〇'.code + move.to.col).toChar().toString()
            }
        }

        return buildString {
            append(pieceName)
            if (isVertical) {
                append(fromFileChar)
            } else {
                append(fromRankChar)
            }
            append(direction)
            append(targetNumber)
        }
    }
}
