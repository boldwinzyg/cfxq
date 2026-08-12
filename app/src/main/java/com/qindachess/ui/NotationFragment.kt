package com.qindachess.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.qindachess.QinDaApp
import com.qindachess.R
import com.qindachess.board.ChessBoard
import com.qindachess.board.Move
import com.qindachess.board.PieceColor
import com.qindachess.board.PieceType
import com.qindachess.record.RecordFile
import com.qindachess.record.RecordManager
import com.qindachess.record.RecordNode

class NotationFragment : Fragment() {

    private var currentRecord: RecordFile? = null
    private var currentNodeId: String? = null

    private lateinit var boardView: ChessBoardView
    private lateinit var moveTreeContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var annotateBar: LinearLayout
    private lateinit var annotateInput: EditText

    private val chineseColsRed = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val chineseColsBlack = arrayOf("９", "８", "７", "６", "５", "４", "３", "２", "１")
    private val pieceNamesRed = mapOf(
        PieceType.KING to "帥", PieceType.ADVISOR to "仕",
        PieceType.BISHOP to "相", PieceType.KNIGHT to "馬",
        PieceType.ROOK to "車", PieceType.CANNON to "砲",
        PieceType.PAWN to "兵"
    )
    private val pieceNamesBlack = mapOf(
        PieceType.KING to "將", PieceType.ADVISOR to "士",
        PieceType.BISHOP to "象", PieceType.KNIGHT to "馬",
        PieceType.ROOK to "車", PieceType.CANNON to "砲",
        PieceType.PAWN to "卒"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_notation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        boardView = view.findViewById(R.id.notationBoardView)
        moveTreeContainer = view.findViewById(R.id.notationMoveTree)
        statusText = view.findViewById(R.id.notationStatus)
        annotateBar = view.findViewById(R.id.notationAnnotateBar)
        annotateInput = view.findViewById(R.id.notationAnnotationInput)

        val app = requireActivity().application as QinDaApp
        boardView.skin = app.themeManager.currentSkin.value
        boardView.pieceStyle = app.themeManager.currentPieceStyle.value

        setupBoardListener()
        setupButtons()
        initNewRecord()
    }

    private fun setupBoardListener() {
        boardView.onMoveListener = { move ->
            handleBoardMove(move)
        }
    }

