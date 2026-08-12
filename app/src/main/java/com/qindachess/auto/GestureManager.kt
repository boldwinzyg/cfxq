package com.qindachess.auto

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import android.util.Log

/**
 * 手势注入策略管理器。
 *
 * 优先级：
 *   1. 用户在设置里强制选择的通道（adb / accessibility / auto）
 *   2. auto 模式下的选择逻辑：
 *      - 鸿蒙系统（HarmonyOS / Harmony） → 优先 ADB Shell
 *      - Android 11+ MIUI/ColorOS/OriginOS → 优先 ADB Shell
 *      - 其他普通 Android → 优先 Accessibility
 *   3. 第一选择不可用 → 自动降级到备用通道
 *
 * 两个通道都不可用 → performXxx() 返回 false，调用方提示用户。
 */
class GestureManager private constructor() {

    companion object {
        private const val TAG = "GestureManager"

        const val PREFS_NAME = "gesture_prefs"
        const val KEY_MODE = "inject_mode"

        const val MODE_AUTO = "auto"
        const val MODE_ADB = "adb"
        const val MODE_A11Y = "a11y"

        private val adbInjector = AdbGestureInjector()
        private val a11yInjector = AccessibilityGestureInjector()

        @Volatile private var _instance: GestureManager? = null

        fun get(): GestureManager {
            return _instance ?: GestureManager().also { _instance = it }
        }
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 提前检测一次 adb 可用性（IO 线程）
        Thread { AdbGestureInjector.preCheck() }.start()
    }

    fun setMode(mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
        Log.i(TAG, "setMode => $mode")
    }

    fun getMode(): String = prefs.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun currentInjector(): GestureInjector? {
        val mode = getMode()
        val picked = when (mode) {
            MODE_ADB  -> adbInjector
            MODE_A11Y -> a11yInjector
            else -> pickAuto()
        }
        if (picked.isAvailable()) return picked

        // 降级到另一个
        val fallback = if (picked === adbInjector) a11yInjector else adbInjector
        if (fallback.isAvailable()) {
            Log.w(TAG, "${picked.name()} 不可用，降级到 ${fallback.name()}")
            return fallback
        }
        Log.e(TAG, "所有手势通道都不可用！")
        return null
    }

    private fun pickAuto(): GestureInjector {
        val isHarmony = runCatching {
            val cls = Class.forName("com.huawei.system.BuildEx")
            val m = cls.getMethod("getOsBrand")
            val brand = m.invoke(null) as? String
            brand?.contains("Harmony", ignoreCase = true) == true
        }.getOrDefault(false)

        val rom = (android.os.Build.DISPLAY + " " + android.os.Build.FINGERPRINT).lowercase()
        val isAggressiveRom = rom.contains("miui") || rom.contains("coloros") ||
                              rom.contains("originos") || rom.contains("oneui")

        val preferAdb = isHarmony || isAggressiveRom || Build.VERSION.SDK_INT >= 30

        return if (preferAdb) adbInjector else a11yInjector
    }

    fun availableChannels(): List<GestureInjector> {
        return listOf(adbInjector, a11yInjector).filter { it.isAvailable() }
    }

    // ---------- 便捷委托 ----------

    fun performTap(x: Float, y: Float): Boolean =
        currentInjector()?.tap(x, y) ?: false

    fun performSwipe(from: PointF, to: PointF, durationMs: Long = 150): Boolean =
        currentInjector()?.swipe(from, to, durationMs) ?: false

    fun performChessMove(from: PointF, to: PointF): Boolean {
        // 先尝试标准 swipe
        val ok = performSwipe(from, to, 180)
        if (ok) return ok
        // 某些象棋应用只响应点击-点击（而不是拖拽），兜底：先点起点再点终点
        Log.w(TAG, "swipe 失败，fallback 到 tap-tap 模式")
        performTap(from.x, from.y)
        Thread.sleep(80)
        return performTap(to.x, to.y)
    }

    fun takeScreenshot(): Bitmap? = currentInjector()?.screenshot()
}
