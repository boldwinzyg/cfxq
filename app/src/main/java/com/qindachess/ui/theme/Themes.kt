package com.qindachess.ui.theme

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

data class PieceStyle(
    val id: String,
    val name: String,
    val useTraditional: Boolean,
    val showCharacter: Boolean,
    val showBorder: Boolean,
    val borderWidth: Float,
    val shadowEnabled: Boolean
)

data class AppTheme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val backgroundColor: String,
    val primaryColor: String,
    val accentColor: String,
    val textPrimary: String,
    val textSecondary: String,
    val cardBackground: String,
    val buttonColor: String,
    val boardSkin: BoardSkin,
    val pieceStyle: PieceStyle
)

object BoardSkins {
    val WOOD_CLASSIC = BoardSkin(
        id = "wood_classic",
        name = "传统木色",
        boardBg = "#E8D5B5",
        boardBorder = "#8D6E63",
        gridLine = "#2C1810",
        redPiece = "#C62828",
        redPieceBorder = "#8B0000",
        blackPiece = "#212121",
        blackPieceBorder = "#000000",
        pieceBg = "#FFFFFF",
        pieceInner = "#F5F5F5",
        riverText = "#8D6E63",
        crossMark = "#2C1810"
    )

    val LIGHT_BEECH = BoardSkin(
        id = "light_beech",
        name = "浅色榉木",
        boardBg = "#F5E6D3",
        boardBorder = "#B8956E",
        gridLine = "#5D4037",
        redPiece = "#D32F2F",
        redPieceBorder = "#7F0000",
        blackPiece = "#1A1A1A",
        blackPieceBorder = "#000000",
        pieceBg = "#FFFBF5",
        pieceInner = "#EFE9DE",
        riverText = "#6D4C41",
        crossMark = "#5D4037"
    )

    val DARK_EBONY = BoardSkin(
        id = "dark_ebony",
        name = "深色乌木",
        boardBg = "#2B1810",
        boardBorder = "#5D4037",
        gridLine = "#C0A080",
        redPiece = "#FF5252",
        redPieceBorder = "#FF1744",
        blackPiece = "#E0E0E0",
        blackPieceBorder = "#FFFFFF",
        pieceBg = "#3E2723",
        pieceInner = "#4E342E",
        riverText = "#BCAAA4",
        crossMark = "#A1887F"
    )

    val JADE_GREEN = BoardSkin(
        id = "jade_green",
        name = "翡翠绿",
        boardBg = "#C8E6C9",
        boardBorder = "#2E7D32",
        gridLine = "#1B5E20",
        redPiece = "#C62828",
        redPieceBorder = "#8B0000",
        blackPiece = "#1B5E20",
        blackPieceBorder = "#0D3B12",
        pieceBg = "#FFFFFF",
        pieceInner = "#E8F5E9",
        riverText = "#2E7D32",
        crossMark = "#1B5E20"
    )

    val MINIMAL_WHITE = BoardSkin(
        id = "minimal_white",
        name = "极简白",
        boardBg = "#FAFAFA",
        boardBorder = "#9E9E9E",
        gridLine = "#424242",
        redPiece = "#E53935",
        redPieceBorder = "#B71C1C",
        blackPiece = "#212121",
        blackPieceBorder = "#000000",
        pieceBg = "#FFFFFF",
        pieceInner = "#FAFAFA",
        riverText = "#757575",
        crossMark = "#424242"
    )

    val ALL = listOf(WOOD_CLASSIC, LIGHT_BEECH, DARK_EBONY, JADE_GREEN, MINIMAL_WHITE)

    fun findById(id: String): BoardSkin = ALL.firstOrNull { it.id == id } ?: WOOD_CLASSIC
}

object PieceStyles {
    val TRADITIONAL = PieceStyle(
        id = "traditional",
        name = "传统汉字",
        useTraditional = true,
        showCharacter = true,
        showBorder = true,
        borderWidth = 2f,
        shadowEnabled = false
    )

    val SIMPLIFIED = PieceStyle(
        id = "simplified",
        name = "简化汉字",
        useTraditional = false,
        showCharacter = true,
        showBorder = true,
        borderWidth = 2f,
        shadowEnabled = false
    )

    val ICON_ONLY = PieceStyle(
        id = "icon_only",
        name = "图标模式",
        useTraditional = true,
        showCharacter = false,
        showBorder = true,
        borderWidth = 2f,
        shadowEnabled = true
    )

    val MODERN = PieceStyle(
        id = "modern",
        name = "现代简约",
        useTraditional = false,
        showCharacter = true,
        showBorder = false,
        borderWidth = 0f,
        shadowEnabled = false
    )

    val ALL = listOf(TRADITIONAL, SIMPLIFIED, ICON_ONLY, MODERN)

    fun findById(id: String): PieceStyle = ALL.firstOrNull { it.id == id } ?: TRADITIONAL
}

object AppThemes {
    val LIGHT = AppTheme(
        id = "light",
        name = "浅色",
        isDark = false,
        backgroundColor = "#F5F5F5",
        primaryColor = "#1976D2",
        accentColor = "#FF5722",
        textPrimary = "#212121",
        textSecondary = "#757575",
        cardBackground = "#FFFFFF",
        buttonColor = "#1976D2",
        boardSkin = BoardSkins.WOOD_CLASSIC,
        pieceStyle = PieceStyles.TRADITIONAL
    )

    val DARK = AppTheme(
        id = "dark",
        name = "深色",
        isDark = true,
        backgroundColor = "#121212",
        primaryColor = "#90CAF9",
        accentColor = "#FF8A65",
        textPrimary = "#E0E0E0",
        textSecondary = "#9E9E9E",
        cardBackground = "#1E1E1E",
        buttonColor = "#1976D2",
        boardSkin = BoardSkins.DARK_EBONY,
        pieceStyle = PieceStyles.TRADITIONAL
    )

    val ALL = listOf(LIGHT, DARK)

    fun findById(id: String): AppTheme = ALL.firstOrNull { it.id == id } ?: LIGHT
}
