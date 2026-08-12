package com.qindachess.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.qindachess.board.ChessBoard
import com.qindachess.board.Piece
import com.qindachess.board.PieceColor
import com.qindachess.board.PieceType
import com.qindachess.board.Position

data class RecognitionResult(
    val board: ChessBoard,
    val confidence: Float,
    val detectedCount: Int,
    val debugInfo: String = ""
)

data class BoardConfig(
    var topLeftX: Int = 0,
    var topLeftY: Int = 0,
    var cellWidth: Int = 0,
    var cellHeight: Int = 0,
    var pieceSize: Int = 0,
    var useDefaultConfig: Boolean = true
)

/**
 * 棋局识别统一门面 —— 四级策略链：
 *
 *   [1] YOLOv8n 目标检测器（推荐）—— 同时定位+分类，带 NPU 加速
 *        ↓ 不可用/失败
 *   [2] OpenCV 霍夫直线定位 + 单格 ONNX 分类器
 *        ↓ 不可用
 *   [3] Kotlin 原生霍夫直线 + 单格 ONNX 分类器
 *        ↓ 不可用
 *   [4] Fallback 像素采样（只区分红黑，类型按位置猜）
 */
class ChessRecognizer {

    companion object { private const val TAG = "ChessRecognizer" }

    private val fallback = PixelFallbackRecognizer()

    /** 主入口 */
    fun recognize(context: Context, bitmap: Bitmap): RecognitionResult {
        val t0 = System.currentTimeMillis()

        // 策略 [1]: YOLOv8n 目标检测
        val yolo = YoloChessDetector.get()
        if (yolo.ensureLoaded(context)) {
            val boardConfig = tryOpenCVConfig(bitmap) ?: autoGridConfig(bitmap)
            val board = yolo.detectToBoard(bitmap, context, boardConfig)
            val count = board.pieceCount()
            val conf = count.toFloat() / 32f  // 合理棋子数 ~32
            val t1 = System.currentTimeMillis()
            val info = buildString {
                append("YOLOv8n (${if (yolo.usingNpu()) "NPU" else "CPU"}) ")
                append("detected=$count, boardConfig=${boardConfig.cellWidth}x${boardConfig.cellHeight}")
                append(", time=${t1 - t0}ms")
            }
            Log.i(TAG, "✅ $info")
            if (count > 0) return RecognitionResult(board, conf.coerceIn(0f, 1f), count, info)
            Log.w(TAG, "YOLO 没检测到棋子，降级")
        } else {
            Log.w(TAG, "YOLO 不可用: ${yolo.getLoadError()}")
        }

        // 策略 [2/3]: 单格 ONNX 分类器 + 霍夫定位
        val onnx = OnnxChessRecognizer.get()
        if (onnx.ensureLoaded(context)) {
            val boardCfg = tryOpenCVConfig(bitmap)
                ?: onnx.detectBoardCornersKotlin(bitmap)
                ?: autoGridConfig(bitmap)
            val result = onnx.classifyAllCells(bitmap, boardCfg)
            val t1 = System.currentTimeMillis()
            Log.i(TAG, "✅ ONNX classifier: ${result.detectedCount}/90, time=${t1 - t0}ms")
            return result.copy(debugInfo = result.debugInfo + " | OpenCV=${boardCfg.topLeftX != 0}")
        } else {
            Log.w(TAG, "ONNX classifier 不可用: ${onnx.getLoadError()}")
        }

        // 策略 [4]: 像素采样 fallback
        val fallbackCfg = tryOpenCVConfig(bitmap) ?: autoGridConfig(bitmap)
        val result = fallback.recognize(bitmap, fallbackCfg)
        Log.i(TAG, "⚠️ 使用像素采样 fallback: ${result.detectedCount}/90")
        return result
    }

    /** 尝试用 OpenCV 定位棋盘，失败返回 null */
    private fun tryOpenCVConfig(bitmap: Bitmap): BoardConfig? {
        val quad = OpenCVBoardLocator.locateBoard(bitmap) ?: return null
        val xs = quad.bl.x - quad.tl.x
        val ys = quad.br.y - quad.tl.y
        val cw = xs.toInt() / 9; val ch = ys.toInt() / 10
        return BoardConfig(
            topLeftX = quad.tl.x.toInt(), topLeftY = quad.tl.y.toInt(),
            cellWidth = cw, cellHeight = ch,
            pieceSize = minOf(cw, ch), useDefaultConfig = false
        )
    }

