#!/usr/bin/env swift
// NowFocus brand kit generator — single source of truth for the ViewFinder
// mark. Run from the repo root: `swift brand/generate.swift <command>`.
//
// Commands:
//   gate   — renders the go/no-go check set (shadowed macOS tile, 16px thick
//            mark, wordmark) into brand/gate/ for visual inspection.
//   all    — generates every asset this repo's platforms need.

import AppKit
import CoreText
import CoreGraphics

// MARK: - Paths

let repoRoot = FileManager.default.currentDirectoryPath
guard FileManager.default.fileExists(atPath: repoRoot + "/apps/macos") else {
    fputs("Run this from the repo root (apps/macos not found under \(repoRoot))\n", stderr)
    exit(1)
}
let archivoPath = repoRoot + "/apps/android/app/src/main/res/font/archivo_variable.ttf"

// MARK: - Color

let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!

func hexColor(_ hex: String, alpha: CGFloat = 1) -> CGColor {
    var h = hex
    if h.hasPrefix("#") { h.removeFirst() }
    var v: UInt64 = 0
    Scanner(string: h).scanHexInt64(&v)
    let r = CGFloat((v >> 16) & 0xFF) / 255
    let g = CGFloat((v >> 8) & 0xFF) / 255
    let b = CGFloat(v & 0xFF) / 255
    return CGColor(colorSpace: sRGB, components: [r, g, b, alpha])!
}

let inkHex = "#201e1d"
let accentHex = "#ec3013"
let groundHex = "#f3f2f2"
let whiteHex = "#ffffff"

// MARK: - Context

func makeContext(width: Int, height: Int, opaque: Bool) -> CGContext {
    let ctx = CGContext(
        data: nil, width: width, height: height,
        bitsPerComponent: 8, bytesPerRow: 0, space: sRGB,
        bitmapInfo: (opaque ? CGImageAlphaInfo.noneSkipLast.rawValue : CGImageAlphaInfo.premultipliedLast.rawValue)
    )!
    ctx.interpolationQuality = .high
    ctx.setShouldAntialias(true)
    // Flip to a top-left-origin, y-down space (matches the SVG source and
    // makes every coordinate below read the same as the design canvas).
    ctx.translateBy(x: 0, y: CGFloat(height))
    ctx.scaleBy(x: 1, y: -1)
    return ctx
}

// MARK: - The mark (viewfinder brackets + "now" square)

/// Draws the mark into `rect` (assumed square). `strokeThick` selects the
/// design's two stroke masters (13 for nominal sizes <28, else 10 — the
/// caller decides which applies, this function just draws the one asked
/// for). `accent` is nil for "idle" (the square is omitted entirely, not
/// just made transparent — matches the design's own `accent="none"` case).
func drawMark(_ ctx: CGContext, rect: CGRect, strokeThick: Bool, color: CGColor, accent: CGColor?) {
    let scale = rect.width / 100.0
    ctx.saveGState()
    ctx.translateBy(x: rect.origin.x, y: rect.origin.y)
    ctx.scaleBy(x: scale, y: scale)

    let sw: CGFloat = strokeThick ? 13 : 10
    ctx.setLineWidth(sw)
    ctx.setLineCap(.square)
    ctx.setLineJoin(.miter)
    ctx.setStrokeColor(color)

    let brackets: [[CGPoint]] = [
        [CGPoint(x: 9, y: 36), CGPoint(x: 9, y: 9), CGPoint(x: 36, y: 9)],
        [CGPoint(x: 64, y: 9), CGPoint(x: 91, y: 9), CGPoint(x: 91, y: 36)],
        [CGPoint(x: 91, y: 64), CGPoint(x: 91, y: 91), CGPoint(x: 64, y: 91)],
        [CGPoint(x: 36, y: 91), CGPoint(x: 9, y: 91), CGPoint(x: 9, y: 64)],
    ]
    for b in brackets {
        let p = CGMutablePath()
        p.move(to: b[0])
        p.addLine(to: b[1])
        p.addLine(to: b[2])
        ctx.addPath(p)
    }
    ctx.strokePath()

    if let accent = accent {
        ctx.setFillColor(accent)
        ctx.fill(CGRect(x: 37, y: 37, width: 26, height: 26))
    }
    ctx.restoreGState()
}

