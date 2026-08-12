package com.qindachess.board

class ChessBoard {
    private val board: Array<Array<Piece?>> = Array(10) { arrayOfNulls(9) }
    var sideToMove: PieceColor = PieceColor.RED
        private set
    private var halfMoveClock: Int = 0
    private var fullMoveNumber: Int = 1

    companion object {
        const val INITIAL_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR r - - 0 1"
    }

    fun copy(): ChessBoard {
        val newBoard = ChessBoard()
        for (r in 0 until 10)
            for (c in 0 until 9)
                newBoard.board[r][c] = board[r][c]
        newBoard.sideToMove = sideToMove
        newBoard.halfMoveClock = halfMoveClock
        newBoard.fullMoveNumber = fullMoveNumber
        return newBoard
    }

    fun getPiece(row: Int, col: Int): Piece? =
        if (row in 0 until 10 && col in 0 until 9) board[row][col] else null

    fun setPiece(row: Int, col: Int, piece: Piece?) {
        if (row in 0 until 10 && col in 0 until 9) {
            board[row][col] = piece
        }
    }

    fun applyMove(move: Move): Piece? {
        val piece = board[move.from.row][move.from.col] ?: return null
        val captured = board[move.to.row][move.to.col]
        board[move.to.row][move.to.col] = piece.copy(position = move.to)
        board[move.from.row][move.from.col] = null
        sideToMove = if (sideToMove == PieceColor.RED) PieceColor.BLACK else PieceColor.RED
        if (sideToMove == PieceColor.RED) fullMoveNumber++
        return captured
    }

    fun undoMove(move: Move, captured: Piece?) {
        val piece = board[move.to.row][move.to.col] ?: return
        board[move.from.row][move.from.col] = piece.copy(position = move.from)
        board[move.to.row][move.to.col] = captured
        sideToMove = if (sideToMove == PieceColor.RED) PieceColor.BLACK else PieceColor.RED
        if (sideToMove == PieceColor.RED) fullMoveNumber--
    }

    fun parseFen(fen: String) {
        for (r in 0 until 10)
            for (c in 0 until 9)
                board[r][c] = null

        val parts = fen.trim().split(" ")
        if (parts.isEmpty()) return

        val ranks = parts[0].split("/")
        for ((row, rank) in ranks.withIndex()) {
            var col = 0
            for (ch in rank) {
                if (ch.isDigit()) {
                    col += ch.digitToInt()
                } else {
                    val pieceInfo = PieceType.fromChar(ch)
                    if (pieceInfo != null) {
                        board[row][col] = Piece(pieceInfo.first, pieceInfo.second, Position(row, col))
                    }
                    col++
                }
            }
        }

        if (parts.size > 1) {
            sideToMove = if (parts[1] == "w") PieceColor.RED else PieceColor.BLACK
        }
        if (parts.size > 4) {
            halfMoveClock = parts[4].toIntOrNull() ?: 0
        }
        if (parts.size > 5) {
            fullMoveNumber = parts[5].toIntOrNull() ?: 1
        }
    }

    fun toFen(): String {
        val fen = StringBuilder()
        for (row in 0 until 10) {
            var emptyCount = 0
            for (col in 0 until 9) {
                val piece = board[row][col]
                if (piece == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        fen.append(emptyCount)
                        emptyCount = 0
                    }
                    fen.append(piece.type.toChar(piece.color))
                }
            }
            if (emptyCount > 0) fen.append(emptyCount)
            if (row < 9) fen.append('/')
        }
        fen.append(' ').append(if (sideToMove == PieceColor.RED) 'w' else 'b')
        fen.append(" - - ").append(halfMoveClock).append(' ').append(fullMoveNumber)
        return fen.toString()
    }

    fun getAllPiecePositions(color: PieceColor): List<Piece> {
        val result = mutableListOf<Piece>()
        for (r in 0 until 10)
            for (c in 0 until 9) {
                val p = board[r][c]
                if (p != null && p.color == color) result.add(p)
            }
        return result
    }

    fun findKing(color: PieceColor): Piece? {
        for (r in 0 until 10)
            for (c in 0 until 9) {
                val p = board[r][c]
                if (p != null && p.type == PieceType.KING && p.color == color) return p
            }
        return null
    }
}
