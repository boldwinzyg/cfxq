package com.qindachess.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.qindachess.board.ChessBoard
import com.qindachess.board.Move
import com.qindachess.board.PieceColor
import com.qindachess.board.PieceType
import com.qindachess.board.Position
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 独立悬浮小棋盘 —— 精致版。
 *
 * 视觉特色：
 *   - 仿木纹米色棋盘（径向渐变）
 *   - 双线网格，立体感九宫
 *   - 棋子用径向高光仿木雕，阴影投影
 *   - 上一手落点：浅蓝光晕
 *   - 最佳招法：渐变粗箭头 + 端点圆环
 *   - 第二/第三招法：虚线箭头，颜色更淡
 */
class MiniBoardView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    var board: ChessBoard = ChessBoard().apply { parseFen(ChessBoard.INITIAL_FEN) }
        set(v) { field = v; invalidate() }

    var lastMove: Move? = null
        set(v) { field = v; invalidate() }

    var bestMove: Move? = null
        set(v) { field = v; invalidate() }

    var pvMoves: List<Move> = emptyList()
        set(v) { field = v; invalidate() }

    // ---------- 画笔 ----------
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val boardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 120, 76, 36)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 70, 40, 15)
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
    }
    private val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 130, 80, 30)
        style = Paint.Style.FILL
    }
    private val riverText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 110, 60, 25)
        textSize = 12f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SERIF
    }
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val redShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val redRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 240, 240, 240)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val redHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 200, 40, 20)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.SERIF
    }
    private val blackHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 35, 25, 15)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.SERIF
    }
    private val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blackRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 230, 230, 230)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val blackText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 245, 245, 245)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.SERIF
    }

    // 最佳招法箭头（粗实线渐变）
    private val bestArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // 后续 PV 箭头（虚线）
    private val pvArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    // 落点光晕
    private val lastMoveGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 33, 150, 243)
        style = Paint.Style.FILL
    }
    // 端点圆环
    private val endDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 76, 175, 80)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pieceNameRed = mapOf(
        PieceType.KING to "帅", PieceType.ADVISOR to "仕",
        PieceType.BISHOP to "相", PieceType.KNIGHT to "马",
        PieceType.ROOK to "车", PieceType.CANNON to "炮", PieceType.PAWN to "兵"
    )
    private val pieceNameBlack = mapOf(
        PieceType.KING to "将", PieceType.ADVISOR to "士",
        PieceType.BISHOP to "象", PieceType.KNIGHT to "马",
        PieceType.ROOK to "车", PieceType.CANNON to "炮", PieceType.PAWN to "卒"
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (108 * resources.displayMetrics.density).toInt()
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: size
        val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: size
        val s = minOf(w, h).coerceIn(96, 280)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val W = width.toFloat()
        val H = height.toFloat()
        val pad = W * 0.05f
        val innerW = W - 2 * pad
        val innerH = H - 2 * pad
        val cellX = innerW / 8f
        val cellY = innerH / 9f

        // 1. 棋盘木纹背景（径向渐变 + 边框）
        val cx = W / 2f
        val cy = H / 2f
        val maxR = sqrt((W * W + H * H).toDouble()).toFloat() / 2f
        bgPaint.shader = RadialGradient(
            cx, cy, maxR,
            intArrayOf(Color.argb(255, 250, 230, 195), Color.argb(255, 225, 195, 150)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(0f, 0f, W, H, 8f, 8f, bgPaint)
        bgPaint.shader = null

        // 2. 棋盘边框
        canvas.drawRoundRect(pad, pad, W - pad, H - pad, 6f, 6f, boardBorder)

        // 3. 中间楚河汉界
        val riverTop = pad + 4 * cellY
        val riverBot = pad + 5 * cellY
        riverPaint.shader = LinearGradient(
            0f, riverTop, 0f, riverBot,
            Color.argb(80, 200, 160, 110), Color.argb(40, 200, 160, 110),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(pad, riverTop, W - pad, riverBot, riverPaint)
        riverPaint.shader = null
        // 楚河 / 汉界 文字
        val riverFont = Paint(riverText).apply {
            textSize = cellY * 0.55f
        }
        canvas.save()
        canvas.rotate(-90f, pad + (W - 2 * pad) / 4f, (riverTop + riverBot) / 2f)
        canvas.drawText("楚 河", pad + (W - 2 * pad) / 4f, (riverTop + riverBot) / 2f + riverFont.textSize / 3f, riverFont)
        canvas.restore()
        canvas.save()
        canvas.rotate(-90f, pad + 3 * (W - 2 * pad) / 4f, (riverTop + riverBot) / 2f)
        canvas.drawText("汉 界", pad + 3 * (W - 2 * pad) / 4f, (riverTop + riverBot) / 2f + riverFont.textSize / 3f, riverFont)
        canvas.restore()

        // 4. 横线
        for (r in 0..9) {
            val y = pad + r * cellY
            canvas.drawLine(pad, y, W - pad, y, gridPaint)
        }
        // 5. 竖线（中间断）
        for (c in 0..8) {
            val x = pad + c * cellX
            if (c == 0 || c == 8) {
                canvas.drawLine(x, pad, x, H - pad, gridPaint)
            } else {
                canvas.drawLine(x, pad, x, riverTop, gridPaint)
                canvas.drawLine(x, riverBot, x, H - pad, gridPaint)
            }
        }
        // 6. 九宫斜线
        canvas.drawLine(pad + 3 * cellX, pad, pad + 5 * cellX, pad + 2 * cellY, gridPaint)
        canvas.drawLine(pad + 5 * cellX, pad, pad + 3 * cellX, pad + 2 * cellY, gridPaint)
        canvas.drawLine(pad + 3 * cellX, pad + 7 * cellY, pad + 5 * cellX, pad + 9 * cellY, gridPaint)
        canvas.drawLine(pad + 5 * cellX, pad + 7 * cellY, pad + 3 * cellX, pad + 9 * cellY, gridPaint)

        val centerOf: (Int, Int) -> PointF = { r, c ->
            PointF(pad + c * cellX, pad + r * cellY)
        }

        // 7. 上一手落点高亮
        lastMove?.let { m ->
            listOf(m.from, m.to).forEach { p ->
                centerOf(p.row, p.col).let {
                    canvas.drawCircle(it.x, it.y, cellX * 0.32f, lastMoveGlow)
                }
            }
        }

        // 8. PV 后续变招箭头（虚线，2-3手）
        for ((idx, m) in pvMoves.drop(1).take(2).withIndex()) {
            drawArrow(canvas, centerOf(m.from.row, m.from.col),
                centerOf(m.to.row, m.to.col),
                cellX, cellY, idx + 1)
        }

        // 9. 最佳招法箭头
        bestMove?.let { m ->
            drawArrow(canvas, centerOf(m.from.row, m.from.col),
                centerOf(m.to.row, m.to.col),
                cellX, cellY, 0)
        }

        // 10. 棋子
        val pieceR = minOf(cellX, cellY) * 0.42f
        for (r in 0..9) {
            for (c in 0..8) {
                val p = board.getPiece(r, c) ?: continue
                val center = centerOf(r, c)
                val isRed = p.color == PieceColor.RED
                val name = (if (isRed) pieceNameRed else pieceNameBlack)[p.type] ?: "?"

                // 阴影
                canvas.drawCircle(center.x + 1f, center.y + 2f, pieceR, redShadow)

                // 棋子底（径向高光）
                val piecePaint = if (isRed) redFill else blackFill
                piecePaint.shader = RadialGradient(
                    center.x - pieceR * 0.3f, center.y - pieceR * 0.3f, pieceR,
                    if (isRed) intArrayOf(
                        Color.argb(255, 255, 230, 200),
                        Color.argb(255, 220, 100, 60)
                    ) else intArrayOf(
                        Color.argb(255, 200, 200, 200),
                        Color.argb(255, 50, 50, 50)
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(center.x, center.y, pieceR, piecePaint)
                piecePaint.shader = null

                // 描边
                canvas.drawCircle(center.x, center.y, pieceR, if (isRed) redRing else blackRing)

                // 文字
                val tp = if (isRed) redHighlight else blackText
                tp.textSize = pieceR * 1.05f
                val metrics = tp.fontMetrics
                val textH = metrics.descent - metrics.ascent
                canvas.drawText(name, center.x, center.y + textH / 2f - metrics.descent, tp)
            }
        }
    }

    /**
     * 画箭头。
     * tier=0 → 最佳招法（粗实线渐变 + 端点圆环）
     * tier≥1 → 变招（虚线）
     */
    private fun drawArrow(
        canvas: Canvas, from: PointF, to: PointF,
        cellX: Float, cellY: Float, tier: Int
    ) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist < 1f) return
        val angle = atan2(dy, dx).toDouble()
        val cellMin = min(cellX, cellY)
        val startShrink = cellMin * 0.30f
        val endShrink = cellMin * 0.40f
        val sx = from.x + (dx / dist) * startShrink
        val sy = from.y + (dy / dist) * startShrink
        val ex = to.x - (dx / dist) * endShrink
        val ey = to.y - (dy / dist) * endShrink
        val headLen = cellMin * 0.28f
        val headWidth = cellMin * 0.18f

        if (tier == 0) {
            // 最佳招法：渐变粗箭头
            bestArrowPaint.shader = LinearGradient(
                sx, sy, ex, ey,
                Color.argb(230, 255, 200, 60),
                Color.argb(230, 76, 175, 80),
                Shader.TileMode.CLAMP
            )
            val strokeW = cellMin * 0.16f
            val p = Path().apply {
                moveTo(sx, sy)
                lineTo(ex, ey)
            }
            bestArrowPaint.style = Paint.Style.STROKE
            bestArrowPaint.strokeWidth = strokeW
            bestArrowPaint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(p, bestArrowPaint)
            // 端点圆环
            canvas.drawCircle(ex, ey, cellMin * 0.18f, endDot)
            // 三角形箭头
            drawArrowHead(canvas, ex, ey, angle, headLen, headWidth, Color.argb(230, 76, 175, 80))
            bestArrowPaint.shader = null
        } else {
            // 变招：虚线
            val (c1, c2) = when (tier) {
                1 -> Color.argb(180, 33, 150, 243) to Color.argb(180, 100, 181, 246)
                else -> Color.argb(160, 255, 152, 0) to Color.argb(160, 255, 193, 7)
            }
            pvArrowPaint.shader = LinearGradient(sx, sy, ex, ey, c1, c2, Shader.TileMode.CLAMP)
            canvas.drawLine(sx, sy, ex, ey, pvArrowPaint)
            drawArrowHead(canvas, ex, ey, angle, headLen * 0.85f, headWidth * 0.85f,
                if (tier == 1) Color.argb(180, 100, 181, 246) else Color.argb(160, 255, 193, 7))
        }
    }

    private fun drawArrowHead(
        canvas: Canvas, x: Float, y: Float, angle: Double,
        len: Float, w: Float, color: Int
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val a1 = angle + Math.PI - 0.5
        val a2 = angle + Math.PI + 0.5
        val p1x = x + cos(a1) * len
        val p1y = y + sin(a1) * len
        val p2x = x + cos(a2) * (len * 0.6f)
        val p2y = y + sin(a2) * (len * 0.6f)
        val p3x = x + cos(angle + Math.PI) * w
        val p3y = y + sin(angle + Math.PI) * w
        val path = Path().apply {
            moveTo(x, y)
            lineTo(p1x.toFloat(), p1y.toFloat())
            lineTo(p3x.toFloat(), p3y.toFloat())
            lineTo(p2x.toFloat(), p2y.toFloat())
            close()
        }
        canvas.drawPath(path, p)
    }
}
