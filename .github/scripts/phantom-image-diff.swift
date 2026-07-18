#!/usr/bin/env swift
import Foundation
import CoreGraphics
import ImageIO

private struct DiffOptions {
    let before: URL
    let after: URL
    let diff: URL
    let json: URL?
    let maskTopPixels: Int
    let threshold: Double
}

private struct RGBAImage {
    let width: Int
    let height: Int
    let pixels: [UInt8]
}

private enum ToolError: Error, CustomStringConvertible {
    case usage(String)
    case input(String)
    case output(String)
    case dimensions(Int, Int, Int, Int)

    var description: String {
        switch self {
        case .usage(let message), .input(let message), .output(let message):
            return message
        case .dimensions(let beforeWidth, let beforeHeight, let afterWidth, let afterHeight):
            return "mismatched image dimensions: before \(beforeWidth)x\(beforeHeight), after \(afterWidth)x\(afterHeight)"
        }
    }
}

private func nextValue(_ arguments: [String], _ index: inout Int, _ option: String) throws -> String {
    index += 1
    guard index < arguments.count else {
        throw ToolError.usage("missing value for \(option)")
    }
    let value = arguments[index]
    guard !value.hasPrefix("--") else {
        throw ToolError.usage("missing value for \(option)")
    }
    return value
}

private func parseOptions() throws -> DiffOptions {
    let arguments = Array(CommandLine.arguments.dropFirst())
    var before: URL?
    var after: URL?
    var diff: URL?
    var json: URL?
    var maskTopPixels = 0
    var threshold = 0.0
    var index = 0

    while index < arguments.count {
        let argument = arguments[index]
        switch argument {
        case "--before":
            before = URL(fileURLWithPath: try nextValue(arguments, &index, argument))
        case "--after":
            after = URL(fileURLWithPath: try nextValue(arguments, &index, argument))
        case "--diff":
            diff = URL(fileURLWithPath: try nextValue(arguments, &index, argument))
        case "--json":
            let value = try nextValue(arguments, &index, argument)
            if value != "-" {
                json = URL(fileURLWithPath: value)
            }
        case "--mask-top-pixels":
            let value = try nextValue(arguments, &index, argument)
            guard let parsed = Int(value), parsed >= 0 else {
                throw ToolError.usage("--mask-top-pixels must be a non-negative integer")
            }
            maskTopPixels = parsed
        case "--threshold":
            let value = try nextValue(arguments, &index, argument)
            guard let parsed = Double(value), parsed.isFinite, parsed >= 0, parsed <= 255 else {
                throw ToolError.usage("--threshold must be a number from 0 through 255")
            }
            threshold = parsed
        case "--help", "-h":
            throw ToolError.usage("usage: phantom-image-diff --before PATH --after PATH --diff PATH --json PATH [--mask-top-pixels N] [--threshold N]")
        default:
            throw ToolError.usage("unknown option: \(argument)")
        }
        index += 1
    }

    guard let before, let after, let diff else {
        throw ToolError.usage("usage: phantom-image-diff --before PATH --after PATH --diff PATH --json PATH [--mask-top-pixels N] [--threshold N]")
    }
    return DiffOptions(
        before: before,
        after: after,
        diff: diff,
        json: json,
        maskTopPixels: maskTopPixels,
        threshold: threshold
    )
}

