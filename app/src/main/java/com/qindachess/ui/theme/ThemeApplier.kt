package com.qindachess.ui.theme

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.qindachess.ui.ChessBoardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主题应用器：一次性把 ThemeConfig 应用到 Activity 所有 UI 元素。
 *
 * 使用方式（在 Activity 的 onCreate 里）：
 * ```kotlin
 * ThemeApplier.attach(activity, themeManager, lifecycleScope,
 *     boardView = findViewById(R.id.boardView),
 *     topBar = findViewById(R.id.topBar),
 *     boardContainer = findViewById(R.id.boardContainer),
 *     panelContainer = findViewById(R.id.panelContainer),
 *     bottomBar = findViewById(R.id.bottomBar),
 *     topControlsBar = findViewById(R.id.topControlsBar),
 *     globalBackground = findViewById(R.id.globalBackground)
 * )
 * ```
 */
object ThemeApplier {

    fun attach(
        activity: Activity,
        tm: ThemeManager,
        scope: CoroutineScope,
        boardView: ChessBoardView,
        topBar: LinearLayout,
        boardContainer: LinearLayout,
        panelContainer: LinearLayout,
        bottomBar: LinearLayout,
        topControlsBar: LinearLayout,
        globalBackground: ImageView
    ) {
        scope.launch {
            tm.config.collect { cfg ->
                withContext(Dispatchers.Main) {
                    applyAll(
                        activity, cfg, boardView,
                        topBar, boardContainer, panelContainer, bottomBar,
                        topControlsBar, globalBackground
                    )
                }
            }
        }
    }

    private fun applyAll(
        activity: Activity,
        cfg: ThemeConfig,
        boardView: ChessBoardView,
        topBar: LinearLayout,
        boardContainer: LinearLayout,
        panelContainer: LinearLayout,
        bottomBar: LinearLayout,
        topControlsBar: LinearLayout,
        globalBackground: ImageView
    ) {
        val theme = cfg.theme

        // 1) 全局背景：如果用户选了一张图就覆盖整个页面；否则用 theme.backgroundColor
        if (cfg.customBackgroundPath.isNotBlank()) {
            val file = File(cfg.customBackgroundPath)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    globalBackground.setImageBitmap(bmp)
                    globalBackground.visibility = ImageView.VISIBLE
                } else {
                    globalBackground.setImageDrawable(null)
                    globalBackground.visibility = ImageView.GONE
                }
            } else {
                globalBackground.setImageDrawable(null)
                globalBackground.visibility = ImageView.GONE
            }
        } else {
            globalBackground.setImageDrawable(null)
            globalBackground.visibility = ImageView.GONE
        }
        activity.window.decorView.setBackgroundColor(Color.parseColor(theme.backgroundColor))

        // 2) 顶栏
        topBar.background = makeSolidDrawable(theme.cardBackground, cfg.topBarAlpha)
        topBar.alpha = 1f   // 背景本身已含 alpha，容器 alpha 保持 1

        // 3) 棋盘容器 + ChessBoardView（alpha 作用在整个棋盘）
        boardContainer.alpha = cfg.boardAlpha
        boardView.skin = cfg.boardSkin
        boardView.pieceStyle = cfg.pieceStyle

        // 4) 回放控制栏：跟着顶栏 alpha 走（顶栏 alpha 调的是"工具条整体"）
        topControlsBar.background = makeSolidDrawable(theme.cardBackground, cfg.topBarAlpha)

        // 5) 面板（Tab + 内容 + 引擎信息条）统一 panelAlpha
        panelContainer.alpha = cfg.panelAlpha

        // 6) 底栏
        bottomBar.alpha = cfg.bottomBarAlpha
        // 底栏的背景用 theme.buttonColor 或 cardBackground，不做实心（保留原来的 bg_bottom_control 更美观）

        // 7) Tab 栏子项的文字颜色
        applyThemeTextColors(panelContainer, theme)

        // 8) 状态栏 & 导航栏颜色
        activity.window.statusBarColor = Color.parseColor(theme.primaryColor)
        activity.window.navigationBarColor = Color.parseColor(theme.backgroundColor)
    }

    /**
     * 给一个"实心背景"加上 alpha —— 因为 GradientDrawable 支持 setAlpha，所以这里直接返回它。
     */
    private fun makeSolidDrawable(colorHex: String, alpha: Float): GradientDrawable {
        val solid = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(colorHex))
        }
        solid.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return solid
    }

    /**
     * 把面板里所有的 TextView 按主题着色（避免硬编码 #333333）
     */
    private fun applyThemeTextColors(root: android.view.View, theme: AppTheme) {
        val visit = { v: android.view.View ->
            when (v) {
                is TextView -> {
                    // 默认文字色
                    v.setTextColor(Color.parseColor(theme.textPrimary))
                }
                is android.view.ViewGroup -> {
                    for (i in 0 until v.childCount) {
                        @Suppress("UNCHECKED_CAST")
                        applyThemeTextColors(v.getChildAt(i), theme)
                    }
                }
            }
        }
        visit(root)
    }
}