/// macOS app-icon tile: a rounded, drop-shadowed square (baked in by hand —
/// the design sheet is explicit that macOS does not auto-mask icons) with
/// the mark centered inside. `canvas` is the full image size in pixels.
func drawTile(_ ctx: CGContext, canvas: Int, strokeThick: Bool, bg: CGColor, fg: CGColor, accent: CGColor?, shadow: Bool) {
    let contentSize = CGFloat(canvas) * (824.0 / 1024.0)
    let origin = (CGFloat(canvas) - contentSize) / 2
    let rect = CGRect(x: origin, y: origin, width: contentSize, height: contentSize)
    let radius = contentSize * 0.22
    let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)

    ctx.saveGState()
    if shadow {
        ctx.setShadow(offset: CGSize(width: 0, height: contentSize * 0.035), blur: contentSize * 0.06, color: CGColor(gray: 0, alpha: 0.4))
    }
    ctx.addPath(path)
    ctx.setFillColor(bg)
    ctx.fillPath()
    ctx.restoreGState()

    let markFrac: CGFloat = 0.587
    let markSize = contentSize * markFrac
    let markOrigin = CGPoint(x: origin + (contentSize - markSize) / 2, y: origin + (contentSize - markSize) / 2)
    drawMark(ctx, rect: CGRect(origin: markOrigin, size: CGSize(width: markSize, height: markSize)), strokeThick: strokeThick, color: fg, accent: accent)
}

// MARK: - Archivo wordmark (CoreText, variable font instantiated at weight 800)

let kWghtAxis: UInt32 = 0x77676874 // 'wght'

func archivoFont(size: CGFloat, weight: CGFloat = 800) -> CTFont {
    let url = URL(fileURLWithPath: archivoPath)
    var cfErr: Unmanaged<CFError>?
    CTFontManagerRegisterFontsForURL(url as CFURL, .process, &cfErr) // ignore "already registered"
    guard let provider = CGDataProvider(url: url as CFURL), let cgFont = CGFont(provider) else {
        fatalError("could not load Archivo font at \(archivoPath)")
    }
    let base = CTFontCreateWithGraphicsFont(cgFont, size, nil, nil)
    let variationAttrs: [CFString: Any] = [kCTFontVariationAttribute: [NSNumber(value: kWghtAxis): NSNumber(value: Double(weight))]]
    let descriptor = CTFontDescriptorCreateWithAttributes(variationAttrs as CFDictionary)
    return CTFontCreateCopyWithAttributes(base, size, nil, descriptor)
}

/// Draws left-aligned Archivo text with the brand's -0.03em tracking.
/// `origin` is the top-left of the text's cap-height box in the ambient
/// (top-left, y-down) coordinate space.
func drawText(_ ctx: CGContext, _ text: String, at origin: CGPoint, size: CGFloat, color: CGColor, weight: CGFloat = 800) {
    let font = archivoFont(size: size, weight: weight)
    let tracking = -0.03 * size
    let attrs: [NSAttributedString.Key: Any] = [
        .font: font,
        .foregroundColor: color,
        .kern: tracking,
    ]
    let attributed = NSAttributedString(string: text, attributes: attrs)
    let line = CTLineCreateWithAttributedString(attributed)
    var ascent: CGFloat = 0, descent: CGFloat = 0, leading: CGFloat = 0
    CTLineGetTypographicBounds(line, &ascent, &descent, &leading)

    ctx.saveGState()
    // CoreText draws in a y-up space with the baseline at y=0; our ambient
    // space is y-down, so flip locally around the text's own origin.
    ctx.translateBy(x: origin.x, y: origin.y + ascent)
    ctx.scaleBy(x: 1, y: -1)
    ctx.textPosition = .zero
    CTLineDraw(line, ctx)
    ctx.restoreGState()
}

// MARK: - PNG output

func writePNG(_ ctx: CGContext, to path: String, stripAlpha: Bool = false) {
    guard var image = ctx.makeImage() else { fatalError("no image from context for \(path)") }
    if stripAlpha {
        let flatCtx = makeContext(width: image.width, height: image.height, opaque: true)
        flatCtx.setFillColor(hexColor(groundHex)) // never used for iOS master (opaque bg already drawn); placeholder bg
        flatCtx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        image = flatCtx.makeImage()!
    }
    let url = URL(fileURLWithPath: path)
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    guard let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) else {
        fatalError("could not create PNG destination at \(path)")
    }
    CGImageDestinationAddImage(dest, image, nil)
    guard CGImageDestinationFinalize(dest) else { fatalError("could not write PNG to \(path)") }
}

// MARK: - Self-check (reads the PNG back and asserts per-recipe expectations)

struct PixelReader {
    let width: Int
    let height: Int
    let hasAlpha: Bool
    private let data: [UInt8]

