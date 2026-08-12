package com.qindachess.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.util.Log
import java.io.File

/**
 * ADB Shell 手势注入实现。
 *
 * 原理：通过 Runtime.exec() 在设备本地调用 android shell 的 input 命令，
 *      等价于电脑 adb shell 下发的 input tap / input swipe。
 *
 * 优势：
 *   - 鸿蒙全版本通用（adbd 是系统服务，不受无障碍限制）
 *   - 国产 ROM（MIUI/ColorOS/OriginOS）不会杀进程
 *   - 免 root、免 USB 连接
 *   - 真正稳定
 *
 * 限制：
 *   - 非 userdebug/root 版本的 ROM 上，某些品牌会禁用 shell input 权限
 *     本类自动检测可用状态，不可用时 isAvailable() 返回 false。
 */
class AdbGestureInjector : GestureInjector {

    companion object {
        private const val TAG = "AdbGesture"

        @Volatile
        private var _available: Boolean? = null

        fun preCheck(): Boolean {
            _available?.let { return it }
            return try {
                val exit = Runtime.getRuntime().exec(arrayOf("input", "tap", "1", "1")).waitFor()
                _available = (exit == 0)
                Log.i(TAG, "ADB shell input check => exit=$exit, available=${_available}")
                _available!!
            } catch (e: Throwable) {
                Log.w(TAG, "ADB shell input NOT available: ${e.message}")
                _available = false
                false
            }
        }
    }

    override fun name(): String = "ADB Shell input"

    override fun isAvailable(): Boolean = preCheck()

    override fun tap(x: Float, y: Float): Boolean {
        return runCmd("input tap ${x.toInt()} ${y.toInt()}")
    }

    override fun swipe(from: PointF, to: PointF, durationMs: Long): Boolean {
        return runCmd(
            "input swipe ${from.x.toInt()} ${from.y.toInt()} ${to.x.toInt()} ${to.y.toInt()} $durationMs"
        )
    }

    override fun dragPath(points: List<PointF>, totalDurationMs: Long): Boolean {
        if (points.size < 2) return false
        val seg = totalDurationMs / (points.size - 1).coerceAtLeast(1)
        for (i in 0 until points.size - 1) {
            val ok = swipe(points[i], points[i + 1], seg.coerceAtLeast(20))
            if (!ok) return false
            Thread.sleep(30)
        }
        return true
    }

    override fun screenshot(): Bitmap? {
        return try {
            val file = File("/data/local/tmp/qindachess_screen.png")
            if (file.exists()) file.delete()
            val ok = runCmd("screencap -p ${file.absolutePath}")
            if (!ok || !file.exists()) return null
            BitmapFactory.decodeFile(file.absolutePath)?.also {
                Thread { file.delete() }.start()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "screenshot failed: ${e.message}")
            null
        }
    }

    private fun runCmd(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exit = process.waitFor()
            if (exit != 0) {
                val err = process.errorStream.bufferedReader().use { it.readLine() }
                Log.w(TAG, "cmd=[$command] exit=$exit err=$err")
            }
            exit == 0
        } catch (e: Throwable) {
            Log.w(TAG, "cmd=[$command] exception=${e.message}")
            false
        }
    }
}
