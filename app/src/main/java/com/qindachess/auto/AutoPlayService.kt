package com.qindachess.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.qindachess.R
import com.qindachess.board.ChessBoard
import com.qindachess.board.ChineseNotation
import com.qindachess.board.Move
import com.qindachess.engine.EngineMove
import com.qindachess.engine.EngineState
import com.qindachess.engine.GameManager
import com.qindachess.engine.SearchOptions
import com.qindachess.engine.UciEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 悬浮窗自动下棋服务。
 *
 * 包含三个可切换显示的窗口：
 *   1. panelView   —— 展开态面板（7个图标按钮 + 数据行）
 *   2. ballView    —— 折叠态小球
 *   3. miniBoardView —— 独立悬浮小棋盘（可拖到任意位置，可与 panel 吸附/分离）
 *
 * 引擎链路：
 *   UciEngineManager → 周期性 Search → 取 bestmove
 *   → BoardCoordinateMapper.moveToScreen() → GestureManager.performChessMove()
 */
class AutoPlayService : Service() {

    companion object {
        private const val TAG = "AutoPlayService"
        private const val CHANNEL_ID = "qinda_auto_play"
        private const val NOTIFICATION_ID = 7
        const val ACTION_START = "com.qindachess.ACTION_START_AUTO"
        const val ACTION_STOP = "com.qindachess.ACTION_STOP_AUTO"

        const val PREFS = "auto_play_prefs"
        const val KEY_DELAY = "move_delay_ms"
        const val KEY_DEPTH = "search_depth"
        const val KEY_AUTO_MODE = "auto_mode"
        const val KEY_MINI_BOARD_VISIBLE = "mini_board_visible"

        const val SNAP_DISTANCE_DP = 28f

        var isRunning: Boolean = false
            private set

        fun start(ctx: Context) {
            val intent = Intent(ctx, AutoPlayService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, AutoPlayService::class.java).apply { action = ACTION_STOP }
            ctx.startService(intent)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wm: WindowManager? = null

    // --- 三个窗口 ---
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var miniBoard: MiniBoardView? = null
    private var miniBoardParams: WindowManager.LayoutParams? = null

    // --- 状态 ---
    private var expanded = true          // true=展开面板, false=小球
    private var miniBoardVisible = false
    private var miniBoardAttached = false // miniBoard 是否吸附到 panel 旁
    private var isAutoMode = true        // true=自动连线下棋, false=手动（等用户点估值再走）
    private var isPlaying = false        // 是否正在自动走子
    private var moveDelayMs = 1000L      // 出招延时（毫秒）
    private var searchDepth = 12         // 思考层数
    private var showBoardAfterSnap = true

    // --- 引擎 ---
    private var gameManager: GameManager? = null
    private var engineManager: UciEngineManager? = null
    private var analysisRunnable: Runnable? = null
    private var autoPlayRunnable: Runnable? = null
    private var lastKnownBoard: ChessBoard = ChessBoard().apply { parseFen(ChessBoard.INITIAL_FEN) }
    private var lastBestMove: Move? = null
    private var lastScoreCp: Int? = null
    private var lastPv: List<Move> = emptyList()
    private var lastPvRaw: List<String> = emptyList()
    private var lastPvBoard: ChessBoard? = null
    private var lastDepthShown: Int = 0
    private var lastTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        loadPrefs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("宸风象棋 运行中"))
                setupWindows()
                startEngineObserver()
                startAnalysisLoop()
                GestureManager.get().init(this)
                isRunning = true
            }
            ACTION_STOP -> {
                tearDownAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tearDownAll()
        scope.cancel()
        uiScope.cancel()
        isRunning = false
    }

    // ---------- 生命周期 ----------