    init?(path: String) {
        guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        width = image.width
        height = image.height
        hasAlpha = image.alphaInfo != .none && image.alphaInfo != .noneSkipFirst && image.alphaInfo != .noneSkipLast
        let ctx = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let buf = ctx.data else { return nil }
        data = Array(UnsafeBufferPointer(start: buf.assumingMemoryBound(to: UInt8.self), count: width * height * 4))
    }

    // (x, y) in top-left-origin pixel coordinates, as the file itself is laid out.
    func pixel(_ x: Int, _ y: Int) -> (r: UInt8, g: UInt8, b: UInt8, a: UInt8) {
        let o = (y * width + x) * 4
        return (data[o], data[o + 1], data[o + 2], data[o + 3])
    }
}

func assertTrue(_ ok: Bool, _ message: String) {
    if !ok {
        fputs("SELF-CHECK FAILED: \(message)\n", stderr)
        exit(1)
    }
}

// MARK: - Commands

func hexFromRGB(_ p: (r: UInt8, g: UInt8, b: UInt8, a: UInt8)) -> String {
    String(format: "#%02x%02x%02x", p.r, p.g, p.b)
}

func cmdGate() {
    let outDir = repoRoot + "/brand/gate"

    // 1. Shadowed macOS tile (512px, red bg / white brackets / ink square, thin stroke since 512 is well above the 32pt breakpoint).
    let tileSize = 512
    let tileCtx = makeContext(width: tileSize, height: tileSize, opaque: false)
    drawTile(tileCtx, canvas: tileSize, strokeThick: false, bg: hexColor(accentHex), fg: hexColor(whiteHex), accent: hexColor(inkHex), shadow: true)
    let tilePath = outDir + "/macos-tile-512.png"
    writePNG(tileCtx, to: tilePath)
    if let r = PixelReader(path: tilePath) {
        assertTrue(r.pixel(4, 4).a == 0, "tile corner should be transparent outside the rounded shape")
    }

    // 2. 16px thick-stroke mark (transparent bg, ink brackets, red square — active state).
    let markSize = 16
    let markCtx = makeContext(width: markSize, height: markSize, opaque: false)
    drawMark(markCtx, rect: CGRect(x: 0, y: 0, width: CGFloat(markSize), height: CGFloat(markSize)), strokeThick: true, color: hexColor(inkHex), accent: hexColor(accentHex))
    let markPath = outDir + "/mark-16-thick-active.png"
    writePNG(markCtx, to: markPath)
    if let r = PixelReader(path: markPath) {
        let center = r.pixel(markSize / 2, markSize / 2)
        assertTrue(hexFromRGB(center) == accentHex, "16px mark center should be \(accentHex), got \(hexFromRGB(center))")
        // Note: pixel (0,0) is NOT a useful "should be transparent" probe at
        // this size — the bracket's own 13-unit stroke width, centered just
        // 9 units from each edge, legitimately extends into the corner
        // pixel once scaled down to 16px. Confirmed by direct visual read
        // instead (see plan's go/no-go gate).
    }

    // 3. Archivo wordmark lockup ("NowFocus", ink on transparent, weight 800).
    let wmW = 600, wmH = 160
    let wmCtx = makeContext(width: wmW, height: wmH, opaque: false)
    drawText(wmCtx, "NowFocus", at: CGPoint(x: 20, y: 30), size: 96, color: hexColor(inkHex))
    let wmPath = outDir + "/wordmark.png"
    writePNG(wmCtx, to: wmPath)

    print("Gate renders written to \(outDir):")
    print("  \(tilePath)")
    print("  \(markPath)")
    print("  \(wmPath)")
    print("Inspect all three (Read tool) before running `swift brand/generate.swift all`.")
}

// MARK: - Concrete SVG emission (generated output, not a hand source)

func markSVG(strokeThick: Bool, color: String, accent: String?) -> String {
    let sw = strokeThick ? 13 : 10
    var s = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">\n"
    s += "  <path d=\"M9 36V9h27M64 9h27v27M91 64v27H64M36 91H9V64\" fill=\"none\" stroke=\"\(color)\" stroke-width=\"\(sw)\" stroke-linecap=\"square\"/>\n"
    if let accent = accent {
        s += "  <rect x=\"37\" y=\"37\" width=\"26\" height=\"26\" fill=\"\(accent)\"/>\n"
    }
    s += "</svg>\n"
    return s
}

func writeText(_ content: String, to path: String) {
    let url = URL(fileURLWithPath: path)
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! content.write(to: url, atomically: true, encoding: .utf8)
}

// MARK: - Xcode asset catalog Contents.json

