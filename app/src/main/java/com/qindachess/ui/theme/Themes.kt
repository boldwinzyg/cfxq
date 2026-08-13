package com.qindachess.ui.theme

/**
 * 棋盘皮肤（配色方案）
 */
data class BoardSkin(
    val id: String,
    val name: String,
    val boardBg: String,
    val boardBorder: String,
    val gridLine: String,
    val redPiece: String,
    val redPieceBorder: String,
    val blackPiece: String,
    val blackPieceBorder: String,
    val pieceBg: String,
    val pieceInner: String,
    val riverText: String,
    val crossMark: String,
    val lightSquare: String? = null,
    val darkSquare: String? = null
)

/**
 * 棋子样式：传统/简化汉字/图标/现代简约，还支持 3D 浮雕感。
 */
data class PieceStyle(
    val id: String,
    val name: String,
    val useTraditional: Boolean,
    val showCharacter: Boolean,
    val showBorder: Boolean,
    val borderWidth: Float,
    val shadowEnabled: Boolean,
    val embossed: Boolean = false   // 3D 浮雕（类似截图中的效果）
)

/**
 * 全局主题：
 *   - 背景图：可指定一张图片（asset 或用户选择的文件）覆盖整个页面
 *   - 四个区域各自独立的透明度滑杆（0 = 完全透明，1 = 完全不透明）
 */
data class AppTheme(
    val id: String,
    val name: String,
    val isDark: Boolean,

    val customBackgroundPath: String? = null,   // 用户选的背景图绝对路径；null 表示使用 backgroundColor
    val backgroundColor: String,

    val topBarAlpha: Float = 1f,
    val boardAlpha: Float = 1f,
    val panelAlpha: Float = 1f,
    val bottomBarAlpha: Float = 1f,

    val primaryColor: String,
    val accentColor: String,
    val textPrimary: String,
    val textSecondary: String,
    val cardBackground: String,
    val buttonColor: String,

    val boardSkin: BoardSkin,
    val pieceStyle: PieceStyle
)

// ────────────────────────────── BoardSkin 预置 ──────────────────────────────

object BoardSkins {
    val WOOD_CLASSIC = BoardSkin(
        id = "wood_classic", name = "传统木色",
        boardBg = "#E8D5B5", boardBorder = "#8D6E63", gridLine = "#2C1810",
        redPiece = "#C62828", redPieceBorder = "#8B0000",
        blackPiece = "#212121", blackPieceBorder = "#000000",
        pieceBg = "#FFFFFF", pieceInner = "#F5F5F5",
        riverText = "#8D6E63", crossMark = "#2C1810"
    )

    val LIGHT_BEECH = BoardSkin(
        id = "light_beech", name = "浅色榉木",
        boardBg = "#F5E6D3", boardBorder = "#B8956E", gridLine = "#5D4037",
        redPiece = "#D32F2F", redPieceBorder = "#7F0000",
        blackPiece = "#1A1A1A", blackPieceBorder = "#000000",
        pieceBg = "#FFFBF5", pieceInner = "#EFE9DE",
        riverText = "#6D4C41", crossMark = "#5D4037"
    )

    val DARK_EBONY = BoardSkin(
        id = "dark_ebony", name = "深色乌木",
        boardBg = "#2B1810", boardBorder = "#5D4037", gridLine = "#C0A080",
        redPiece = "#FF5252", redPieceBorder = "#FF1744",
        blackPiece = "#E0E0E0", blackPieceBorder = "#FFFFFF",
        pieceBg = "#3E2723", pieceInner = "#4E342E",
        riverText = "#BCAAA4", crossMark = "#A1887F"
    )

    val JADE_GREEN = BoardSkin(
        id = "jade_green", name = "翡翠绿",
        boardBg = "#C8E6C9", boardBorder = "#2E7D32", gridLine = "#1B5E20",
        redPiece = "#C62828", redPieceBorder = "#8B0000",
        blackPiece = "#1B5E20", blackPieceBorder = "#0D3B12",
        pieceBg = "#FFFFFF", pieceInner = "#E8F5E9",
        riverText = "#2E7D32", crossMark = "#1B5E20"
    )

    val MINIMAL_WHITE = BoardSkin(
        id = "minimal_white", name = "极简白",
        boardBg = "#FAFAFA", boardBorder = "#9E9E9E", gridLine = "#424242",
        redPiece = "#E53935", redPieceBorder = "#B71C1C",
        blackPiece = "#212121", blackPieceBorder = "#000000",
        pieceBg = "#FFFFFF", pieceInner = "#FAFAFA",
        riverText = "#757575", crossMark = "#424242"
    )

    val GOLD_LUXE = BoardSkin(
        id = "gold_luxe", name = "鎏金豪华",
        boardBg = "#F3E5C4", boardBorder = "#B8860B", gridLine = "#6B4E00",
        redPiece = "#C62828", redPieceBorder = "#7F0000",
        blackPiece = "#3E2723", blackPieceBorder = "#1B0F0A",
        pieceBg = "#FFF9E6", pieceInner = "#F5EBC8",
        riverText = "#8B6914", crossMark = "#6B4E00"
    )

    val BLACK_MARBLE = BoardSkin(
        id = "black_marble", name = "黑曜石",
        boardBg = "#1E1E1E", boardBorder = "#424242", gridLine = "#9E9E9E",
        redPiece = "#EF5350", redPieceBorder = "#B71C1C",
        blackPiece = "#F5F5F5", blackPieceBorder = "#FAFAFA",
        pieceBg = "#2C2C2C", pieceInner = "#383838",
        riverText = "#757575", crossMark = "#9E9E9E"
    )

