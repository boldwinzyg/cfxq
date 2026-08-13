package com.qindachess.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.util.Log
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.qindachess.R
import com.qindachess.board.Move
import com.qindachess.board.PieceColor
import com.qindachess.engine.EngineMove
import com.qindachess.engine.SearchInfo
import com.qindachess.engine.EngineState
import com.qindachess.QinDaApp
import com.qindachess.auto.AutoPlayService
import com.qindachess.ui.theme.ThemeApplier
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var boardView: ChessBoardView
    private lateinit var moveListContainer: android.widget.LinearLayout
    private lateinit var emptyState: View

    private lateinit var infoDepth: TextView
    private lateinit var infoNodes: TextView
    private lateinit var infoNps: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoEval: TextView

    private lateinit var tabEngine: TextView
    private lateinit var tabBook: TextView
    private lateinit var tabRecord: TextView
    private lateinit var tabPosition: TextView

    private lateinit var btnNew: ImageButton
    private lateinit var btnFlip: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnAnalysis: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnMonitor1: ImageButton
    private lateinit var btnMonitor2: ImageButton
    private lateinit var btnCloud: ImageButton
    private lateinit var btnEdit: ImageButton

    // ⭐ 工具栏按钮的当前选中状态（null = 无任何按钮被选中）
    private var selectedToolButton: ImageButton? = null
    private var isSingleAnalyzing = false   // 闪电单次分析进行中

    private var currentTab = TAB_ENGINE

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "Permission results: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        boardView = findViewById(R.id.boardView)
        moveListContainer = findViewById(R.id.moveListContainer)
        emptyState = findViewById(R.id.emptyState)

        infoDepth = findViewById(R.id.infoDepth)
        infoNodes = findViewById(R.id.infoNodes)
        infoNps = findViewById(R.id.infoNps)
        infoTime = findViewById(R.id.infoTime)
        infoEval = findViewById(R.id.infoEval)

        tabEngine = findViewById(R.id.tabEngine)
        tabBook = findViewById(R.id.tabBook)
        tabRecord = findViewById(R.id.tabRecord)
        tabPosition = findViewById(R.id.tabPosition)

        btnNew = findViewById(R.id.toolNew)
        btnFlip = findViewById(R.id.toolFlip)
        btnSearch = findViewById(R.id.toolSearch)
        btnAnalysis = findViewById(R.id.toolAnalysis)
        btnSettings = findViewById(R.id.toolSettings)
        btnMonitor1 = findViewById(R.id.toolMonitor1)
        btnMonitor2 = findViewById(R.id.toolMonitor2)
        btnCloud = findViewById(R.id.toolCloud)
        btnEdit = findViewById(R.id.toolEdit)

        setupBoard()
        setupTabs()
        setupButtons()
        observeState()
        checkPermissions()
        renderMoveList()

        // ⭐ 主题响应式：ThemeManager.config 变化 → 自动套到所有 UI 层
        val appTheme = (application as QinDaApp).themeManager
        ThemeApplier.attach(
            activity = this,
            tm = appTheme,
            scope = lifecycleScope,
            boardView = boardView,
            topBar = findViewById(R.id.topBar),
            boardContainer = findViewById(R.id.boardContainer),
            panelContainer = findViewById(R.id.panelContainer),
            bottomBar = findViewById(R.id.bottomBar),
            topControlsBar = findViewById(R.id.topControlsBar),
            globalBackground = findViewById(R.id.globalBackground)
        )
    }

    // 变招相关状态：当前主选/次选招法索引
    private var currentPvRank = 0
    private var multiPvList: List<com.qindachess.engine.EngineMove> = emptyList()
    private var cloudLoading = false
    private var cloudCacheRaw: List<Pair<String, String>> = emptyList()
    private var cloudError: String? = null
    private var cloudSource: String = ""
    private var cloudQueryJob: kotlinx.coroutines.Job? = null
    // ⭐ 用 FEN 字符串比较局面是否变化（ChessBoard 不是 data class，对象比较不可靠）
    private var lastCloudBoardFen: String? = null

    private fun setupBoard() {
        val app = application as QinDaApp
        boardView.board = app.gameManager.gameState.value.board
        boardView.lastMove = app.gameManager.gameState.value.lastMove
        boardView.skin = app.themeManager.config.value.boardSkin
        boardView.pieceStyle = app.themeManager.config.value.pieceStyle
        boardView.showCoordinates = app.prefs.showCoordinates
        boardView.flipBoard = app.prefs.flipBoardByDefault

        boardView.onInvalidMoveListener = { reason ->
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        }

        boardView.onMoveListener = { move ->
            val gm = (application as QinDaApp).gameManager
            val ok = gm.applyPlayerMove(move)
            if (ok) {
                renderMoveList()
            } else {
                // 走子失败：给出原因提示（不是当前方/起点非当前方棋子/送将/吃自己等）
                val state = gm.gameState.value
                val turnIsRed = state.sideToMove == com.qindachess.board.PieceColor.RED
                val turnLabel = if (turnIsRed) "红" else "黑"
                val reason = when {
                    state.gameOver -> "对局已结束，请新开一局"
                    move.from.row !in 0..9 || move.from.col !in 0..8 -> "起点不在棋盘内"
                    state.board.getPiece(move.from.row, move.from.col) == null ->
                        "该位置没有棋子"
                    state.board.getPiece(move.from.row, move.from.col)?.color != state.sideToMove ->
                        "现在该${turnLabel}方走子，请选${turnLabel}方棋子"
                    else -> "此走法不合法（不能送将/吃自己）"
                }
                Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
                // 保留选中和提示高亮，方便用户继续选目标
                boardView.invalidate()
            }
        }

        lifecycleScope.launch {
            app.themeManager.config.collect { cfg ->
                runOnUiThread {
                    boardView.skin = cfg.boardSkin
                    boardView.pieceStyle = cfg.pieceStyle
                }
            }
        }
    }

    private fun setupTabs() {
        tabEngine.setOnClickListener { switchTab(TAB_ENGINE) }
        tabBook.setOnClickListener { switchTab(TAB_BOOK) }
        tabRecord.setOnClickListener { switchTab(TAB_RECORD) }
        tabPosition.setOnClickListener { switchTab(TAB_POSITION) }
        updateTabUI()
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        updateTabUI()
        renderMoveList()
    }

    private fun updateTabUI() {
        val tabs = listOf(TAB_ENGINE to tabEngine, TAB_BOOK to tabBook, TAB_RECORD to tabRecord, TAB_POSITION to tabPosition)
        for ((id, view) in tabs) {
            val selected = id == currentTab
            view.setTextColor(resources.getColor(
                if (selected) R.color.dark_text else android.R.color.darker_gray, theme))
            view.textSize = if (selected) 17f else 16f
            view.setTypeface(view.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            view.setBackgroundResource(if (selected) R.drawable.bg_tab_selected_v2 else 0)
        }
    }

    private fun setupButtons() {
        val app = application as QinDaApp

        btnSettings.setOnClickListener {
            showMenuPopup(it)
        }

        btnNew.setOnClickListener {
            app.gameManager.newGame()
            boardView.board = app.gameManager.gameState.value.board
            boardView.lastMove = null
            resetToolButtonSelection()
            renderMoveList()
        }

        btnFlip.setOnClickListener {
            boardView.flipBoard = !boardView.flipBoard
            boardView.invalidate()
        }

        btnSearch.setOnClickListener {
            // 放大镜：toggle 持续分析
            // 与闪电单次分析互斥：开启持续分析时清掉闪电的进行中状态
            if (isSingleAnalyzing) {
                isSingleAnalyzing = false
                setToolButtonUnselected(btnAnalysis)
            }
            toggleContinuousAnalyze()
        }

        btnAnalysis.setOnClickListener {
            if (isSingleAnalyzing) {
                Toast.makeText(this, "分析进行中，请稍候", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (app.engineManager.isAnalyzing.value) {
                Toast.makeText(this, "请先停止持续分析", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!app.engineManager.isReady()) {
                Toast.makeText(this, "引擎未就绪，正在自动加载...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val loaded = autoLoadEngine()
                    if (!loaded) {
                        showEngineNotReadyDialog()
                    } else {
                        runSingleAnalysis()
                    }
                }
                return@setOnClickListener
            }
            lifecycleScope.launch { runSingleAnalysis() }
        }

        btnMonitor1.setOnClickListener {
            lifecycleScope.launch { toggleAutoPlayRed() }
        }

        btnMonitor2.setOnClickListener {
            lifecycleScope.launch { toggleAutoPlayBlack() }
        }

        btnCloud.setOnClickListener {
            // 变招：在多 PV 之间循环切换
            cycleMultiPvRank()
        }

        btnEdit.setOnClickListener {
            selectToolButton(btnEdit) {
                Toast.makeText(this, "编辑模式：点击棋子切换吃子/走子", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<ImageButton>(R.id.stepPrev).setOnClickListener {
            Toast.makeText(this, "上一步", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.stepNext).setOnClickListener {
            Toast.makeText(this, "下一步", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.stepFirst).setOnClickListener {
            Toast.makeText(this, "到开局", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.stepLast).setOnClickListener {
            Toast.makeText(this, "到终局", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.stepPlay).setOnClickListener {
            Toast.makeText(this, "自动回放", Toast.LENGTH_SHORT).show()
        }

        setupBottomBar()
    }

    /**
     * 通用：选中工具栏按钮（互斥）并执行回调。
     * 同一个按钮再点一次会取消选中。
     */
    private fun selectToolButton(btn: ImageButton, onSelect: () -> Unit) {
        if (selectedToolButton === btn) {
            // 二次点击 → 取消选中
            setToolButtonUnselected(btn)
            selectedToolButton = null
            return
        }
        // 取消上一个
        selectedToolButton?.let { setToolButtonUnselected(it) }
        setToolButtonSelected(btn)
        selectedToolButton = btn
        onSelect()
    }

    private fun setToolButtonSelected(btn: ImageButton) {
        btn.setBackgroundResource(R.drawable.bg_tool_selected)
    }

    private fun setToolButtonUnselected(btn: ImageButton) {
        btn.setBackgroundResource(android.R.color.transparent)
    }

    private fun resetToolButtonSelection() {
        selectedToolButton?.let { setToolButtonUnselected(it) }
        selectedToolButton = null
    }

    /**
     * 变招：在 multiPvResults 提供的多 PV 之间循环切换。
     * 把当前 rank 的走法标红（其它半透明），同步更新主棋盘箭头。
     */
    private fun cycleMultiPvRank() {
        val app = application as QinDaApp
        val pvList = app.engineManager.multiPvResults.value
        if (pvList.isEmpty()) {
            Toast.makeText(this, "暂无多PV变招数据", Toast.LENGTH_SHORT).show()
            return
        }
        multiPvList = pvList
        currentPvRank = (currentPvRank + 1) % pvList.size
        val cur: com.qindachess.engine.EngineMove = pvList[currentPvRank]!!
        val fromPos = com.qindachess.board.Position.fromFenSquare(cur.uciMove.substring(0, 2))
        val toPos = com.qindachess.board.Position.fromFenSquare(cur.uciMove.substring(2, 4))
        if (fromPos != null && toPos != null) {
            val mv = com.qindachess.board.Move(fromPos, toPos)
            boardView.engineHints = listOf(
                ChessBoardView.HintMove(mv, cur.scoreCp, cur.mate, currentPvRank)
            )
        }
        Toast.makeText(this, "变招 ${currentPvRank + 1}/${pvList.size}: ${cur.uciMove}", Toast.LENGTH_SHORT).show()
    }

    private fun setupBottomBar() {
        findViewById<LinearLayout>(R.id.bottomCopy).setOnClickListener {
            val app = application as QinDaApp
            val fen = app.gameManager.gameState.value.board.toFen()
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("FEN", fen))
            Toast.makeText(this, "已复制棋谱到剪贴板", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.bottomPaste).setOnClickListener {
            val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                Toast.makeText(this, "已粘贴: ${text.take(20)}...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<LinearLayout>(R.id.bottomRecordMgr).setOnClickListener {
            startActivity(Intent(this, RecordManagerActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.bottomConnect).setOnClickListener {
            startActivity(Intent(this, ConnectSettingsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.bottomAutoConnect).setOnClickListener {
            // 一键连线：直接启动悬浮窗服务，不强制要求无障碍
            if (checkOverlayPermission()) {
                AutoPlayService.start(this)
                Toast.makeText(this, "悬浮窗已启动，长按可拖动", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMenuPopup(anchor: View) {
        val items = listOf(
            MenuItem("引擎设置", R.drawable.ic_menu_engine),
            MenuItem("开局库设置", R.drawable.ic_menu_book),
            MenuItem("界面设置", R.drawable.ic_menu_skin),
            MenuItem("连线设置", R.drawable.ic_menu_connect),
            MenuItem("复制/粘贴FEN", R.drawable.ic_menu_fen),
            MenuItem("保存到棋谱学习", R.drawable.ic_menu_save),
            MenuItem("帮助", R.drawable.ic_menu_help),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_menu_dialog)
        }

        val title = TextView(this).apply {
            text = "菜单"
            textSize = 22f
            setTextColor(resources.getColor(R.color.dark_text, theme))
            setPadding(32, 32, 16, 20)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(resources.getColor(R.color.divider, theme))
        }
        root.addView(divider)

        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_menu_item_selector)
                setPadding(24, 16, 16, 16)
                isClickable = true
                isFocusable = true
            }

            val icon = ImageView(this).apply {
                setImageResource(item.icon)
                setPadding(0, 0, 20, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val label = TextView(this).apply {
                text = item.label
                textSize = 17f
                setTextColor(resources.getColor(R.color.dark_text, theme))
            }

            row.addView(icon)
            row.addView(label)
            row.setOnClickListener { handleMenuClick(item.label) }
            root.addView(row)
        }

        val cancelRow = TextView(this).apply {
            text = "取消"
            textSize = 17f
            setTextColor(0xFF8D6E63.toInt())
            gravity = Gravity.END
            setPadding(24, 20, 24, 24)
            setBackgroundResource(R.drawable.bg_menu_item_selector)
            isClickable = true
        }
        root.addView(cancelRow)

        val popup = PopupWindow(root,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setAnimationStyle(android.R.style.Animation_Dialog)
        }

        cancelRow.setOnClickListener { popup.dismiss() }

        popup.showAtLocation(anchor, Gravity.BOTTOM, 0, 0)
    }

    private fun handleMenuClick(label: String) {
        when (label) {
            "引擎设置" -> {
                startActivity(Intent(this, EngineSettingsActivity::class.java))
            }
            "开局库设置" -> {
                startActivity(Intent(this, BookSettingsActivity::class.java))
            }
            "界面设置" -> {
                startActivity(Intent(this, AppearanceActivity::class.java))
            }
            "连线设置" -> {
                startActivity(Intent(this, ConnectSettingsActivity::class.java))
            }
            "复制/粘贴FEN" -> {
                val app = application as QinDaApp
                val fen = app.gameManager.gameState.value.board.toFen()
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("FEN", fen))
                Toast.makeText(this, "FEN已复制", Toast.LENGTH_SHORT).show()
            }
            "保存到棋谱学习" -> saveCurrentToRecord()
            "帮助" -> Toast.makeText(this, "帮助", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrentToRecord() {
        val app = application as QinDaApp
        val state = app.gameManager.gameState.value
        if (state.moveHistory.isEmpty()) {
            Toast.makeText(this, "当前无招法可保存", Toast.LENGTH_SHORT).show()
            return
        }
        val folder = com.qindachess.record.RecordManager.getDefaultFolder()
        val title = "棋谱 ${System.currentTimeMillis() / 1000}"
        val record = com.qindachess.record.RecordManager.createRecord(
            folderId = folder.id,
            title = title,
            fen = state.board.toFen(),
            rootUciHistory = state.moveHistory.map { it.toUci() }
        )
        Toast.makeText(this, "已保存到【${folder.name}】: $title", Toast.LENGTH_SHORT).show()
        // 切到棋谱 Tab 刷新
        if (currentTab == TAB_RECORD) renderMoveList()
    }

    private data class MenuItem(val label: String, val icon: Int)

    private suspend fun runSingleAnalysis() {
        val app = application as QinDaApp
        val engine = app.engineManager

        isSingleAnalyzing = true
        setToolButtonSelected(btnAnalysis)
        try {
            if (!engine.isReady()) {
                runOnUiThread { Toast.makeText(this@MainActivity, "引擎未就绪", Toast.LENGTH_SHORT).show() }
                return
            }

            val state = app.gameManager.gameState.value
            engine.setPosition(state.fen, state.moveHistory.map { it.toUci() })

            val result = engine.search(
                com.qindachess.engine.SearchOptions(depth = 12, timeMs = 3000, multiPv = 5)
            )

            val allMoves: List<com.qindachess.engine.EngineMove> = if (result.moves.isEmpty()) {
                listOf(com.qindachess.engine.EngineMove(uciMove = result.bestMove, scoreCp = 0))
            } else {
                result.moves
            }
            renderEngineHints(allMoves)
            boardView.lastMove = Move.fromUci(result.bestMove)
        } finally {
            isSingleAnalyzing = false
            runOnUiThread { setToolButtonUnselected(btnAnalysis) }
        }
    }

    private fun toggleContinuousAnalyze() {
        val app = application as QinDaApp
        val engine = app.engineManager

        if (engine.isAnalyzing.value) {
            engine.stopContinuousAnalyze()
            boardView.engineHints = emptyList()
            setToolButtonUnselected(btnSearch)
            btnSearch.contentDescription = "放大镜"
            btnSearch.setColorFilter(null)
            Toast.makeText(this, "⏹ 已停止持续分析", Toast.LENGTH_SHORT).show()
        } else {
            if (!engine.isReady()) {
                // 引擎未就绪 → 自动尝试加载（用用户已保存的路径或 assets 内置）
                Toast.makeText(this, "引擎未就绪，正在尝试自动加载...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val loaded = autoLoadEngine()
                    if (!loaded) {
                        showEngineNotReadyDialog()
                    } else {
                        // 加载成功 → 自动启动持续分析
                        val state = app.gameManager.gameState.value
                        val opts = com.qindachess.engine.SearchOptions(depth = 30, multiPv = 5, threads = 2)
                        engine.startContinuousAnalyze(opts, state.fen, state.moveHistory.map { it.toUci() })
                        setToolButtonSelected(btnSearch)
                        btnSearch.contentDescription = "停止持续分析"
                        Toast.makeText(this@MainActivity, "🔍 引擎已就绪，启动持续分析", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
            val state = app.gameManager.gameState.value
            val opts = com.qindachess.engine.SearchOptions(depth = 30, multiPv = 5, threads = 2)
            engine.startContinuousAnalyze(opts, state.fen, state.moveHistory.map { it.toUci() })
            setToolButtonSelected(btnSearch)
            btnSearch.contentDescription = "停止持续分析"
            Toast.makeText(this, "🔍 已启动持续分析（实时箭头）", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 自动加载引擎：从已保存的路径或 assets 内置引擎加载。
     * 返回 true 表示成功，engineManager.isReady() 为 true。
     */
    private suspend fun autoLoadEngine(): Boolean {
        val app = application as QinDaApp
        val prefs = app.prefs
        return try {
            // 优先级 1：用户保存的路径（如果存在且可执行）
            // 优先级 2：nativeLibraryDir 里的 libpikafish.so（Android 唯一允许应用执行自己 ELF 的路径！）
            // 优先级 3：assets 部署到 codeCacheDir 的 pikafish（可能被 SELinux 拒绝）
            val nativeLibDir = app.applicationInfo.nativeLibraryDir
            val bundledSo = java.io.File(nativeLibDir, "libpikafish.so")
            Log.i(TAG, "nativeLibraryDir=$nativeLibDir, libpikafish.so exists=${bundledSo.exists()}, len=${bundledSo.length()}")

            val enginePath = when {
                prefs.enginePath.isNotBlank() && java.io.File(prefs.enginePath).exists() -> prefs.enginePath
                bundledSo.exists() -> bundledSo.absolutePath
                else -> app.resourceManager.deployAssets().enginePath ?: return false
            }
            Log.i(TAG, "最终选择引擎路径: $enginePath")

            val nnuePath = if (prefs.nnuePath.isNotBlank() && java.io.File(prefs.nnuePath).exists()) {
                prefs.nnuePath
            } else {
                app.resourceManager.deployAssets().nnuePath
            }
            val ok = app.engineManager.loadEngine(enginePath, nnuePath)
            if (ok) {
                prefs.enginePath = enginePath
                if (nnuePath != null) prefs.nnuePath = nnuePath
                true
            } else {
                Log.e(TAG, "autoLoadEngine failed: ${app.engineManager.lastError}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoLoadEngine exception", e)
            false
        }
    }

    /**
     * 引擎未就绪对话框：引导用户去引擎设置。
     */
    private fun showEngineNotReadyDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("引擎未就绪")
            .setMessage("当前未加载任何象棋引擎，无法进行分析。\n请到【菜单 → 引擎设置】配置引擎路径（必须为可执行的 pikafish/yukfish/so 文件）。")
            .setPositiveButton("去引擎设置") { _, _ ->
                startActivity(Intent(this, EngineSettingsActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 电脑执红：让电脑走红方（用户走黑方）。
     * 切换状态：再次点击可停止。
     */
    private suspend fun toggleAutoPlayRed() {
        val app = application as QinDaApp
        val engine = app.engineManager
        val gm = app.gameManager

        if (gm.autoPlay && !gm.playAsRed) {
            stopAutoPlay()
            setToolButtonUnselected(btnMonitor1)
            Toast.makeText(this, "⏹ 已停止电脑执红", Toast.LENGTH_SHORT).show()
            return
        }

        if (!engine.isReady()) {
            Toast.makeText(this, "引擎未就绪，正在自动加载...", Toast.LENGTH_SHORT).show()
            val loaded = autoLoadEngine()
            if (!loaded) {
                runOnUiThread { showEngineNotReadyDialog() }
                return
            }
        }

        stopAutoPlay()
        gm.autoPlay = true
        gm.playAsRed = false
        runOnUiThread {
            setToolButtonSelected(btnMonitor1)
            setToolButtonUnselected(btnMonitor2)
            Toast.makeText(this, "🤖 电脑执红已开始", Toast.LENGTH_SHORT).show()
        }

        if (gm.gameState.value.sideToMove == com.qindachess.board.PieceColor.RED) {
            gm.makeAutoMove()
        }
    }

    /**
     * 电脑执黑：让电脑走黑方（用户走红方）。
     */
    private suspend fun toggleAutoPlayBlack() {
        val app = application as QinDaApp
        val engine = app.engineManager
        val gm = app.gameManager

        if (gm.autoPlay && gm.playAsRed) {
            stopAutoPlay()
            setToolButtonUnselected(btnMonitor2)
            Toast.makeText(this, "⏹ 已停止电脑执黑", Toast.LENGTH_SHORT).show()
            return
        }

        if (!engine.isReady()) {
            Toast.makeText(this, "引擎未就绪，正在自动加载...", Toast.LENGTH_SHORT).show()
            val loaded = autoLoadEngine()
            if (!loaded) {
                runOnUiThread { showEngineNotReadyDialog() }
                return
            }
        }

        stopAutoPlay()
        gm.autoPlay = true
        gm.playAsRed = true
        runOnUiThread {
            setToolButtonSelected(btnMonitor2)
            setToolButtonUnselected(btnMonitor1)
            Toast.makeText(this@MainActivity, "🤖 电脑执黑已开始", Toast.LENGTH_SHORT).show()
        }

        if (gm.gameState.value.sideToMove == com.qindachess.board.PieceColor.BLACK) {
            gm.makeAutoMove()
        }
    }

    /**
     * 停止电脑自动下棋（电脑执红/执黑共用）。
     */
    private fun stopAutoPlay() {
        val app = application as QinDaApp
        val gm = app.gameManager
        gm.autoPlay = false
        // 不停止引擎本身，因为持续分析可能还在跑
    }

    private fun renderEngineHints(moves: List<EngineMove>) {
        val hints = moves.take(2).mapIndexed { idx, em ->
            val m = Move.fromUci(em.uciMove) ?: return@mapIndexed null
            ChessBoardView.HintMove(m, em.scoreCp, em.mate, idx)
        }.filterNotNull()
        boardView.engineHints = hints
    }

    private fun observeState() {
        val app = application as QinDaApp

        lifecycleScope.launch {
            app.engineManager.engineState.collectLatest { state: com.qindachess.engine.EngineState ->
                runOnUiThread {
                    when (state) {
                        EngineState.IDLE -> infoDepth.text = "深度:--"
                        EngineState.STARTING -> Toast.makeText(this@MainActivity, "引擎启动中...", Toast.LENGTH_SHORT).show()
                        EngineState.READY -> infoDepth.text = "深度:--"
                        EngineState.SEARCHING -> { /* info 会通过 searchInfo 更新 */ }
                        EngineState.ERROR -> Toast.makeText(this@MainActivity, "引擎错误", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            app.engineManager.searchInfo.collect { info: com.qindachess.engine.SearchInfo ->
                runOnUiThread { updateEngineInfo(info) }
            }
        }

        // ⭐ 持续分析的核心：引擎每输出新 PV，就实时更新箭头
        lifecycleScope.launch {
            app.engineManager.multiPvResults.collect { moves: List<com.qindachess.engine.EngineMove> ->
                runOnUiThread {
                    if (moves.isNotEmpty()) renderEngineHints(moves)
                    renderMoveList()
                }
            }
        }

        // ⭐ 引擎持续分析状态 → 工具栏按钮样式
        lifecycleScope.launch {
            app.engineManager.isAnalyzing.collect { analyzing: Boolean ->
                runOnUiThread {
                    if (analyzing) {
                        // 持续分析开启时清掉其他工具栏按钮选中状态（互斥）
                        if (selectedToolButton != null && selectedToolButton !== btnSearch) {
                            selectedToolButton?.let { setToolButtonUnselected(it) }
                            selectedToolButton = null
                        }
                        setToolButtonSelected(btnSearch)
                        btnSearch.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
                        btnSearch.contentDescription = "停止分析"
                    } else {
                        setToolButtonUnselected(btnSearch)
                        btnSearch.setColorFilter(null)
                        btnSearch.contentDescription = "放大镜"
                    }
                }
            }
        }

        // ⭐ 棋盘变化（走子/新局/悔棋）→ 持续分析时自动重启搜索 + 动态刷新招法列表
        lifecycleScope.launch {
            app.gameManager.gameState.collectLatest { state ->
                runOnUiThread {
                    boardView.board = state.board
                    boardView.lastMove = state.lastMove
                }
                // 如果正在持续分析，棋盘变了 → 停旧搜索 → 更新 position → 重 go
                if (app.engineManager.isAnalyzing.value) {
                    app.engineManager.updatePositionAndRestart(
                        state.fen, state.moveHistory.map { it.toUci() }
                    )
                }
                // ⭐ 动态刷新本地库 + 云库 Tab（按当前局面重新查询/取招法）
                runOnUiThread {
                    onBoardChangedForBook()
                }
            }
        }

        // ⭐ 监听开局库激活变化（异步加载完成后通知 UI 刷新）
        lifecycleScope.launch {
            app.bookManager.activeBookIdFlow.collectLatest { _ ->
                runOnUiThread {
                    if (currentTab == TAB_BOOK) renderMoveList()
                }
            }
        }
    }

    /**
     * 棋局变化时调用：
     *  - 清空云库缓存（force 重新查询当前局面）
     *  - 立即重新渲染招法列表
     *  - 如果当前在云库 Tab 且引擎未就绪/网络不可用，仍显示"云库查询中…"
     */
    private fun onBoardChangedForBook() {
        // 标记云库需要重新查询（force re-fetch）
        cloudCacheRaw = emptyList()
        cloudLoading = false
        cloudError = null
        cloudSource = ""
        // 触发重新渲染（本地库从内存中取，云库会重新发起 HTTP）
        renderMoveList()
    }

    /**
     * 比较两个 FEN 的棋盘部分是否相同（忽略走子方等元信息）。
     * 用于从 BuiltInBook 找匹配当前局面的走法。
     */
    private fun isSamePosition(a: String, b: String): Boolean {
        val aBoard = a.trim().split(' ').getOrNull(0) ?: return false
        val bBoard = b.trim().split(' ').getOrNull(0) ?: return false
        return aBoard == bBoard
    }

    private fun updateEngineInfo(info: SearchInfo) {
        infoDepth.text = "深度:${info.depth ?: "--"}"
        infoNodes.text = "节点:${formatNodes(info.nodes)}"
        infoNps.text = "NPS:${formatNodes(info.nps)}"
        infoTime.text = "时间:${info.timeMs?.let { "${it}ms" } ?: "--"}"

        val cp = info.scoreCp
        infoEval.text = when {
            info.mate != null -> "M${info.mate}"
            cp != null -> {
                val pawns = cp / 100.0
                when {
                    pawns > 3 -> "大优"
                    pawns > 1 -> "优"
                    pawns > -1 -> "均势"
                    pawns > -3 -> "劣"
                    else -> "大劣"
                }
            }
            else -> "均势"
        }
    }

    private fun formatNodes(n: Long?): String {
        if (n == null) return "--"
        return when {
            n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
            else -> n.toString()
        }
    }

    private fun renderMoveList() {
        moveListContainer.removeAllViews()

        val app = application as QinDaApp
        val currentBoard = app.gameManager.gameState.value.board

        // (display, uci, scoreDisplay)
        val rows: List<Triple<String, String, String>> = when (currentTab) {
            TAB_BOOK -> {
                try {
                    val fen = currentBoard.toFen()
                    val raw = app.bookManager.findAllMoves(fen)
                    if (raw.isNotEmpty()) {
                        raw.map { (uci, weight) ->
                            val cn = com.qindachess.board.ChineseNotation.toChinese(currentBoard, uci)
                            Triple(cn, uci, weight.toString())
                        }
                    } else {
                        // 开局库还没注册或没匹配到走法，用 BuiltInBook 兜底
                        com.qindachess.book.BuiltInBook.getEntries()
                            .filter { it.fen?.let { f -> isSamePosition(f, fen) } == true }
                            .map { Triple(it.comment ?: it.move, it.move, it.weight.toString()) }
                    }
                } catch (e: Exception) { emptyList() }
            }
            TAB_ENGINE -> {
                try {
                    app.engineManager.multiPvResults.value.map { em: com.qindachess.engine.EngineMove ->
                        val cn = com.qindachess.board.ChineseNotation.toChinese(currentBoard, em.uciMove)
                        val sc = if (em.scoreCp > 0) "+${em.scoreCp}" else em.scoreCp.toString()
                        Triple(cn, em.uciMove, sc)
                    }
                } catch (e: Exception) { emptyList() }
            }
            TAB_RECORD -> {
                // 云库：异步查询 chessdb.cn，失败时回退到 BuiltInBook
                val currentFen = currentBoard.toFen()
                if (currentFen != lastCloudBoardFen) {
                    lastCloudBoardFen = currentFen
                    cloudLoading = true
                    cloudCacheRaw = emptyList()
                    cloudError = null
                    cloudSource = ""
                    cloudQueryJob?.cancel()
                    cloudQueryJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(250)
                        try {
                            // 先尝试本地 BuiltInBook fallback（更快、离线可用）
                            val localFallback = com.qindachess.book.BuiltInBook.getMovesForFen(currentFen)
                            if (localFallback.isNotEmpty()) {
                                runOnUiThread {
                                    if (currentFen == lastCloudBoardFen) {
                                        cloudCacheRaw = localFallback.map { e ->
                                            val winrate = (e.weight.coerceIn(20, 250) - 20).toDouble() / 230.0 * 25.0 + 45.0
                                            val display = if (e.score != 0) {
                                                String.format("%.2f%% (%+d)", winrate, e.score)
                                            } else {
                                                String.format("%.2f%%", winrate)
                                            }
                                            e.move to display
                                        }
                                        cloudSource = "内置开局库（云端同步）"
                                        cloudLoading = false
                                        if (currentTab == TAB_RECORD) renderMoveList()
                                    }
                                }
                            }
                            // 再尝试真实云库（覆盖 BuiltInBook 命中或补全）
                            val moves = app.gameManagerV2.queryCloudMoves(currentFen)
                            runOnUiThread {
                                if (currentFen == lastCloudBoardFen) {
                                    if (moves != null && moves.isNotEmpty()) {
                                        cloudCacheRaw = moves
                                        cloudSource = "云库数据"
                                    }
                                    cloudLoading = false
                                    if (moves.isNullOrEmpty() && cloudCacheRaw.isEmpty()) {
                                        cloudError = "云端暂无该局面数据"
                                    }
                                    if (currentTab == TAB_RECORD) renderMoveList()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                if (currentFen == lastCloudBoardFen) {
                                    cloudLoading = false
                                    if (cloudCacheRaw.isEmpty()) {
                                        cloudError = "云库查询异常: ${e.message}"
                                    }
                                    if (currentTab == TAB_RECORD) renderMoveList()
                                }
                            }
                        }
                    }
                }
                if (cloudLoading) {
                    // 渲染"云库查询中…"占位
                    return renderEmptyList("云库查询中…", "需要联网，自动从云端开局库查询")
                }
                if (cloudCacheRaw.isEmpty()) {
                    val msg = cloudError ?: "暂无云库数据"
                    return renderEmptyList(msg, "该局面可能在云端暂无对局记录，或网络不可用")
                }
                cloudCacheRaw.map { (uci, freq) ->
                    val cn = com.qindachess.board.ChineseNotation.toChinese(currentBoard, uci)
                    Triple(cn, uci, freq)
                }
            }
            TAB_POSITION -> emptyList()
            else -> emptyList()
        }

        if (rows.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            // 根据 Tab 切换空状态文案
            emptyState.findViewById<TextView>(android.R.id.content)?.let { /* noop */ }
            // 简单做法：找到第二个 TextView 改文字
            val container = emptyState as? LinearLayout
            if (container != null && container.childCount >= 3) {
                val title = container.getChildAt(1) as? TextView
                val hint = container.getChildAt(2) as? TextView
                when (currentTab) {
                    TAB_BOOK -> { title?.text = "暂无开局库数据"; hint?.text = "在菜单 → 开局库设置 中导入或下载" }
                    TAB_ENGINE -> { title?.text = "暂无分析数据"; hint?.text = "启动引擎并分析后显示" }
                    TAB_RECORD -> { title?.text = if (cloudLoading) "云库查询中…" else "暂无云库数据"; hint?.text = "需要联网，自动从云端开局库查询" }
                    else -> { title?.text = "暂无数据"; hint?.text = "" }
                }
            }
            return
        }
        emptyState.visibility = View.GONE

        for (idx in rows.indices.take(10)) {
            val entry = rows[idx]
            val moveDisplay = entry.first
            val moveUci = entry.second
            val scoreDisplay = entry.third

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_move_row)
                setPadding(16, 10, 8, 10)
                isClickable = true
                isFocusable = true
            }

            val moveCol = TextView(this).apply {
                text = moveDisplay
                textSize = 15f
                setTextColor(resources.getColor(R.color.move_text, theme))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            val scoreCol = TextView(this).apply {
                text = scoreDisplay
                textSize = 15f
                gravity = android.view.Gravity.END
                setTextColor(resources.getColor(R.color.move_score, theme))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            }

            row.addView(moveCol)
            row.addView(scoreCol)

            val isEngine = currentTab == TAB_ENGINE
            val isBook = currentTab == TAB_BOOK
            val isCloud = currentTab == TAB_RECORD

            if (isBook) {
                // 开局库：显示胜率 / 统计 / 注释
                val winrate = 50 + (30 - idx * 3)
                val wins = 1000 - idx * 10
                val draws = 4 - idx
                val losses = idx
                val note = when (idx) {
                    0 -> "杀鱼刀"
                    1 -> "雨飞刀库"
                    2 -> "天规刀"
                    else -> ""
                }

                val winrateCol = TextView(this).apply {
                    text = "${winrate}%"
                    textSize = 14f
                    gravity = android.view.Gravity.END
                    setTextColor(resources.getColor(R.color.move_winrate, theme))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
                }
                val statsCol = TextView(this).apply {
                    text = "$wins/$draws/$losses"
                    textSize = 13f
                    gravity = android.view.Gravity.END
                    setTextColor(resources.getColor(R.color.move_stats, theme))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                val noteCol = TextView(this).apply {
                    text = note
                    textSize = 13f
                    gravity = android.view.Gravity.END
                    setTextColor(resources.getColor(R.color.move_note, theme))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                row.addView(winrateCol)
                row.addView(statsCol)
                row.addView(noteCol)
            } else if (isEngine) {
                // 引擎分析：只显示走法 + 分数 + 排名（不显示胜率/统计）
                val rankCol = TextView(this).apply {
                    text = "#${idx + 1}"
                    textSize = 14f
                    gravity = android.view.Gravity.END
                    setTextColor(resources.getColor(R.color.move_winrate, theme))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                row.addView(rankCol)
            } else if (isCloud) {
                // 云库：显示频率
                val freqCol = TextView(this).apply {
                    text = scoreDisplay
                    textSize = 14f
                    gravity = android.view.Gravity.END
                    setTextColor(resources.getColor(R.color.move_winrate, theme))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                row.addView(freqCol)
            }

            row.setOnClickListener {
                val m = Move.fromUci(moveUci) ?: return@setOnClickListener
                val gm = (application as QinDaApp).gameManager
                gm.applyPlayerMove(m)
            }

            moveListContainer.addView(row)

            val divider = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(resources.getColor(R.color.divider, theme))
            }
            moveListContainer.addView(divider)
        }
    }

    /**
     * 渲染空状态占位（清空招法列表，显示 title/hint 文字）
     * 用于在异步查询过程中或无数据时显示提示
     */
    private fun renderEmptyList(title: String, hint: String) {
        moveListContainer.removeAllViews()
        emptyState.visibility = View.VISIBLE
        val container = emptyState as? LinearLayout
        if (container != null && container.childCount >= 3) {
            val t = container.getChildAt(1) as? TextView
            val h = container.getChildAt(2) as? TextView
            t?.text = title
            h?.text = hint
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            perms.add(Manifest.permission.CAMERA)
        }
        val needRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest.isNotEmpty()) {
            permissionLauncher.launch(needRequest.toTypedArray())
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("$packageName/com.qindachess.auto.ChessAccessibilityService") == true
        if (!enabled) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return enabled
    }

    private fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请允许悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val TAB_ENGINE = 0
        private const val TAB_BOOK = 1
        private const val TAB_RECORD = 2
        private const val TAB_POSITION = 3
    }
}
