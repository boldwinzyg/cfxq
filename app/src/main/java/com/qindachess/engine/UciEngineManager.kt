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

    private val engineBanner = mutableListOf<String>()

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
                engineBanner.clear()

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

                try {
                    inputWriter = BufferedWriter(
                        OutputStreamWriter(process!!.outputStream, "UTF-8")
                    )
                    outputReader = BufferedReader(
                        InputStreamReader(process!!.inputStream, "UTF-8")
                    )
                } catch (e: Exception) {
                    lastError = "创建进程管道失败: ${e.message}"
                    Log.e(TAG, lastError!!, e)
                    _engineState.value = EngineState.ERROR
                    stopEngine()
                    return@withContext false
                }

                running = true
                _engineState.value = EngineState.STARTING

                readThread = Thread { readLoop() }
                readThread!!.isDaemon = true
                readThread!!.start()

                // 先等一会让引擎输出 banner（不发任何命令）
                Thread.sleep(300)

                // 先试 UCI（Pikafish 默认 UCI）
                sendCommand("uci")
                val uciOk = waitForResponse("uciok", 5000)

                val ucciOk = if (!uciOk) {
                    // 引擎可能是 UCCI，重启走 UCCI 握手
                    Log.i(TAG, "uci 无响应，重启尝试 ucci 协议")
                    stopEngineQuiet()
                    Thread.sleep(200)
                    engineBanner.clear()
                    process = tryStartProcess(enginePath)
                    if (process != null) {
                        inputWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream, "UTF-8"))
                        outputReader = BufferedReader(InputStreamReader(process!!.inputStream, "UTF-8"))
                        running = true
                        readThread = Thread { readLoop() }
                        readThread!!.isDaemon = true
                        readThread!!.start()
                        Thread.sleep(300)
                        sendCommand("ucci")
                        waitForResponse("ucciok", 5000)
                    } else false
                } else false

                if (!uciOk && !ucciOk) {
                    synchronized(responseLock) {
                        pendingResponses.clear()
                    }
                    lastError = "引擎启动了但不响应 uciok/ucciok\n" +
                        "进程存活: ${process?.isAlive}\n" +
                        "引擎启动后共输出 ${engineBanner.size} 行:\n" +
                        engineBanner.take(20).joinToString("\n") { "  | $it" } +
                        if (engineBanner.size > 20) "\n  ... (共 ${engineBanner.size} 行)" else "" +
                        "\n\n💡 可能原因:\n" +
                        "  1) 引擎需要 su/shell 方式执行才能正常握手\n" +
                        "  2) 引擎文件损坏或不是象棋引擎\n" +
                        "  3) 引擎是特殊协议（非 UCI/UCCI）"
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    stopEngineQuiet()
                    return@withContext false
                }
                val protocol = if (uciOk) "UCI" else "UCCI"
                Log.i(TAG, "✅ 引擎协议握手成功 [$protocol]")

                // 应用引擎参数
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
                    lastError = "引擎加载选项后无响应 readyok\n" +
                        "引擎输出:\n" + engineBanner.take(30).joinToString("\n") { "  | $it" }
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    stopEngineQuiet()
                    return@withContext false
                }

                sendCommand("newgame")
                sendCommand("ucinewgame")
                sendCommand("isready")
                if (!waitForResponse("readyok", 5000)) {
                    lastError = "新游戏初始化后无响应 readyok"
                    Log.e(TAG, lastError!!)
                    _engineState.value = EngineState.ERROR
                    stopEngineQuiet()
                    return@withContext false
                }

                _engineState.value = EngineState.READY
                Log.i(TAG, "🎉 引擎加载成功 [$protocol]")
                true
            } catch (e: Exception) {
                lastError = "意外异常: ${e.javaClass.simpleName} - ${e.message}"
                Log.e(TAG, lastError!!, e)
                _engineState.value = EngineState.ERROR
                stopEngineQuiet()
                false
            }
        }

    private fun tryStartProcess(enginePath: String): Process? {
        val file = java.io.File(enginePath)

        // 🔥 关键：如果路径包含 /lib/ 且以 .so 结尾，那是 nativeLibraryDir 里的文件
        // Android SELinux 唯一允许应用直接执行自己 ELF 的目录！直接尝试。
        val isNativeLib = enginePath.contains("/lib/") && enginePath.endsWith(".so")
        if (isNativeLib) {
            Log.i(TAG, "🚀 检测到 nativeLibraryDir 路径，直接执行！$enginePath")
            try {
                val p = ProcessBuilder(enginePath).redirectErrorStream(true).directory(file.parentFile).start()
                Thread.sleep(400)
                if (p.isAlive) {
                    Log.i(TAG, "✅ nativeLibraryDir 直接执行成功！PID=${p.hashCode()}")
                    running = true
                    return p
                } else {
                    val exit = try { p.exitValue() } catch (_: Exception) { -1 }
                    Log.w(TAG, "nativeLibraryDir 引擎立即退出 exitCode=$exit，fallback 其他策略")
                    p.destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "nativeLibraryDir 直接执行异常: ${e.message}，fallback 其他策略")
            }
        }

        data class Attempt(val label: String, val cmd: Array<String>)

        val attempts = mutableListOf<Attempt>()
        attempts.add(Attempt("直接执行", arrayOf(enginePath)))
        attempts.add(Attempt("sh exec", arrayOf("/system/bin/sh", "-c", "exec \"$enginePath\"")))
        attempts.add(Attempt("run-as", arrayOf("run-as", "com.qindachess", "sh -c \"exec $enginePath\"")))

        val hasSu = java.io.File("/system/bin/su").exists() || java.io.File("/system/xbin/su").exists()
        if (hasSu) {
            attempts.add(Attempt("su -c exec", arrayOf("su", "-c", "exec \"$enginePath\"")))
            attempts.add(Attempt("su sh exec", arrayOf("su", "-c", "sh -c \"exec $enginePath\"")))
            attempts.add(Attempt("su 直接", arrayOf("su", "-c", enginePath)))
        }

        val termuxBin = java.io.File("/data/data/com.termux/files/usr/bin")
        if (termuxBin.exists()) {
            try {
                val dest = java.io.File(termuxBin, "qinda_engine")
                file.copyTo(dest, overwrite = true)
                dest.setExecutable(true, false)
                Log.i(TAG, "Termux 拷贝: ${dest.absolutePath} (${dest.length()}B)")
                attempts.add(Attempt("Termux", arrayOf(dest.absolutePath)))
            } catch (e: Exception) {
                Log.w(TAG, "Termux 拷贝失败: ${e.message}")
            }
        }

        for ((label, cmd) in attempts) {
            Log.i(TAG, "🪄 [$label]: ${cmd.joinToString(" ")}")
            try {
                val p = ProcessBuilder(*cmd).redirectErrorStream(true).directory(file.parentFile).start()
                Thread.sleep(400)
                if (p.isAlive) {
                    Log.i(TAG, "✅ [$label] 进程存活！引擎已启动 (PID=${p.hashCode()})")
                    running = true
                    return p
                } else {
                    val exit = try { p.exitValue() } catch (_: Exception) { -1 }
                    Log.w(TAG, "[$label] 立即退出 exitCode=$exit")
                    p.destroy()
                }
            } catch (e: Exception) {
                Log.d(TAG, "[$label] 异常: ${e.message}")
            }
        }

        Log.e(TAG, "❌ 所有启动策略均失败")
        return null
    }

    private fun buildLastError(enginePath: String, file: java.io.File): String {
        val reasons = mutableListOf<String>()
        reasons.add("路径: $enginePath")
        reasons.add("大小: ${file.length()}B")
        reasons.add("canExecute: ${file.canExecute()}")
        reasons.add("canRead: ${file.canRead()}")

        val hasSu = java.io.File("/system/bin/su").exists() || java.io.File("/system/xbin/su").exists()
        val hasTermux = java.io.File("/data/data/com.termux").exists()

        val tip = when {
            hasSu -> "检测到设备有 root，已尝试 su/run-as 方式仍失败。可能引擎文件损坏或架构不匹配。"
            hasTermux -> "检测到 Termux，已尝试拷贝执行仍失败。"
            else -> "Android SELinux 限制：无法执行数据目录下的 ELF\n" +
                "解决方案:\n" +
                "  1) Root 设备会自动用 su/run-as 执行\n" +
                "  2) 安装 Termux 后再导入引擎\n" +
                "  3) 确认引擎是 ARM64 (aarch64) 架构"
        }
        return "启动引擎失败（所有策略均被拒绝）\n${reasons.joinToString("\n")}\n\n💡 $tip"
    }

    private fun readLoop() {
        try {
            while (running) {
                val line = outputReader?.readLine()
                if (line != null) {
                    synchronized(responseLock) {
                        pendingResponses.add(line)
                        engineBanner.add(line)
                        responseLock.notifyAll()
                    }
                    if (engineBanner.size <= 5 || line.startsWith("info") || line.startsWith("bestmove") ||
                        line.startsWith("id") || line.startsWith("option") || line.startsWith("uciok") ||
                        line.startsWith("ucciok") || line.startsWith("readyok") || line.startsWith("Copyright") ||
                        line.contains("pikafish", ignoreCase = true) || line.contains("engine", ignoreCase = true)) {
                        Log.i(TAG, "⚙️ 引擎输出: $line")
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
                    sendCommand("go depth 30")
                } else {
                    _engineState.value = EngineState.IDLE
                }
            }
        }
    }

    private fun parseInfoLine(line: String) {
        val info = SearchInfo()
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
                token == "pv" -> inPv = true
                inPv -> currentPv.add(token)
                token == "nodes" && j + 1 < tokens.size -> info.nodes = tokens[++j].toLongOrNull() ?: 0
                token == "time" && j + 1 < tokens.size -> info.timeMs = tokens[++j].toLongOrNull() ?: 0
                token == "nps" && j + 1 < tokens.size -> info.nps = tokens[++j].toLongOrNull() ?: 0
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
                            if (line.matches(infoPattern)) allInfo.add(line)
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
            sb.append(" wtime $wtime btime $btime winc 0 binc 0")
            if (options.movestogo > 0) sb.append(" movestogo ${options.movestogo}")
        }
        return sb.toString()
    }

    fun stop() {
        sendCommand("stop")
        _engineState.value = if (_analyzingMode.value) EngineState.READY else EngineState.IDLE
    }

    fun startContinuousAnalyze(options: SearchOptions, fen: String, moves: List<String>) {
        if (_engineState.value != EngineState.READY && _engineState.value != EngineState.IDLE) return
        sendCommand("setoption name MultiPV value ${options.multiPv.coerceAtLeast(3)}")
        _analyzingMode.value = true
        _engineState.value = EngineState.SEARCHING
        pvMap.clear()
        setPosition(fen, moves)
        sendCommand("go depth ${options.depth.coerceIn(10, 40)}")
    }

    fun stopContinuousAnalyze() {
        if (!_analyzingMode.value) return
        _analyzingMode.value = false
        sendCommand("stop")
        _engineState.value = EngineState.READY
    }

    fun updatePositionAndRestart(fen: String, moves: List<String>) {
        if (!_analyzingMode.value) return
        sendCommand("stop")
        pvMap.clear()
        setPosition(fen, moves)
        sendCommand("go depth 30")
    }

    fun ponderHit() {
        sendCommand("ponderhit")
    }

    private fun stopEngineQuiet() {
        running = false
        _analyzingMode.value = false
        try { readThread?.interrupt(); readThread?.join(500) } catch (_: InterruptedException) {}
        readThread = null
        try { inputWriter?.write("quit\n"); inputWriter?.flush() } catch (_: Exception) {}
        try { process?.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        try { process?.destroyForcibly() } catch (_: Exception) {}
        process = null
        inputWriter = null
        outputReader = null
    }

    fun stopEngine() {
        stopEngineQuiet()
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