private func loadRGBA(_ url: URL, label: String) throws -> RGBAImage {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
    else {
        throw ToolError.input("could not decode \(label) image")
    }

    let width = image.width
    let height = image.height
    guard width > 0, height > 0 else {
        throw ToolError.input("\(label) image has invalid dimensions")
    }
    var pixels = [UInt8](repeating: 0, count: width * height * 4)
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
    let rendered = pixels.withUnsafeMutableBytes { rawBuffer -> Bool in
        guard let context = CGContext(
            data: rawBuffer.baseAddress,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else {
            return false
        }
        context.interpolationQuality = .none
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return true
    }
    guard rendered else {
        throw ToolError.input("could not convert \(label) image to RGBA")
    }
    return RGBAImage(width: width, height: height, pixels: pixels)
}

private func writePNG(_ pixels: [UInt8], width: Int, height: Int, to url: URL) throws {
    let data = Data(pixels) as CFData
    guard let provider = CGDataProvider(data: data),
          let image = CGImage(
              width: width,
              height: height,
              bitsPerComponent: 8,
              bitsPerPixel: 32,
              bytesPerRow: width * 4,
              space: CGColorSpaceCreateDeviceRGB(),
              bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
              provider: provider,
              decode: nil,
              shouldInterpolate: false,
              intent: .defaultIntent
          ),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)
    else {
        throw ToolError.output("could not create diff PNG")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else {
        throw ToolError.output("could not write diff PNG")
    }
}

private func jsonNumber(_ value: Double) -> NSNumber {
    NSNumber(value: value)
}

private func makeJSON(
    width: Int,
    height: Int,
    changedPixels: Int,
    consideredPixels: Int,
    meanChannelDelta: Double,
    maxChannelDelta: Int,
    maskTopPixels: Int,
    threshold: Double,
    passed: Bool
) -> [String: Any] {
    let ratio = consideredPixels == 0 ? 0.0 : Double(changedPixels) / Double(consideredPixels)
    return [
        "passed": passed,
        "thresholdPassed": passed,
        "dimensions": ["width": width, "height": height],
        "width": width,
        "height": height,
        "changedPixels": changedPixels,
        "changedPixelRatio": jsonNumber(ratio),
        "changedRatio": jsonNumber(ratio),
        "meanChannelDelta": jsonNumber(meanChannelDelta),
        "maxChannelDelta": maxChannelDelta,
        "maskTopPixels": maskTopPixels,
        "threshold": jsonNumber(threshold),
    ]
}

private func writeJSON(_ object: [String: Any], to url: URL?) throws {
    let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    if let url {
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            throw ToolError.output("could not write JSON result")
        }
    } else {
        FileHandle.standardOutput.write(data)
        FileHandle.standardOutput.write(Data([0x0A]))
    }
}

private func compare(_ options: DiffOptions) throws -> [String: Any] {
    let before = try loadRGBA(options.before, label: "before")
    let after = try loadRGBA(options.after, label: "after")
    guard before.width == after.width, before.height == after.height else {
        throw ToolError.dimensions(before.width, before.height, after.width, after.height)
    }

    let width = before.width
    let height = before.height
    let maskedRows = min(options.maskTopPixels, height)
    let consideredPixels = (height - maskedRows) * width
    var output = [UInt8](repeating: 0, count: width * height * 4)
    var changedPixels = 0
    var channelDeltaSum: UInt64 = 0
    var maxChannelDelta = 0

    for row in 0..<height {
        // ImageIO exposes decoded scanlines in top-origin order here.  Apply
        // the mask to those rows directly so --mask-top-pixels never hides a
        // meaningful body change.
        let topOriginRow = row
        let masked = topOriginRow < maskedRows
        for column in 0..<width {
            let sourceOffset = (row * width + column) * 4
            var pixelMaxDelta = 0
            var pixelDeltaSum = 0
            for channel in 0..<4 {
                let delta = abs(Int(before.pixels[sourceOffset + channel]) - Int(after.pixels[sourceOffset + channel]))
                pixelDeltaSum += delta
                pixelMaxDelta = max(pixelMaxDelta, delta)
            }
            guard !masked else { continue }

            channelDeltaSum += UInt64(pixelDeltaSum)
            maxChannelDelta = max(maxChannelDelta, pixelMaxDelta)
            if Double(pixelMaxDelta) > options.threshold {
                changedPixels += 1
                let outputOffset = sourceOffset
                output[outputOffset] = 255
                output[outputOffset + 1] = 0
                output[outputOffset + 2] = 0
                output[outputOffset + 3] = 255
            }
        }
    }

    let denominator = max(1, consideredPixels * 4)
    let meanChannelDelta = Double(channelDeltaSum) / Double(denominator)
    let passed = changedPixels == 0
    try writePNG(output, width: width, height: height, to: options.diff)
    return makeJSON(
        width: width,
        height: height,
        changedPixels: changedPixels,
        consideredPixels: consideredPixels,
        meanChannelDelta: meanChannelDelta,
        maxChannelDelta: maxChannelDelta,
        maskTopPixels: maskedRows,
        threshold: options.threshold,
        passed: passed
    )
}

 do {
    let options = try parseOptions()
    let result = try compare(options)
    try writeJSON(result, to: options.json)
} catch {
    FileHandle.standardError.write(Data(("phantom-image-diff: \(error)\n").utf8))
    exit(1)
}