    private fun tearDownAll() {
        stopAnalysisLoop()
        stopAutoPlayLoop()
        try { panelView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { ballView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { miniBoard?.let { wm?.removeView(it) } } catch (_: Exception) {}
        panelView = null; ballView = null; miniBoard = null
        panelParams = null; ballParams = null; miniBoardParams = null
    }

    // ---------- 窗口搭建 ----------

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        moveDelayMs = p.getLong(KEY_DELAY, 1000L)
        searchDepth = p.getInt(KEY_DEPTH, 12)
        isAutoMode = p.getBoolean(KEY_AUTO_MODE, true)
        miniBoardVisible = p.getBoolean(KEY_MINI_BOARD_VISIBLE, false)
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            putLong(KEY_DELAY, moveDelayMs)
            putInt(KEY_DEPTH, searchDepth)
            putBoolean(KEY_AUTO_MODE, isAutoMode)
            putBoolean(KEY_MINI_BOARD_VISIBLE, miniBoardVisible)
        }.apply()
    }

    private fun setupWindows() {
        createExpandedPanel()
        createCollapsedBall()
        if (miniBoardVisible) createMiniBoard() else removeMiniBoard()
        applyVisibility()
    }

    private fun applyVisibility() {
        if (expanded) {
            panelView?.let { v ->
                if (v.parent == null) wm?.addView(v, panelParams ?: return@let)
            }
            ballView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        } else {
            panelView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            ballView?.let { v ->
                if (v.parent == null) wm?.addView(v, ballParams ?: return@let)
            }
        }
    }

    private fun createExpandedPanel() {
        if (panelView != null) return
        val ctx = this
        val view = LayoutInflater.from(ctx).inflate(R.layout.view_floating_panel, null)
        panelView = view
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60; y = 220
        }

        // 给所有按钮初始白色着色
        val white = Color.WHITE
        view.findViewById<ImageView>(R.id.btnConnect).setColorFilter(white)
        view.findViewById<ImageView>(R.id.btnAuto).setColorFilter(white)
        view.findViewById<ImageView>(R.id.btnDelay).setColorFilter(white)
        view.findViewById<ImageView>(R.id.btnDepth).setColorFilter(white)
        view.findViewById<ImageView>(R.id.btnMiniBoard).setColorFilter(white)
        view.findViewById<ImageView>(R.id.btnStop).setColorFilter(white)

