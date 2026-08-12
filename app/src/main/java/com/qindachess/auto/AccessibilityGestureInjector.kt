package com.qindachess.auto

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.accessibilityservice.GestureDescription
import android.os.Build
import android.util.Log

/**
 * AccessibilityService 手势注入的 GestureInjector 适配器。
 * 通过 dispatchGesture() 注入手势，无需 root，纯 Android 框架 API。
 *
 * 缺点：
 *   - 鸿蒙 4.0+ 无障碍被严格限制
 *   - 国产 ROM (MIUI/ColorOS/OriginOS) 可能回收后台无障碍
 *   - 部分应用检测到无障碍后会屏蔽 dispatchGesture
 */
class AccessibilityGestureInjector : GestureInjector {

    companion object { private const val TAG = "A11yGesture" }

    override fun name(): String = "Accessibility dispatchGesture"

    override fun isAvailable(): Boolean {
        return ChessAccessibilityService.instance != null
    }

    override fun tap(x: Float, y: Float): Boolean {
        val svc = ChessAccessibilityService.instance ?: return false
        val path = Path().apply { moveTo(x, y) }
        return try {
            svc.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build(), null, null
            )
        } catch (e: Throwable) {
            Log.w(TAG, "tap failed: ${e.message}")
            false
        }
    }

    override fun swipe(from: PointF, to: PointF, durationMs: Long): Boolean {
        val svc = ChessAccessibilityService.instance ?: return false
        val path = Path().apply { moveTo(from.x, from.y); lineTo(to.x, to.y) }
        return try {
            svc.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(20, 500)))
                    .build(), null, null
            )
        } catch (e: Throwable) {
            Log.w(TAG, "swipe failed: ${e.message}")
            false
        }
    }

    override fun dragPath(points: List<PointF>, totalDurationMs: Long): Boolean {
        if (points.size < 2) return false
        val svc = ChessAccessibilityService.instance ?: return false
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        val dur = totalDurationMs.coerceIn(30, 800)
        return try {
            svc.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                    .build(), null, null
            )
        } catch (e: Throwable) {
            Log.w(TAG, "dragPath failed: ${e.message}")
            false
        }
    }

    override fun screenshot(): Bitmap? {
        val svc = ChessAccessibilityService.instance ?: return null
        return svc.takeScreenshotCompat()
    }
}
