package com.qindachess.engine

import android.util.Log
import com.qindachess.board.ChessBoard
import com.qindachess.board.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UciEngineManager {
    private var process: Process? = null
    private var inputWriter: BufferedWriter? = null
    private var outputReader: BufferedReader? = null
    private var readThread: Thread? = null
    @Volatile private var running: Boolean = false
    private val pendingResponses = mutableListOf<String>()
    private val responseLock = Object()

    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _searchInfo = MutableStateFlow(SearchInfo())
    val searchInfo: StateFlow<SearchInfo> = _searchInfo.asStateFlow()

    private val _multiPvResults = MutableStateFlow<List<EngineMove>>(emptyList())
    val multiPvResults: StateFlow<List<EngineMove>> = _multiPvResults.asStateFlow()

    private val _analyzingMode = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _analyzingMode.asStateFlow()

    private var moveHistory: MutableList<String> = mutableListOf()

    suspend fun loadEngine(enginePath: String, nnuePath: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                stopEngine()

                val builder = ProcessBuilder(enginePath)
                builder.redirectErrorStream(true)
                process = builder.start()
                inputWriter = BufferedWriter(
                    OutputStreamWriter(process!!.outputStream, "UTF-8")
                )
                outputReader = BufferedReader(
                    InputStreamReader(process!!.inputStream, "UTF-8")
                )
                running = true
                _engineState.value = EngineState.STARTING

                readThread = Thread { readLoop() }
                readThread!!.isDaemon = true
                readThread!!.start()

                sendCommand("uci")
                if (!waitForResponse("uciok", 5000)) {
                    Log.e(TAG, "Engine did not respond with uciok")
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }

                sendCommand("setoption name Threads value ${SearchOptions().threads}")
                sendCommand("setoption name Hash value ${SearchOptions().hashSize}")
                sendCommand("setoption name MultiPV value ${SearchOptions().multiPv}")

                if (nnuePath != null) {
                    sendCommand("setoption name EvalFile value $nnuePath")
                    sendCommand("setoption name UseNNUE value true")
                }

                sendCommand("isready")
                if (!waitForResponse("readyok", 5000)) {
                    Log.e(TAG, "Engine did not respond with readyok after options")
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }

                sendCommand("ucinewgame")
                sendCommand("isready")
                if (!waitForResponse("readyok", 5000)) {
                    Log.e(TAG, "Engine did not respond with readyok after ucinewgame")
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }

                _engineState.value = EngineState.READY
                Log.i(TAG, "Engine loaded successfully from $enginePath")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load engine", e)
                _engineState.value = EngineState.ERROR
                stopEngine()
                false
            }
        }

    private fun readLoop() {
        try {
            while (running) {
                val line = outputReader?.readLine()
                if (line != null) {
                    synchronized(responseLock) {
                        pendingResponses.add(line)
                        responseLock.notifyAll()
                    }
                    handleEngineOutput(line)
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (e: IOException) {
            if (running) Log.e(TAG, "Read loop error", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handleEngineOutput(line: String) {
        when {
            line.startsWith("info") -> parseInfoLine(line)
            line.startsWith("bestmove") -> {
                if (_analyzingMode.value) {
                    // 持续分析时收到 bestmove（引擎自己结束了？）
                    // 不停止，重发 go 继续挖
                    sendCommand("go depth 30")
                } else {
                    _engineState.value = EngineState.IDLE
                }
            }
        }
    }

    private fun parseInfoLine(line: String) {
        val info = SearchInfo()
        var i = 5
        val tokens = line.substring(5).split("\\s+".toRegex())

        var currentDepth = 0
        var currentScore: Int? = null
        var currentMate: Int? = null
        var currentMultiPv = 1
        var currentPv = mutableListOf<String>()
        var inPv = false

        var j = 0
        while (j < tokens.size) {
            val token = tokens[j]
            when {
                token == "depth" && j + 1 < tokens.size -> {
                    currentDepth = tokens[++j].toIntOrNull() ?: 0
                    info.depth = currentDepth
                }
                token == "score" && j + 2 < tokens.size -> {
                    val scoreType = tokens[++j]
                    val scoreVal = tokens[++j].toIntOrNull()
                    if (scoreVal != null) {
                        if (scoreType == "cp") currentScore = scoreVal
                        else if (scoreType == "mate") currentMate = scoreVal
                    }
                }
                token == "multipv" && j + 1 < tokens.size -> {
                    currentMultiPv = tokens[++j].toIntOrNull() ?: 1
                }
                token == "pv" -> {
                    inPv = true
                }
                inPv -> {
                    currentPv.add(token)
                }
                token == "nodes" && j + 1 < tokens.size -> {
                    info.nodes = tokens[++j].toLongOrNull() ?: 0
                }
                token == "time" && j + 1 < tokens.size -> {
                    info.timeMs = tokens[++j].toLongOrNull() ?: 0
                }
                token == "nps" && j + 1 < tokens.size -> {
                    info.nps = tokens[++j].toLongOrNull() ?: 0
                }
            }
            j++
        }

        info.scoreCp = currentScore
        info.mate = currentMate

        _searchInfo.value = info

        val pv = currentPv.firstOrNull()
        if (pv != null && (currentScore != null || currentMate != null)) {
            val move = EngineMove(
                uciMove = pv,
                scoreCp = currentScore ?: if (currentMate != null) 99999 * if (currentMate > 0) 1 else -1 else 0,
                mate = currentMate,
                pv = currentPv,
                depth = currentDepth,
                multipv = currentMultiPv
            )
            updateMultiPvResult(move)
        }
    }

    private val pvMap = mutableMapOf<Int, EngineMove>()

    private fun updateMultiPvResult(move: EngineMove) {
        pvMap[move.multipv] = move
        val sorted = pvMap.values.sortedBy { it.multipv }
        _multiPvResults.value = sorted
    }

    fun setPosition(fen: String, moves: List<String> = emptyList()) {
        moveHistory = moves.toMutableList()
        val sb = StringBuilder("position fen ")
        sb.append(fen)
        if (moves.isNotEmpty()) {
            sb.append(" moves ")
            sb.append(moves.joinToString(" "))
        }
        sendCommand(sb.toString())
        pvMap.clear()
    }

    fun setPosition(board: ChessBoard, moves: List<Move> = emptyList()) {
        val fen = board.toFen()
        val moveStrs = moves.map { it.toUci() }
        setPosition(fen, moveStrs)
    }

    suspend fun search(options: SearchOptions): SearchResult =
        suspendCancellableCoroutine { continuation ->
            if (_engineState.value != EngineState.READY && _engineState.value != EngineState.IDLE) {
                continuation.resumeWithException(IllegalStateException("Engine not ready"))
                return@suspendCancellableCoroutine
            }

            _engineState.value = EngineState.SEARCHING
            val cmd = buildGoCommand(options)
            sendCommand(cmd)

            synchronized(responseLock) {
                val bestMovePattern = Regex("bestmove\\s+(\\w+)(?:\\s+ponder\\s+(\\w+))?")
                val infoPattern = Regex("^info\\s")

                try {
                    val deadline = System.currentTimeMillis() + options.timeMs + 5000
                    val allInfo = mutableListOf<String>()

                    while (System.currentTimeMillis() < deadline) {
                        responseLock.wait(100)
                        while (pendingResponses.isNotEmpty()) {
                            val line = pendingResponses.removeAt(0)
                            if (line.matches(infoPattern)) {
                                allInfo.add(line)
                            }
                            val match = bestMovePattern.find(line)
                            if (match != null) {
                                val result = SearchResult(
                                    bestMove = match.groupValues[1],
                                    ponder = match.groupValues[2].ifBlank { null },
                                    moves = _multiPvResults.value,
                                    infoLines = allInfo
                                )
                                continuation.resume(result)
                                return@synchronized
                            }
                        }
                    }
                    continuation.resumeWithException(TimeoutException("Search timed out"))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    continuation.resumeWithException(e)
                }
            }
        }

    private fun buildGoCommand(options: SearchOptions): String {
        val sb = StringBuilder("go")
        if (options.depth > 0) sb.append(" depth ${options.depth}")
        if (options.timeMs > 0) {
            val wtime = options.timeMs
            val btime = options.timeMs
            val winc = 0
            val binc = 0
            sb.append(" wtime $wtime btime $btime winc $winc binc $binc")
            if (options.movestogo > 0) sb.append(" movestogo ${options.movestogo}")
        }
        return sb.toString()
    }

    fun stop() {
        sendCommand("stop")
        _engineState.value = if (_analyzingMode.value) EngineState.READY else EngineState.IDLE
    }

    /** 启动持续分析（引擎一直跑，multiPvResults 实时更新） */
    fun startContinuousAnalyze(options: SearchOptions, fen: String, moves: List<String>) {
        if (_engineState.value != EngineState.READY && _engineState.value != EngineState.IDLE) {
            Log.w(TAG, "引擎没准备好，不能启动持续分析 (state=${_engineState.value})")
            return
        }
        sendCommand("setoption name MultiPV value ${options.multiPv.coerceAtLeast(3)}")
        _analyzingMode.value = true
        _engineState.value = EngineState.SEARCHING
        pvMap.clear()
        setPosition(fen, moves)
        val depth = options.depth.coerceIn(10, 40)
        sendCommand("go depth $depth")
        Log.i(TAG, "✅ 持续分析已启动 depth=$depth multiPv=${options.multiPv}")
    }

    /** 停止持续分析 */
    fun stopContinuousAnalyze() {
        if (!_analyzingMode.value) return
        _analyzingMode.value = false
        sendCommand("stop")
        _engineState.value = EngineState.READY
        Log.i(TAG, "⏹ 持续分析已停止")
    }

    /** 棋盘变化后，停止旧搜索 → 更新 position → 重新 go */
    fun updatePositionAndRestart(fen: String, moves: List<String>) {
        if (!_analyzingMode.value) return
        sendCommand("stop")
        pvMap.clear()
        setPosition(fen, moves)
        sendCommand("go depth 30")
        Log.d(TAG, "持续分析：棋盘已更新，重新搜索")
    }

    fun ponderHit() {
        sendCommand("ponderhit")
    }

    fun stopEngine() {
        running = false
        _analyzingMode.value = false
        try {
            readThread?.interrupt()
            readThread?.join(500)
        } catch (_: InterruptedException) {}
        readThread = null

        try {
            inputWriter?.write("quit\n")
            inputWriter?.flush()
        } catch (_: Exception) {}

        try {
            process?.waitFor(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}

        try {
            process?.destroyForcibly()
        } catch (_: Exception) {}

        process = null
        inputWriter = null
        outputReader = null
        _engineState.value = EngineState.IDLE
        pvMap.clear()
    }

    private fun sendCommand(cmd: String) {
        if (!running) return
        try {
            Log.d(TAG, ">> $cmd")
            inputWriter?.write(cmd)
            inputWriter?.newLine()
            inputWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: $cmd", e)
        }
    }

    fun sendEngineOption(name: String, value: String) {
        if (_engineState.value != EngineState.READY) return
        sendCommand("setoption name $name value $value")
    }

    fun applySearchOptions(options: SearchOptions) {
        sendEngineOption("Threads", options.threads.toString())
        sendEngineOption("Hash", options.hashSize.toString())
        sendEngineOption("MultiPV", options.multiPv.toString())
        sendEngineOption("UseNNUE", options.useNnue.toString())
    }

    suspend fun loadEngineIfReady(options: SearchOptions) {
        if (_engineState.value == EngineState.READY) {
            applySearchOptions(options)
            sendCommand("ucinewgame")
            sendCommand("isready")
            waitForResponse("readyok", 3000)
        }
    }

    private fun waitForResponse(prefix: String, timeoutMs: Long): Boolean {
        synchronized(responseLock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val found = pendingResponses.firstOrNull { it.startsWith(prefix) }
                if (found != null) {
                    pendingResponses.remove(found)
                    return true
                }
                try {
                    responseLock.wait(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    fun isReady(): Boolean = _engineState.value == EngineState.READY

    fun addMoveHistory(uciMove: String) {
        moveHistory.add(uciMove)
    }

    fun resetHistory() {
        moveHistory.clear()
    }

    companion object {
        private const val TAG = "UciEngineManager"
    }
}

enum class EngineState {
    IDLE, STARTING, READY, SEARCHING, ERROR
}

data class SearchInfo(
    var depth: Int = 0,
    var scoreCp: Int? = null,
    var mate: Int? = null,
    var nodes: Long = 0,
    var timeMs: Long = 0,
    var nps: Long = 0
)

class TimeoutException(msg: String) : Exception(msg)
