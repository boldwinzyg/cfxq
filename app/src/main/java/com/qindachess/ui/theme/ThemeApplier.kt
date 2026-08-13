package com.qindachess.ui.theme

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.qindachess.ui.ChessBoardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ThemeApplier {

    fun attach(
        activity: Activity,
        tm: ThemeManager,
        scope: CoroutineScope,
        boardView: ChessBoardView,
        topBar: View,
        boardContainer: View,
        panelContainer: View,
        bottomBar: View,
        topControlsBar: View,
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
        topBar: View,
        boardContainer: View,
        panelContainer: View,
        bottomBar: View,
        topControlsBar: View,
        globalBackground: ImageView
    ) {
        val theme = cfg.theme

        if (cfg.customBackgroundPath.isNotBlank()) {
            val file = File(cfg.customBackgroundPath)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    globalBackground.setImageBitmap(bmp)
                    globalBackground.visibility = View.VISIBLE
                } else {
                    globalBackground.setImageDrawable(null)
                    globalBackground.visibility = View.GONE
                }
            } else {
                globalBackground.setImageDrawable(null)
                globalBackground.visibility = View.GONE
            }
        } else {
            globalBackground.setImageDrawable(null)
            globalBackground.visibility = View.GONE
        }
        activity.window.decorView.setBackgroundColor(Color.parseColor(theme.backgroundColor))

        topBar.background = makeSolidDrawable(theme.cardBackground, cfg.topBarAlpha)
        topBar.alpha = 1f

        boardContainer.alpha = cfg.boardAlpha
        boardView.skin = cfg.boardSkin
        boardView.pieceStyle = cfg.pieceStyle

        topControlsBar.background = makeSolidDrawable(theme.cardBackground, cfg.topBarAlpha)

        panelContainer.alpha = cfg.panelAlpha

        bottomBar.alpha = cfg.bottomBarAlpha

        applyThemeTextColors(panelContainer, theme)

        activity.window.statusBarColor = Color.parseColor(theme.primaryColor)
        activity.window.navigationBarColor = Color.parseColor(theme.backgroundColor)
    }

    private fun makeSolidDrawable(colorHex: String, alpha: Float): GradientDrawable {
        val solid = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(colorHex))
        }
        solid.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return solid
    }

    private fun applyThemeTextColors(root: View, theme: AppTheme) {
        val visit = { v: View ->
            when (v) {
                is TextView -> v.setTextColor(Color.parseColor(theme.textPrimary))
                is android.view.ViewGroup -> {
                    for (i in 0 until v.childCount) {
                        applyThemeTextColors(v.getChildAt(i), theme)
                    }
                }
            }
        }
        visit(root)
    }
}
