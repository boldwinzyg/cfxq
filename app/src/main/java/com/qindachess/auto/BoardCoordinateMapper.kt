package com.qindachess.auto

import android.graphics.PointF
import android.util.Log
import com.qindachess.board.Move
import com.qindachess.board.Position
import kotlin.math.abs

/**
 * 棋盘坐标 → 目标APP屏幕坐标 映射器。
 *
 * 用"锚点"方案：用户在目标APP棋盘上点3个锚点（左上、右下、棋子中心任意1个），
 * 即可算出从 9×10 逻辑棋盘到屏幕像素的仿射变换。
 *
 * 未校准时使用默认棋盘估算（适配大多数 9宫格 象棋APP）。
 */
class BoardCoordinateMapper {

    companion object {
        private const val TAG = "CoordMapper"

        /** 棋盘左上角锚点（屏幕像素） */
        @Volatile var anchorTopLeft = PointF(120f, 420f)
        /** 棋盘右下角锚点（屏幕像素） */
        @Volatile var anchorBottomRight = PointF(960f, 1780f)
        /** 是否已经由用户校准过 */
        @Volatile var calibrated = false

        fun setAnchors(tl: PointF, br: PointF) {
            anchorTopLeft = tl
            anchorBottomRight = br
            calibrated = true
            Log.i(TAG, "anchors set: tl=$tl br=$br")
        }

        /**
         * 把 9×10 象棋棋盘上的一个格子(row 0..9, col 0..8)映射到屏幕坐标
         * row=0 是红方底线，row=9 是黑方底线（UI坐标常见约定）
         */
        fun gridToScreen(row: Int, col: Int): PointF {
            val xStep = (anchorBottomRight.x - anchorTopLeft.x) / 8f
            val yStep = (anchorBottomRight.y - anchorTopLeft.y) / 9f
            return PointF(
                anchorTopLeft.x + col * xStep,
                anchorTopLeft.y + row * yStep
            )
        }

        /**
         * 把 UCI (h8g8) 这种走法转成屏幕上的两个坐标点，用于 swipe。
         * 象棋 UCI 约定：红方在下方（rank 1=红方底线）。
         * Position.fromFenSquare 里 rank = 9 - (s[1]-'0')
         * → UCI "a0" = 黑方底线（row=9），"h2" = 红方底线（row=0）
         * 但我们 gridToScreen 里 row=0 应该对应屏幕底部（红方底线）。
         * 为了让 row=0 在屏幕下方，把 UCI row 翻转：9 - row。
         */
        fun uciToScreenSwipe(uci: String): Pair<PointF, PointF>? {
            val from = Position.fromFenSquare(uci.substring(0, 2)) ?: return null
            val to = Position.fromFenSquare(uci.substring(2, 4)) ?: return null
            val flip = { r: Int -> 9 - r }
            val p1 = gridToScreen(flip(from.row), from.col)
            val p2 = gridToScreen(flip(to.row), to.col)
            return p1 to p2
        }

        fun moveToScreen(move: Move): Pair<PointF, PointF> {
            val flip = { r: Int -> 9 - r }
            return gridToScreen(flip(move.from.row), move.from.col) to
                   gridToScreen(flip(move.to.row), move.to.col)
        }

        /** 估算棋盘中心（给截图识别用） */
        fun boardCenter(): PointF = PointF(
            (anchorTopLeft.x + anchorBottomRight.x) / 2f,
            (anchorTopLeft.y + anchorBottomRight.y) / 2f
        )

        fun boardSizePx(): PointF = PointF(
            abs(anchorBottomRight.x - anchorTopLeft.x),
            abs(anchorBottomRight.y - anchorTopLeft.y)
        )
    }
}
