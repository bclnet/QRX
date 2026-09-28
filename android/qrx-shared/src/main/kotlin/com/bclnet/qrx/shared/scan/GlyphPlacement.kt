/*
 * GlyphPlacement.kt
 * QRX
 *
 * Pure geometry for the 2D overlay: where to draw a glyph's content on the
 * camera preview given the detected code's bounding box and the glyph's
 * `size:` rule. The image-to-view mapping matches CameraX's PreviewView
 * FILL_CENTER scaling. Kept free of Android types so it can be unit tested.
 */
package com.bclnet.qrx.shared.scan

import com.bclnet.qrx.core.GlyphSize

/** An axis-aligned rectangle in some coordinate space. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    companion object {
        fun centered(cx: Float, cy: Float, width: Float, height: Float) = Box(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
    }
}

/** Maps analysis-image coordinates to view coordinates (rotation already applied to the image size). */
data class ImageToView(val imageWidth: Float, val imageHeight: Float, val viewWidth: Float, val viewHeight: Float) {
    /** FILL_CENTER: scale so the image covers the view, then centre it. */
    val scale: Float get() = maxOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val offsetX: Float get() = (viewWidth - imageWidth * scale) / 2
    val offsetY: Float get() = (viewHeight - imageHeight * scale) / 2

    fun map(box: Box): Box = Box(
        box.left * scale + offsetX, box.top * scale + offsetY,
        box.right * scale + offsetX, box.bottom * scale + offsetY,
    )
}

object GlyphPlacement {
    /**
     * The content rectangle for a code occupying `code` (in view coordinates),
     * following the glyph's size rule: `*` values are multiples of the code's
     * size, absolute values are in code units (one unit = the code's width),
     * anchors align an edge of the content with the same edge of the code.
     */
    fun place(code: Box, size: GlyphSize): Box {
        val unit = code.width
        val width = if (size.width.multiple) size.width.value * code.width else size.width.value * unit
        val height = if (size.height.multiple) size.height.value * code.height else size.height.value * unit
        val dx = offset(size.width, code.width, width, unit)
        val dy = offset(size.height, code.height, height, unit)
        return Box.centered(code.centerX + dx.toFloat(), code.centerY + dy.toFloat(), width.toFloat(), height.toFloat())
    }

    private fun offset(dimension: GlyphSize.Dimension, code: Float, content: Double, unit: Float): Double {
        val anchored = when (dimension.anchor) {
            GlyphSize.Anchor.Center -> 0.0
            GlyphSize.Anchor.Start -> -(code - content) / 2
            GlyphSize.Anchor.End -> (code - content) / 2
        }
        return anchored + if (dimension.multiple) dimension.offset * code else dimension.offset * unit
    }

    /** Bounding box of corner points (x, y pairs). */
    fun bounds(points: List<Pair<Float, Float>>): Box? {
        if (points.isEmpty()) return null
        return Box(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
    }
}