func writeAppIconContents(dir: String, sizes: [Int]) {
    var images: [[String: String]] = []
    for size in sizes {
        for scale in [1, 2] {
            images.append([
                "idiom": "mac",
                "size": "\(size)x\(size)",
                "scale": "\(scale)x",
                "filename": "icon_\(size)x\(size)\(scale == 2 ? "@2x" : "").png",
            ])
        }
    }
    let json: [String: Any] = ["images": images, "info": ["version": 1, "author": "xcode"]]
    let data = try! JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys])
    try! data.write(to: URL(fileURLWithPath: dir + "/Contents.json"))
}

func writeTemplateImagesetContents(dir: String, baseName: String) {
    let images: [[String: String]] = [
        ["idiom": "mac", "scale": "1x", "filename": "\(baseName).png"],
        ["idiom": "mac", "scale": "2x", "filename": "\(baseName)@2x.png"],
    ]
    let json: [String: Any] = [
        "images": images,
        "info": ["version": 1, "author": "xcode"],
        "properties": ["template-rendering-intent": "template"],
    ]
    let data = try! JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys])
    try! data.write(to: URL(fileURLWithPath: dir + "/Contents.json"))
}

// MARK: - ICO packing (modern format: each entry is an embedded PNG)

func packICO(entries: [(size: Int, pngData: Data)], to path: String) {
    var data = Data()
    // ICONDIR
    data.append(contentsOf: [0, 0])                 // reserved
    data.append(contentsOf: [1, 0])                 // type = icon
    withUnsafeBytes(of: UInt16(entries.count).littleEndian) { data.append(contentsOf: $0) }

    var offset = 6 + 16 * entries.count
    var directory = Data()
    var imageData = Data()
    for e in entries {
        let dim: UInt8 = e.size >= 256 ? 0 : UInt8(e.size)
        directory.append(dim)                        // width
        directory.append(dim)                         // height
        directory.append(0)                           // color count
        directory.append(0)                           // reserved
        withUnsafeBytes(of: UInt16(1).littleEndian) { directory.append(contentsOf: $0) }   // planes
        withUnsafeBytes(of: UInt16(32).littleEndian) { directory.append(contentsOf: $0) }  // bit count
        withUnsafeBytes(of: UInt32(e.pngData.count).littleEndian) { directory.append(contentsOf: $0) }
        withUnsafeBytes(of: UInt32(offset).littleEndian) { directory.append(contentsOf: $0) }
        offset += e.pngData.count
        imageData.append(e.pngData)
    }
    data.append(directory)
    data.append(imageData)
    try! data.write(to: URL(fileURLWithPath: path))
}

