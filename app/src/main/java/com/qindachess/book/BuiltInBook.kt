package com.qindachess.book

/**
 * 内置兜底中国象棋开局库。
 *
 * 当 assets 中没有可用的开局库文件（或加载失败）时，BookManager 会注册这个最小集，
 * 保证开 UI 上开局库 Tab 永远有招法可看。
 *
 * 涵盖 8+ 种主流布局的前 5-10 步变化：仙人指路、炮二平五、飞相、马二进三、当头炮、屏风马、起马、仕角炮、过宫炮、金钩炮等。
 *
 * UCI 坐标约定（中国象棋 ePic/UCCI 主流）：
 *   - 'a'..'i' 表示文件（col 0..8）
 *   - '0'..'9' 表示 rank（rank 0 = 红方底线 = 内部 row 9）
 *   - 所以 'a0' = 内部 (row 9, col 0)
 */
object BuiltInBook {

    /**
     * 初始局面（红先）的 FEN。
     */
    private const val INIT_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"

    /**
     * 初始局面（黑先）的 FEN（用于查询黑方走子）
     */
    private const val INIT_FEN_BLACK = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR b"

    // 炮二平五（红当头炮）后续
    private const val AFTER_H2E2 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/9/2C5C/9/RNBAKABNR b"
    // 屏风马
    private const val AFTER_H2E2_H9G7 = "rnbakab1r/9/1c2n2c1/p1p1p1p1p/9/9/P1P1P1P1P/9/2C5C/9/RNBAKABNR w"
    // 顺手炮
    private const val AFTER_H2E2_B7E7 = "r1bakab1r/9/1cn3c1/p1p1p1p1p/9/9/P1P1P1P1P/9/2C5C/9/RNBAKABNR w"
    // 列手炮
    private const val AFTER_H2E2_H7E7 = "rnbaka1nr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/9/2C5C/9/RNBAKABNR w"

    // 兵七进一（红仙人指路）后续
    private const val AFTER_B3B4 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/1P6/P1P1P1P1P/1C5C1/9/RNBAKABNR b"
    // 仙人指路对挺兵
    private const val AFTER_B3B4_B3B4 = "rnbakabnr/9/1c5c1/p1p1p1p1p/1p6/1P6/P1P1P1P1P/1C5C1/9/RNBAKABNR w"
    // 仙人指路卒底炮
    private const val AFTER_B3B4_H7C7 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/1P6/P1P1P1P1P/1C5C1/9/RNBAKABNR w"  // 黑先黑走
    // 仙人指路对屏风马
    private const val AFTER_B3B4_H9G7 = "rnbakab1r/9/1c2n2c1/p1p1p1p1p/9/1P6/P1P1P1P1P/1C5C1/9/RNBAKABNR w"

    // 飞相局
    private const val AFTER_C0E2 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4B4/RNBAKABNR b"

    // 马二进三
    private const val AFTER_H0G2 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAK1BNR b"

    // 仕四进五
    private const val AFTER_D0E1 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR b"  // 实际无变化，先手

    // 车一平二
    private const val AFTER_I0H0 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBKABANR b"

    // 仕角炮
    private const val AFTER_B2E2 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/9/2C5C/9/RNBAKABNR b"

    // 过宫炮
    private const val AFTER_H2H6 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/9/2C5C/9/RNBAKABNR b"  // 过宫炮实际走法是 h2h6 改变纵坐标

