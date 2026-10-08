package com.mosaic.gallery

import kotlin.math.roundToInt

/** Crop coordinates are normalized to the oriented image, independent of screen/preview size. */
data class CropBounds(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    data class Pixels(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width() = right - left
        fun height() = bottom - top
    }
    fun pixelRect(width: Int, height: Int): Pixels {
        require(width > 0 && height > 0)
        val x = (left * width).roundToInt().coerceIn(0, width - 1)
        val y = (top * height).roundToInt().coerceIn(0, height - 1)
        return Pixels(x, y, (right * width).roundToInt().coerceIn(x + 1, width),
            (bottom * height).roundToInt().coerceIn(y + 1, height))
    }
}