func renderMarkPNGData(size: Int, strokeThick: Bool, color: CGColor, accent: CGColor?) -> Data {
    let ctx = makeContext(width: size, height: size, opaque: false)
    drawMark(ctx, rect: CGRect(x: 0, y: 0, width: CGFloat(size), height: CGFloat(size)), strokeThick: strokeThick, color: color, accent: accent)
    let image = ctx.makeImage()!
    let mutableData = NSMutableData()
    let dest = CGImageDestinationCreateWithData(mutableData, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
    return mutableData as Data
}

// MARK: - Platform asset generation

func generateMacAppIcon() {
    let dir = repoRoot + "/apps/macos/NowFocus/Assets.xcassets/AppIcon.appiconset"
    let sizes = [16, 32, 128, 256, 512]
    for size in sizes {
        for scale in [1, 2] {
            let px = size * scale
            let thick = size <= 32 // 16pt and 32pt slots (both scales) use the thick master
            let ctx = makeContext(width: px, height: px, opaque: false)
            drawTile(ctx, canvas: px, strokeThick: thick, bg: hexColor(accentHex), fg: hexColor(whiteHex), accent: hexColor(inkHex), shadow: true)
            let name = "icon_\(size)x\(size)\(scale == 2 ? "@2x" : "").png"
            writePNG(ctx, to: dir + "/" + name)
        }
    }
    writeAppIconContents(dir: dir, sizes: sizes)
    print("macOS AppIcon.appiconset written (\(sizes.count * 2) images)")
}

func generateMacMenuBar() {
    let base = repoRoot + "/apps/macos/NowFocus/Assets.xcassets"
    // Template images: alpha-shape only, drawn in black (color is irrelevant — macOS tints).
    let black = hexColor("#000000")
    for (name, active) in [("MenuBarIdleTemplate", false), ("MenuBarActiveTemplate", true)] {
        let dir = base + "/\(name).imageset"
        for scale in [1, 2] {
            let px = 18 * scale
            let ctx = makeContext(width: px, height: px, opaque: false)
            // Active state: square drawn in the SAME color as the brackets
            // (not red) — template images are single-color masks; macOS
            // tints the whole shape at draw time.
            drawMark(ctx, rect: CGRect(x: 0, y: 0, width: CGFloat(px), height: CGFloat(px)), strokeThick: true, color: black, accent: active ? black : nil)
            writePNG(ctx, to: dir + "/\(name)\(scale == 2 ? "@2x" : "").png")
        }
        writeTemplateImagesetContents(dir: dir, baseName: name)
    }
    print("macOS menu-bar template imagesets written")
}

func generateMacDMG() {
    // Canvas matches build_dmg.sh exactly: 500x320 window, icon-size 100,
    // icon at (125,160), Applications alias at (375,160). Keep the mark and
    // wordmark clear of both ~100x100 footprints; only the arrow crosses
    // between them, at the same height.
    let w = 500, h = 320
    let ctx = makeContext(width: w, height: h, opaque: true)
    ctx.setFillColor(hexColor(groundHex))
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))

    // Wordmark + mark, top-left, well clear of the icon row (icons sit
    // vertically centered around y=160 with ~50px radius, i.e. y:110-210).
    drawMark(ctx, rect: CGRect(x: 36, y: 34, width: 40, height: 40), strokeThick: false, color: hexColor(inkHex), accent: hexColor(accentHex))
    drawText(ctx, "NowFocus", at: CGPoint(x: 86, y: 44), size: 30, color: hexColor(inkHex))

    // Arrow between the two icon slots (x:175 to x:325, y=160), clear of
    // both 100x100 footprints (75-175 and 325-425).
    ctx.saveGState()
    ctx.setStrokeColor(hexColor(inkHex))
    ctx.setLineWidth(3)
    ctx.setLineCap(.square)
    let arrow = CGMutablePath()
    arrow.move(to: CGPoint(x: 190, y: 160))
    arrow.addLine(to: CGPoint(x: 300, y: 160))
    arrow.move(to: CGPoint(x: 288, y: 148))
    arrow.addLine(to: CGPoint(x: 300, y: 160))
    arrow.addLine(to: CGPoint(x: 288, y: 172))
    ctx.addPath(arrow)
    ctx.strokePath()
    ctx.restoreGState()

    let dir = repoRoot + "/apps/macos/scripts"
    writePNG(ctx, to: dir + "/dmg-background.png")

    // @2x for HiDPI (combined via tiffutil at build time if create-dmg
    // supports it — see plan; keep the base canvas at 500x320 either way).
    let ctx2x = makeContext(width: w * 2, height: h * 2, opaque: true)
    ctx2x.scaleBy(x: 2, y: 2) // draw the same content, just at double resolution
    ctx2x.setFillColor(hexColor(groundHex))
    ctx2x.fill(CGRect(x: 0, y: 0, width: w, height: h))
    drawMark(ctx2x, rect: CGRect(x: 36, y: 34, width: 40, height: 40), strokeThick: false, color: hexColor(inkHex), accent: hexColor(accentHex))
    drawText(ctx2x, "NowFocus", at: CGPoint(x: 86, y: 44), size: 30, color: hexColor(inkHex))
    ctx2x.saveGState()
    ctx2x.setStrokeColor(hexColor(inkHex))
    ctx2x.setLineWidth(3)
    ctx2x.setLineCap(.square)
    ctx2x.addPath(arrow)
    ctx2x.strokePath()
    ctx2x.restoreGState()
    writePNG(ctx2x, to: dir + "/dmg-background@2x.png")

    print("macOS DMG background written (500x320 + @2x)")
}

func generateAndroidPlayIcon() {
    let size = 512
    let ctx = makeContext(width: size, height: size, opaque: true)
    ctx.setFillColor(hexColor(accentHex))
    ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
    drawMark(ctx, rect: CGRect(x: size / 2 - 150, y: size / 2 - 150, width: 300, height: 300), strokeThick: false, color: hexColor(whiteHex), accent: hexColor(inkHex))
    writePNG(ctx, to: repoRoot + "/brand/exports/android/play-store-icon-512.png")
    print("Android Play Store icon written")
}

func androidVectorPath(strokeColor: String, strokeWidth: Int, squareColor: String?) -> String {
    var s = """
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="108dp" android:height="108dp"
        android:viewportWidth="108" android:viewportHeight="108">
      <group android:scaleX="0.46" android:scaleY="0.46" android:translateX="31" android:translateY="31">
        <path
            android:pathData="M9,36 L9,9 L36,9 M64,9 L91,9 L91,36 M91,64 L91,91 L64,91 M36,91 L9,91 L9,64"
            android:strokeColor="\(strokeColor)"
            android:strokeWidth="\(strokeWidth)"
            android:strokeLineCap="square"
            android:fillColor="#00000000"/>

    """
    if let squareColor = squareColor {
        s += "    <path android:pathData=\"M37,37 L63,37 L63,63 L37,63 Z\" android:fillColor=\"\(squareColor)\"/>\n"
    }
    s += "  </group>\n</vector>\n"
    return s
}

