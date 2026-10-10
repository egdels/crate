// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Draws the app icon: three records standing in a wooden crate on a dark
// rounded square, in the proportions of Apple's macOS icon template
// (the square fills 824 of 1024 points). Run via scripts/icon.sh, which
// turns the PNGs into Packaging/AppIcon.icns.
//
//   swift scripts/icon.swift <output directory> [--android <res directory>]
//
// With --android it also writes the Android launcher icon's foreground
// layer into the app's res/ folder, mipmap-*/ic_launcher_foreground.png:
// the scene without frame or shadow on the full canvas, so that every
// launcher mask shape shows sky around the crate. (The app's minimum
// Android version has adaptive icons, so no legacy square is needed.)

import AppKit
import Foundation

let sizes: [(Int, Int)] = [(16, 1), (16, 2), (32, 1), (32, 2), (128, 1), (128, 2), (256, 1), (256, 2), (512, 1), (512, 2)]
let outDir = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

func color(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(red: CGFloat((hex >> 16) & 0xff) / 255, green: CGFloat((hex >> 8) & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255, alpha: alpha)
}

/// Everything is drawn in a 1024-point space, origin bottom-left: a
/// record pulled halfway out of a crate, the way you flip through one.
/// `framed` is the macOS icon: the scene inside a rounded square with a
/// drop shadow. Unframed (Android's adaptive foreground) the sky fills
/// the whole canvas and the scene sits a little smaller in the middle, so
/// that a circular mask does not clip the crate's corners.
func draw(in ctx: CGContext, scale: CGFloat, framed: Bool = true) {
    ctx.scaleBy(x: scale, y: scale)
    let canvas = CGRect(x: 0, y: 0, width: 1024, height: 1024)
    let square = framed ? canvas.insetBy(dx: 100, dy: 100) : canvas
    let radius: CGFloat = square.width * 0.2237
    let squarePath = CGPath(roundedRect: square, cornerWidth: radius, cornerHeight: radius, transform: nil)
    let rgb = CGColorSpaceCreateDeviceRGB()

    if framed {
        // Drop shadow under the square, like the system icons.
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -14), blur: 40, color: color(0x000000, 0.45))
        ctx.addPath(squarePath)
        ctx.setFillColor(color(0x1A2238))
        ctx.fillPath()
        ctx.restoreGState()
    }

    ctx.saveGState()
    if framed {
        ctx.addPath(squarePath)
        ctx.clip()
    }

    // Background: deep blue, lit from the top, darker at the bottom.
    let sky = CGGradient(colorsSpace: rgb, colors: [color(0x4A5C9A), color(0x27305A), color(0x161B33)] as CFArray,
                         locations: [0, 0.55, 1])!
    ctx.drawLinearGradient(sky, start: CGPoint(x: 0, y: square.maxY), end: CGPoint(x: 0, y: square.minY), options: [])
    if !framed {
        ctx.translateBy(x: 512, y: 512)
        ctx.scaleBy(x: 0.78, y: 0.78)
        ctx.translateBy(x: -512, y: -512)
    }
    let glow = CGGradient(colorsSpace: rgb, colors: [color(0xFFFFFF, 0.18), color(0xFFFFFF, 0)] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(glow, startCenter: CGPoint(x: 400, y: 880), startRadius: 0,
                           endCenter: CGPoint(x: 400, y: 880), endRadius: 520, options: [])

    // Crate geometry. The front face covers the lower part; the records
    // stand behind it. A sliver of the inside back wall shows above the
    // front edge, which is what makes it a box and not a fence.
    let front = CGRect(x: 196, y: 150, width: 632, height: 320)
    let inside = CGRect(x: front.minX + 12, y: front.maxY - 40, width: front.width - 24, height: 150)
    ctx.setFillColor(color(0x2A1A0E))
    ctx.fill(inside)
    let insideShade = CGGradient(colorsSpace: rgb, colors: [color(0x000000, 0.55), color(0x000000, 0)] as CFArray, locations: [0, 1])!
    ctx.saveGState()
    ctx.clip(to: inside)
    ctx.drawLinearGradient(insideShade, start: CGPoint(x: 0, y: inside.maxY), end: CGPoint(x: 0, y: inside.minY), options: [])
    ctx.restoreGState()

    // Two records still in the crate: just their rims above the front edge.
    for (x, y) in [(CGFloat(425), CGFloat(420)), (CGFloat(610), CGFloat(428))] {
        let r: CGFloat = 200
        let disc = CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -6), blur: 16, color: color(0x000000, 0.5))
        ctx.setFillColor(color(0x121216))
        ctx.fillEllipse(in: disc)
        ctx.restoreGState()
        ctx.setStrokeColor(color(0xFFFFFF, 0.07))
        ctx.setLineWidth(2.5)
        var ring = r - 12
        while ring > r * 0.55 {
            ctx.strokeEllipse(in: CGRect(x: x - ring, y: y - ring, width: ring * 2, height: ring * 2))
            ring -= 11
        }
    }

    // The hero record, pulled up and tilted a little, label showing.
    ctx.saveGState()
    let center = CGPoint(x: 512, y: 560)
    let r: CGFloat = 262
    ctx.translateBy(x: center.x, y: center.y)
    ctx.rotate(by: -0.12)
    let disc = CGRect(x: -r, y: -r, width: r * 2, height: r * 2)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -10), blur: 28, color: color(0x000000, 0.6))
    ctx.setFillColor(color(0x0E0E12))
    ctx.fillEllipse(in: disc)
    ctx.restoreGState()
    // Vinyl: near-black with a faint radial sheen, grooves in bands.
    ctx.saveGState()
    ctx.addEllipse(in: disc)
    ctx.clip()
    let vinyl = CGGradient(colorsSpace: rgb, colors: [color(0x2A2A32), color(0x111115)] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(vinyl, startCenter: CGPoint(x: -r * 0.35, y: r * 0.45), startRadius: 0,
                           endCenter: .zero, endRadius: r * 1.1, options: [])
    ctx.setLineWidth(2)
    var ring = r - 14
    var band = 0
    while ring > r * 0.40 {
        ctx.setStrokeColor(color(0xFFFFFF, band % 9 == 0 ? 0.03 : 0.085))
        ctx.strokeEllipse(in: CGRect(x: -ring, y: -ring, width: ring * 2, height: ring * 2))
        ring -= 7
        band += 1
    }
    // Light catching the grooves: two soft arcs, upper left and lower right.
    for (angle, alpha) in [(CGFloat(2.1), 0.22), (CGFloat(-1.0), 0.12)] {
        let sheen = CGGradient(colorsSpace: rgb, colors: [color(0xFFFFFF, alpha), color(0xFFFFFF, 0)] as CFArray, locations: [0, 1])!
        ctx.saveGState()
        ctx.rotate(by: angle)
        ctx.addRect(CGRect(x: -r, y: 0, width: r * 2, height: r))
        ctx.clip()
        ctx.drawRadialGradient(sheen, startCenter: .zero, startRadius: r * 0.42, endCenter: .zero, endRadius: r, options: [])
        ctx.restoreGState()
    }
    ctx.restoreGState()
    // Label: warm orange with a thin cream ring and the spindle hole.
    let labelRadius = r * 0.37
    let labelRect = CGRect(x: -labelRadius, y: -labelRadius, width: labelRadius * 2, height: labelRadius * 2)
    ctx.saveGState()
    ctx.addEllipse(in: labelRect)
    ctx.clip()
    let label = CGGradient(colorsSpace: rgb, colors: [color(0xFFB347), color(0xE8702A)] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(label, start: CGPoint(x: 0, y: labelRadius), end: CGPoint(x: 0, y: -labelRadius), options: [])
    ctx.restoreGState()
    ctx.setStrokeColor(color(0xFFF1D6, 0.9))
    ctx.setLineWidth(5)
    ctx.strokeEllipse(in: labelRect.insetBy(dx: 14, dy: 14))
    ctx.setStrokeColor(color(0x000000, 0.18))
    ctx.setLineWidth(3)
    ctx.strokeEllipse(in: labelRect.insetBy(dx: 1.5, dy: 1.5))
    ctx.setFillColor(color(0x161B33))
    ctx.fillEllipse(in: CGRect(x: -13, y: -13, width: 26, height: 26))
    ctx.restoreGState()

    // Crate front: three slats with real gaps, rounded ends, corner posts.
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -12), blur: 30, color: color(0x000000, 0.55))
    ctx.setFillColor(color(0x5C3A1C))
    ctx.fill(front)
    ctx.restoreGState()
    let gap: CGFloat = 18
    let slatHeight = (front.height - gap * 2) / 3
    for i in 0..<3 {
        let slat = CGRect(x: front.minX, y: front.minY + CGFloat(i) * (slatHeight + gap), width: front.width, height: slatHeight)
        let slatPath = CGPath(roundedRect: slat, cornerWidth: 10, cornerHeight: 10, transform: nil)
        ctx.saveGState()
        ctx.addPath(slatPath)
        ctx.clip()
        let wood = CGGradient(colorsSpace: rgb, colors: [color(0xE2A45C), color(0xC9843C), color(0xA86A2C)] as CFArray,
                              locations: [0, 0.6, 1])!
        ctx.drawLinearGradient(wood, start: CGPoint(x: 0, y: slat.maxY), end: CGPoint(x: 0, y: slat.minY), options: [])
        ctx.setStrokeColor(color(0x7A4A1E, 0.28))
        ctx.setLineWidth(2.5)
        var y = slat.minY + 10 + CGFloat(i * 7)
        while y < slat.maxY {
            ctx.move(to: CGPoint(x: slat.minX, y: y))
            ctx.addCurve(to: CGPoint(x: slat.maxX, y: y + 4),
                         control1: CGPoint(x: slat.minX + 250, y: y + 9), control2: CGPoint(x: slat.maxX - 250, y: y - 6))
            ctx.strokePath()
            y += 21
        }
        ctx.restoreGState()
        // Top edge catches light, bottom edge falls into shadow.
        ctx.setStrokeColor(color(0xFFFFFF, 0.25))
        ctx.setLineWidth(3)
        ctx.move(to: CGPoint(x: slat.minX + 10, y: slat.maxY - 1.5))
        ctx.addLine(to: CGPoint(x: slat.maxX - 10, y: slat.maxY - 1.5))
        ctx.strokePath()
    }
    for x in [front.minX + 22, front.maxX - 22 - 46] {
        let post = CGRect(x: x, y: front.minY - 6, width: 46, height: front.height + 12)
        ctx.saveGState()
        ctx.addPath(CGPath(roundedRect: post, cornerWidth: 8, cornerHeight: 8, transform: nil))
        ctx.clip()
        let postWood = CGGradient(colorsSpace: rgb, colors: [color(0xB8742F), color(0x8E5522)] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(postWood, start: CGPoint(x: post.minX, y: 0), end: CGPoint(x: post.maxX, y: 0), options: [])
        ctx.restoreGState()
        // Nails.
        ctx.setFillColor(color(0x3A2410))
        for i in 0..<3 {
            let ny = front.minY + CGFloat(i) * (slatHeight + gap) + slatHeight / 2
            ctx.fillEllipse(in: CGRect(x: post.midX - 6, y: ny - 6, width: 12, height: 12))
        }
    }

    // Bottom of the square: a soft floor shadow under the crate.
    ctx.restoreGState()
}

func writePNG(pixels: Int, framed: Bool, to url: URL) throws {
    let ctx = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .high
    draw(in: ctx, scale: CGFloat(pixels) / 1024, framed: framed)
    let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try rep.representation(using: .png, properties: [:])!.write(to: url)
}

for (points, scale) in sizes {
    let name = scale == 1 ? "icon_\(points)x\(points).png" : "icon_\(points)x\(points)@2x.png"
    try writePNG(pixels: points * scale, framed: true, to: outDir.appendingPathComponent(name))
}
print("wrote \(sizes.count) PNGs to \(outDir.path)")

if let flag = CommandLine.arguments.firstIndex(of: "--android"), CommandLine.arguments.count > flag + 1 {
    let res = URL(fileURLWithPath: CommandLine.arguments[flag + 1], isDirectory: true)
    // Adaptive icon layers are 108 dp; one PNG per density.
    let densities: [(String, CGFloat)] = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]
    for (density, factor) in densities {
        let folder = res.appendingPathComponent("mipmap-\(density)", isDirectory: true)
        try writePNG(pixels: Int(108 * factor), framed: false, to: folder.appendingPathComponent("ic_launcher_foreground.png"))
    }
    print("wrote Android launcher icons to \(res.path)")
}