        setupDragAndSnap(view, panelParams!!, isPanel = true)
        bindPanelButtons(view)
        updatePanelUI()
    }

    private fun createCollapsedBall() {
        if (ballView != null) return
        val ctx = this
        val view = LayoutInflater.from(ctx).inflate(R.layout.view_floating_ball, null)
        ballView = view
        ballParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60; y = 220
        }
        setupDragAndSnap(view, ballParams!!, isPanel = false)
        view.setOnClickListener {
            expanded = true
            // 小球位置还原到 panel 原位置
            ballParams?.let { bp ->
                panelParams?.x = bp.x
                panelParams?.y = bp.y
            }
            applyVisibility()
            savePrefs()
        }
    }

    private fun createMiniBoard() {
        if (miniBoard != null) return
        val mb = MiniBoardView(this)
        mb.minimumWidth = (96f * resources.displayMetrics.density).toInt()
        mb.minimumHeight = (96f * resources.displayMetrics.density).toInt()
        miniBoard = mb
        miniBoardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            panelParams?.let { x = it.x + (panelView?.width ?: 0) + 12; y = it.y }
        }
        wm?.addView(mb, miniBoardParams)
        miniBoardAttached = true
        setupMiniBoardDrag()
        miniBoardVisible = true
        savePrefs()
    }

    private fun removeMiniBoard() {
        miniBoard?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        miniBoard = null; miniBoardParams = null
        miniBoardAttached = false
        miniBoardVisible = false
        savePrefs()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ---------- 拖拽 + 吸附 ----------

    private fun setupDragAndSnap(view: View, params: WindowManager.LayoutParams, isPanel: Boolean) {
        var startRawX = 0f; var startRawY = 0f
        var startX = 0; var startY = 0
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    startX = params.x; startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    wm?.updateViewLayout(v, params)

                    // 如果是 panel 在拖，miniBoard 处于吸附就跟随
                    if (isPanel && miniBoardAttached && miniBoard != null && miniBoardParams != null) {
                        miniBoardParams!!.x = params.x + v.width + dp(SNAP_DISTANCE_DP)
                        miniBoardParams!!.y = params.y
                        wm?.updateViewLayout(miniBoard!!, miniBoardParams!!)
                    }
                }
            }
            false
        }
    }

    private fun setupMiniBoardDrag() {
        val mb = miniBoard ?: return
        val mp = miniBoardParams ?: return

        var startRawX = 0f; var startRawY = 0f
        var startX = 0; var startY = 0
        var longPressStart = 0L
        var longPressArmed = false

        mb.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    startX = mp.x; startY = mp.y
                    longPressStart = System.currentTimeMillis()
                    longPressArmed = !miniBoardAttached
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    mp.x = (startX + dx).coerceAtLeast(0)
                    mp.y = (startY + dy).coerceAtLeast(0)
                    wm?.updateViewLayout(v, mp)
                }
                MotionEvent.ACTION_UP -> {
                    // 检测是否应该吸附回 panel（长按已完成 → 分离；短按未完成 → 可吸附）
                    val duration = System.currentTimeMillis() - longPressStart
                    val wasLongPress = duration > 500L || !longPressArmed
                    if (!wasLongPress) {
                        trySnapMiniBoard()
                    } else {
                        // 长按分离：不再跟随 panel
                        miniBoardAttached = false
                    }
                }
            }
            false
        }
    }

    private fun trySnapMiniBoard() {
        val mb = miniBoard ?: return
        val mp = miniBoardParams ?: return
        val pv = panelView ?: return
        val pp = panelParams ?: return

        val mbCx = mp.x + pv.width / 2f
        val mbCy = mp.y + pv.height / 2f
        val pvCx = pp.x + pv.width / 2f
        val pvCy = pp.y + pv.height / 2f
        val distPx = hypot((mbCx - pvCx).toDouble(), (mbCy - pvCy).toDouble()).toFloat()
        val threshold = dp(80f)

        if (distPx < threshold) {
            mp.x = pp.x + pv.width + dp(4f)
            mp.y = pp.y
            wm?.updateViewLayout(mb, mp)
            miniBoardAttached = true
            Log.i(TAG, "miniBoard snapped to panel (dist=$distPx)")
        } else {
            miniBoardAttached = false
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 按钮绑定 ----------

    private fun bindPanelButtons(panel: View) {
        panel.findViewById<ImageView>(R.id.btnConnect).apply {
            setOnClickListener { toggleAutoPlay() }
            setOnLongClickListener {
                showAnalysisSnapshot()
                true
            }
        }
        panel.findViewById<ImageView>(R.id.btnAuto).apply {
            setOnClickListener {
                isAutoMode = !isAutoMode
                savePrefs(); updatePanelUI()
                Toast.makeText(this@AutoPlayService,
                    if (isAutoMode) "自动连线下棋模式" else "手动模式（引擎代走）",
                    Toast.LENGTH_SHORT).show()
            }
        }
        panel.findViewById<ImageView>(R.id.btnDelay).apply {
            setOnClickListener { showDelayPicker() }
        }
        panel.findViewById<ImageView>(R.id.btnDepth).apply {
            setOnClickListener { showDepthPicker() }
        }
        panel.findViewById<ImageView>(R.id.btnMiniBoard).apply {
            setOnClickListener {
                if (miniBoard == null) createMiniBoard() else removeMiniBoard()
            }
        }
        panel.findViewById<ImageView>(R.id.btnStop).apply {
            setOnClickListener {
                Toast.makeText(this@AutoPlayService, "已终止连线", Toast.LENGTH_SHORT).show()
                stopAutoPlayLoop()
                stopAnalysisLoop()
                AutoPlayService.stop(this@AutoPlayService)
            }
        }
    }

    private fun updatePanelUI() {
        val v = panelView ?: return
        val btnConnect = v.findViewById<ImageView>(R.id.btnConnect)
        val btnAuto = v.findViewById<ImageView>(R.id.btnAuto)
        val btnMiniBoard = v.findViewById<ImageView>(R.id.btnMiniBoard)

        val green = Color.argb(255, 76, 175, 80)
        val red = Color.argb(255, 244, 67, 54)
        val white = Color.WHITE

        btnConnect.setColorFilter(if (isPlaying) red else green)
        btnAuto.setColorFilter(if (isAutoMode) green else Color.argb(255, 255, 152, 0))
        btnMiniBoard.setColorFilter(if (miniBoardVisible) green else white)

        // 我方视角分数（engine 给的 scoreCp 总是"走子方视角"，需根据 sideToMove 翻转）
        val tvScoreText = v.findViewById<TextView>(R.id.tvScoreText)
        val rawScore = lastScoreCp
        if (rawScore == null) {
            tvScoreText.text = "—"
            tvScoreText.setTextColor(Color.WHITE)
        } else {
            val ourSideIsRed = gameManager?.gameState?.value?.sideToMove ==
                com.qindachess.board.PieceColor.RED
            val ourScore = if (ourSideIsRed) rawScore else -rawScore
            val display = when {
                ourScore > 5000 -> "+M1"
                ourScore < -5000 -> "-M1"
                else -> "%+.2f".format(ourScore / 100.0)
            }
            tvScoreText.text = display
            tvScoreText.setTextColor(
                when {
                    ourScore > 200 -> Color.argb(255, 76, 175, 80)   // 绿：优势
                    ourScore < -200 -> Color.argb(255, 244, 67, 54)  // 红：劣势
                    else -> Color.argb(255, 255, 241, 118)           // 黄：均势
                }
            )
        }

        // 数据行（分栏显示）
        val tvEval = v.findViewById<TextView>(R.id.tvEval)
        val tvDepth = v.findViewById<TextView>(R.id.tvDepth)
        val tvTime = v.findViewById<TextView>(R.id.tvTime)
        val tvPv = v.findViewById<TextView>(R.id.tvPv)

        val score = lastScoreCp
        tvEval.text = when {
            score == null -> "—"
            score > 5000 -> "将死+"
            score < -5000 -> "被将"
            score > 0 -> "+${score / 100.0}"
            else -> "${score / 100.0}"
        }
        tvDepth.text = "d$lastDepthShown"
        tvTime.text = "${lastTimeMs}ms"
        // 招法区：转中文记谱
        tvPv.text = if (lastBestMove != null && lastPvRaw.isNotEmpty()) {
            val boardRef = lastPvBoard
            if (boardRef != null) {
                val moves = lastPvRaw.take(4).mapNotNull { Move.fromUci(it) }
                if (moves.isNotEmpty()) {
                    val cn = ChineseNotation.pvToChinese(boardRef, lastPvRaw.take(4))
                    cn.joinToString(" ")
                } else lastBestMove?.toUci() ?: "—"
            } else lastBestMove?.toUci() ?: "—"
        } else lastBestMove?.toUci() ?: "—"
    }

    // ---------- 选择器：延时 + 层数 ----------

    private fun showDelayPicker() {
        val steps = doubleArrayOf(0.1, 0.3, 0.5, 1.0, 2.0, 4.0, 8.0)
        val curMs = moveDelayMs
        val curIdx = steps.indexOfFirst { (it * 1000).toLong() == curMs }.coerceAtLeast(0)

        val ctx = this
        val bar = SeekBar(ctx).apply {
            max = steps.size - 1
            progress = curIdx
        }
        val label = TextView(ctx).apply {
            text = "${steps[curIdx]}s"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                label.text = "${steps[p]}s"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                moveDelayMs = (steps[sb.progress] * 1000).toLong()
                savePrefs()
                if (isPlaying) restartAutoPlayLoop()
            }
        })
        AlertDialog.Builder(ctx)
            .setTitle("出招延时")
            .setView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(bar)
            })
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDepthPicker() {
        val steps = intArrayOf(8, 10, 12, 15, 18, 20, 25, 30, 40, 50)
        val curIdx = steps.indexOfFirst { it == searchDepth }.coerceAtLeast(0)

        val ctx = this
        val bar = SeekBar(ctx).apply {
            max = steps.size - 1
            progress = curIdx
        }
        val label = TextView(ctx).apply {
            text = "d${steps[curIdx]}"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                label.text = "d${steps[p]}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                searchDepth = steps[sb.progress]
                savePrefs()
            }
        })
        AlertDialog.Builder(ctx)
            .setTitle("思考层数")
            .setView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(bar)
            })
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAnalysisSnapshot() {
        val v = panelView ?: return
        val lines = buildString {
            append("当前局面: ").append(lastKnownBoard.toFen().substringBeforeLast(' ')).append('\n')
            append("最佳着法: ").append(lastBestMove?.toUci() ?: "—").append('\n')
            append("变招: ").append(lastPv.take(6).joinToString(" ") { it.toUci() }).append('\n')
            append("评分: ").append(lastScoreCp?.let { "%.2f".format(it / 100.0) } ?: "—").append('\n')
            append("深度: d").append(lastDepthShown).append('\n')
            append("用时: ").append(lastTimeMs).append("ms")
        }
        uiScope.launch {
            AlertDialog.Builder(this@AutoPlayService)
                .setTitle("引擎分析")
                .setMessage(lines)
                .setPositiveButton("走这步") { _, _ ->
                    scope.launch(Dispatchers.IO) { triggerEngineMove() }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    // ---------- 引擎链路 ----------

    fun bindGameManager(gm: GameManager) {
        gameManager = gm
        engineManager = getEngineManager(gm)
    }

    private fun getEngineManager(gm: GameManager): UciEngineManager? =
        try {
            val f = GameManager::class.java.getDeclaredField("engineManager")
            f.isAccessible = true
            f.get(gm) as? UciEngineManager
        } catch (e: Exception) { null }

    private fun startEngineObserver() {
        // 监听 engineManager.multiPvResults 变化
        uiScope.launch {
            engineManager?.multiPvResults?.collect { results ->
                val top = results.firstOrNull() ?: run {
                    updatePanelUI(); return@collect
                }
                lastBestMove = Move.fromUci(top.pv.firstOrNull().orEmpty())
                lastScoreCp = top.scoreCp
                lastPv = top.pv.mapNotNull { Move.fromUci(it) }
                lastPvRaw = top.pv
                // 中文招法需要"走子前"的局面作为参考
                lastPvBoard = gameManager?.gameState?.value?.board?.copy()
                lastDepthShown = top.depth
                updateMiniBoard()
                updatePanelUI()
            }
        }
        uiScope.launch {
            engineManager?.engineState?.collect { state ->
                Log.i(TAG, "engine state = $state")
                updatePanelUI()
            }
        }
    }

    private fun startAnalysisLoop() {
        stopAnalysisLoop()
        val runnable = object : Runnable {
            override fun run() {
                // 让 UI 有机会更新
                val fen = gameManager?.gameState?.value?.fen
                    ?: lastKnownBoard.toFen()
                val ucis = gameManager?.gameState?.value?.uciHistory.orEmpty()

                engineManager?.let { em ->
                    if (em.isReady()) {
                        em.setPosition(fen, ucis)
                        em.startContinuousAnalyze(
                            SearchOptions(depth = searchDepth, timeMs = 5000),
                            fen, ucis
                        )
                    }
                }

                miniBoard?.let { mb ->
                    mb.board = gameManager?.gameState?.value?.board
                        ?: ChessBoard().apply { parseFen(fen) }
                    mb.lastMove = gameManager?.gameState?.value?.lastMove
                    mb.bestMove = lastBestMove
                    mb.pvMoves = lastPv.take(3)
                }

                analysisRunnable = this
                mainHandler.postDelayed(this, 2000)
            }
        }
        analysisRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopAnalysisLoop() {
        analysisRunnable?.let { mainHandler.removeCallbacks(it) }
        analysisRunnable = null
        engineManager?.stopContinuousAnalyze()
    }

    private fun toggleAutoPlay() {
        if (isPlaying) stopAutoPlayLoop() else startAutoPlayLoop()
    }

    private fun startAutoPlayLoop() {
        if (!isAutoMode) {
            Toast.makeText(this, "当前是手动模式，请点估值按钮触发走子", Toast.LENGTH_SHORT).show()
            return
        }
        isPlaying = true
        updatePanelUI()
        val runnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                scope.launch(Dispatchers.IO) {
                    try {
                        triggerEngineMove()
                    } catch (e: Exception) {
                        Log.e(TAG, "auto move failed", e)
                    }
                }
                autoPlayRunnable = this
                mainHandler.postDelayed(this, moveDelayMs)
            }
        }
        autoPlayRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopAutoPlayLoop() {
        isPlaying = false
        autoPlayRunnable?.let { mainHandler.removeCallbacks(it) }
        autoPlayRunnable = null
        updatePanelUI()
    }

    private fun restartAutoPlayLoop() {
        if (isPlaying) { stopAutoPlayLoop(); startAutoPlayLoop() }
    }

    /** 核心：让引擎思考一步并把手势打到目标APP */
    private suspend fun triggerEngineMove(): Boolean {
        val em = engineManager ?: run {
            Log.w(TAG, "engineManager not bound")
            runOnUiThread { Toast.makeText(this, "引擎未绑定", Toast.LENGTH_SHORT).show() }
            return false
        }
        if (!em.isReady()) {
            runOnUiThread { Toast.makeText(this, "引擎未就绪", Toast.LENGTH_SHORT).show() }
            return false
        }

        val fen = gameManager?.gameState?.value?.fen ?: lastKnownBoard.toFen()
        val ucis = gameManager?.gameState?.value?.uciHistory.orEmpty()

        // 先走识别：截图 → 识别出目标APP上的实际局面
        runRecognitionLoopIfNeeded()

        em.setPosition(fen, ucis)
        val result = em.search(SearchOptions(
            depth = searchDepth, timeMs = (moveDelayMs * 1.5).toLong().coerceAtLeast(1500)
        ))

        val best = result.bestMove
        val ponder = result.ponder
        val move = Move.fromUci(best) ?: return false

        // 更新本地游戏状态（保持同步）
        runOnUiThread {
            try {
                gameManager?.applyPlayerMove(move)
            } catch (_: Exception) {}
        }

        // 计算屏幕坐标
        val (from, to) = BoardCoordinateMapper.moveToScreen(move)

        // 下发手势
        val ok = GestureManager.get().performChessMove(from, to)
        Log.i(TAG, "triggerEngineMove -> uci=$best swipe from=$from to=$to ok=$ok")

        if (!ok) {
            runOnUiThread {
                Toast.makeText(this, "手势注入失败，请检查连线设置", Toast.LENGTH_SHORT).show()
            }
        }
        return ok
    }

    private fun runRecognitionLoopIfNeeded() {
        // 留给后续：截图 → OpenCV定位 → YOLO检测 → ONNX识别 → 拼FEN
        // 目前让 gameManager 本地状态作为参考
    }

    private fun runOnUiThread(b: () -> Unit) = mainHandler.post(b)

    private fun updateMiniBoard() {
        val mb = miniBoard ?: return
        val curState = gameManager?.gameState?.value
        curState?.let {
            mb.board = it.board
            mb.lastMove = it.lastMove
        }
        // 设置最佳招法 + 后续变招（用于箭头提示）
        mb.bestMove = lastBestMove
        mb.pvMoves = lastPv.take(3)
    }

    // ---------- 通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(
                CHANNEL_ID, "宸风象棋自动下棋",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "自动下棋悬浮窗服务" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("宸风象棋")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_auto_connect)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