func generateAndroidVectors() {
    let resDir = repoRoot + "/apps/android/app/src/main/res"
    writeText(androidVectorPath(strokeColor: "#FFFFFF", strokeWidth: 10, squareColor: "#201E1D"), to: resDir + "/drawable/ic_launcher_foreground.xml")
    writeText("""
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="108dp" android:height="108dp"
        android:viewportWidth="108" android:viewportHeight="108">
      <path android:pathData="M0,0h108v108h-108z" android:fillColor="#EC3013"/>
    </vector>
    """, to: resDir + "/drawable/ic_launcher_background.xml")
    writeText(androidVectorPath(strokeColor: "#000000", strokeWidth: 10, squareColor: "#000000"), to: resDir + "/drawable/ic_launcher_monochrome.xml")

    let adaptiveXML = """
    <?xml version="1.0" encoding="utf-8"?>
    <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        <background android:drawable="@drawable/ic_launcher_background"/>
        <foreground android:drawable="@drawable/ic_launcher_foreground"/>
        <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
    </adaptive-icon>
    """
    writeText(adaptiveXML, to: resDir + "/mipmap-anydpi-v26/ic_launcher.xml")
    writeText(adaptiveXML, to: resDir + "/mipmap-anydpi-v26/ic_launcher_round.xml")

    // Status-bar/notification icons — files only, plumbing deferred (see plan).
    // Android status icons are 24dp white-only silhouettes on a 24x24 viewport.
    func statusIconXML(active: Bool) -> String {
        var s = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp" android:height="24dp"
            android:viewportWidth="100" android:viewportHeight="100">
          <path
              android:pathData="M9,36 L9,9 L36,9 M64,9 L91,9 L91,36 M91,64 L91,91 L64,91 M36,91 L9,91 L9,64"
              android:strokeColor="#FFFFFF"
              android:strokeWidth="13"
              android:strokeLineCap="square"
              android:fillColor="#00000000"/>

        """
        if active {
            s += "  <path android:pathData=\"M37,37 L63,37 L63,63 L37,63 Z\" android:fillColor=\"#FFFFFF\"/>\n"
        }
        s += "</vector>\n"
        return s
    }
    writeText(statusIconXML(active: false), to: resDir + "/drawable/ic_stat_nowfocus_idle.xml")
    writeText(statusIconXML(active: true), to: resDir + "/drawable/ic_stat_nowfocus_active.xml")

    print("Android vector drawables + adaptive icon XML written")
}

func generateWindowsAssets() {
    let iconsDir = repoRoot + "/apps/windows/src-tauri/icons"
    let trayDir = iconsDir + "/tray"

    // 1024 master for `npm run tauri icon` — full-bleed, thin stroke (Tauri/
    // the OS applies per-surface shaping, no baked rounding/shadow here).
    let masterSize = 1024
    let masterCtx = makeContext(width: masterSize, height: masterSize, opaque: true)
    masterCtx.setFillColor(hexColor(accentHex))
    masterCtx.fill(CGRect(x: 0, y: 0, width: masterSize, height: masterSize))
    let markFrac: CGFloat = 0.6
    let markSize = CGFloat(masterSize) * markFrac
    let markOrigin = (CGFloat(masterSize) - markSize) / 2
    drawMark(masterCtx, rect: CGRect(x: markOrigin, y: markOrigin, width: markSize, height: markSize), strokeThick: false, color: hexColor(whiteHex), accent: hexColor(inkHex))
    writePNG(masterCtx, to: repoRoot + "/brand/exports/windows/nowfocus-master-1024.png")

    // Purpose-built .ico: thick 16/24/32, thin 48/256 — sharper at the sizes
    // that would otherwise blur if downsampled from the 1024 master.
    let icoEntries: [(Int, Bool)] = [(16, true), (24, true), (32, true), (48, false), (256, false)]
    var packed: [(size: Int, pngData: Data)] = []
    for (size, thick) in icoEntries {
        let data = renderMarkPNGDataOpaqueIcon(size: size, strokeThick: thick)
        packed.append((size, data))
    }
    packICO(entries: packed, to: iconsDir + "/icon.ico")

    // 32x32.png, sharpened the same way.
    let px32Ctx = makeContext(width: 32, height: 32, opaque: false)
    drawIconTileFlat(px32Ctx, canvas: 32, strokeThick: true)
    writePNG(px32Ctx, to: iconsDir + "/32x32.png")

    // Tray: one 32px render per idle/active x light/dark, thick stroke.
    // Light taskbar -> ink brackets; dark taskbar -> white brackets. Square
    // is red only when active.
    let traySize = 32
    func trayImage(color: CGColor, active: Bool) -> CGContext {
        let ctx = makeContext(width: traySize, height: traySize, opaque: false)
        drawMark(ctx, rect: CGRect(x: 0, y: 0, width: CGFloat(traySize), height: CGFloat(traySize)), strokeThick: true, color: color, accent: active ? hexColor(accentHex) : nil)
        return ctx
    }
    writePNG(trayImage(color: hexColor(inkHex), active: false), to: trayDir + "/light-idle.png")
    writePNG(trayImage(color: hexColor(inkHex), active: true), to: trayDir + "/light-active.png")
    writePNG(trayImage(color: hexColor(whiteHex), active: false), to: trayDir + "/dark-idle.png")
    writePNG(trayImage(color: hexColor(whiteHex), active: true), to: trayDir + "/dark-active.png")

    // Splash asset (620x300) — file only, window wiring deferred (see plan).
    let splashW = 620, splashH = 300
    let splashCtx = makeContext(width: splashW, height: splashH, opaque: true)
    splashCtx.setFillColor(hexColor(accentHex))
    splashCtx.fill(CGRect(x: 0, y: 0, width: splashW, height: splashH))
    drawMark(splashCtx, rect: CGRect(x: 36, y: 36, width: 64, height: 64), strokeThick: false, color: hexColor(whiteHex), accent: hexColor(inkHex))
    drawText(splashCtx, "NowFocus", at: CGPoint(x: 36, y: 220), size: 48, color: hexColor(whiteHex))
    writePNG(splashCtx, to: repoRoot + "/brand/exports/windows/SplashScreen-620x300.png")

    // Favicon, copied into apps/windows/public/ so index.html can reference it directly.
    let favDir = repoRoot + "/apps/windows/public"
    let favCtx = makeContext(width: 32, height: 32, opaque: false)
    drawMark(favCtx, rect: CGRect(x: 0, y: 0, width: 32, height: 32), strokeThick: true, color: hexColor(inkHex), accent: hexColor(accentHex))
    writePNG(favCtx, to: favDir + "/favicon-32.png")
    writeText(markSVG(strokeThick: true, color: inkHex, accent: accentHex), to: favDir + "/favicon.svg")

    print("Windows assets written (master, ico, tray x4, splash, favicon)")
}

/// A flat (non-shadowed, non-rounded) icon tile — used where the OS applies
/// its own shaping (Windows .ico/taskbar), unlike macOS's baked tile.
func drawIconTileFlat(_ ctx: CGContext, canvas: Int, strokeThick: Bool) {
    ctx.setFillColor(hexColor(accentHex))
    ctx.fill(CGRect(x: 0, y: 0, width: canvas, height: canvas))
    let markFrac: CGFloat = 0.6
    let markSize = CGFloat(canvas) * markFrac
    let origin = (CGFloat(canvas) - markSize) / 2
    drawMark(ctx, rect: CGRect(x: origin, y: origin, width: markSize, height: markSize), strokeThick: strokeThick, color: hexColor(whiteHex), accent: hexColor(inkHex))
}

func renderMarkPNGDataOpaqueIcon(size: Int, strokeThick: Bool) -> Data {
    let ctx = makeContext(width: size, height: size, opaque: true)
    drawIconTileFlat(ctx, canvas: size, strokeThick: strokeThick)
    let image = ctx.makeImage()!
    let mutableData = NSMutableData()
    let dest = CGImageDestinationCreateWithData(mutableData, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
    return mutableData as Data
}

func generateExports() {
    let exportsDir = repoRoot + "/brand/exports"

    // Canonical SVGs (single source of truth outputs, per the plan).
    writeText(markSVG(strokeThick: false, color: inkHex, accent: accentHex), to: repoRoot + "/brand/mark.svg")
    writeText(markSVG(strokeThick: true, color: inkHex, accent: accentHex), to: repoRoot + "/brand/mark-thick.svg")

    // iOS app icon masters — no iOS project exists, staged for later.
    // Light master: NO alpha channel at all (App Store Connect rejects one).
    let lightCtx = makeContext(width: 1024, height: 1024, opaque: true)
    lightCtx.setFillColor(hexColor(accentHex))
    lightCtx.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
    drawMark(lightCtx, rect: CGRect(x: 212, y: 212, width: 600, height: 600), strokeThick: false, color: hexColor(whiteHex), accent: hexColor(inkHex))
    writePNG(lightCtx, to: exportsDir + "/ios/AppIcon-1024.png")

    let darkCtx = makeContext(width: 1024, height: 1024, opaque: false)
    drawMark(darkCtx, rect: CGRect(x: 212, y: 212, width: 600, height: 600), strokeThick: false, color: hexColor(accentHex), accent: hexColor(groundHex))
    writePNG(darkCtx, to: exportsDir + "/ios/AppIcon-1024-dark.png")

    let tintedCtx = makeContext(width: 1024, height: 1024, opaque: false)
    drawMark(tintedCtx, rect: CGRect(x: 212, y: 212, width: 600, height: 600), strokeThick: false, color: hexColor("#e8e8e8"), accent: hexColor("#8e8e93"))
    writePNG(tintedCtx, to: exportsDir + "/ios/AppIcon-1024-tinted.png")

    // Web favicon set.
    writeText(markSVG(strokeThick: true, color: inkHex, accent: accentHex), to: exportsDir + "/web/favicon.svg")
    let fav16 = renderMarkPNGDataOpaqueIcon(size: 16, strokeThick: true)
    let fav32 = renderMarkPNGDataOpaqueIcon(size: 32, strokeThick: true)
    let fav48 = renderMarkPNGDataOpaqueIcon(size: 48, strokeThick: false)
    packICO(entries: [(16, fav16), (32, fav32)], to: exportsDir + "/web/favicon.ico")
    let touchCtx = makeContext(width: 180, height: 180, opaque: false)
    drawIconTileFlat(touchCtx, canvas: 180, strokeThick: false)
    writePNG(touchCtx, to: exportsDir + "/web/apple-touch-icon-180.png")
    _ = fav48

    // Store/web collateral — domain left as `nowfocus.app` per the design
    // sheet; flagged in the plan as needing confirmation against the repo's
    // actual `getnowfocus` bundle IDs before this is treated as final.
    let pfW = 1024, pfH = 500
    let pfCtx = makeContext(width: pfW, height: pfH, opaque: true)
    pfCtx.setFillColor(hexColor(accentHex))
    pfCtx.fill(CGRect(x: 0, y: 0, width: pfW, height: pfH))
    drawMark(pfCtx, rect: CGRect(x: 56, y: 56, width: 64, height: 64), strokeThick: false, color: hexColor(whiteHex), accent: hexColor(inkHex))
    drawText(pfCtx, "NowFocus", at: CGPoint(x: 136, y: 68), size: 44, color: hexColor(whiteHex))
    drawText(pfCtx, "One session. Every screen.", at: CGPoint(x: 56, y: 260), size: 64, color: hexColor(whiteHex))
    writePNG(pfCtx, to: exportsDir + "/store/play-feature-graphic-1024x500.png")

    let ogW = 1200, ogH = 630
    let ogCtx = makeContext(width: ogW, height: ogH, opaque: true)
    ogCtx.setFillColor(hexColor(groundHex))
    ogCtx.fill(CGRect(x: 0, y: 0, width: ogW, height: ogH))
    drawMark(ogCtx, rect: CGRect(x: 64, y: 64, width: 80, height: 80), strokeThick: false, color: hexColor(inkHex), accent: hexColor(accentHex))
    drawText(ogCtx, "Focus that follows you.", at: CGPoint(x: 64, y: 220), size: 68, color: hexColor(inkHex))
    ogCtx.setFillColor(hexColor(accentHex))
    ogCtx.fill(CGRect(x: 0, y: ogH - 140, width: ogW / 2, height: 140))
    drawText(ogCtx, "nowfocus.app", at: CGPoint(x: 64, y: ogH - 100), size: 36, color: hexColor(whiteHex))
    writePNG(ogCtx, to: exportsDir + "/store/og-image-1200x630.png")

    print("iOS/store/web exports + canonical SVGs written")
}

func cmdAll() {
    generateMacAppIcon()
    generateMacMenuBar()
    generateMacDMG()
    generateAndroidPlayIcon()
    generateAndroidVectors()
    generateWindowsAssets()
    generateExports()
    print("Done.")
}

// MARK: - Entry point

let args = CommandLine.arguments
guard args.count >= 2 else {
    print("usage: swift brand/generate.swift <gate|all>")
    exit(1)
}
switch args[1] {
case "gate":
    cmdGate()
case "all":
    cmdAll()
default:
    fputs("Unknown command \(args[1]). Use `gate` or `all`.\n", stderr)
    exit(1)
}
