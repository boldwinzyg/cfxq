package com.qindachess.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
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
        linePaint.strokeWidth = 1.0f
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
        crossPaint.strokeWidth = 2f

        riverPaint.color = Color.parseColor(skin.riverText)
        riverPaint.textAlign = Paint.Align.CENTER
        riverPaint.isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateDimensions()
    }

    private fun calculateDimensions() {
        val density = resources.displayMetrics.density

        // 棋盘完全填满 View 区域，不留任何内部 padding（细黑边由父 FrameLayout 的 2dp padding 提供）
        // 棋子中心点位于 row=0 / row=9 的格线上 → 棋子圆自然贴到 View 边缘（与目标样式一致）
        val availWidth = (width.toFloat() - paddingLeft - paddingRight).coerceAtLeast(1f)
        val availHeight = (height.toFloat() - paddingTop - paddingBottom).coerceAtLeast(1f)

        // 中国象棋棋盘 9 列 × 10 行：8 个水平间隔，9 个垂直间隔 → 宽高比 = 8 / 9
        val ratioBoard = 8f / 9f
        val ratioSpace = availWidth / availHeight

        if (ratioSpace > ratioBoard) {
            // 容器比棋盘更宽 → 以高度为基准，水平居中
            boardHeight = availHeight
            boardWidth = boardHeight * ratioBoard
        } else {
            // 容器比棋盘更高（或正好）→ 以宽度为基准，垂直居中
            boardWidth = availWidth
            boardHeight = boardWidth / ratioBoard
        }

        cellSize = boardWidth / 8f
        pieceRadius = cellSize * 0.42f

        // 棋盘在 View 内水平/垂直居中（不再上下各加 pieceRadius 余量）
        originX = paddingLeft + (availWidth - boardWidth) / 2f
        originY = paddingTop + (availHeight - boardHeight) / 2f

        textPaint.textSize = cellSize * 0.25f
        redPiecePaint.textSize = pieceRadius * 1.1f
        blackPiecePaint.textSize = pieceRadius * 1.1f
    }

    override fun onDraw(canvas: Canvas) {
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
        val r = 6f
        val borderInset = 3f
        // 用"边框色"画一个略大的圆角矩形
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor(skin.boardBorder)
        }
        canvas.drawRoundRect(
            originX - borderInset, originY - borderInset,
            originX + boardWidth + borderInset, originY + boardHeight + borderInset,
            r, r, borderPaint
        )
        // 内部填"棋盘底色"，与外框紧贴
        canvas.drawRoundRect(
            originX, originY,
            originX + boardWidth, originY + boardHeight,
            r, r, boardPaint
        )
    }

    private fun drawGridLines(canvas: Canvas) {
        // 在画格线之前，先擦除掉所有棋子位置周围的格子线痕迹
        for (row in 0 until 10) {
            for (col in 0 until 9) {
                val piece = board.getPiece(row, col) ?: continue
                val (drawRow, drawCol) = transformPosition(row, col)
                if (!isInBounds(drawRow, drawCol)) continue
                val pt = toScreen(drawRow, drawCol)
                // 用 boardBg 同色画一个比棋子略大的圆，覆盖格子线在棋子区域内的痕迹
                val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.parseColor(skin.boardBg)
                }
                canvas.drawCircle(pt.x, pt.y, pieceRadius + 1.5f, eraser)
            }
        }

        for (row in 0..9) {
            val y = originY + row * cellSize
            canvas.drawLine(originX, y, originX + boardWidth, y, linePaint)
        }
        for (col in 0..8) {
            val x = originX + col * cellSize
            if (col == 0 || col == 8) {
                canvas.drawLine(x, originY, x, originY + boardHeight, linePaint)
            } else {
                canvas.drawLine(x, originY, x, originY + 4 * cellSize, linePaint)
                canvas.drawLine(x, originY + 5 * cellSize, x, originY + boardHeight, linePaint)
            }
        }
    }

    private fun drawPalaceDiagonals(canvas: Canvas) {
        canvas.drawLine(
            originX + 3 * cellSize, originY,
            originX + 5 * cellSize, originY + 2 * cellSize,
            linePaint
        )
        canvas.drawLine(
            originX + 5 * cellSize, originY,
            originX + 3 * cellSize, originY + 2 * cellSize,
            linePaint
        )
        canvas.drawLine(
            originX + 3 * cellSize, originY + 7 * cellSize,
            originX + 5 * cellSize, originY + 9 * cellSize,
            linePaint
        )
        canvas.drawLine(
            originX + 5 * cellSize, originY + 7 * cellSize,
            originX + 3 * cellSize, originY + 9 * cellSize,
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
        val markSize = cellSize * 0.15f
        for ((row, col) in marks) {
            val (drawRow, drawCol) = transformPosition(row, col)
            if (!isInBounds(drawRow, drawCol)) continue
            val cx = originX + drawCol * cellSize
            val cy = originY + drawRow * cellSize

            val onEdgeLeft = drawCol == 0
            val onEdgeRight = drawCol == 8
            val onEdgeTop = drawRow == 0 || drawRow == 9

            if (!onEdgeLeft) {
                canvas.drawLine(cx - cellSize / 2 + 5f, cy - markSize, cx - cellSize / 2 + 5f, cy - 5f, crossPaint)
                canvas.drawLine(cx - cellSize / 2 + 5f, cy - markSize, cx - cellSize / 2 + 5f + markSize, cy - markSize, crossPaint)
            }
            if (!onEdgeRight) {
                canvas.drawLine(cx + cellSize / 2 - 5f, cy - markSize, cx + cellSize / 2 - 5f, cy - 5f, crossPaint)
                canvas.drawLine(cx + cellSize / 2 - 5f - markSize, cy - markSize, cx + cellSize / 2 - 5f, cy - markSize, crossPaint)
            }
            if (!onEdgeTop || (drawRow == 0 || drawRow == 9)) {
                if (drawRow != 0) {
                    canvas.drawLine(cx - markSize, cy - cellSize / 2 + 5f, cx - 5f, cy - cellSize / 2 + 5f, crossPaint)
                    canvas.drawLine(cx - markSize, cy - cellSize / 2 + 5f, cx - markSize, cy - cellSize / 2 + 5f - markSize, crossPaint)
                }
                if (drawRow != 9) {
                    canvas.drawLine(cx - markSize, cy + cellSize / 2 - 5f, cx - 5f, cy + cellSize / 2 - 5f, crossPaint)
                    canvas.drawLine(cx - markSize, cy + cellSize / 2 - 5f, cx - markSize, cy + cellSize / 2 - 5f + markSize, crossPaint)
                }
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
        for (row in 0 until 10) {
            for (col in 0 until 9) {
                val (drawRow, drawCol) = transformPosition(row, col)
                if (!isInBounds(drawRow, drawCol)) continue
                val piece = board.getPiece(row, col) ?: continue

                val pt = toScreen(drawRow, drawCol)
                val isRed = piece.color == PieceColor.RED

                // 1) 棋子背景圆（实心，覆盖格线在棋子区域内的部分，避免"头发丝"格线露出）
                canvas.drawCircle(pt.x, pt.y, pieceRadius, pieceBgPaint)

                // 2) 描边：宽度 1f，位置在 pieceRadius - 0.5f 紧贴圆内
                if (pieceStyle.showBorder) {
                    val borderW = 1.0f
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
                    tp.textSize = pieceRadius * 1.1f
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

        for (col in 0 until 9) {
            val (_, drawCol) = transformPosition(0, col)
            val x = originX + drawCol * cellSize
            val topY = originY - textPaint.textSize
            val bottomY = originY + boardHeight + textPaint.textSize - 4
            canvas.drawText((9 - col).toString(), x, topY, coordPaint)
            canvas.drawText((9 - col).toString(), x, bottomY, coordPaint)
        }
        for (row in 0 until 10) {
            val (drawRow, _) = transformPosition(row, 0)
            val leftX = originX - textPaint.textSize
            val rightX = originX + boardWidth + textPaint.textSize
            val y = originY + drawRow * cellSize + cellSize / 3
            canvas.drawText((10 - row).toString(), leftX, y, coordPaint)
            canvas.drawText((10 - row).toString(), rightX, y, coordPaint)
        }

        val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8D6E63")
            textSize = cellSize * 0.35f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val midY = originY + 4.5f * cellSize
        val midX = originX + boardWidth / 2f
        canvas.drawText("楚 河", midX - cellSize * 2, midY + cellSize * 0.15f, riverPaint)
        canvas.drawText("漢 界", midX + cellSize * 2, midY + cellSize * 0.15f, riverPaint)
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
            originX + drawCol * cellSize,
            originY + drawRow * cellSize
        )
    }

    private fun fromScreen(x: Float, y: Float): Position? {
        val col = ((x - originX) / cellSize + 0.5f).toInt()
        val row = ((y - originY) / cellSize + 0.5f).toInt()
        if (col !in 0..8 || row !in 0..9) return null
        val (origRow, origCol) = transformPosition(row, col)
        if (origRow !in 0..9 || origCol !in 0..8) return null
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

        if (selectedPosition == null) {
            if (piece != null) {
                selectedPosition = pos
                computeValidMoves(pos)
            }
        } else if (selectedPosition == pos) {
            selectedPosition = null
            validMoves.clear()
        } else {
            val move = validMoves.firstOrNull { it.to == pos }
            if (move != null) {
                onMoveListener?.invoke(move)
                selectedPosition = null
                validMoves.clear()
            } else if (piece != null) {
                selectedPosition = pos
                computeValidMoves(pos)
            } else {
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
