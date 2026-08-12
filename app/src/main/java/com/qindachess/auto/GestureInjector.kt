package com.qindachess.auto

import android.graphics.Bitmap
import android.graphics.PointF

interface GestureInjector {

    fun name(): String

    fun isAvailable(): Boolean

    fun tap(x: Float, y: Float): Boolean

    fun swipe(from: PointF, to: PointF, durationMs: Long = 150): Boolean

    fun dragPath(points: List<PointF>, totalDurationMs: Long = 200): Boolean

    fun screenshot(): Bitmap?
}
