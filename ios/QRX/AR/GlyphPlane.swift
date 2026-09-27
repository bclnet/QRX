//
//  GlyphPlane.swift
//  QRX
//
//  Pure geometry: the size and offset (in metres) of the content plane
//  anchored on a tracked code, from the glyph's `size:` rule. Kept free of
//  ARKit so it can be unit tested.
//

import Foundation
import QRXCore

struct GlyphPlane: Equatable {
    /// Content size in metres.
    var width: Double
    var height: Double
    /// Offset of the content centre from the code centre, in metres (x right, y up in the code's plane).
    var offsetX: Double
    var offsetY: Double

    /// `physical` is the tracked code's physical size in metres.
    init(size: GlyphSize, physicalWidth: Double, physicalHeight: Double, unit: Double? = nil) {
        // Absolute values in the size rule are in "code units": one unit is the code's width.
        let codeUnit = unit ?? physicalWidth
        let w = size.width.resolve(code: physicalWidth)
        let h = size.height.resolve(code: physicalHeight)
        width = size.width.multiple ? w.size : size.width.value * codeUnit
        height = size.height.multiple ? h.size : size.height.value * codeUnit
        // Anchor offsets computed by resolve() assume the same unit; recompute for absolute sizes.
        offsetX = GlyphPlane.offset(size.width, code: physicalWidth, content: width, unit: codeUnit)
        // Screen/plane y grows upwards; a "bottom" anchor moves the content down.
        offsetY = -GlyphPlane.offset(size.height, code: physicalHeight, content: height, unit: codeUnit)
    }

    private static func offset(_ dimension: GlyphSize.Dimension, code: Double, content: Double, unit: Double) -> Double {
        let anchored: Double
        switch dimension.anchor {
        case .center: anchored = 0
        case .start: anchored = -(code - content) / 2
        case .end: anchored = (code - content) / 2
        }
        return anchored + (dimension.multiple ? dimension.offset * code : dimension.offset * unit)
    }
}
