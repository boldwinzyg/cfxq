package com.qindachess.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.OrtException
import android.content.Context
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
import kotlin.math.exp
import kotlin.math.max

/**
 * YOLOv8 风格象棋目标检测器。
 *
 * 输入: [1, 3, 416, 416] NCHW float32
 * 输出: [1, 19, 169]   (4 bbox + 15 类置信度, 13×13 单尺度)
 * 后处理: sigmoid 解码 → 置信度过滤 → NMS → 映射回原图坐标 → 输出 Piece 列表
 *
 * 15 类: EMPTY(0) + RED×7(1-7) + BLACK×7(8-14)
 */
class YoloChessDetector private constructor() {

    companion object {
        private const val TAG = "YoloChess"
        private const val MODEL_ASSET = "yolo_chess.onnx"
        private const val INPUT_SIZE = 416
        private const val NUM_CLASSES = 15
        private const val CONF_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
        private const val GRID = 13

        private val CLASS_MAP: Array<Pair<PieceColor, PieceType>?> = arrayOf(
            null,
            PieceColor.RED    to PieceType.KING,     // 1
            PieceColor.RED    to PieceType.ADVISOR,  // 2
            PieceColor.RED    to PieceType.BISHOP,   // 3
            PieceColor.RED    to PieceType.KNIGHT,   // 4
            PieceColor.RED    to PieceType.ROOK,     // 5
            PieceColor.RED    to PieceType.CANNON,   // 6
            PieceColor.RED    to PieceType.PAWN,     // 7
            PieceColor.BLACK  to PieceType.KING,     // 8
            PieceColor.BLACK  to PieceType.ADVISOR,  // 9
            PieceColor.BLACK  to PieceType.BISHOP,   // 10
            PieceColor.BLACK  to PieceType.KNIGHT,   // 11
            PieceColor.BLACK  to PieceType.ROOK,     // 12
            PieceColor.BLACK  to PieceType.CANNON,   // 13
            PieceColor.BLACK  to PieceType.PAWN,     // 14
        )

        @Volatile private var instance: YoloChessDetector? = null

        fun get(): YoloChessDetector = instance ?: YoloChessDetector().also { instance = it }
    }

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var ready = false
    @Volatile private var loadError: String? = null
    @Volatile private var useNnapi = false

    val isReady: Boolean get() = ready && session != null
    fun getLoadError(): String? = loadError

