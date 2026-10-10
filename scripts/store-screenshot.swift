// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Frames a raw device screenshot for the Google Play listing: the app's
// blue sky as background, a caption on top, the screenshot as a rounded
// card below it. Play wants 16:9 or 9:16, which no phone screen is, so
// the phone card runs off the bottom edge; on the tablet canvases the
// whole screenshot fits.
//
//   swift scripts/store-screenshot.swift <in.png> <out.png> <width> <height> <caption> [<subcaption>]
//
// Used by scripts/store-assets.sh, which knows the sizes and captions.

import AppKit
import Foundation

let args = CommandLine.arguments
guard args.count >= 6, let width = Int(args[3]), let height = Int(args[4]) else {
    FileHandle.standardError.write("usage: store-screenshot.swift <in.png> <out.png> <width> <height> <caption> [<subcaption>]\n".data(using: .utf8)!)
    exit(2)
}
let input = URL(fileURLWithPath: args[1])
let output = URL(fileURLWithPath: args[2])
let caption = args[5]
let subcaption = args.count > 6 ? args[6] : ""

guard let source = CGImageSourceCreateWithURL(input as CFURL, nil),
      let shot = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    FileHandle.standardError.write("cannot read \(input.path)\n".data(using: .utf8)!)
    exit(1)
}

func color(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(red: CGFloat((hex >> 16) & 0xff) / 255, green: CGFloat((hex >> 8) & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255, alpha: alpha)
}

let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.interpolationQuality = .high
let rgb = CGColorSpaceCreateDeviceRGB()
let sky = CGGradient(colorsSpace: rgb, colors: [color(0x4A5C9A), color(0x27305A), color(0x161B33)] as CFArray,
                     locations: [0, 0.55, 1])!
ctx.drawLinearGradient(sky, start: CGPoint(x: 0, y: CGFloat(height)), end: .zero, options: [])

// Everything scales with the short side, so phone and tablet canvases
// get the same proportions.
let unit = CGFloat(min(width, height)) / 1080
let portrait = height > width
let margin = 90 * unit
let titleSize = (portrait ? 64 : 56) * unit
let subSize = (portrait ? 36 : 32) * unit

/// Draws one centred line; a caption too wide for the canvas is set
/// smaller rather than clipped.
func drawCentered(_ string: String, size: CGFloat, weight: NSFont.Weight, alpha: CGFloat, baseline: CGFloat) {
    var size = size
    var line: CTLine
    var lineWidth: CGFloat
    repeat {
        let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: size, weight: weight),
                                                         .foregroundColor: NSColor(white: 1, alpha: alpha)]
        line = CTLineCreateWithAttributedString(NSAttributedString(string: string, attributes: attributes))
        lineWidth = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        size -= 1
    } while lineWidth > CGFloat(width) - 2 * margin
    ctx.textPosition = CGPoint(x: (CGFloat(width) - lineWidth) / 2, y: baseline)
    CTLineDraw(line, ctx)
}

var top = CGFloat(height) - margin
drawCentered(caption, size: titleSize, weight: .bold, alpha: 1, baseline: top - titleSize)
top -= titleSize * 1.25
if !subcaption.isEmpty {
    drawCentered(subcaption, size: subSize, weight: .regular, alpha: 0.8, baseline: top - subSize)
    top -= subSize * 1.3
}
top -= margin * 0.6

// The card: scaled to the width between the margins; on a portrait
// canvas it may run off the bottom, on landscape it has to fit.
let shotSize = CGSize(width: shot.width, height: shot.height)
var scale = (CGFloat(width) - 2 * margin) / shotSize.width
if !portrait {
    scale = min(scale, (top - margin * 0.6) / shotSize.height)
}
let card = CGRect(x: (CGFloat(width) - shotSize.width * scale) / 2, y: top - shotSize.height * scale,
                  width: shotSize.width * scale, height: shotSize.height * scale)
// Landscape screenshots carry the clock in the top-left corner, so their
// corners stay tighter than the phone card's.
let radius = (portrait ? 40 : 16) * unit
let path = CGPath(roundedRect: card, cornerWidth: radius, cornerHeight: radius, transform: nil)
ctx.saveGState()
ctx.setShadow(offset: CGSize(width: 0, height: -12 * unit), blur: 48 * unit, color: color(0x000000, 0.5))
ctx.setFillColor(color(0x000000))
ctx.addPath(path)
ctx.fillPath()
ctx.restoreGState()
ctx.saveGState()
ctx.addPath(path)
ctx.clip()
ctx.draw(shot, in: card)
ctx.restoreGState()
ctx.setStrokeColor(color(0xFFFFFF, 0.18))
ctx.setLineWidth(2 * unit)
ctx.addPath(path)
ctx.strokePath()

let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
try rep.representation(using: .png, properties: [:])!.write(to: output)
