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

    @Volatile var lastError: String? = null
        private set

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
                lastError = null

                val file = java.io.File(enginePath)
                if (!file.exists()) {
                    lastError = "文件不存在: $enginePath"
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }
                if (!file.canRead()) {
                    lastError = "文件不可读: $enginePath (权限不足)"
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }

                process = tryStartProcess(enginePath)

                if (process == null) {
                    lastError = buildLastError(enginePath, file)
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }

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

                // 同时发送 uci 和 ucci（中国象棋引擎大多支持 UCCI 协议）
                sendCommand("ucci")
                sendCommand("uci")
                val uciOk = waitForResponse("uciok", 2000)
                val ucciOk = if (!uciOk) waitForResponse("ucciok", 3000) else false
                if (!uciOk && !ucciOk) {
                    val existingOutput: List<String> = synchronized(responseLock) {
                        pendingResponses.toList()
                    }
                    lastError = "引擎无响应 uciok/ucciok（文件可能不是合法象棋引擎）\n" +
                        "已收到输出(${existingOutput.size}行): ${existingOutput.take(5).joinToString(" | ")}\n" +
                        "进程是否存活: ${process?.isAlive}\n" +
                        "running flag: $running"
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }
                Log.i(TAG, "引擎协议握手成功 (uciok=$uciOk, ucciok=$ucciOk)")

                sendCommand("setoption name Threads value ${SearchOptions().threads}")
                sendCommand("setoption name Hash value ${SearchOptions().hashSize}")
                sendCommand("setoption name MultiPV value ${SearchOptions().multiPv}")

                if (nnuePath != null) {
                    sendCommand("setoption name EvalFile value $nnuePath")
                    sendCommand("setoption name evalfile value $nnuePath")
                    sendCommand("setoption name UseNNUE value true")
                    sendCommand("setoption name usennue value true")
                }

                sendCommand("isready")
                if (!waitForResponse("readyok", 5000)) {
                    lastError = "引擎加载选项后无响应 readyok"
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }

                sendCommand("newgame")
                sendCommand("ucinewgame")
                sendCommand("isready")
                if (!waitForResponse("readyok", 5000)) {
                    lastError = "新游戏初始化后无响应 readyok"
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    return@withContext false
                }

                _engineState.value = EngineState.READY
                Log.i(TAG, "Engine loaded successfully from $enginePath")
                true
            } catch (e: Exception) {
                lastError = "意外异常: ${e.javaClass.simpleName} - ${e.message}"
                Log.e(TAG, lastError!!, e)
                _engineState.value = EngineState.ERROR
                stopEngine()
                false
            }
        }

    private fun tryStartProcess(enginePath: String): Process? {
        val file = java.io.File(enginePath)
        val attempts = mutableListOf<Pair<String, () -> Process?>>()

        // 1) 直接路径
        attempts.add("直接执行" to {
            try {
                ProcessBuilder(enginePath).redirectErrorStream(true).start()
            } catch (e: Exception) {
                Log.d(TAG, "直接执行失败: ${e.message}")
                null
            }
        })

        // 2) 通过 sh -c 执行（绕过路径中特殊字符）
        attempts.add("sh -c 执行" to {
            try {
                ProcessBuilder("/system/bin/sh", "-c", "\"$enginePath\"").redirectErrorStream(true).start()
            } catch (e: Exception) {
                Log.d(TAG, "sh -c 执行失败: ${e.message}")
                null
            }
        })

        // 3) Root 设备用 su 执行（绕过 SELinux）
        if (java.io.File("/system/bin/su").exists() || java.io.File("/system/xbin/su").exists()) {
            attempts.add("su -c 执行" to {
                try {
                    ProcessBuilder("su", "-c", "\"$enginePath\"").redirectErrorStream(true).start()
                } catch (e: Exception) {
                    Log.d(TAG, "su -c 执行失败: ${e.message}")
                    null
                }
            })
        }

        // 4) Termux 兼容：如果 Termux 存在，尝试用它的 bin 目录
        val termuxBin = java.io.File("/data/data/com.termux/files/usr/bin")
        if (termuxBin.exists()) {
            attempts.add("Termux 环境执行" to {
                try {
                    // 先把引擎拷贝到 Termux 可执行目录
                    val dest = java.io.File(termuxBin, "qinda_engine")
                    file.copyTo(dest, overwrite = true)
                    dest.setExecutable(true, false)
                    ProcessBuilder(dest.absolutePath).redirectErrorStream(true).start()
                } catch (e: Exception) {
                    Log.d(TAG, "Termux 执行失败: ${e.message}")
                    null
                }
            })
        }

        for ((label, block) in attempts) {
            Log.i(TAG, "尝试 [$label] 启动引擎: $enginePath")
            val p = block()
            if (p != null && p.isAlive) {
                Log.i(TAG, "✅ 引擎启动成功 via [$label]")
                running = true
                return p
            }
            p?.destroy()
        }

        return null
    }

    private fun buildLastError(enginePath: String, file: java.io.File): String {
        val reasons = mutableListOf<String>()
        reasons.add("路径: $enginePath")
        reasons.add("大小: ${file.length()}B")
        reasons.add("canExecute: ${file.canExecute()}")
        reasons.add("canRead: ${file.canRead()}")
        reasons.add("isFile: ${file.isFile}")
        reasons.add("parent exists: ${file.parentFile?.exists()}")
        reasons.add("isSymlink: ${file.canonicalPath != file.absolutePath}")

        // 检查是否有 root
        val hasSu = java.io.File("/system/bin/su").exists() || java.io.File("/system/xbin/su").exists()
        val hasTermux = java.io.File("/data/data/com.termux").exists()

        val tip = when {
            hasSu -> "检测到设备有 root，已尝试 su -c 方式仍失败。可能引擎不是 ARM64 架构，或文件损坏。"
            hasTermux -> "检测到 Termux，已尝试拷贝到 Termux bin 目录执行仍失败。"
            else -> "Android SELinux 限制：普通应用无法直接执行数据目录下的 ELF 二进制文件。\n" +
                "解决方案:\n" +
                "  1) 使用 Root 设备，本应用会自动用 su 执行\n" +
                "  2) 安装 Termux，把引擎放到 /data/data/com.termux/files/usr/bin/\n" +
                "  3) 确认引擎文件是 ARM64 (aarch64) 架构的二进制"
        }
        return "启动引擎失败（尝试了多种方式均被拒绝）\n${reasons.joinToString("\n")}\n\n💡 $tip"
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