    /**
     * 加载 YOLO 模型。尝试启用 NNAPI NPU 加速。
     */
    fun ensureLoaded(context: android.content.Context): Boolean {
        if (ready) return true
        try {
            val modelPath = copyModelToCache(context)
            val env = OrtEnvironment.getEnvironment()

            // 尝试 NNAPI EP（Android 8.1+）
            val sessionOpts = SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
            }
            val epList = mutableListOf<String>()
            runCatching {
                sessionOpts.addNnapi()
                epList.add("NNAPI-NPU")
            }.onFailure {
                Log.w(TAG, "NNAPI 不可用（将退回 CPU）: ${it.message}")
            }

            val sess = env.createSession(modelPath, sessionOpts)
            this.env = env
            this.session = sess
            ready = true
            useNnapi = epList.isNotEmpty()
            Log.i(TAG, "✅ YOLO loaded. EP=${epList.ifEmpty { "CPU" }} inputs=${sess.inputNames}")
        } catch (e: OrtException) {
            loadError = "ONNX: ${e.message}"
            Log.e(TAG, "❌ ONNX load failed", e)
        } catch (e: Throwable) {
            loadError = "Load: ${e.message}"
            Log.e(TAG, "❌ Load failed", e)
        }
        return ready
    }

    private fun copyModelToCache(context: Context): String {
        val outFile = File(context.cacheDir, MODEL_ASSET)
        if (outFile.exists() && outFile.length() > 500_000) {
            return outFile.absolutePath
        }
        context.assets.open(MODEL_ASSET).use { input ->
            FileOutputStream(outFile).use { out -> input.copyTo(out) }
        }
        Log.d(TAG, "YOLO model copied ${outFile.absolutePath} (${outFile.length()} bytes)")
        return outFile.absolutePath
    }

    /** 是否成功用 NPU 加速 */
    fun usingNpu(): Boolean = useNnapi

    // ============================================================
    //  推理 + 后处理
    // ============================================================

    data class Detection(
        val bbox: FloatArray,   // [cx, cy, w, h] 原图坐标
        val classId: Int,
        val confidence: Float
    ) {
        fun toBox(): FloatArray {
            val cx = bbox[0]; val cy = bbox[1]; val w = bbox[2]; val h = bbox[3]
            return floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }
    }

    /**
     * 检测整张图上的棋子，返回 [Detection] 列表。
     */
    fun detect(bitmap: Bitmap, context: Context): List<Detection> {
        if (!ensureLoaded(context)) return emptyList()
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuf = buildInput(resized)
            val env = env ?: return emptyList()
            val sess = session ?: return emptyList()
            val inputName = sess.inputNames.iterator().next()
            val tensor = OnnxTensor.createTensor(env, inputBuf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
            sess.run(mapOf(inputName to tensor)).use { result ->
                val raw = result[0].value as Array<FloatArray>
                tensor.close()
                postProcess(raw[0], bitmap.width, bitmap.height)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "detect 异常", e)
            emptyList()
        }
    }

    /**
     * 检测并直接转换成 ChessBoard。
     */
    fun detectToBoard(bitmap: Bitmap, context: Context, boardConfig: BoardConfig? = null): ChessBoard {
        val board = ChessBoard()
        val detections = detect(bitmap, context)
        if (detections.isEmpty()) return board

        val cfg = boardConfig ?: run {
            // 自动从检测框估计棋盘范围
            val xs = detections.map { it.bbox[0] }
            val ys = detections.map { it.bbox[1] }
            val left = xs.min() - 20; val right = xs.max() + 20
            val top = ys.min() - 20; val bottom = ys.max() + 20
            BoardConfig(
                topLeftX = left.toInt(), topLeftY = top.toInt(),
                cellWidth = ((right - left) / 9).toInt(),
                cellHeight = ((bottom - top) / 10).toInt(),
                pieceSize = 40, useDefaultConfig = false
            )
        }

        // 9×10 网格归属：每个检测框找最近的格子中心
        for (d in detections) {
            val piece = CLASS_MAP[d.classId] ?: continue
            val cx = d.bbox[0]; val cy = d.bbox[1]
            val col = ((cx - cfg.topLeftX) / cfg.cellWidth).toInt().coerceIn(0, 8)
            val row = ((cy - cfg.topLeftY) / cfg.cellHeight).toInt().coerceIn(0, 9)
            val existing = board.getPiece(row, col)
            if (existing == null || d.confidence > 0.5f) {
                board.setPiece(row, col, Piece(piece.second, piece.first, Position(row, col)))
            }
        }
        return board
    }

    // ============================================================
    //  私有辅助
    // ============================================================

    private fun buildInput(bmp: Bitmap): FloatBuffer {
        val buf = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buf.put(Color.red(p) / 255f)
            buf.put(Color.green(p) / 255f)
            buf.put(Color.blue(p) / 255f)
        }
        buf.rewind()
        return buf
    }

    /**
     * YOLOv8 单尺度输出后处理。
     * output shape: [19, 169] → [19, 13×13]
     * 前 4 维: cx, cy, w, h（格坐标）；后 15 维: 15 类置信度
     */
    private fun postProcess(output: FloatArray, origW: Int, origH: Int): List<Detection> {
        val stride = INPUT_SIZE / GRID  // 32
        val scaleX = origW.toFloat() / INPUT_SIZE
        val scaleY = origH.toFloat() / INPUT_SIZE
        val bboxCount = GRID * GRID

        val raw = mutableListOf<Detection>()

        for (cell in 0 until bboxCount) {
            val cx = output[cell]
            val cy = output[bboxCount + cell]
            val w  = output[bboxCount * 2 + cell]
            val h  = output[bboxCount * 3 + cell]

            // sigmoid
            val cx_sig = 1f / (1f + exp(-cx))
            val cy_sig = 1f / (1f + exp(-cy))
            val w_sig  = exp(w)
            val h_sig  = exp(h)

            // 映射回原图坐标
            val gx = cell % GRID
            val gy = cell / GRID
            val absCx = (gx + cx_sig) * stride * scaleX
            val absCy = (gy + cy_sig) * stride * scaleY
            val absW  = w_sig * scaleX
            val absH  = h_sig * scaleY

            // 找最高置信度的类
            var bestClass = -1; var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val score = 1f / (1f + exp(-output[bboxCount * (4 + c) + cell]))
                if (score > bestScore) { bestScore = score; bestClass = c }
            }
            if (bestScore < CONF_THRESHOLD) continue
            if (bestClass == 0) continue  // EMPTY 跳过

            raw.add(Detection(floatArrayOf(absCx, absCy, absW, absH), bestClass, bestScore))
        }

        return nms(raw)
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val top = sorted.removeAt(0)
            keep.add(top)
            val topBox = top.toBox()
            val topArea = top.bbox[2] * top.bbox[3]
            val iter = sorted.iterator()
            while (iter.hasNext()) {
                val d = iter.next()
                val box = d.toBox()
                val interX1 = maxOf(topBox[0], box[0])
                val interY1 = maxOf(topBox[1], box[1])
                val interX2 = minOf(topBox[2], box[2])
                val interY2 = minOf(topBox[3], box[3])
                val interArea = max(0f, interX2 - interX1) * max(0f, interY2 - interY1)
                val area = d.bbox[2] * d.bbox[3]
                val iou = interArea / (topArea + area - interArea + 1e-6f)
                if (iou > IOU_THRESHOLD) iter.remove()
            }
        }
        return keep
    }

    fun release() {
        runCatching { session?.close() }
        runCatching { env?.close() }
        session = null; env = null; ready = false
    }
}
