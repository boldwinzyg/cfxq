package com.qindachess.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Path
import android.accessibilityservice.GestureDescription
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.qindachess.board.ChessBoard
import com.qindachess.board.Move
import com.qindachess.board.Position
import com.qindachess.recognition.ChessRecognizer

class ChessAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChessAccessService"
        var instance: ChessAccessibilityService? = null
            private set
        private val BOARD_IDENTIFIERS = arrayOf("chess", "xiangqi", "棋", "board", "棋盘")
    }

    private val recognizer = ChessRecognizer()
    private var boardBounds: android.graphics.Rect? = null
    private var cellPoints: Array<Array<android.graphics.PointF>> = emptyArray()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                Log.d(TAG, "Window changed: $pkg")
                locateBoardInPackage(pkg)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    private fun locateBoardInPackage(packageName: String) {
        val rootNode = rootInActiveWindow ?: return
        traverseAndFindBoard(rootNode)
    }

    private fun traverseAndFindBoard(node: AccessibilityNodeInfo) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val resId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        val allIdentifiers = text + " " + desc + " " + resId + " " + className

        for (identifier in BOARD_IDENTIFIERS) {
            if (identifier in allIdentifiers) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 200 && rect.height() > 200) {
                    boardBounds = rect
                    calculateCellPoints(rect)
                    Log.i(TAG, "Found chess board at: $rect")
                }
                break
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndFindBoard(child)
            if (boardBounds != null) break
            child.recycle()
        }
    }

    private fun calculateCellPoints(boardRect: android.graphics.Rect) {
        val paddingX = boardRect.width() * 0.05f
        val paddingTop = boardRect.height() * 0.05f
        val playableWidth = boardRect.width() - 2 * paddingX
        val playableHeight = boardRect.height() - 2 * paddingTop
        val cellWidth = playableWidth / 8f
        val cellHeight = playableHeight / 10f

        cellPoints = Array(10) { row ->
            Array(9) { col ->
                android.graphics.PointF(
                    boardRect.left + paddingX + col * cellWidth,
                    boardRect.top + paddingTop + row * cellHeight
                )
            }
        }
    }

    fun performChessMove(move: Move): Boolean {
        try {
            val from = cellPoints.getOrNull(move.from.row)?.getOrNull(move.from.col)
                ?: run {
                    Log.e(TAG, "Invalid from position")
                    return false
                }
            val to = cellPoints.getOrNull(move.to.row)?.getOrNull(move.to.col)
                ?: run {
                    Log.e(TAG, "Invalid to position")
                    return false
                }

            return performDrag(from, to)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform chess move", e)
            return false
        }
    }

    private fun performDrag(from: android.graphics.PointF, to: android.graphics.PointF): Boolean {
        val path = Path().apply {
            moveTo(from.x, from.y)
            lineTo(to.x, to.y)
        }

        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build(),
            null, null
        )
    }

    fun performClickAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build(),
            null, null
        )
    }

    fun getBoardCellCenter(row: Int, col: Int): android.graphics.PointF? {
        return cellPoints.getOrNull(row)?.getOrNull(col)
    }

    fun takeScreenshot(): android.graphics.Bitmap? = takeScreenshotCompat()

    fun takeScreenshotCompat(): android.graphics.Bitmap? {
        // AccessibilityService 的 takeScreenshot API 在 Android 11+ 上可用但需要 CAPTURE_SECURE_UI 权限，
        // 这里只做占位。真正稳定的截图由 AdbGestureInjector（screencap -p）提供。
        return null
    }

    fun findNodesByText(text: String, packageFilter: String? = null): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val root = rootInActiveWindow ?: return results
        findNodesRecursive(root, text, packageFilter, results)
        return results
    }

    private fun findNodesRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        packageFilter: String?,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.text?.toString()?.contains(text) == true) {
            val nodePkg = node.packageName?.toString()
            if (packageFilter == null || nodePkg == packageFilter) {
                results.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesRecursive(child, text, packageFilter, results)
            child.recycle()
        }
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                performClickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
