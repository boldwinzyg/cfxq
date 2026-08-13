package com.qindachess.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.qindachess.board.*
import com.qindachess.ui.theme.BoardSkin
import com.qindachess.ui.theme.BoardSkins
import com.qindachess.ui.theme.PieceStyle
import com.qindachess.ui.theme.PieceStyles
import kotlin.math.min
import kotlin.math.max

class ChessBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var board: ChessBoard = ChessBoard().apply { parseFen(ChessBoard.INITIAL_FEN) }
        set(value) { field = value; invalidate() }

    var lastMove: Move? = null
        set(value) { field = value; invalidate() }

    var onMoveListener: ((Move) -> Unit)? = null
    var onInvalidMoveListener: ((String) -> Unit)? = null
    var isInteractive: Boolean = true
    var showCoordinates: Boolean = true
    var flipBoard: Boolean = false

    var skin: BoardSkin = BoardSkins.WOOD_CLASSIC
        set(value) { field = value; applySkin(); invalidate() }

    var pieceStyle: PieceStyle = PieceStyles.TRADITIONAL
        set(value) { field = value; invalidate() }

    private var selectedPosition: Position? = null
    private var currentPointerPos: Position? = null

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redPiecePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackPiecePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redPieceBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackPieceBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var pieceRadius: Float = 0f
    private var cellSize: Float = 0f
    private var cellW: Float = 0f   // 单格宽度（横向 = width / 8）
    private var cellH: Float = 0f   // 单格高度（纵向 = height / 9）
    private var padding: Float = 0f
    private var boardWidth: Float = 0f
    private var boardHeight: Float = 0f
    private var originX: Float = 0f
    private var originY: Float = 0f

    private val validMoves: MutableList<Move> = mutableListOf()

    data class HintMove(val move: Move, val scoreCp: Int? = null, val mate: Int? = null, val rank: Int = 0)

    var engineHints: List<HintMove> = emptyList()
        set(value) { field = value; invalidate() }

    data class BranchArrowInfo(
        val move: Move,
        val label: String? = null,
        val annotation: String? = null,
        val colorIndex: Int = 0
    )

    var branchArrows: List<BranchArrowInfo> = emptyList()
        set(value) { field = value; invalidate() }

    init {
        isClickable = true
        isFocusable = true
        applySkin()
    }

    fun applySkin() {
        boardPaint.color = Color.parseColor(skin.boardBg)
        boardPaint.style = Paint.Style.FILL
        boardPaint.isAntiAlias = true

        // 外框直接和填充色融合，不再单独画描边，避免边缘出现"头发丝"
        boardBorderPaint.color = Color.parseColor(skin.boardBg)
        boardBorderPaint.style = Paint.Style.STROKE
        boardBorderPaint.strokeWidth = 0f

        linePaint.color = Color.parseColor(skin.gridLine)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1.4f
        linePaint.isAntiAlias = true

        redPiecePaint.color = Color.parseColor(skin.redPiece)
        redPiecePaint.textAlign = Paint.Align.CENTER
        redPiecePaint.isFakeBoldText = false
        redPiecePaint.isAntiAlias = true

        blackPiecePaint.color = Color.parseColor(skin.blackPiece)
        blackPiecePaint.textAlign = Paint.Align.CENTER
        blackPiecePaint.isFakeBoldText = false
        blackPiecePaint.isAntiAlias = true

        redPieceBorderPaint.color = Color.parseColor(skin.redPieceBorder)
        redPieceBorderPaint.style = Paint.Style.STROKE

        blackPieceBorderPaint.color = Color.parseColor(skin.blackPieceBorder)
        blackPieceBorderPaint.style = Paint.Style.STROKE

        pieceBgPaint.color = Color.parseColor(skin.pieceBg)
        pieceBgPaint.style = Paint.Style.FILL

        pieceInnerPaint.color = Color.parseColor(skin.pieceInner)
        pieceInnerPaint.style = Paint.Style.FILL

        selectedPaint.color = Color.argb(120, 66, 165, 245)
        selectedPaint.style = Paint.Style.FILL

        lastMovePaint.color = Color.argb(80, 255, 235, 59)
        lastMovePaint.style = Paint.Style.FILL

        textPaint.color = Color.parseColor(skin.crossMark)
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER

        hintPaint.color = Color.argb(100, 66, 165, 245)
        hintPaint.style = Paint.Style.FILL

        crossPaint.color = Color.parseColor(skin.crossMark)
        crossPaint.style = Paint.Style.STROKE
        crossPaint.strokeWidth = 1f
        crossPaint.alpha = 128

        riverPaint.color = Color.parseColor(skin.riverText)
        riverPaint.textAlign = Paint.Align.CENTER
        riverPaint.isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateDimensions()
    }

    private fun calculateDimensions() {
        // 完美铺满整个 View 的策略：
        //   - 棋盘是 9 列 × 10 行，横向有 8 段 (col0..col8)，纵向有 9 段 (row0..row9)
        //   - 让最后一颗棋子的右/下边缘与 View 的右/下边缘重合：
        //       2*pr + 8*cellW = width    →  cellW = (width  - 2*pr) / 8
        //       2*pr + 9*cellH = height   →  cellH = (height - 2*pr) / 9
        //   - 棋子半径 pr ≤ min(cellW, cellH)/2 才不会与邻位重叠
        //   - 取 pr = min(cellW, cellH) * 0.45 (留 10% 边距) ，迭代 2-3 次即可稳定
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        var pr = 0f
        var cw = w / 8f
        var ch = h / 9f
        repeat(3) {
            pr = min(cw, ch) * 0.45f
            cw = (w - 2f * pr) / 8f
            ch = (h - 2f * pr) / 9f
        }

        pieceRadius = pr
        cellW = cw
        cellH = ch
        // cellSize 保留为 min 维度，供指示器、十字、坐标等"逻辑单位"用
        cellSize = min(cellW, cellH)
        // boardWidth/boardHeight 用实际的水平/垂直跨度（用于"屏幕宽 = 8 段 + 2 颗棋子半径"）
        boardWidth = 8f * cellW
        boardHeight = 9f * cellH

        // 棋盘原点 = 棋子半径 → 第 0 行/0 列的棋子中心距离 View 左/上边各 pr 像素
        originX = pieceRadius
        originY = pieceRadius

        textPaint.textSize = cellSize * 0.25f
        redPiecePaint.textSize = pieceRadius * 1.05f
        blackPiecePaint.textSize = pieceRadius * 1.05f
    }

    override fun onDraw(canvas: Canvas) {
        if (cellW <= 0f || cellH <= 0f) calculateDimensions()
        super.onDraw(canvas)
        drawBoardBackground(canvas)
        drawGridLines(canvas)
        drawPalaceDiagonals(canvas)
        drawCrossMarks(canvas)
        drawLastMove(canvas)
        drawSelectedHint(canvas)
        drawHints(canvas)
        drawEngineArrows(canvas)
        drawBranchArrows(canvas)
        drawPieces(canvas)
        if (showCoordinates) drawCoordinates(canvas)
    }

    private fun drawBoardBackground(canvas: Canvas) {
        // 整块木质底色铺满整个 View（不局限于 board 范围）
        // 用矩形（不要圆角），让棋子完美触达屏幕四边
        val r = 0f
        canvas.drawRect(
            0f, 0f,
            width.toFloat(), height.toFloat(),
            boardPaint
        )
    }

    private fun drawGridLines(canvas: Canvas) {
        // 横线（10 条，从 row=0 到 row=9）
        for (row in 0..9) {
            val y = originY + row * cellH
            canvas.drawLine(originX, y, originX + 8 * cellW, y, linePaint)
        }
        // 竖线（9 条，从 col=0 到 col=8）
        //   - 两边的 col=0 / col=8 走完整 9 段
        //   - 中间的 col 1..7 在"楚河汉界"处 (row=4..row=5) 断开
        for (col in 0..8) {
            val x = originX + col * cellW
            if (col == 0 || col == 8) {
                canvas.drawLine(x, originY, x, originY + 9 * cellH, linePaint)
            } else {
                canvas.drawLine(x, originY, x, originY + 4 * cellH, linePaint)
                canvas.drawLine(x, originY + 5 * cellH, x, originY + 9 * cellH, linePaint)
            }
        }
    }

    private fun drawPalaceDiagonals(canvas: Canvas) {
        // 上方九宫 (row 0..2, col 3..5)
        canvas.drawLine(
            originX + 3 * cellW, originY,
            originX + 5 * cellW, originY + 2 * cellH,
            linePaint
        )
        canvas.drawLine(
            originX + 5 * cellW, originY,
            originX + 3 * cellW, originY + 2 * cellH,
            linePaint
        )
        // 下方九宫 (row 7..9, col 3..5)
        canvas.drawLine(
            originX + 3 * cellW, originY + 7 * cellH,
            originX + 5 * cellW, originY + 9 * cellH,
            linePaint
        )
        canvas.drawLine(
            originX + 5 * cellW, originY + 7 * cellH,
            originX + 3 * cellW, originY + 9 * cellH,
            linePaint
        )
    }

    private fun drawCrossMarks(canvas: Canvas) {
        val marks = listOf(
            0 to 1, 0 to 7, 2 to 1, 2 to 7,
            3 to 0, 3 to 2, 3 to 4, 3 to 6, 3 to 8,
            6 to 0, 6 to 2, 6 to 4, 6 to 6, 6 to 8,
            7 to 1, 7 to 7, 9 to 1, 9 to 7
        )
        val markSize = cellSize * 0.06f
        for ((row, col) in marks) {
            val (drawRow, drawCol) = transformPosition(row, col)
            if (!isInBounds(drawRow, drawCol)) continue
            val cx = originX + drawCol * cellW
            val cy = originY + drawRow * cellH

            val onEdgeLeft = drawCol == 0
            val onEdgeRight = drawCol == 8
            val onEdgeTop = drawRow == 0 || drawRow == 9

            // 左外侧拐角（不在最左列时画左上拐角）
            if (!onEdgeLeft) {
                canvas.drawLine(cx - cellW / 2 + 5f, cy - markSize, cx - cellW / 2 + 5f, cy - 5f, crossPaint)
                canvas.drawLine(cx - cellW / 2 + 5f, cy - markSize, cx - cellW / 2 + 5f + markSize, cy - markSize, crossPaint)
            }
            // 右外侧拐角（不在最右列时画右上拐角）
            if (!onEdgeRight) {
                canvas.drawLine(cx + cellW / 2 - 5f, cy - markSize, cx + cellW / 2 - 5f, cy - 5f, crossPaint)
                canvas.drawLine(cx + cellW / 2 - 5f - markSize, cy - markSize, cx + cellW / 2 - 5f, cy - markSize, crossPaint)
            }
            // 上/下拐角（用于不是顶/底行时画上下拐角）
            if (drawRow != 0) {
                canvas.drawLine(cx - markSize, cy - cellH / 2 + 5f, cx - 5f, cy - cellH / 2 + 5f, crossPaint)
                canvas.drawLine(cx - markSize, cy - cellH / 2 + 5f, cx - markSize, cy - cellH / 2 + 5f - markSize, crossPaint)
            }
            if (drawRow != 9) {
                canvas.drawLine(cx - markSize, cy + cellH / 2 - 5f, cx - 5f, cy + cellH / 2 - 5f, crossPaint)
                canvas.drawLine(cx - markSize, cy + cellH / 2 - 5f, cx - markSize, cy + cellH / 2 - 5f + markSize, crossPaint)
            }
        }
    }

    private fun drawLastMove(canvas: Canvas) {
        val lm = lastMove ?: return
        val from = toScreen(lm.from.row, lm.from.col)
        val to = toScreen(lm.to.row, lm.to.col)

        canvas.drawCircle(from.x, from.y, pieceRadius * 0.95f, lastMovePaint)
        canvas.drawCircle(to.x, to.y, pieceRadius * 0.95f, lastMovePaint)
    }

    private fun drawSelectedHint(canvas: Canvas) {
        val sel = selectedPosition ?: return
        val pt = toScreen(sel.row, sel.col)
        canvas.drawCircle(pt.x, pt.y, pieceRadius * 0.95f, selectedPaint)
    }

    private fun drawHints(canvas: Canvas) {
        for (move in validMoves) {
            val pt = toScreen(move.to.row, move.to.col)
            val targetPiece = board.getPiece(move.to.row, move.to.col)
            if (targetPiece != null) {
                canvas.drawCircle(pt.x, pt.y, pieceRadius, hintPaint)
            } else {
                canvas.drawCircle(pt.x, pt.y, pieceRadius * 0.3f, hintPaint)
            }
        }
    }

    private fun drawEngineArrows(canvas: Canvas) {
        if (engineHints.isEmpty()) return
        val arrowColors = intArrayOf(
            Color.argb(210, 76, 175, 80),     // 第1名：绿色
            Color.argb(170, 33, 150, 243),     // 第2名：蓝色
        )
        val strokeWidths = floatArrayOf(
            cellSize * 0.18f,
            cellSize * 0.13f,
        )
        val arrowHeadSize = cellSize * 0.38f

        for (hint in engineHints.take(2)) {
            val from = toScreen(hint.move.from.row, hint.move.from.col)
            val to = toScreen(hint.move.to.row, hint.move.to.col)
            val color = arrowColors.getOrElse(hint.rank) { arrowColors.last() }
            val strokeW = strokeWidths.getOrElse(hint.rank) { strokeWidths.last() }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }

            // 起终点离棋子远一点，避免被棋子盖死
            val dirX = to.x - from.x
            val dirY = to.y - from.y
            val len = maxOf(1.0, kotlin.math.hypot(dirX.toDouble(), dirY.toDouble())).toFloat()
            val ux = dirX / len
            val uy = dirY / len
            val startX = from.x + ux * pieceRadius * 0.5f
            val startY = from.y + uy * pieceRadius * 0.5f
            val endX = to.x - ux * pieceRadius * 0.5f
            val endY = to.y - uy * pieceRadius * 0.5f

            if (len < 1f) continue

            // 先画半透明宽底（让箭头更醒目）
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
                style = Paint.Style.STROKE
                strokeWidth = strokeW * 2.2f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(startX, startY, endX, endY, bgPaint)
            canvas.drawLine(startX, startY, endX, endY, paint)

            // 箭头三角
            drawArrowHead(canvas, endX, endY, ux, uy, arrowHeadSize, fillPaint, paint)
        }
    }

    private fun drawBranchArrows(canvas: Canvas) {
        if (branchArrows.isEmpty()) return

        val palette = intArrayOf(
            Color.argb(230, 244, 67, 54),    // 红
            Color.argb(230, 33, 150, 243),   // 蓝
            Color.argb(230, 255, 152, 0),    // 橙
            Color.argb(230, 156, 39, 176),   // 紫
            Color.argb(230, 0, 150, 136),    // 青
            Color.argb(230, 76, 175, 80),    // 绿
            Color.argb(230, 233, 30, 99),    // 粉
            Color.argb(230, 63, 81, 181),    // 靛
        )
        val bgPalette = intArrayOf(
            Color.argb(200, 244, 67, 54),
            Color.argb(200, 33, 150, 243),
            Color.argb(200, 255, 152, 0),
            Color.argb(200, 156, 39, 176),
            Color.argb(200, 0, 150, 136),
            Color.argb(200, 76, 175, 80),
            Color.argb(200, 233, 30, 99),
            Color.argb(200, 63, 81, 181),
        )

        val arrowHeadSize = cellSize * 0.32f

        for ((idx, branch) in branchArrows.withIndex()) {
            val from = toScreen(branch.move.from.row, branch.move.from.col)
            val to = toScreen(branch.move.to.row, branch.move.to.col)

            val dirX = to.x - from.x
            val dirY = to.y - from.y
            val len = maxOf(1.0, kotlin.math.hypot(dirX.toDouble(), dirY.toDouble())).toFloat()
            if (len < 1f) continue
            val ux = dirX / len
            val uy = dirY / len
            val startX = from.x + ux * pieceRadius * 0.5f
            val startY = from.y + uy * pieceRadius * 0.5f
            val endX = to.x - ux * pieceRadius * 0.5f
            val endY = to.y - uy * pieceRadius * 0.5f

            val color = palette.getOrElse(branch.colorIndex) { palette[idx % palette.size] }
            val bgColor = bgPalette.getOrElse(branch.colorIndex) { bgPalette[idx % bgPalette.size] }

            val strokeW = cellSize * 0.14f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
                style = Paint.Style.STROKE
                strokeWidth = strokeW * 2.2f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(startX, startY, endX, endY, bgPaint)
            canvas.drawLine(startX, startY, endX, endY, paint)
            drawArrowHead(canvas, endX, endY, ux, uy, arrowHeadSize, fillPaint, paint)

            // 序号圆圈徽章（贴在箭头中部偏终点处，避开棋子）
            val badgeT = 0.58f
            val badgeX = startX + (endX - startX) * badgeT + (-uy) * cellSize * 0.22f
            val badgeY = startY + (endY - startY) * badgeT + (ux) * cellSize * 0.22f
            val badgeR = cellSize * 0.24f

            val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = bgColor
                style = Paint.Style.FILL
            }
            val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = cellSize * 0.04f
            }
            canvas.drawCircle(badgeX, badgeY, badgeR, badgeFill)
            canvas.drawCircle(badgeX, badgeY, badgeR, badgeStroke)

            val labelText = branch.label ?: (idx + 1).toString()
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = Color.WHITE
                it.textSize = badgeR * 1.35f
                it.textAlign = Paint.Align.CENTER
                it.isFakeBoldText = true
            }
            canvas.drawText(labelText, badgeX, badgeY + lp.textSize / 3f, lp)

            // 注释浮窗（贴着终点的一侧，避免压到数字）
            if (!branch.annotation.isNullOrBlank()) {
                drawAnnotationBubble(canvas, endX, endY, ux, uy, branch.annotation, idx)
            }
        }
    }

    private fun drawAnnotationBubble(
        canvas: Canvas,
        anchorX: Float, anchorY: Float,
        ux: Float, uy: Float,
        text: String,
        idx: Int
    ) {
        val shortText = if (text.length > 10) text.substring(0, 10) + "…" else text
        val bgColor = Color.argb(220, 33, 33, 33)
        val fgColor = Color.WHITE

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            textSize = cellSize * 0.22f
            textAlign = Paint.Align.LEFT
        }
        val metrics = tp.fontMetrics
        val textWidth = tp.measureText(shortText)
        val textHeight = metrics.descent - metrics.ascent
        val padX = cellSize * 0.16f
        val padY = cellSize * 0.12f
        val w = textWidth + padX * 2
        val h = textHeight + padY * 2

        // 箭头方向的法线，交替左右两侧放置
        val sign = if (idx % 2 == 0) 1f else -1f
        val nx = -uy * sign
        val ny = ux * sign
        val offsetDist = cellSize * 0.38f
        val cx = anchorX + nx * offsetDist
        val cy = anchorY + ny * offsetDist

        val bubbleL = cx - w / 2f
        val bubbleT = cy - h / 2f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val path = android.graphics.Path().apply {
            val r = cellSize * 0.1f
            moveTo(bubbleL + r, bubbleT)
            lineTo(bubbleL + w - r, bubbleT)
            quadTo(bubbleL + w, bubbleT, bubbleL + w, bubbleT + r)
            lineTo(bubbleL + w, bubbleT + h - r)
            quadTo(bubbleL + w, bubbleT + h, bubbleL + w - r, bubbleT + h)
            lineTo(bubbleL + r, bubbleT + h)
            quadTo(bubbleL, bubbleT + h, bubbleL, bubbleT + h - r)
            lineTo(bubbleL, bubbleT + r)
            quadTo(bubbleL, bubbleT, bubbleL + r, bubbleT)
            close()
        }
        canvas.drawPath(path, bgPaint)
        canvas.drawPath(path, strokePaint)
        canvas.drawText(shortText, cx - textWidth / 2f, cy + textHeight / 2f - metrics.descent + padY / 2f, tp)
    }

    private fun drawArrowHead(
        canvas: Canvas, tipX: Float, tipY: Float,
        ux: Float, uy: Float, size: Float,
        fillPaint: Paint, strokePaint: Paint
    ) {
        val a1x = tipX - ux * size - uy * size * 0.55f
        val a1y = tipY - uy * size + ux * size * 0.55f
        val a2x = tipX - ux * size + uy * size * 0.55f
        val a2y = tipY - uy * size - ux * size * 0.55f
        val path = android.graphics.Path().apply {
            moveTo(tipX, tipY)
            lineTo(a1x, a1y)
            lineTo(a2x, a2y)
            close()
        }
        canvas.drawPath(path, fillPaint)
    }

    private fun drawPieces(canvas: Canvas) {
        // 棋子背景圆比 pieceRadius 大 2.5f（linePaint.strokeWidth = 1.4f，0.7f 半宽 + 1.8f 余量），
        // 既能完全遮住格线交叉处的痕迹，又不至于在棋子周围形成可见"光晕"。
        val bgRadius = pieceRadius + 2.5f
        for (row in 0 until 10) {
            for (col in 0 until 9) {
                val (drawRow, drawCol) = transformPosition(row, col)
                if (!isInBounds(drawRow, drawCol)) continue
                val piece = board.getPiece(row, col) ?: continue

                val pt = toScreen(drawRow, drawCol)
                val isRed = piece.color == PieceColor.RED

                // 1) 棋子背景圆：覆盖格线交叉位置，避免"毛毛"
                canvas.drawCircle(pt.x, pt.y, bgRadius, pieceBgPaint)

                // 2) 描边：宽度 1.5f，紧贴圆内
                if (pieceStyle.showBorder) {
                    val borderW = 1.5f
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = borderW
                        color = Color.parseColor(if (isRed) skin.redPieceBorder else skin.blackPieceBorder)
                    }
                    canvas.drawCircle(pt.x, pt.y, pieceRadius - borderW * 0.5f, borderPaint)
                }

                // 3) 文字
                if (pieceStyle.showCharacter) {
                    val char = getPieceChar(piece)
                    val tp = if (isRed) redPiecePaint else blackPiecePaint
                    tp.textSize = pieceRadius * 1.05f
                    val fm = tp.fontMetrics
                    val baseline = pt.y - (fm.ascent + fm.descent) / 2f
                    canvas.drawText(char, pt.x, baseline, tp)
                } else {
                    drawIconOnlyPiece(canvas, pt, piece)
                }
            }
        }
    }

    private fun getPieceChar(piece: Piece): String {
        val isRed = piece.color == PieceColor.RED
        return when (piece.type) {
            PieceType.KING -> if (pieceStyle.useTraditional) if (isRed) "帥" else "將" else if (isRed) "帅" else "将"
            PieceType.ADVISOR -> if (pieceStyle.useTraditional) if (isRed) "仕" else "士" else "士"
            PieceType.BISHOP -> if (pieceStyle.useTraditional) if (isRed) "相" else "象" else if (isRed) "相" else "象"
            PieceType.KNIGHT -> if (pieceStyle.useTraditional) "馬" else "马"
            PieceType.ROOK -> if (pieceStyle.useTraditional) "車" else "车"
            PieceType.CANNON -> if (pieceStyle.useTraditional) "砲" else "炮"
            PieceType.PAWN -> if (pieceStyle.useTraditional) if (isRed) "兵" else "卒" else if (isRed) "兵" else "卒"
        }
    }

    private fun drawIconOnlyPiece(canvas: Canvas, pt: android.graphics.PointF, piece: Piece) {
        val isRed = piece.color == PieceColor.RED
        val color = if (isRed) skin.redPiece else skin.blackPiece
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor(color)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor(color)
            alpha = 80
            style = Paint.Style.FILL
        }

        val r = pieceRadius * 0.5f
        when (piece.type) {
            PieceType.KING -> {
                canvas.drawRect(pt.x - r, pt.y - r, pt.x + r, pt.y + r, fillPaint)
                canvas.drawRect(pt.x - r, pt.y - r, pt.x + r, pt.y + r, strokePaint)
            }
            PieceType.ROOK -> {
                canvas.drawRect(pt.x - r * 0.8f, pt.y - r, pt.x + r * 0.8f, pt.y + r, fillPaint)
                canvas.drawRect(pt.x - r * 0.8f, pt.y - r, pt.x + r * 0.8f, pt.y + r, strokePaint)
            }
            PieceType.KNIGHT -> {
                canvas.drawCircle(pt.x, pt.y, r, fillPaint)
                canvas.drawCircle(pt.x, pt.y, r, strokePaint)
            }
            PieceType.CANNON -> {
                canvas.drawCircle(pt.x, pt.y, r, fillPaint)
                canvas.drawCircle(pt.x, pt.y, r, strokePaint)
                canvas.drawCircle(pt.x, pt.y, r * 0.5f, strokePaint)
            }
            PieceType.PAWN -> {
                canvas.drawCircle(pt.x, pt.y, r * 0.7f, fillPaint)
                canvas.drawCircle(pt.x, pt.y, r * 0.7f, strokePaint)
            }
            else -> {
                canvas.drawCircle(pt.x, pt.y, r, fillPaint)
                canvas.drawCircle(pt.x, pt.y, r, strokePaint)
            }
        }
    }

    private fun drawCoordinates(canvas: Canvas) {
        val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#666666")
            textSize = cellSize * 0.28f
            textAlign = Paint.Align.CENTER
        }

        // 顶部 + 底部：棋盘列号（黑方视角下方向相反，flipBoard 时由 transformPosition 翻转）
        for (col in 0 until 9) {
            val (_, drawCol) = transformPosition(0, col)
            val x = originX + drawCol * cellW
            val topY = originY - textPaint.textSize * 0.6f
            val bottomY = originY + 9 * cellH + textPaint.textSize * 0.9f
            canvas.drawText((9 - col).toString(), x, topY, coordPaint)
            canvas.drawText((9 - col).toString(), x, bottomY, coordPaint)
        }
        // 右：棋盘行号（只保留右侧）
        for (row in 0 until 10) {
            val (drawRow, _) = transformPosition(row, 0)
            val rightX = originX + 8 * cellW + textPaint.textSize * 0.8f
            val y = originY + drawRow * cellH + cellSize / 3
            canvas.drawText((10 - row).toString(), rightX, y, coordPaint)
        }

        val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8D6E63")
            textSize = cellSize * 0.35f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val midY = originY + 4.5f * cellH
        val midX = originX + 4 * cellW
        canvas.drawText("楚 河", midX - cellW, midY + cellH * 0.15f, riverPaint)
        canvas.drawText("漢 界", midX + cellW, midY + cellH * 0.15f, riverPaint)
    }

    private fun transformPosition(row: Int, col: Int): Pair<Int, Int> {
        return if (flipBoard) {
            9 - row to 8 - col
        } else {
            row to col
        }
    }

    private fun isInBounds(row: Int, col: Int): Boolean = row in 0..9 && col in 0..8

    private fun toScreen(row: Int, col: Int): PointF {
        val (drawRow, drawCol) = transformPosition(row, col)
        return PointF(
            originX + drawCol * cellW,
            originY + drawRow * cellH
        )
    }

    private fun fromScreen(x: Float, y: Float): Position? {
        if (cellW <= 0f || cellH <= 0f) {
            // 尺寸未初始化，无法定位
            Log.w("ChessBoardView", "fromScreen: cellW/cellH 为 0，尺寸未就绪 (w=$cellW, h=$cellH, width=$width, height=$height)")
            return null
        }
        val drawCol = ((x - originX) / cellW + 0.5f).toInt()
        val drawRow = ((y - originY) / cellH + 0.5f).toInt()
        if (drawCol !in 0..8 || drawRow !in 0..9) return null
        val (origRow, origCol) = transformPosition(drawRow, drawCol)
        if (origRow !in 0..9 || origCol !in 0..8) return null
        Log.d("ChessBoardView", "fromScreen ($x,$y) → pos($origRow,$origCol) draw=($drawRow,$drawCol)")
        return Position(origRow, origCol)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractive) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPointerPos = fromScreen(event.x, event.y)
                handleTouch(currentPointerPos)
            }
            MotionEvent.ACTION_MOVE -> {
                currentPointerPos = fromScreen(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                val pos = fromScreen(event.x, event.y)
                handleTouch(pos)
                currentPointerPos = null
            }
        }
        return true
    }

    private fun handleTouch(pos: Position?) {
        pos ?: run { invalidate(); return }

        val piece = board.getPiece(pos.row, pos.col)
        val currentTurn = board.sideToMove
        Log.d("ChessBoardView", "handleTouch pos=$pos piece=${piece?.type?.name} color=${piece?.color?.name} currentTurn=${currentTurn.name} selected=${selectedPosition}")

        if (selectedPosition == null) {
            if (piece != null && piece.color == currentTurn) {
                // 选中的必须是当前应走方的棋子（避免误选对方棋子又点不到合法目标）
                selectedPosition = pos
                computeValidMoves(pos)
            } else if (piece != null) {
                // 对方棋子：触发 onError 提示（不是你的回合）
                onInvalidMoveListener?.invoke("现在该${if (currentTurn == PieceColor.RED) "红" else "黑"}方走子")
            }
        } else if (selectedPosition == pos) {
            // 再次点击同一颗 → 取消选中
            selectedPosition = null
            validMoves.clear()
        } else {
            val move = validMoves.firstOrNull { it.to == pos }
            if (move != null) {
                onMoveListener?.invoke(move)
                selectedPosition = null
                validMoves.clear()
            } else if (piece != null && piece.color == currentTurn) {
                // 切换选中到另一颗己方棋子
                selectedPosition = pos
                computeValidMoves(pos)
            } else if (piece != null) {
                // 点击对方棋子（不在 validMoves 里）：无效走法
                onInvalidMoveListener?.invoke("此走法不合法")
                selectedPosition = null
                validMoves.clear()
            } else {
                // 空白格且不在 validMoves 里：无效走法
                onInvalidMoveListener?.invoke("此走法不合法")
                selectedPosition = null
                validMoves.clear()
            }
        }
        invalidate()
    }

    private fun computeValidMoves(from: Position) {
        validMoves.clear()
        val piece = board.getPiece(from.row, from.col) ?: return

        val candidates = when (piece.type) {
            PieceType.ROOK -> getRookCandidates(from)
            PieceType.KNIGHT -> getKnightCandidates(from)
            PieceType.CANNON -> getCannonCandidates(from)
            PieceType.KING -> getKingCandidates(from, piece.color)
            PieceType.ADVISOR -> getAdvisorCandidates(from, piece.color)
            PieceType.BISHOP -> getBishopCandidates(from, piece.color)
            PieceType.PAWN -> getPawnCandidates(from, piece.color)
        }

        for (to in candidates) {
            if (!isInBounds(to.row, to.col)) continue
            val target = board.getPiece(to.row, to.col)
            if (target != null && target.color == piece.color) continue
            validMoves.add(Move(from, to))
        }
    }

    private fun getRookCandidates(from: Position): List<Position> {
        val result = mutableListOf<Position>()
        val dirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in dirs) {
            var r = from.row + dr; var c = from.col + dc
            while (isInBounds(r, c)) {
                result.add(Position(r, c))
                if (board.getPiece(r, c) != null) break
                r += dr; c += dc
            }
        }
        return result
    }

    private fun getKnightCandidates(from: Position): List<Position> {
        val result = mutableListOf<Position>()
        val jumps = listOf(
            -2 to -1, -2 to 1, -1 to -2, -1 to 2,
            1 to -2, 1 to 2, 2 to -1, 2 to 1
        )
        for ((dr, dc) in jumps) {
            val r = from.row + dr; val c = from.col + dc
            if (!isInBounds(r, c)) continue
            val legR = from.row + dr / 2; val legC = from.col + dc / 2
            if (board.getPiece(legR, legC) != null) continue
            result.add(Position(r, c))
        }
        return result
    }

    private fun getCannonCandidates(from: Position): List<Position> {
        val result = mutableListOf<Position>()
        val dirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in dirs) {
            var r = from.row + dr; var c = from.col + dc
            var jumped = false
            while (isInBounds(r, c)) {
                val p = board.getPiece(r, c)
                if (!jumped) {
                    result.add(Position(r, c))
                    if (p != null) jumped = true
                } else {
                    if (p != null) {
                        result.add(Position(r, c))
                        break
                    }
                }
                r += dr; c += dc
            }
        }
        return result
    }

    private fun getKingCandidates(from: Position, color: PieceColor): List<Position> {
        val result = mutableListOf<Position>()
        val dirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        for ((dr, dc) in dirs) {
            val r = from.row + dr; val c = from.col + dc
            if (!isInPalace(r, c, color)) continue
            result.add(Position(r, c))
        }
        return result
    }

    private fun getAdvisorCandidates(from: Position, color: PieceColor): List<Position> {
        val result = mutableListOf<Position>()
        val dirs = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        for ((dr, dc) in dirs) {
            val r = from.row + dr; val c = from.col + dc
            if (!isInPalace(r, c, color)) continue
            result.add(Position(r, c))
        }
        return result
    }

    private fun getBishopCandidates(from: Position, color: PieceColor): List<Position> {
        val result = mutableListOf<Position>()
        val jumps = listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2)
        for ((dr, dc) in jumps) {
            val r = from.row + dr; val c = from.col + dc
            if (!isInBounds(r, c)) continue
            val eyeR = from.row + dr / 2; val eyeC = from.col + dc / 2
            if (board.getPiece(eyeR, eyeC) != null) continue
            if (color == PieceColor.RED && r > 4) continue
            if (color == PieceColor.BLACK && r < 5) continue
            result.add(Position(r, c))
        }
        return result
    }

    private fun getPawnCandidates(from: Position, color: PieceColor): List<Position> {
        val result = mutableListOf<Position>()
        val forward = if (color == PieceColor.RED) -1 else 1
        result.add(Position(from.row + forward, from.col))
        val crossedRiver = if (color == PieceColor.RED) from.row <= 4 else from.row >= 5
        if (crossedRiver) {
            result.add(Position(from.row, from.col - 1))
            result.add(Position(from.row, from.col + 1))
        }
        return result
    }

    private fun isInPalace(row: Int, col: Int, color: PieceColor): Boolean {
        if (col !in 3..5) return false
        return if (color == PieceColor.RED) row in 7..9 else row in 0..2
    }

    fun highlightSquare(pos: Position?) {
        selectedPosition = pos
        if (pos != null && board.getPiece(pos.row, pos.col) != null) {
            computeValidMoves(pos)
        } else {
            validMoves.clear()
        }
        invalidate()
    }

    fun clearHighlight() {
        selectedPosition = null
        validMoves.clear()
        invalidate()
    }
}
