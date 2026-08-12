package com.qindachess.engine

import android.util.Log
import com.qindachess.board.ChessBoard
import com.qindachess.board.Move
import com.qindachess.board.PieceColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EngineConfig(
    var depth: Int = 12,
    var timeMs: Long = 3000,
    var threads: Int = 2,
    var hashSize: Int = 256,
    var useNnue: Boolean = true,
    var multiPv: Int = 3,
    var ponderEnabled: Boolean = false,
    var contempt: Int = 0,
    var minimax: Boolean = true,
    var UCI_LimitStrength: Boolean = false,
    var UCI_Elo: Int = 1800
) {
    fun toSearchOptions(): SearchOptions = SearchOptions(
        depth = depth,
        timeMs = timeMs,
        movestogo = 30,
        multiPv = multiPv,
        threads = threads,
        hashSize = hashSize,
        useNnue = useNnue
    )
}

data class BookConfig(
    var enabled: Boolean = true,
    var maxBookMoves: Int = 20,
    var randomize: Boolean = true,
    var minBookWeight: Int = 10,
    var cloudEnabled: Boolean = true,
    var cloudPrioritize: Boolean = false,
    var maxCloudMoves: Int = 30
) {
    val effectiveMaxMoves: Int
        get() = maxOf(maxBookMoves, if (cloudEnabled) maxCloudMoves else 0)
}

data class GameConfig(
    var engine: EngineConfig = EngineConfig(),
    var book: BookConfig = BookConfig()
)

class GameManagerV2(
    private val engineManager: UciEngineManager,
    private var bookProvider: com.qindachess.book.BookManager,
    private val cloudBookProvider: com.qindachess.book.CloudBookManager
) {
    /**
     * 查询云库当前局面的招法，返回 [UCI, 胜率/频率] 二元组。
     * 若联网失败返回 null。
     */
    suspend fun queryCloudMoves(fen: String): List<Pair<String, String>>? {
        val resp = cloudBookProvider.queryMoves(fen, 0) ?: return null
        return resp.moves.map { it.uciMove to String.format("%.0f%%", it.score * 100) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var config: GameConfig = GameConfig()
        private set

    private var totalMovesPlayed: Int = 0

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _statusMessage = MutableStateFlow("就绪")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _bookSource = MutableStateFlow<String>("")
    var bookSource: StateFlow<String> = _bookSource.asStateFlow()

    private val _cloudBookResults = MutableStateFlow<List<com.qindachess.book.CloudBookMove>>(emptyList())
    val cloudBookResults: StateFlow<List<com.qindachess.book.CloudBookMove>> = _cloudBookResults.asStateFlow()

    fun updateConfig(updater: GameConfig.() -> Unit) {
        val newConfig = config.copy()
        newConfig.engine = config.engine.copy()
        newConfig.book = config.book.copy()
        updater(newConfig)
        config = newConfig
        applyEngineConfig()
    }

    private fun applyEngineConfig() {
        val engineOpts = config.engine.toSearchOptions()
        scope.launch {
            engineManager.loadEngineIfReady(engineOpts)
        }
    }

    fun newGame() {
        _gameState.value = GameState()
        totalMovesPlayed = 0
        engineManager.resetHistory()
        engineManager.setPosition(ChessBoard.INITIAL_FEN)
        _statusMessage.value = "新对局开始"
        _bookSource.value = ""
    }

    fun applyPlayerMove(move: Move) {
        val currentState = _gameState.value
        if (currentState.gameOver) return
        executeMove(move)
    }

    private fun executeMove(move: Move) {
        val currentState = _gameState.value
        val newBoard = currentState.board.copy()
        newBoard.applyMove(move)
        val uci = move.toUci()
        val newMoveHistory = currentState.moveHistory + move
        val newUciHistory = currentState.uciHistory + uci

        engineManager.addMoveHistory(uci)
        engineManager.setPosition(newBoard, newMoveHistory)
        totalMovesPlayed = newUciHistory.size / 2

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

    suspend fun makeAutoMove(): Move? {
        val state = _gameState.value
        if (state.gameOver) return null

        _statusMessage.value = "思考中..."

        val fen = state.fen

        if (shouldUseLocalBook()) {
            var bookMove = bookProvider.findBestMove(fen, config.book.maxBookMoves, totalMovesPlayed)
            if (bookMove != null) {
                val move = Move.fromUci(bookMove)
                if (move != null) {
                    executeMove(move)
                    _bookSource.value = "本地开局库"
                    _statusMessage.value = "开局库: $bookMove"
                    return move
                }
            }
        }

        if (shouldUseCloudBook()) {
            val cloudMove = tryCloudBook(fen)
            if (cloudMove != null) {
                val move = Move.fromUci(cloudMove)
                if (move != null) {
                    executeMove(move)
                    _bookSource.value = "云库"
                    _statusMessage.value = "云库着: $cloudMove"
                    return move
                }
            }
        }

        val move = tryEngine(fen)
        if (move != null) {
            executeMove(move)
            _bookSource.value = "引擎"
        }
        return move
    }

    private suspend fun tryCloudBook(fen: String): String? {
        if (!config.book.cloudEnabled) return null
        if (totalMovesPlayed >= config.book.maxCloudMoves) return null

        return try {
            val response = cloudBookProvider.queryMoves(fen, totalMovesPlayed)
            if (response != null && response.moves.isNotEmpty()) {
                _cloudBookResults.value = response.moves
                response.moves.firstOrNull()?.let {
                    if (it.games >= config.book.minBookWeight) {
                        it.uciMove
                    } else null
                }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Cloud book failed", e)
            null
        }
    }

    private suspend fun tryEngine(fen: String): Move? {
        _statusMessage.value = "引擎深搜中 (depth=${config.engine.depth}, time=${config.engine.timeMs}ms)..."
        return try {
            val result = engineManager.search(config.engine.toSearchOptions())
            Move.fromUci(result.bestMove)
        } catch (e: Exception) {
            Log.e(TAG, "Engine search failed", e)
            null
        }
    }

    private fun shouldUseLocalBook(): Boolean {
        if (!config.book.enabled) return false
        if (bookProvider.activeBook == null) return false
        return totalMovesPlayed < config.book.maxBookMoves
    }

    private fun shouldUseCloudBook(): Boolean {
        if (!config.book.cloudEnabled) return false
        return totalMovesPlayed < config.book.maxCloudMoves
    }

    fun setEngineOption(name: String, value: String) {
        engineManager.sendEngineOption(name, value)
    }

    fun undoMove() {
        val currentState = _gameState.value
        if (currentState.moveHistory.isEmpty()) return

        val historyWithoutLast = currentState.moveHistory.dropLast(1)
        val board = ChessBoard().apply { parseFen(ChessBoard.INITIAL_FEN) }
        for (m in historyWithoutLast) board.applyMove(m)

        totalMovesPlayed = currentState.uciHistory.size / 2

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
        private const val TAG = "GameManagerV2"
    }
}
