package com.qindachess.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtException
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.qindachess.board.ChessBoard
import com.qindachess.board.Piece
import com.qindachess.board.PieceColor
import com.qindachess.board.PieceType
import com.qindachess.board.Position
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 基于 ONNX Runtime 的象棋图片识别器。
 *
 * 完整推理管线：
 *   截图 → 霍夫直线检测定位棋盘 → 切 90 个 cell → 归一化 → ONNX 推理 → 输出 FEN
 *
 * 模型 assets/chess_piece.onnx：
 *   输入: [1, 3, 48, 48] NCHW float32
 *   输出: [1, 15] softmax probabilities
 *   15 类: EMPTY + 红7 + 黑7
 */
class OnnxChessRecognizer private constructor() {

    companion object {
        private const val TAG = "OnnxChessRec"
        private const val MODEL_ASSET = "chess_piece.onnx"
        private const val INPUT_SIZE = 48
        private const val NUM_CLASSES = 15

        // 15 类 → (PieceColor, PieceType)
        // 0: EMPTY
        // 1..7: RED   帅/仕/相/马/车/炮/兵
        // 8..14: BLACK 将/士/象/马/车/炮/卒
        private val CLASS_MAP: Array<Pair<PieceColor, PieceType>?> = arrayOf(
            null,                                                                       // 0 EMPTY
            PieceColor.RED to PieceType.KING,                                           // 1
            PieceColor.RED to PieceType.ADVISOR,                                        // 2
            PieceColor.RED to PieceType.BISHOP,                                         // 3
            PieceColor.RED to PieceType.KNIGHT,                                         // 4
            PieceColor.RED to PieceType.ROOK,                                           // 5
            PieceColor.RED to PieceType.CANNON,                                         // 6
            PieceColor.RED to PieceType.PAWN,                                           // 7
            PieceColor.BLACK to PieceType.KING,                                         // 8
            PieceColor.BLACK to PieceType.ADVISOR,                                      // 9
            PieceColor.BLACK to PieceType.BISHOP,                                       // 10
            PieceColor.BLACK to PieceType.KNIGHT,                                       // 11
            PieceColor.BLACK to PieceType.ROOK,                                         // 12
            PieceColor.BLACK to PieceType.CANNON,                                       // 13
            PieceColor.BLACK to PieceType.PAWN,                                         // 14
        )

        @Volatile private var instance: OnnxChessRecognizer? = null

        fun get(): OnnxChessRecognizer {
            return instance ?: OnnxChessRecognizer().also { instance = it }
        }
    }

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    val isReady: Boolean get() = loaded && session != null

