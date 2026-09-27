/*
 * GlyphSize.kt
 * QRX
 *
 * The `size:` header of a glyph payload (mirror of GlyphSize.swift).
 *
 *     <width>x<height>[:selector] | <value> | ~
 *     width  := [*] number [anchor [offset]]
 */
package com.bclnet.qrx.core

enum class GlyphSelector(val id: String) {
    Fixed("fixed"), Normal("normal"), Focus("focus"), Active("active");

    val description: String get() = ":$id"

    companion object {
        fun of(s: String): GlyphSelector = when (s.lowercase().removePrefix(":")) {
            "fixed" -> Fixed
            "focus" -> Focus
            "active" -> Active
            else -> Normal
        }

        internal fun parse(s: String): GlyphSelector {
            val idx = s.lastIndexOf(':')
            return if (idx < 0) Normal else of(s.substring(idx))
        }
    }
}

data class GlyphSize(val selector: GlyphSelector = GlyphSelector.Normal, val width: Dimension, val height: Dimension) {

    enum class Anchor {
        /** left / top */ Start, Center, /** right / bottom */ End;

        val widthLetter: String get() = when (this) { Start -> "l"; End -> "r"; Center -> "c" }
        val heightLetter: String get() = when (this) { Start -> "t"; End -> "b"; Center -> "c" }

        companion object {
            fun of(s: String): Anchor? = when (s.lowercase()) {
                "l", "t" -> Start
                "r", "b" -> End
                "c" -> Center
                else -> null
            }
        }
    }

    /** One dimension of a size. */
    data class Dimension(
        /** When true `value` is a multiple of the code's size, otherwise it is in code units. */
        val multiple: Boolean,
        val value: Double,
        val anchor: Anchor = Anchor.Center,
        val offset: Double = 0.0,
    ) {
        fun description(letter: (Anchor) -> String): String = buildString {
            if (multiple) append('*')
            append(clean(value))
            if (anchor != Anchor.Center || offset != 0.0) append(letter(anchor))
            if (offset != 0.0) append(clean(offset))
        }

        /** Resolves against the code's size in some unit: (size, offset from the code's centre). */
        fun resolve(code: Double): Pair<Double, Double> {
            val size = if (multiple) value * code else value
            val anchored = when (anchor) {
                Anchor.Center -> 0.0
                Anchor.Start -> -(code - size) / 2
                Anchor.End -> (code - size) / 2
            }
            return size to anchored + offset
        }

        companion object {
            val unit = Dimension(multiple = true, value = 1.0)

            /** Parses `[*]value[anchor[offset]]`. A trailing `:selector` is ignored. */
            fun parse(s: String): Dimension? {
                val colon = s.lastIndexOf(':')
                var rest = if (colon >= 0) s.substring(0, colon) else s
                val multiple = rest.startsWith("*")
                if (multiple) rest = rest.substring(1)
                val numberEnd = rest.indexOfFirst { it !in "+-.0123456789" }.let { if (it < 0) rest.length else it }
                val value = rest.substring(0, numberEnd).toDoubleOrNull() ?: return null
                if (numberEnd >= rest.length) return Dimension(multiple, value)
                val anchor = Anchor.of(rest[numberEnd].toString()) ?: return null
                val offsetText = rest.substring(numberEnd + 1)
                val offset = if (offsetText.isEmpty()) 0.0 else offsetText.toDoubleOrNull() ?: return null
                return Dimension(multiple, value, anchor, offset)
            }
        }
    }

    val description: String
        get() {
            if (this == zero) return "~"
            var b = width.description { it.widthLetter } + "x" + height.description { it.heightLetter }
            if (selector != GlyphSelector.Normal) b += selector.description
            return b
        }

    override fun toString(): String = description

    companion object {
        val zero = GlyphSize(GlyphSelector.Normal, Dimension.unit, Dimension.unit)

        fun parse(s: String): GlyphSize? {
            val text = s.trim()
            if (text.isEmpty() || text == "~") return zero
            val parts = text.split('x', 'X', limit = 2)
            val width = Dimension.parse(parts[0]) ?: return null
            val last = if (parts.size > 1) parts[1] else parts[0]
            val height = Dimension.parse(last) ?: return null
            return GlyphSize(GlyphSelector.parse(last), width, height)
        }

        internal fun clean(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    }
}

/** The size for `selector`, falling back to Normal, then the default size. */
fun Map<GlyphSelector, GlyphSize>.sizeFor(selector: GlyphSelector): GlyphSize =
    this[selector] ?: this[GlyphSelector.Normal] ?: GlyphSize.zero