    private fun handleBoardMove(move: Move) {
        val record = currentRecord ?: return
        val nodeId = currentNodeId ?: return
        val uci = move.toUci()

        val currentNode = record.nodes[nodeId] ?: return
        val existingChild = currentNode.childrenIds.firstOrNull { childId ->
            record.nodes[childId]?.uci == uci
        }

        if (existingChild != null) {
            navigateToNode(existingChild)
            return
        }

        try {
            val updated = RecordManager.appendMoveToBranch(record, nodeId, uci)
            currentRecord = updated
            val newChildId = updated.nodes.values.firstOrNull {
                it.uci == uci && it.parentId == nodeId
            }?.id
            if (newChildId != null) {
                navigateToNode(newChildId)
            } else {
                renderMoveTree()
                updateBoard()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "创建分支失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtons() {
        view?.findViewById<ImageButton>(R.id.notationUndo)?.setOnClickListener { goParent() }
        view?.findViewById<ImageButton>(R.id.notationRedo)?.setOnClickListener { goForward() }
        view?.findViewById<Button>(R.id.notationSave)?.setOnClickListener { showSaveDialog() }
        view?.findViewById<Button>(R.id.notationNewGame)?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("新棋谱")
                .setMessage("确定要开始新棋谱吗？当前未保存的走法将丢失。")
                .setPositiveButton("确定") { _, _ -> initNewRecord() }
                .setNegativeButton("取消", null)
                .show()
        }
        view?.findViewById<Button>(R.id.notationDeleteNode)?.setOnClickListener {
            deleteCurrentNode()
        }
        view?.findViewById<Button>(R.id.notationAnnotateBtn)?.setOnClickListener {
            showAnnotationMenu()
        }
        view?.findViewById<Button>(R.id.notationAnnotateSave)?.setOnClickListener {
            saveAnnotation()
        }
    }

    private fun goParent() {
        val record = currentRecord ?: return
        val nid = currentNodeId ?: return
        val parentId = record.nodes[nid]?.parentId ?: return
        navigateToNode(parentId)
    }

    private fun goForward() {
        val record = currentRecord ?: return
        val nid = currentNodeId ?: return
        val firstChild = record.nodes[nid]?.childrenIds?.firstOrNull() ?: return
        navigateToNode(firstChild)
    }

    private fun deleteCurrentNode() {
        val rec = currentRecord
        val nid = currentNodeId
        if (rec == null || nid == null) {
            Toast.makeText(requireContext(), "没有当前节点", Toast.LENGTH_SHORT).show()
            return
        }
        if (nid == rec.record.rootNodeId) {
            Toast.makeText(requireContext(), "根节点不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("删除节点")
            .setMessage("确定删除当前节点及其所有分支？")
            .setPositiveButton("删除") { _, _ ->
                try {
                    val updated = RecordManager.deleteNode(rec, nid)
                    currentRecord = updated
                    val fallbackId = updated.record.rootNodeId
                    currentNodeId = fallbackId
                    renderMoveTree()
                    updateBoard()
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSaveDialog() {
        val record = currentRecord
        if (record == null) {
            Toast.makeText(requireContext(), "没有棋谱可保存", Toast.LENGTH_SHORT).show()
            return
        }

        val folders = RecordManager.listFolders()
        if (folders.isEmpty()) {
            Toast.makeText(requireContext(), "没有可用文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        val folderNames = folders.map { it.name }.toTypedArray()
        val defaultIdx = folders.indexOfFirst { it.name == "默认收藏" }.coerceAtLeast(0)
        var selectedFolderIdx = defaultIdx

        val input = EditText(requireContext()).apply {
            hint = "棋谱标题"
            setText(record.record.title)
            setPadding(32, 16, 32, 16)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("保存棋谱")
            .setView(input)
            .setSingleChoiceItems(folderNames, selectedFolderIdx) { _, which ->
                selectedFolderIdx = which
            }
            .setPositiveButton("保存") { _, _ ->
                val title = input.text?.toString()?.trim().orEmpty().ifBlank { "未命名棋谱" }
                val folder = folders.getOrNull(selectedFolderIdx) ?: return@setPositiveButton
                try {
                    val updated = currentRecord!!.copy(
                        record = currentRecord!!.record.copy(
                            title = title,
                            folderId = folder.id,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    RecordManager.saveRecord(updated)
                    currentRecord = updated
                    Toast.makeText(requireContext(), "已保存到 ${folder.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAnnotationMenu() {
        val record = currentRecord
        val nid = currentNodeId
        if (record == null || nid == null) {
            Toast.makeText(requireContext(), "没有当前节点可注释", Toast.LENGTH_SHORT).show()
            return
        }
        val node = record.nodes[nid] ?: return
        val hasNote = !node.annotation.isNullOrBlank()

        val ctx = requireContext()
        val options = mutableListOf<String>()
        options += if (hasNote) "修改当前节点注释" else "为当前节点添加注释"
        options += "为某个分支添加注释"
        if (hasNote) options += "删除当前节点注释"

        AlertDialog.Builder(ctx)
            .setTitle("注释管理（每步都可加注释）")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        editAnnotationForNode(nid)
                    }
                    which == 1 -> {
                        pickBranchAndAnnotate()
                    }
                    which == 2 && hasNote -> {
                        AlertDialog.Builder(ctx)
                            .setTitle("删除注释")
                            .setMessage("确定删除当前节点的注释？")
                            .setPositiveButton("删除") { _, _ ->
                                try {
                                    val updated = RecordManager.setAnnotation(record, nid, null)
                                    currentRecord = updated
                                    annotateBar.visibility = View.GONE
                                    renderMoveTree()
                                    updateBoard()
                                    Toast.makeText(ctx, "注释已删除", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickBranchAndAnnotate() {
        val record = currentRecord ?: return
        val nid = currentNodeId ?: return
        val node = record.nodes[nid] ?: return
        val children = node.childrenIds.mapNotNull { record.nodes[it] }

        if (children.isEmpty()) {
            Toast.makeText(requireContext(), "当前节点还没有分支，先走几步再试", Toast.LENGTH_SHORT).show()
            return
        }

        val ucis = RecordManager.uciHistoryUpTo(record, nid)
        val board = ChessBoard().apply { parseFen(record.record.fen) }
        for (u in ucis) Move.fromUci(u)?.let { board.applyMove(it) }

        val items = children.mapIndexed { idx, child ->
            val label = try { uciToChinese(child.uci ?: "", board.toFen()) } catch (e: Exception) { child.uci ?: "" }
            val note = if (!child.annotation.isNullOrBlank()) "  [已有: ${child.annotation}]" else ""
            "${idx + 1}. $label$note"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("选择分支添加注释")
            .setItems(items) { _, which ->
                val targetId = children[which].id
                editAnnotationForNode(targetId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editAnnotationForNode(nodeId: String) {
        val record = currentRecord ?: return
        val node = record.nodes[nodeId] ?: return
        annotateBar.visibility = View.VISIBLE
        annotateInput.setText(node.annotation.orEmpty())
        annotateInput.requestFocus()
        annotateInput.setSelection(annotateInput.text.length)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("编辑注释")
            .setView(annotateBar.parent as? View ?: annotateBar)
            .setPositiveButton("保存") { _, _ ->
                val text = annotateInput.text?.toString().orEmpty().trim()
                try {
                    val updated = RecordManager.setAnnotation(record, nodeId, text.ifBlank { null })
                    currentRecord = updated
                    annotateBar.visibility = View.GONE
                    renderMoveTree()
                    updateBoard()
                    Toast.makeText(requireContext(), if (text.isBlank()) "注释已删除" else "注释已保存", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("删除") { _, _ ->
                try {
                    val updated = RecordManager.setAnnotation(record, nodeId, null)
                    currentRecord = updated
                    annotateBar.visibility = View.GONE
                    renderMoveTree()
                    updateBoard()
                    Toast.makeText(requireContext(), "注释已删除", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { _, _ -> annotateBar.visibility = View.GONE }
            .create()
        dialog.show()
    }

    private fun showAnnotateBar() {
        val record = currentRecord
        val nid = currentNodeId
        if (record == null || nid == null) {
            Toast.makeText(requireContext(), "没有当前节点", Toast.LENGTH_SHORT).show()
            return
        }
        val node = record.nodes[nid] ?: return
        annotateBar.visibility = View.VISIBLE
        annotateInput.setText(node.annotation.orEmpty())
        annotateInput.requestFocus()
    }

    private fun saveAnnotation() {
        val record = currentRecord ?: return
        val nid = currentNodeId ?: return
        val text = annotateInput.text?.toString().orEmpty()
        try {
            val updated = RecordManager.setAnnotation(record, nid, text)
            currentRecord = updated
            annotateBar.visibility = View.GONE
            renderMoveTree()
            updateBoard()
            Toast.makeText(requireContext(), "注释已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "注释保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun loadRecord(file: RecordFile) {
        currentRecord = file
        currentNodeId = file.record.activeNodeId
        renderMoveTree()
        updateBoard()
    }

    fun refreshFromGame() {
        initNewRecord()
    }

    private fun initNewRecord() {
        val app = requireActivity().application as QinDaApp
        val fen = app.gameManager.gameState.value.fen
        val uciHistory = app.gameManager.gameState.value.uciHistory

        val defaultFolder = RecordManager.getDefaultFolder()
        val chessRecord = RecordManager.createRecord(
            folderId = defaultFolder.id,
            title = "未命名棋谱",
            fen = fen,
            rootUciHistory = uciHistory
        )

        val loaded = RecordManager.loadRecord(chessRecord.id)
        currentRecord = loaded
        currentNodeId = loaded?.record?.activeNodeId ?: loaded?.record?.rootNodeId

        renderMoveTree()
        updateBoard()
    }

    private fun updateBoard() {
        val record = currentRecord
        val nid = currentNodeId

        if (record == null || nid == null) {
            statusText.text = "当前: 无棋谱"
            boardView.board = ChessBoard().apply { parseFen(ChessBoard.INITIAL_FEN) }
            boardView.lastMove = null
            return
        }

        val board = ChessBoard().apply { parseFen(record.record.fen) }
        val ucis = RecordManager.uciHistoryUpTo(record, nid)
        var lastMove: Move? = null
        for (uci in ucis) {
            val move = Move.fromUci(uci) ?: continue
            board.applyMove(move)
            lastMove = move
        }

        boardView.board = board
        boardView.lastMove = lastMove
        boardView.clearHighlight()

        val currentNode = record.nodes[nid]
        val path = RecordManager.buildNodePath(record, nid)
        val moveIndex = path.count { it.uci != null }

        val side = if (board.sideToMove == PieceColor.RED) "红方" else "黑方"
        val annotation = currentNode?.annotation
        val moveCount = record.nodes.values.count { it.uci != null }
        val branchCount = currentNode?.childrenIds?.size ?: 0
        statusText.text = buildString {
            append("当前: 第").append(moveIndex).append("手 · ").append(side).append("走")
            if (branchCount > 1) append(" · 共").append(branchCount).append("个分支")
            if (!annotation.isNullOrBlank()) {
                append("  ·  📝 ").append(annotation)
            }
        }

        boardView.branchArrows = buildBranchArrowsForCurrent()
    }

    private fun buildBranchArrowsForCurrent(): List<ChessBoardView.BranchArrowInfo> {
        val record = currentRecord ?: return emptyList()
        val nid = currentNodeId ?: return emptyList()
        val node = record.nodes[nid] ?: return emptyList()

        if (node.childrenIds.isEmpty()) {
            // 当前节点无子节点时，提示用户可以继续走棋
            return emptyList()
        }

        val ucis = RecordManager.uciHistoryUpTo(record, nid)
        val board = ChessBoard().apply { parseFen(record.record.fen) }
        for (uci in ucis) {
            Move.fromUci(uci)?.let { board.applyMove(it) }
        }
        val sideToMove = board.sideToMove

        return node.childrenIds.mapIndexed { idx, childId ->
            val child = record.nodes[childId] ?: return@mapIndexed null
            val uci = child.uci ?: return@mapIndexed null
            val move = Move.fromUci(uci) ?: return@mapIndexed null
            val chinese = try { uciToChinese(uci, board.toFen()) } catch (e: Exception) { uci }

            // 把当前 board 执行一步，得到下一步的 sideToMove，以便生成更准确的中文记谱
            ChessBoard().apply { parseFen(board.toFen()) }.applyMove(move)
            val nextSide = board.sideToMove

            ChessBoardView.BranchArrowInfo(
                move = move,
                label = chinese,
                annotation = child.annotation,
                colorIndex = idx
            )
        }.filterNotNull()
    }

    private fun renderMoveTree() {
        moveTreeContainer.removeAllViews()
        val record = currentRecord
        if (record == null) {
            val empty = TextView(requireContext()).apply {
                text = "暂无棋谱"
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 48, 0, 0)
            }
            moveTreeContainer.addView(empty)
            return
        }

        val rootId = record.record.rootNodeId
        val currentId = currentNodeId

        val row = createNodeRow(
            node = record.nodes[rootId]!!,
            isSelected = currentId == rootId,
            depth = 0
        )
        moveTreeContainer.addView(row)

        renderChildren(record, rootId, 1, currentId)
    }

    private fun renderChildren(
        record: RecordFile,
        parentId: String,
        depth: Int,
        currentId: String?
    ) {
        val parent = record.nodes[parentId] ?: return
        val children = parent.childrenIds
        for ((idx, childId) in children.withIndex()) {
            val child = record.nodes[childId] ?: continue
            val isBranch = children.size > 1 && idx > 0
            val row = createNodeRow(child, currentId == childId, depth, isBranch)
            moveTreeContainer.addView(row)
            renderChildren(record, childId, depth + 1, currentId)
        }
    }

    private fun computeFenBefore(record: RecordFile, parentId: String): String {
        val ucis = RecordManager.uciHistoryUpTo(record, parentId)
        val board = ChessBoard().apply { parseFen(record.record.fen) }
        for (uci in ucis) {
            val move = Move.fromUci(uci) ?: continue
            board.applyMove(move)
        }
        return board.toFen()
    }

    private fun createNodeRow(
        node: RecordNode,
        isSelected: Boolean,
        depth: Int,
        isBranch: Boolean = false
    ): LinearLayout {
        val ctx = requireContext()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            isClickable = true
            isFocusable = true
            val bg = when {
                isSelected -> Color.parseColor("#E3F2FD")
                isBranch -> Color.parseColor("#FFF8E1")
                else -> Color.TRANSPARENT
            }
            setBackgroundColor(bg)
        }

        val mainLine = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val indent = TextView(ctx).apply {
            text = "　".repeat(depth.coerceAtLeast(0))
            textSize = 14f
        }
        mainLine.addView(indent)

        val labelText = if (node.uci == null) {
            "开局"
        } else {
            val fenBefore = currentRecord?.let { rec ->
                node.parentId?.let { pid -> computeFenBefore(rec, pid) }
            }
            val chinese = uciToChinese(node.uci, fenBefore ?: ChessBoard.INITIAL_FEN)
            if (isBranch) "分支: $chinese" else chinese
        }

        val label = TextView(ctx).apply {
            text = labelText
            textSize = 16f
            setTextColor(
                when {
                    isSelected -> Color.parseColor("#1565C0")
                    isBranch -> Color.parseColor("#E65100")
                    else -> Color.parseColor("#37474F")
                }
            )
            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        mainLine.addView(label)

        row.addView(mainLine)

        if (!node.annotation.isNullOrBlank()) {
            val annot = TextView(ctx).apply {
                text = "  ${node.annotation}"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(24, 2, 0, 0)
            }
            row.addView(annot)
        }

        row.setOnClickListener { navigateToNode(node.id) }

        return row
    }

    private fun navigateToNode(nodeId: String) {
        currentNodeId = nodeId
        updateBoard()
        renderMoveTree()

        val scrollView = (moveTreeContainer.parent as? ScrollView) ?: return
        scrollView.post {
            var targetY = 0
            for (i in 0 until moveTreeContainer.childCount) {
                val child = moveTreeContainer.getChildAt(i) as? LinearLayout ?: continue
                val mainLine = child.getChildAt(0) as? LinearLayout ?: continue
                val labelText = mainLine.getChildAt(1) as? TextView ?: continue
                if (labelText.currentTextColor == Color.parseColor("#1565C0")) {
                    targetY = child.top
                    break
                }
            }
            scrollView.smoothScrollTo(0, targetY)
        }
    }

    private fun uciToChinese(uci: String?, fenBefore: String): String {
        if (uci == null) return ""
        val move = Move.fromUci(uci) ?: return uci
        val board = ChessBoard().apply { parseFen(fenBefore) }
        val piece = board.getPiece(move.from.row, move.from.col) ?: return uci

        val isRed = piece.color == PieceColor.RED
        val colLabels = if (isRed) chineseColsRed else chineseColsBlack
        val pieceNames = if (isRed) pieceNamesRed else pieceNamesBlack

        val pieceName = pieceNames[piece.type] ?: return uci

        val isVertical = move.from.col == move.to.col
        val dr = move.to.row - move.from.row

        val direction = when {
            isVertical && dr < 0 -> "進"
            isVertical && dr > 0 -> "退"
            else -> "平"
        }

        val fromColText = colLabels[move.from.col]

        val toText = if (isVertical) {
            if (isRed) (10 - move.to.row).toString() else move.to.row.toString()
        } else {
            colLabels[move.to.col]
        }

        return buildString {
            append(pieceName)
            append(fromColText)
            append(direction)
            append(toText)
        }
    }
}