    fun ensureLoaded(context: android.content.Context): Boolean {
        if (loaded) return true
        try {
            val modelPath = copyModelToCache(context)
            val env = OrtEnvironment.getEnvironment()
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                runCatching { addNnapi() }
            }
            val sess = env.createSession(modelPath, sessionOpts)
            this.env = env
            this.session = sess
            loaded = true
            loadError = null
            Log.i(TAG, "✅ ONNX model loaded: ${sess.inputNames} -> ${sess.outputNames}")
        } catch (e: OrtException) {
            loadError = "ONNX load: ${e.message}"
            Log.e(TAG, "❌ ONNX load failed", e)
        } catch (e: Throwable) {
            loadError = "Model load: ${e.message}"
            Log.e(TAG, "❌ Model load failed", e)
        }
        return loaded
    }

    private fun copyModelToCache(context: android.content.Context): String {
        val outFile = File(context.cacheDir, MODEL_ASSET)
        if (outFile.exists() && outFile.length() > 10_000) {
            return outFile.absolutePath
        }
        context.assets.open(MODEL_ASSET).use { input ->
            FileOutputStream(outFile).use { out -> input.copyTo(out) }
        }
        Log.d(TAG, "Model copied to ${outFile.absolutePath} (${outFile.length()} bytes)")
        return outFile.absolutePath
    }

    fun getLoadError(): String? = loadError

    // ============================================================
    //  公开 API
    // ============================================================

    /** 输入完整截图，返回识别结果。 */
    fun recognize(bitmap: Bitmap, context: android.content.Context): RecognitionResult {
        if (!ensureLoaded(context)) {
            return RecognitionResult(ChessBoard(), 0f, 0, "模型未加载: $loadError")
        }
        val cfg = detectBoardCornersKotlin(bitmap) ?: fallbackGrid(bitmap.width, bitmap.height)
        return classifyAllCells(bitmap, cfg)
    }

    /** 暴露给 ChessRecognizer 的 Kotlin 霍夫定位（返回 null 表示检测不到足够直线） */
    fun detectBoardCornersKotlin(bmp: Bitmap): BoardConfig? {
        val w = bmp.width; val h = bmp.height
        val gray = toGrayFloat(bmp)
        val edges = sobel(gray, w, h)
        val lines = houghLines(edges, w, h, threshold = (w * 0.05).toInt().coerceAtLeast(10))
        val horizontal = lines.filter { abs(it.line.angle) < 15 }
        val vertical = lines.filter { abs(abs(it.line.angle) - 90) < 15 }
        if (horizontal.size < 5 || vertical.size < 5) return null
        val top10H = horizontal.sortedByDescending { it.votes }.take(10)
        val top9V = vertical.sortedByDescending { it.votes }.take(9)
        val ys = top10H.map { it.line.rho }.distinct().sorted()
        val xs = top9V.map { it.line.rho }.distinct().sorted()
        if (ys.size < 2 || xs.size < 2) return null
        val cw = (xs.last() - xs.first()).toFloat() / 8f
        val ch = (ys.last() - ys.first()).toFloat() / 9f
        return BoardConfig(
            topLeftX = xs.first(), topLeftY = ys.first(),
            cellWidth = cw.toInt(), cellHeight = ch.toInt(),
            pieceSize = minOf(cw, ch).toInt(), useDefaultConfig = false
        )
    }

    /** 给定棋盘配置，对 90 个格子跑 ONNX 推理 */
    fun classifyAllCells(bitmap: Bitmap, config: BoardConfig): RecognitionResult {
        return try {
            val board = ChessBoard()
            var detected = 0
            val pieceRadius = minOf(config.cellWidth, config.cellHeight) * 0.4f

            for (row in 0 until 10) {
                for (col in 0 until 9) {
                    val cx = config.topLeftX + col * config.cellWidth + config.cellWidth / 2f
                    val cy = config.topLeftY + row * config.cellHeight + config.cellHeight / 2f
                    val piece = classifyCell(bitmap, cx.toFloat(), cy.toFloat(), pieceRadius)
                    if (piece != null) {
                        board.setPiece(row, col, piece)
                        detected++
                    }
                }
            }
            val conf = detected.toFloat() / 90f
            RecognitionResult(board, conf, detected, "Onnx: $detected/90")
        } catch (e: Throwable) {
            Log.e(TAG, "classifyAllCells 异常", e)
            RecognitionResult(ChessBoard(), 0f, 0, e.message ?: "unknown")
        }
    }

    // ============================================================
    //  核心：对单个格子做 ONNX 推理
    // ============================================================

    private fun classifyCell(
        bitmap: Bitmap, centerX: Float, centerY: Float, radius: Float
    ): Piece? {
        val r = radius.toInt().coerceAtLeast(16)
        val cx = centerX.toInt()
        val cy = centerY.toInt()
        val startX = (cx - r).coerceAtLeast(0)
        val startY = (cy - r).coerceAtLeast(0)
        val endX = (cx + r).coerceAtMost(bitmap.width)
        val endY = (cy + r).coerceAtMost(bitmap.height)
        if (endX - startX < 10 || endY - startY < 10) return null

        val cropped = Bitmap.createBitmap(bitmap, startX, startY, endX - startX, endY - startY)
        val resized = Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)

        // 转换为 [1, 3, 48, 48] float32 归一化 (0~1)
        val floatBuf = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        // ONNX 模型训练时用 BGR 顺序 + ImageNet 均值，这里统一做 RGB/255.0
        for (i in pixels.indices) {
            val p = pixels[i]
            floatBuf.put(Color.red(p) / 255f)
            floatBuf.put(Color.green(p) / 255f)
            floatBuf.put(Color.blue(p) / 255f)
        }
        floatBuf.rewind()

        return try {
            val env = env ?: return null
            val sess = session ?: return null
            val inputName = sess.inputNames.iterator().next()

            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val tensor = OnnxTensor.createTensor(env, floatBuf, shape)

            sess.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0].value as Array<FloatArray>
                val probs = output[0]
                val bestIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
                val bestProb = probs[bestIdx]
                tensor.close()

                // 置信度门槛：< 0.15 视为空
                if (bestIdx == 0 || bestProb < 0.15f) return null

                CLASS_MAP[bestIdx]?.let { (color, type) ->
                    Piece(type, color, Position(-1, -1))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "推理异常: ${e.message}")
            null
        }
    }

    // ============================================================
    //  霍夫直线检测 → 棋盘 9x10 角点
    // ============================================================

    private fun detectBoardCorners(bmp: Bitmap): BoardConfig {
        val w = bmp.width
        val h = bmp.height

        // 灰度
        val gray = toGrayFloat(bmp)

        // Sobel 边缘
        val edges = sobel(gray, w, h)

        val lines: List<HoughResult> = houghLines(edges, w, h,
            threshold = (w * 0.05).toInt().coerceAtLeast(10))

        val horizontal: List<HoughResult> = lines.filter { abs(it.line.angle) < 15 }
            .distinctBy { (it.line.rho / 3).toInt() }
            .sortedBy { it.line.rho }

        val vertical: List<HoughResult> = lines.filter { abs(abs(it.line.angle) - 90) < 15 }
            .distinctBy { (it.line.rho / 3).toInt() }
            .sortedBy { it.line.rho }

        // 如果检测不到足够的直线，fallback 到整图比例
        if (horizontal.size < 5 || vertical.size < 5) {
            Log.w(TAG, "霍夫检测到直线不足 (H=${horizontal.size}, V=${vertical.size})，fallback 到网格切分")
            return fallbackGrid(w, h)
        }

        // 选最长的 10 条水平线 / 9 条垂直线
        val top10H: List<HoughResult> = horizontal.sortedByDescending { it.votes }.take(10)
        val top9V: List<HoughResult> = vertical.sortedByDescending { it.votes }.take(9)

        val ys: List<Int> = top10H.map { it.line.rho }.distinct().sorted()
        val xs: List<Int> = top9V.map { it.line.rho }.distinct().sorted()

        if (ys.size < 2 || xs.size < 2) return fallbackGrid(w, h)

        val topLeftY: Int = ys.first()
        val topLeftX: Int = xs.first()
        val cw: Float = (xs.last() - xs.first()).toFloat() / 8f
        val ch: Float = (ys.last() - ys.first()).toFloat() / 9f

        return BoardConfig(
            topLeftX = topLeftX, topLeftY = topLeftY,
            cellWidth = cw.toInt(), cellHeight = ch.toInt(),
            pieceSize = minOf(cw, ch).toInt(), useDefaultConfig = false
        )
    }

    private fun fallbackGrid(w: Int, h: Int): BoardConfig {
        val leftPad = (w * 0.04).toInt()
        val topPad = (h * 0.05).toInt()
        val cw = (w - 2 * leftPad) / 9
        val ch = (h - 2 * topPad) / 10
        return BoardConfig(
            topLeftX = leftPad, topLeftY = topPad,
            cellWidth = cw, cellHeight = ch,
            pieceSize = minOf(cw, ch), useDefaultConfig = false
        )
    }

    // ---------- 图像处理基础 ----------

    private fun toGrayFloat(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }
        return out
    }

    private fun sobel(gray: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = { dx: Int, dy: Int -> gray[(y + dy) * w + (x + dx)] }
                val gx = -i(-1, -1) + i(1, -1) - 2 * i(-1, 0) + 2 * i(1, 0) - i(-1, 1) + i(1, 1)
                val gy = -i(-1, -1) - 2 * i(0, -1) - i(1, -1) + i(-1, 1) + 2 * i(0, 1) + i(1, 1)
                out[y * w + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    private data class HoughLine(val rho: Int, val angle: Float)
    private data class HoughResult(val line: HoughLine, val votes: Int)

    private fun houghLines(
        edges: FloatArray, w: Int, h: Int, threshold: Int
    ): List<HoughResult> {
        val maxDist = sqrt((w * w + h * h).toDouble()).toInt() + 1
        val rhoSize = maxDist * 2 + 1
        val angleSize = 180
        val acc = IntArray(rhoSize * angleSize)

        var edgesCount = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val mag = edges[y * w + x]
                if (mag < 30f) continue
                edgesCount++
                for (a in 0 until angleSize step 2) {
                    val thetaRad = Math.toRadians(a.toDouble())
                    val rho = (x * kotlin.math.cos(thetaRad) + y * kotlin.math.sin(thetaRad)).toInt() + maxDist
                    if (rho in 0 until rhoSize) acc[rho * angleSize + a]++
                }
            }
        }
        Log.d(TAG, "霍夫: edges=$edgesCount, acc_size=${acc.size}")

        val results = mutableListOf<HoughResult>()
        for (rhoIdx in 0 until rhoSize) {
            for (aIdx in 0 until angleSize step 2) {
                val v = acc[rhoIdx * angleSize + aIdx]
                if (v >= threshold) {
                    val ang = aIdx.toFloat()
                    val rho = rhoIdx - maxDist
                    results.add(HoughResult(HoughLine(rho, ang), v))
                }
            }
        }
        return results
    }

    fun release() {
        runCatching { session?.close() }
        runCatching { env?.close() }
        session = null; env = null; loaded = false
    }
}
