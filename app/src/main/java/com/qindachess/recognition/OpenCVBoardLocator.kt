package com.qindachess.recognition

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * OpenCV 棋盘定位器。
 * 流程：灰度 → Canny 边缘 → HoughLinesP 直线检测 → 聚类出 10 横 + 9 竖 → 四点透视变换。
 *
 * OpenCV 加载失败时（首次使用，JavaAPI 未初始化），所有方法返回 null，
 * 上层应 fallback 到 Kotlin 原生霍夫实现（OnnxChessRecognizer.detectBoardCorners）。
 */
object OpenCVBoardLocator {

    private const val TAG = "OpenCVBoard"

    @Volatile private var opencvReady: Boolean? = null

    /** 初始化 OpenCV Java API（只做一次）。 */
    fun init(): Boolean {
        opencvReady?.let { return it }
        return try {
            val loader = Class.forName("org.opencv.android.OpenCVLoader")
            val method = loader.getMethod("initLocal")
            val ok = method.invoke(null) as? Boolean ?: false
            opencvReady = ok
            Log.i(TAG, "OpenCV init: $ok, version=${Core.VERSION}")
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "OpenCV init failed: ${e.message}")
            opencvReady = false
            false
        }
    }

    fun isAvailable(): Boolean = init()

    /**
     * 定位棋盘四角，返回 [(tl), (tr), (br), (bl)] 或 null。
     */
    fun locateBoard(bitmap: Bitmap): BoardQuad? {
        if (!isAvailable()) return null
        return try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

            // 自适应阈值
            val edges = Mat()
            Imgproc.Canny(gray, edges, 50.0, 150.0, 3)

            // 霍夫线段
            val lines = Mat()
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0,
                60, 30.0, 10.0)

            val hLines = mutableListOf<DoubleArray>()
            val vLines = mutableListOf<DoubleArray>()
            for (i in 0 until lines.rows()) {
                val l = lines.get(i, 0) ?: continue
                // [x1, y1, x2, y2]
                val angle = Math.toDegrees(Math.atan2(l[3] - l[1], l[2] - l[0]))
                val len = Math.hypot(l[2] - l[0], l[3] - l[1])
                if (len < bitmap.width * 0.15) continue
                if (abs(angle) < 20 || abs(angle) > 160) hLines.add(l)
                else if (abs(abs(angle) - 90) < 20) vLines.add(l)
            }

            if (hLines.size < 5 || vLines.size < 5) {
                Log.w(TAG, "直线不足 H=${hLines.size} V=${vLines.size}")
                return null
            }

            // 聚类水平线 y 坐标 & 垂直线 x 坐标
            val ys = clusterYs(hLines).sorted()
            val xs = clusterXs(vLines).sorted()

            if (ys.size < 2 || xs.size < 2) return null

            val tl = Point(xs.first(), ys.first())
            val tr = Point(xs.last(), ys.first())
            val br = Point(xs.last(), ys.last())
            val bl = Point(xs.first(), ys.last())
            BoardQuad(tl, tr, br, bl, xs.size, ys.size)
        } catch (e: Throwable) {
            Log.w(TAG, "定位失败: ${e.message}")
            null
        }
    }

    /** 透视变换矫正为标准 900×1000 棋盘，返回矫正后的 Bitmap。 */
    fun warpPerspective(bitmap: Bitmap, quad: BoardQuad, outWidth: Int = 900, outHeight: Int = 1000): Bitmap? {
        if (!isAvailable()) return null
        return try {
            val src = MatOfPoint2f(quad.tl, quad.tr, quad.br, quad.bl)
            val dst = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(outWidth.toDouble(), 0.0),
                Point(outWidth.toDouble(), outHeight.toDouble()),
                Point(0.0, outHeight.toDouble())
            )
            val M = Imgproc.getPerspectiveTransform(src, dst)
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            val warped = Mat()
            Imgproc.warpPerspective(mat, warped, M, Size(outWidth.toDouble(), outHeight.toDouble()))
            val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, result)
            result
        } catch (e: Throwable) {
            Log.w(TAG, "透视变换失败: ${e.message}")
            null
        }
    }

    private fun clusterYs(lines: List<DoubleArray>): List<Double> {
        val ys = lines.map { (it[1] + it[3]) / 2.0 }.sorted()
        return cluster(ys, threshold = 20.0)
    }

    private fun clusterXs(lines: List<DoubleArray>): List<Double> {
        val xs = lines.map { (it[0] + it[2]) / 2.0 }.sorted()
        return cluster(xs, threshold = 20.0)
    }

    private fun cluster(values: List<Double>, threshold: Double): List<Double> {
        if (values.isEmpty()) return emptyList()
        val result = mutableListOf<Double>()
        var clusterSum = values[0]
        var clusterCount = 1
        for (i in 1 until values.size) {
            if (values[i] - values[i - 1] < threshold) {
                clusterSum += values[i]
                clusterCount++
            } else {
                result.add(clusterSum / clusterCount)
                clusterSum = values[i]
                clusterCount = 1
            }
        }
        result.add(clusterSum / clusterCount)
        return result
    }
}

data class BoardQuad(
    val tl: Point, val tr: Point, val br: Point, val bl: Point,
    val vLineCount: Int, val hLineCount: Int
)
