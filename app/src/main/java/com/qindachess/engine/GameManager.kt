package com.qindachess.engine

import android.util.Log
import com.qindachess.book.IOpeningBook
import com.qindachess.board.ChessBoard
import com.qindachess.board.Move
import com.qindachess.board.PieceColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GameState(
    val board: ChessBoard = ChessBoard().apply { parseFen(ChessBoard.INITIAL_FEN) },
    val moveHistory: List<Move> = emptyList(),
    val uciHistory: List<String> = emptyList(),
    val whiteToMove: Boolean = true,
    val lastMove: Move? = null,
    val gameOver: Boolean = false,
    val winner: PieceColor? = null
) {
    val fen: String get() = board.toFen()
    val sideToMove: PieceColor get() = if (whiteToMove) PieceColor.RED else PieceColor.BLACK
}

class GameManager(
    private val engineManager: UciEngineManager,
    private val bookProvider: com.qindachess.book.BookManager
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _statusMessage = MutableStateFlow("就绪")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    var bookEnabled: Boolean = true
    var autoPlay: Boolean = false
    var playAsRed: Boolean = true

    fun newGame() {
        _gameState.value = GameState()
        engineManager.resetHistory()
        engineManager.setPosition(ChessBoard.INITIAL_FEN)
        _statusMessage.value = "新对局开始"
        if (autoPlay) {
            scope.launch { makeAutoMove() }
        }
    }

    /**
     * 玩家尝试走子。返回 true 表示走子成功；false 表示被拒绝（不合法/已结束）。
     * 走子合法性由 [isValidMove] 校验：会过滤掉吃自己、送将等非法情况。
     *
     * 注意：非 autoPlay 模式下，玩家可以走任意当前方（手动同时下两边）。
     *       autoPlay 模式下，playAsRed 决定电脑走哪一方，玩家走另一方。
     */
    fun applyPlayerMove(move: Move): Boolean {
        val currentState = _gameState.value
        if (currentState.gameOver) return false

        // 1) 起点必须是己方回合的棋子
        val piece = currentState.board.getPiece(move.from.row, move.from.col) ?: return false
        if (piece.color != currentState.sideToMove) return false

        // 2) 走法必须合法（过滤掉吃自己/送将等）
        if (!isValidMove(currentState.board, move)) return false

        executeMove(move)

        // autoPlay 模式下：让电脑走另一方
        if (autoPlay && !_gameState.value.gameOver) {
            scope.launch { makeAutoMove() }
        }
        return true
    }

    private fun executeMove(move: Move) {
        val currentState = _gameState.value
        val newBoard = currentState.board.copy()
        val captured = newBoard.applyMove(move)
        val uci = move.toUci()
        val newMoveHistory = currentState.moveHistory + move
        val newUciHistory = currentState.uciHistory + uci

        engineManager.addMoveHistory(uci)
        engineManager.setPosition(newBoard, newMoveHistory)

        val newState = currentState.copy(
            board = newBoard,
            moveHistory = newMoveHistory,
            uciHistory = newUciHistory,
            whiteToMove = !currentState.whiteToMove,
            lastMove = move
        )
        _gameState.value = newState

        checkGameOver(newBoard, newState)
    }

    private fun checkGameOver(board: ChessBoard, state: GameState) {
        val redKing = board.findKing(PieceColor.RED)
        val blackKing = board.findKing(PieceColor.BLACK)
        when {
            redKing == null -> {
                _gameState.value = state.copy(gameOver = true, winner = PieceColor.BLACK)
                _statusMessage.value = "黑方获胜！"
            }
            blackKing == null -> {
                _gameState.value = state.copy(gameOver = true, winner = PieceColor.RED)
                _statusMessage.value = "红方获胜！"
            }
        }
    }

    suspend fun makeAutoMove() {
        val state = _gameState.value
        if (state.gameOver) return

        val engineColor = if (playAsRed) PieceColor.BLACK else PieceColor.RED
        if (state.sideToMove != engineColor) return

        _statusMessage.value = "引擎思考中..."

        val fen = state.fen
        val uciHistory = state.uciHistory

        engineManager.setPosition(fen, uciHistory)

        // 每次都从 BookManager 取最新的 activeBook（处理异步注册问题）
        val currentBook = bookProvider.activeBook ?: getFallbackBook()
        if (bookEnabled && currentBook.isLoaded()) {
            val bookMove = currentBook.findBestMove(fen)
            if (bookMove != null) {
                val move = Move.fromUci(bookMove)
                if (move != null && isValidMove(state.board, move)) {
                    executeMove(move)
                    _statusMessage.value = "开局库走法: $bookMove"
                    return
                }
            }
        }

        try {
            val options = SearchOptions(depth = 12, timeMs = 3000, multiPv = 3)
            val result = engineManager.search(options)
            val move = Move.fromUci(result.bestMove)
            if (move != null) {
                executeMove(move)
                val score = result.moves.firstOrNull()?.scoreCp ?: 0
                _statusMessage.value = "引擎: ${result.bestMove} (分: $score)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            _statusMessage.value = "引擎搜索失败: ${e.message}"
        }
    }

    /**
     * 兜底开局库：永远有内容（BuiltInBook），不会因为 BookManager 没注册而返回空。
     */
    private fun getFallbackBook(): com.qindachess.book.IOpeningBook {
        return object : com.qindachess.book.IOpeningBook {
            private val book = com.qindachess.book.UcciTextBook().apply {
                appendEntries(com.qindachess.book.BuiltInBook.getEntries())
            }
            override fun isLoaded(): Boolean = book.isLoaded()
            override fun entryCount(): Int = book.entryCount()
            override fun findMovesForPosition(fen: String) = book.findMovesForPosition(fen)
            override fun findBestMove(fen: String) = book.findBestMove(fen)
        }
    }

    private fun isValidMove(board: ChessBoard, move: Move): Boolean {
        val piece = board.getPiece(move.from.row, move.from.col)
        if (piece == null) return false
        val captured = board.getPiece(move.to.row, move.to.col)
        if (captured != null && captured.color == piece.color) return false

        val newBoard = board.copy()
        newBoard.applyMove(move)

        val ownKing = newBoard.findKing(piece.color)
        val enemyKing = newBoard.findKing(if (piece.color == PieceColor.RED) PieceColor.BLACK else PieceColor.RED)

        if (ownKing == null || enemyKing == null) return true

        val enemyPieces = newBoard.getAllPiecePositions(enemyKing.color)
        return !enemyPieces.any { enemyPiece ->
            canAttack(newBoard, enemyPiece.position, ownKing.position)
        }
    }

    private fun canAttack(board: ChessBoard, from: com.qindachess.board.Position, to: com.qindachess.board.Position): Boolean {
        val piece = board.getPiece(from.row, from.col) ?: return false
        val dr = to.row - from.row
        val dc = to.col - from.col

        return when (piece.type) {
            com.qindachess.board.PieceType.ROOK ->
                (dr == 0 || dc == 0) && isPathClear(board, from, to)
            com.qindachess.board.PieceType.KNIGHT -> {
                val moveTypes = arrayOf(
                    -2 to -1, -2 to 1, -1 to -2, -1 to 2,
                    1 to -2, 1 to 2, 2 to -1, 2 to 1
                )
                moveTypes.any { (ddr, ddc) ->
                    dr == ddr && dc == ddc && board.getPiece(from.row + ddr / 2, from.col + ddc / 2) == null
                }
            }
            com.qindachess.board.PieceType.CANNON -> {
                if (dr == 0 || dc == 0) {
                    val pieceOnTarget = board.getPiece(to.row, to.col)
                    if (pieceOnTarget != null) {
                        countPiecesOnPath(board, from, to) == 1
                    } else {
                        isPathClear(board, from, to)
                    }
                } else false
            }
            com.qindachess.board.PieceType.KING -> {
                dr in -1..1 && dc in -1..1 && (dr != 0 || dc != 0)
            }
            com.qindachess.board.PieceType.ADVISOR -> {
                val inPalace = if (piece.color == PieceColor.RED) {
                    to.row in 7..9 && to.col in 3..5
                } else {
                    to.row in 0..2 && to.col in 3..5
                }
                inPalace && dr in -1..1 && dc in -1..1 && (dr != 0 || dc != 0) && kotlin.math.abs(dr) + kotlin.math.abs(dc) == 2
            }
            com.qindachess.board.PieceType.BISHOP -> {
                if (kotlin.math.abs(dr) != 2 || kotlin.math.abs(dc) != 2) return false
                val eyeRow = from.row + dr / 2
                val eyeCol = from.col + dc / 2
                if (board.getPiece(eyeRow, eyeCol) != null) return false
                if (piece.color == PieceColor.RED) to.row >= 5 else to.row <= 4
                true
            }
            com.qindachess.board.PieceType.PAWN -> {
                if (piece.color == PieceColor.RED) {
                    if (dr == -1 && dc == 0) true
                    else if (from.row <= 4 && kotlin.math.abs(dc) == 1 && dr == 0) true
                    else false
                } else {
                    if (dr == 1 && dc == 0) true
                    else if (from.row >= 5 && kotlin.math.abs(dc) == 1 && dr == 0) true
                    else false
                }
            }
        }
    }

    private fun isPathClear(board: ChessBoard, from: com.qindachess.board.Position, to: com.qindachess.board.Position): Boolean {
        return countPiecesOnPath(board, from, to) == 0
    }

    private fun countPiecesOnPath(board: ChessBoard, from: com.qindachess.board.Position, to: com.qindachess.board.Position): Int {
        val dr = to.row - from.row
        val dc = to.col - from.col
        val count = if (dr == 0) kotlin.math.abs(dc) - 1 else kotlin.math.abs(dr) - 1
        if (count <= 0) return 0
        var pieces = 0
        val stepR = dr.coerceIn(-1, 1)
        val stepC = dc.coerceIn(-1, 1)
        var r = from.row + stepR
        var c = from.col + stepC
        repeat(count) {
            if (board.getPiece(r, c) != null) pieces++
            r += stepR; c += stepC
        }
        return pieces
    }

    fun undoMove() {
        val currentState = _gameState.value
        if (currentState.moveHistory.isEmpty()) return

        val lastMove = currentState.moveHistory.last()
        val board = ChessBoard().apply { parseFen(ChessBoard.INITIAL_FEN) }
        val historyWithoutLast = currentState.moveHistory.dropLast(1)

        for (m in historyWithoutLast) {
            board.applyMove(m)
        }

        _gameState.value = currentState.copy(
            board = board,
            moveHistory = historyWithoutLast,
            uciHistory = currentState.uciHistory.dropLast(1),
            whiteToMove = !currentState.whiteToMove,
            lastMove = historyWithoutLast.lastOrNull()
        )

        engineManager.setPosition(board, historyWithoutLast)
    }

    companion object {
        private const val TAG = "GameManager"
    }
}