    private fun autoGridConfig(bitmap: Bitmap): BoardConfig {
        val w = bitmap.width; val h = bitmap.height
        val lp = (w * 0.04).toInt(); val tp = (h * 0.05).toInt()
        val cw = (w - 2 * lp) / 9; val ch = (h - 2 * tp) / 10
        return BoardConfig(lp, tp, cw, ch, minOf(cw, ch), false)
    }

    @Deprecated("Use recognize(context, bitmap)")
    fun recognizeFromImage(bitmap: Bitmap, config: BoardConfig = BoardConfig()): RecognitionResult {
        return fallback.recognize(bitmap, config)
    }

    fun findBoardCorners(bitmap: Bitmap): BoardConfig = autoGridConfig(bitmap)
}

// ============================================================
//  ChessBoard 扩展：pieceCount() 辅助
// ============================================================
private fun ChessBoard.pieceCount(): Int {
    var c = 0
    for (r in 0 until 10) for (col in 0 until 9)
        if (getPiece(r, col) != null) c++
    return c
}

// ============================================================
//  Fallback：颜色像素采样 + 位置启发
// ============================================================
private class PixelFallbackRecognizer {

    fun recognize(bitmap: Bitmap, config: BoardConfig = BoardConfig()): RecognitionResult {
        val board = ChessBoard()
        var detectedCount = 0

        val bmp = if (bitmap.config != Bitmap.Config.ARGB_8888)
            bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap

        val cfg = if (config.useDefaultConfig) autoDetectConfig(bmp) else config

        for (row in 0 until 10) {
            for (col in 0 until 9) {
                val cx = cfg.topLeftX + col * cfg.cellWidth + cfg.cellWidth / 2
                val cy = cfg.topLeftY + row * cfg.cellHeight + cfg.cellHeight / 2
                val size = (cfg.pieceSize.takeIf { it > 0 }
                    ?: minOf(cfg.cellWidth, cfg.cellHeight)) / 2

                if (cx - size < 0 || cy - size < 0 ||
                    cx + size >= bmp.width || cy + size >= bmp.height) continue

                val piece = classifyPieceAt(bmp, cx, cy, size, row, col)
                if (piece != null) {
                    board.setPiece(row, col, piece)
                    detectedCount++
                }
            }
        }

        val conf = detectedCount.toFloat() / 90f
        return RecognitionResult(board, conf, detectedCount,
            "Fallback: $detectedCount/90 (仅红黑区分)")
    }

    private fun autoDetectConfig(bmp: Bitmap): BoardConfig {
        val w = bmp.width; val h = bmp.height
        val cfg = BoardConfig()
        val lp = (w * 0.04).toInt(); val tp = (h * 0.05).toInt()
        cfg.topLeftX = lp; cfg.topLeftY = tp
        cfg.cellWidth = (w - 2 * lp) / 9
        cfg.cellHeight = (h - 2 * tp) / 10
        cfg.pieceSize = minOf(cfg.cellWidth, cfg.cellHeight) * 3 / 4
        cfg.useDefaultConfig = false
        return cfg
    }

    private fun classifyPieceAt(
        bmp: Bitmap, cx: Int, cy: Int, radius: Int, row: Int, col: Int
    ): Piece? {
        var redSum = 0; var darkSum = 0; var total = 0
        val step = maxOf(1, radius / 8)
        for (dy in -radius..radius step step) {
            for (dx in -radius..radius step step) {
                if (dx * dx + dy * dy > radius * radius) continue
                val x = cx + dx; val y = cy + dy
                if (x < 0 || x >= bmp.width || y < 0 || y >= bmp.height) continue
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > g + 20 && r > b + 20 && r > 100) redSum++
                val darkness = 255 - maxOf(r, g, b)
                if (darkness > 120) darkSum++
                total++
            }
        }
        if (total == 0) return null
        val redRatio = redSum.toFloat() / total
        val darkRatio = darkSum.toFloat() / total
        if (redRatio < 0.03 && darkRatio < 0.05) return null
        val color = if (redRatio >= darkRatio) PieceColor.RED else PieceColor.BLACK
        val type = guessTypeByPosition(row, col)
        return Piece(type, color, Position(-1, -1))
    }

    private fun guessTypeByPosition(row: Int, col: Int): PieceType {
        return when (row) {
            0, 9 -> when (col) {
                0, 8 -> PieceType.ROOK
                1, 7 -> PieceType.KNIGHT
                2, 6 -> PieceType.BISHOP
                3, 5 -> PieceType.ADVISOR
                4 -> PieceType.KING
                else -> PieceType.PAWN
            }
            2, 7 -> if (col % 2 == 0) PieceType.CANNON else PieceType.PAWN
            3, 6 -> if (col % 2 == 0) PieceType.PAWN else PieceType.ROOK
            else -> PieceType.ROOK
        }
    }
}
