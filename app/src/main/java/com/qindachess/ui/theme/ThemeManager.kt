package com.qindachess.ui.theme

import android.content.Context
import com.qindachess.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 聚合了主题所有维度的 UI 配置。
 * 整个 App 只 collect 这一份 StateFlow 即可同步所有样式变化。
 */
data class ThemeConfig(
    val themeId: String,
    val boardSkinId: String,
    val pieceStyleId: String,
    val customBackgroundPath: String,   // "" 表示不用自定义背景
    val topBarAlpha: Float,
    val boardAlpha: Float,
    val panelAlpha: Float,
    val bottomBarAlpha: Float
) {
    val theme: AppTheme get() = AppThemes.findById(themeId)
    val boardSkin: BoardSkin get() = BoardSkins.findById(boardSkinId)
    val pieceStyle: PieceStyle get() = PieceStyles.findById(pieceStyleId)
}

class ThemeManager private constructor(context: Context) {

    private val prefs = AppPreferences(context.applicationContext)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<ThemeConfig> = _config.asStateFlow()

    private fun loadConfig(): ThemeConfig = ThemeConfig(
        themeId = prefs.theme,
        boardSkinId = prefs.boardSkinId,
        pieceStyleId = prefs.pieceStyleId,
        customBackgroundPath = prefs.customBackgroundPath,
        topBarAlpha = prefs.topBarAlpha,
        boardAlpha = prefs.boardAlpha,
        panelAlpha = prefs.panelAlpha,
        bottomBarAlpha = prefs.bottomBarAlpha
    )

    private fun saveAndNotify(transform: ThemeConfig.() -> ThemeConfig) {
        val newCfg = _config.value.transform()
        // 写 SharedPreferences
        prefs.theme = newCfg.themeId
        prefs.boardSkinId = newCfg.boardSkinId
        prefs.pieceStyleId = newCfg.pieceStyleId
        prefs.customBackgroundPath = newCfg.customBackgroundPath
        prefs.topBarAlpha = newCfg.topBarAlpha
        prefs.boardAlpha = newCfg.boardAlpha
        prefs.panelAlpha = newCfg.panelAlpha
        prefs.bottomBarAlpha = newCfg.bottomBarAlpha
        _config.value = newCfg
    }

    // ─── 主题切换 ───
    fun setTheme(themeId: String) = saveAndNotify { copy(
        themeId = themeId,
        boardSkinId = AppThemes.findById(themeId).boardSkin.id,
        pieceStyleId = AppThemes.findById(themeId).pieceStyle.id
    )}

    fun setSkin(skinId: String) = saveAndNotify { copy(boardSkinId = skinId) }
    fun setPieceStyle(styleId: String) = saveAndNotify { copy(pieceStyleId = styleId) }

    // ─── 背景图 ───
    fun setCustomBackground(path: String) = saveAndNotify { copy(customBackgroundPath = path) }
    fun clearCustomBackground() = saveAndNotify { copy(customBackgroundPath = "") }

    // ─── 透明度滑杆 ───
    fun setTopBarAlpha(a: Float) = saveAndNotify { copy(topBarAlpha = a.coerceIn(0f, 1f)) }
    fun setBoardAlpha(a: Float) = saveAndNotify { copy(boardAlpha = a.coerceIn(0f, 1f)) }
    fun setPanelAlpha(a: Float) = saveAndNotify { copy(panelAlpha = a.coerceIn(0f, 1f)) }
    fun setBottomBarAlpha(a: Float) = saveAndNotify { copy(bottomBarAlpha = a.coerceIn(0f, 1f)) }

    fun setAllAlphas(
        topBar: Float = _config.value.topBarAlpha,
        board: Float = _config.value.boardAlpha,
        panel: Float = _config.value.panelAlpha,
        bottomBar: Float = _config.value.bottomBarAlpha
    ) = saveAndNotify {
        copy(
            topBarAlpha = topBar.coerceIn(0f, 1f),
            boardAlpha = board.coerceIn(0f, 1f),
            panelAlpha = panel.coerceIn(0f, 1f),
            bottomBarAlpha = bottomBar.coerceIn(0f, 1f)
        )
    }

    // ─── 重置 ───
    fun resetToDefaults() = saveAndNotify {
        copy(
            themeId = "classic",
            boardSkinId = "wood_classic",
            pieceStyleId = "embossed_3d",
            customBackgroundPath = "",
            topBarAlpha = 1f,
            boardAlpha = 1f,
            panelAlpha = 1f,
            bottomBarAlpha = 1f
        )
    }

    companion object {
        @Volatile private var instance: ThemeManager? = null

        fun getInstance(context: Context): ThemeManager {
            return instance ?: synchronized(this) {
                instance ?: ThemeManager(context).also { instance = it }
            }
        }
    }
}