    val ALL = listOf(WOOD_CLASSIC, LIGHT_BEECH, DARK_EBONY, JADE_GREEN, MINIMAL_WHITE, GOLD_LUXE, BLACK_MARBLE)
    fun findById(id: String) = ALL.firstOrNull { it.id == id } ?: WOOD_CLASSIC
}

// ────────────────────────────── PieceStyle 预置 ──────────────────────────────

object PieceStyles {
    val TRADITIONAL = PieceStyle(
        id = "traditional", name = "传统汉字",
        useTraditional = true, showCharacter = true, showBorder = true, borderWidth = 2f,
        shadowEnabled = false
    )

    val SIMPLIFIED = PieceStyle(
        id = "simplified", name = "简化汉字",
        useTraditional = false, showCharacter = true, showBorder = true, borderWidth = 2f,
        shadowEnabled = false
    )

    val ICON_ONLY = PieceStyle(
        id = "icon_only", name = "图标模式",
        useTraditional = true, showCharacter = false, showBorder = true, borderWidth = 2f,
        shadowEnabled = true
    )

    val MODERN = PieceStyle(
        id = "modern", name = "现代简约",
        useTraditional = false, showCharacter = true, showBorder = false, borderWidth = 0f,
        shadowEnabled = false
    )

    val EMBOSSED_3D = PieceStyle(
        id = "embossed_3d", name = "3D 浮雕",
        useTraditional = true, showCharacter = true, showBorder = true, borderWidth = 2.5f,
        shadowEnabled = true, embossed = true
    )

    val GOLD_3D = PieceStyle(
        id = "gold_3d", name = "鎏金浮雕",
        useTraditional = true, showCharacter = true, showBorder = true, borderWidth = 2f,
        shadowEnabled = true, embossed = true
    )

    val ALL = listOf(TRADITIONAL, SIMPLIFIED, ICON_ONLY, MODERN, EMBOSSED_3D, GOLD_3D)
    fun findById(id: String) = ALL.firstOrNull { it.id == id } ?: TRADITIONAL
}

// ────────────────────────────── AppTheme 预置 ──────────────────────────────

object AppThemes {

    val CLASSIC = AppTheme(
        id = "classic", name = "默认", isDark = false,
        backgroundColor = "#F5F5F5",
        primaryColor = "#1976D2", accentColor = "#FF5722",
        textPrimary = "#212121", textSecondary = "#757575",
        cardBackground = "#FFFFFF", buttonColor = "#1976D2",
        boardSkin = BoardSkins.WOOD_CLASSIC,
        pieceStyle = PieceStyles.EMBOSSED_3D
    )

    val LIGHT = AppTheme(
        id = "light", name = "浅色", isDark = false,
        backgroundColor = "#F5F5F5",
        primaryColor = "#1976D2", accentColor = "#FF5722",
        textPrimary = "#212121", textSecondary = "#757575",
        cardBackground = "#FFFFFF", buttonColor = "#1976D2",
        boardSkin = BoardSkins.LIGHT_BEECH,
        pieceStyle = PieceStyles.TRADITIONAL
    )

    val DARK = AppTheme(
        id = "dark", name = "深色", isDark = true,
        backgroundColor = "#121212",
        primaryColor = "#90CAF9", accentColor = "#FF8A65",
        textPrimary = "#E0E0E0", textSecondary = "#9E9E9E",
        cardBackground = "#1E1E1E", buttonColor = "#1976D2",
        boardSkin = BoardSkins.DARK_EBONY,
        pieceStyle = PieceStyles.EMBOSSED_3D
    )

    val JADE = AppTheme(
        id = "jade", name = "翡翠", isDark = false,
        backgroundColor = "#E8F5E9",
        primaryColor = "#2E7D32", accentColor = "#FF6F00",
        textPrimary = "#1B5E20", textSecondary = "#5D4037",
        cardBackground = "#FFFFFF", buttonColor = "#2E7D32",
        boardSkin = BoardSkins.JADE_GREEN,
        pieceStyle = PieceStyles.EMBOSSED_3D
    )

    val GOLD = AppTheme(
        id = "gold", name = "鎏金", isDark = false,
        backgroundColor = "#FFF9E6",
        primaryColor = "#B8860B", accentColor = "#C62828",
        textPrimary = "#3E2723", textSecondary = "#6D4C41",
        cardBackground = "#FFFBF0", buttonColor = "#B8860B",
        boardSkin = BoardSkins.GOLD_LUXE,
        pieceStyle = PieceStyles.GOLD_3D
    )

    val MINIMAL = AppTheme(
        id = "minimal", name = "极简白", isDark = false,
        backgroundColor = "#FFFFFF",
        primaryColor = "#424242", accentColor = "#D32F2F",
        textPrimary = "#212121", textSecondary = "#9E9E9E",
        cardBackground = "#FFFFFF", buttonColor = "#424242",
        boardSkin = BoardSkins.MINIMAL_WHITE,
        pieceStyle = PieceStyles.MODERN
    )

    val NIGHT = AppTheme(
        id = "night", name = "黑曜之夜", isDark = true,
        backgroundColor = "#0D0D0D",
        primaryColor = "#64B5F6", accentColor = "#E57373",
        textPrimary = "#ECEFF1", textSecondary = "#90A4AE",
        cardBackground = "#1A1A1A", buttonColor = "#1976D2",
        boardSkin = BoardSkins.BLACK_MARBLE,
        pieceStyle = PieceStyles.EMBOSSED_3D
    )

    val ALL = listOf(CLASSIC, LIGHT, DARK, JADE, GOLD, MINIMAL, NIGHT)
    fun findById(id: String) = ALL.firstOrNull { it.id == id } ?: CLASSIC
}
