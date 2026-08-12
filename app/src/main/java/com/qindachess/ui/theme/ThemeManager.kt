package com.qindachess.ui.theme

import android.content.Context
import com.qindachess.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeManager private constructor(context: Context) {

    private val prefs = AppPreferences(context.applicationContext)

    private val _currentTheme = MutableStateFlow(resolveTheme(prefs.theme))
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    private val _currentSkin = MutableStateFlow(resolveSkin(prefs.boardSkinId))
    val currentSkin: StateFlow<BoardSkin> = _currentSkin.asStateFlow()

    private val _currentPieceStyle = MutableStateFlow(resolvePieceStyle(prefs.pieceStyleId))
    val currentPieceStyle: StateFlow<PieceStyle> = _currentPieceStyle.asStateFlow()

    fun setTheme(themeId: String) {
        val theme = AppThemes.findById(themeId)
        prefs.theme = themeId
        _currentTheme.value = theme
        _currentSkin.value = theme.boardSkin
    }

    fun setSkin(skinId: String) {
        val skin = BoardSkins.findById(skinId)
        prefs.boardSkinId = skinId
        _currentSkin.value = skin
    }

    fun setPieceStyle(styleId: String) {
        val style = PieceStyles.findById(styleId)
        prefs.pieceStyleId = styleId
        _currentPieceStyle.value = style
    }

    private fun resolveTheme(themeId: String): AppTheme {
        return AppThemes.findById(themeId)
    }

    private fun resolveSkin(skinId: String): BoardSkin {
        return BoardSkins.findById(skinId)
    }

    private fun resolvePieceStyle(styleId: String): PieceStyle {
        return PieceStyles.findById(styleId)
    }

    companion object {
        @Volatile
        private var instance: ThemeManager? = null

        fun getInstance(context: Context): ThemeManager {
            return instance ?: synchronized(this) {
                instance ?: ThemeManager(context).also { instance = it }
            }
        }
    }
}