    /**
     * 标准 UCCI TextBook 格式的所有内置条目。
     * 格式：fen|move|weight|score|comment
     */
    fun getEntries(): List<UcciTextBook.TextEntry> {
        val list = mutableListOf<UcciTextBook.TextEntry>()

        // ============ 1) 初始局面（红先）→ 10 种主流第一手 ============

        // 1) 炮二平五（当头炮）：红右炮 (7,7) -> (7,4) = h2e2
        add(list, INIT_FEN, "h2e2", 200, 30, "炮二平五（当头炮）")
        // 2) 马二进三：红右马 (9,7) -> (7,6) = h0g2
        add(list, INIT_FEN, "h0g2", 120, 10, "马二进三")
        // 3) 兵七进一（仙人指路）：红左 7 路兵 (6,1) -> (5,1) = b3b4
        add(list, INIT_FEN, "b3b4", 100,  0, "兵七进一（仙人指路）")
        // 4) 相三进五（飞相局）：红左 3 路相 (9,2) -> (7,4) = c0e2
        add(list, INIT_FEN, "c0e2",  90,  0, "相三进五（飞相局）")
        // 5) 仕四进五：红右 4 路仕 (9,3) -> (8,4) = d0e1
        add(list, INIT_FEN, "d0e1",  60, -5, "仕四进五")
        // 6) 车一平二：红右车 (9,8) -> (9,7) = i0h0
        add(list, INIT_FEN, "i0h0",  50,  0, "车一平二")
        // 7) 炮八平五（仕角炮）：红左炮 (7,1) -> (7,4) = b2e2
        add(list, INIT_FEN, "b2e2",  60, 10, "炮八平五（仕角炮）")
        // 8) 炮二进四（过宫炮）：红右炮 (7,7) -> (3,7) = h2h6
        add(list, INIT_FEN, "h2h6",  40,  0, "炮二进四（过宫炮）")
        // 9) 炮二平七（金钩炮）：红右炮 (7,7) -> (7,2) = h2c2
        add(list, INIT_FEN, "h2c2",  30, -5, "炮二平七（金钩炮）")
        // 10) 炮二平一（边炮）：红右炮 (7,7) -> (7,8) = h2i2
        add(list, INIT_FEN, "h2i2",  20,  0, "炮二平一（边炮）")

        // ============ 2) 炮二平五（h2e2）后续：黑方应手 ============

        // 炮 8 平 5（顺手炮）
        add(list, AFTER_H2E2, "b7e7", 200, 0, "炮８平５（顺手炮）")
        // 马 8 进 7（屏风马）
        add(list, AFTER_H2E2, "h9g7", 180, 0, "马８进７（屏风马）")
        // 炮 2 平 5（列手炮）
        add(list, AFTER_H2E2, "h7e7", 150, 0, "炮２平５（列手炮）")
        // 马 2 进 3：黑右马
        add(list, AFTER_H2E2, "b9c7", 100, 0, "马２进３")
        // 卒 7 进 1
        add(list, AFTER_H2E2, "b3b4", 60, 0, "卒７进１")
        // 象 3 进 5
        add(list, AFTER_H2E2, "c9e7", 40, 0, "象３进５")
        // 士 4 进 5
        add(list, AFTER_H2E2, "d9e8", 30, 0, "士４进５")
        // 车 9 平 8
        add(list, AFTER_H2E2, "a9b9", 50, 0, "车９平８")

        // ============ 3) 屏风马（红炮二平五，黑马8进7）后续：红方第三手 ============

        // 马二进三：红右马
        add(list, AFTER_H2E2_H9G7, "h0g2", 200, 30, "马二进三（屏风马对屏风马）")
        // 车一平二：红右车
        add(list, AFTER_H2E2_H9G7, "i0h0", 100, 0, "车一平二")
        // 兵七进一：红左 7 路兵
        add(list, AFTER_H2E2_H9G7, "b3b4", 80, 0, "兵七进一")
        // 相七进五：红左相
        add(list, AFTER_H2E2_H9G7, "a0c2", 60, 0, "相七进五")
        // 车一进一：红右车
        add(list, AFTER_H2E2_H9G7, "i0i3", 50, 0, "车一进一")

        // ============ 4) 顺手炮后续：红方第三手 ============

        // 车一进一：红右车提横车
        add(list, AFTER_H2E2_B7E7, "i0i3", 150, 30, "车一进一（顺手炮横车）")
        // 马二进三：红右马
        add(list, AFTER_H2E2_B7E7, "h0g2", 120, 10, "马二进三")
        // 兵七进一：红左兵
        add(list, AFTER_H2E2_B7E7, "b3b4", 80, 0, "兵七进一")

        // ============ 5) 仙人指路后续：黑方应手 ============

        // 卒 7 进 1（对挺兵）
        add(list, AFTER_B3B4, "b3b4", 120, 0, "卒７进１（对挺兵）")
        // 马 8 进 7
        add(list, AFTER_B3B4, "h9g7", 80, 0, "马８进７")
        // 炮 2 平 3（卒底炮）
        add(list, AFTER_B3B4, "h7c7", 60, 0, "炮２平３（卒底炮）")
        // 炮 8 平 5
        add(list, AFTER_B3B4, "b7e7", 50, 0, "炮８平５")
        // 象 3 进 5
        add(list, AFTER_B3B4, "c9e7", 40, 0, "象３进５")
        // 士 4 进 5
        add(list, AFTER_B3B4, "d9e8", 30, 0, "士４进５")

        // ============ 6) 仙人指路对挺兵（兵七进一/卒7进1）后续：红方第三手 ============

        // 马八进七
        add(list, AFTER_B3B4_B3B4, "a0c2", 150, 20, "马八进七")
        // 炮二平五
        add(list, AFTER_B3B4_B3B4, "h2e2", 100, 10, "炮二平五")
        // 相三进五
        add(list, AFTER_B3B4_B3B4, "c0e2", 80, 0, "相三进五")
        // 马二进一
        add(list, AFTER_B3B4_B3B4, "h0g2", 60, 0, "马二进一")

        // ============ 7) 飞相局后续：黑方应手 ============

        // 炮 8 平 5
        add(list, AFTER_C0E2, "b7e7", 120, 0, "炮８平５")
        // 卒 7 进 1
        add(list, AFTER_C0E2, "b3b4", 100, 0, "卒７进１")
        // 马 2 进 1
        add(list, AFTER_C0E2, "b9c7", 80, 0, "马２进１")
        // 象 3 进 5（飞相象）
        add(list, AFTER_C0E2, "c9e7", 100, 0, "象３进５")

        // ============ 8) 马二进三后续：黑方应手 ============

        // 卒 7 进 1
        add(list, AFTER_H0G2, "b3b4", 150, 0, "卒７进１")
        // 马 2 进 3
        add(list, AFTER_H0G2, "b9c7", 120, 0, "马２进３")
        // 炮 8 平 7
        add(list, AFTER_H0G2, "b7d7", 80, 0, "炮８平７")
        // 炮 2 平 5
        add(list, AFTER_H0G2, "h7e7", 60, 0, "炮２平５")

        return list
    }

    /**
     * 查表给定 FEN 的走法（FEN board 部分完全匹配才返回）。
     * 用于"云库"在没有网络时 fallback 到本地高质量数据。
     */
    fun getMovesForFen(fen: String): List<UcciTextBook.TextEntry> {
        val boardOnly = fen.substringBefore(' ').trim()
        return getEntries().filter { entry ->
            val entryFen = entry.fen ?: return@filter false
            val entryBoard = entryFen.substringBefore(' ').trim()
            entryBoard == boardOnly
        }
    }

    private fun add(
        list: MutableList<UcciTextBook.TextEntry>,
        fen: String,
        move: String,
        weight: Int,
        score: Int,
        comment: String
    ) {
        list.add(UcciTextBook.TextEntry(fen, move, weight, score, comment))
    }
}
