// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import CrateCore
import SwiftUI

/// Practising with a record: the track's outline with the loop on it,
/// and tempo, key and tuning. Above the player bar while the sliders
/// button has it open; every value is remembered for the file by the model.
struct PracticePanel: View {
    @EnvironmentObject private var model: PlayerModel

    var body: some View {
        VStack(spacing: 8) {
            WaveformView()
                .frame(height: 64)
            // Only the tempo slider gives way when the window is narrow.
            HStack(spacing: 16) {
                tempo
                key.fixedSize()
                tuning.fixedSize()
                Spacer(minLength: 0)
                loop.fixedSize()
                Button { model.resetSound() } label: { Image(systemName: "arrow.counterclockwise") }
                    .buttonStyle(.borderless)
                    .disabled(!model.practice.changesSound)
                    .help("Back to the Recording's Tempo and Key (⌘0)")
            }
            .font(.callout)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .modifier(PlayerBarBackground())
    }

    private var tempo: some View {
        HStack(spacing: 6) {
            Text("Tempo").foregroundStyle(.secondary).fixedSize()
            StepButton(systemImage: "minus", help: "Slower (⌘↓)") { model.changeTempo(by: -1) }
                .disabled(model.practice.tempo <= PracticeSettings.tempoRange.lowerBound)
            Slider(
                value: Binding(get: { model.practice.tempo }, set: { model.setTempo($0) }),
                in: PracticeSettings.tempoRange
            )
            .frame(minWidth: 70, maxWidth: 160)
            StepButton(systemImage: "plus", help: "Faster (⌘↑)") { model.changeTempo(by: 1) }
                .disabled(model.practice.tempo >= PracticeSettings.tempoRange.upperBound)
            Text("\(Int((model.practice.tempo * 100).rounded())) %")
                .monospacedDigit()
                .fixedSize()
                .frame(width: 44, alignment: .trailing)
                .onTapGesture(count: 2) { model.setTempo(1) }
                .help("Double-click for 100 %")
        }
    }

    private var key: some View {
        HStack(spacing: 6) {
            Text("Key").foregroundStyle(.secondary)
            StepButton(systemImage: "minus", help: "Down a Semitone (⌥⌘↓)") { model.transpose(by: -1) }
                .disabled(model.practice.semitones <= PracticeSettings.semitoneRange.lowerBound)
            Text(signed(model.practice.semitones))
                .monospacedDigit()
                .frame(width: 28)
            StepButton(systemImage: "plus", help: "Up a Semitone (⌥⌘↑)") { model.transpose(by: 1) }
                .disabled(model.practice.semitones >= PracticeSettings.semitoneRange.upperBound)
        }
    }

    private var tuning: some View {
        HStack(spacing: 6) {
            Text("Tuning").foregroundStyle(.secondary)
            StepButton(systemImage: "minus", help: "Tune Down a Cent (⇧⌥⌘↓)") { model.tune(by: -1) }
                .disabled(model.practice.cents <= PracticeSettings.centRange.lowerBound)
            VStack(spacing: 0) {
                Text("\(signed(model.practice.cents)) ct").monospacedDigit()
                Text("A = \(model.practice.referencePitch, specifier: "%.1f") Hz")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            .frame(width: 84)
            .onTapGesture(count: 2) { model.setCents(0) }
            .help("Double-click for A = 440 Hz")
            StepButton(systemImage: "plus", help: "Tune Up a Cent (⇧⌥⌘↑)") { model.tune(by: 1) }
                .disabled(model.practice.cents >= PracticeSettings.centRange.upperBound)
        }
    }

    private var loop: some View {
        HStack(spacing: 6) {
            // The switch: blue while the loop plays, grey while it is
            // kept but off.
            Button { model.toggleLoop() } label: {
                Image(systemName: "repeat")
                    .foregroundStyle(model.practice.activeLoop != nil ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.borderless)
            .disabled(model.practice.loop == nil)
            .help(model.practice.loopOff ? "Turn the Loop On (⌘3)" : "Turn the Loop Off (⌘3)")
            Button { model.setLoopStart() } label: { Text("A").frame(minWidth: 12) }
                .help("Loop Starts Here (⌘1)")
            Button { model.setLoopEnd() } label: { Text("B").frame(minWidth: 12) }
                .help("Loop Ends Here (⌘2)")
            Group {
                if let loop = model.practice.loop {
                    Text("\(timeText(loop.start)) – \(timeText(loop.end))")
                        .foregroundStyle(model.practice.loopOff ? .secondary : .primary)
                } else if let mark = model.loopStartMark {
                    Text("\(timeText(mark)) – …")
                } else {
                    Text("No loop").foregroundStyle(.secondary)
                }
            }
            .monospacedDigit()
            .frame(minWidth: 96, alignment: .leading)
            Button { model.clearLoop() } label: { Image(systemName: "xmark.circle.fill") }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .disabled(model.practice.loop == nil && model.loopStartMark == nil)
                .help("Clear the Loop (⌘⌫)")
        }
    }

    private func signed(_ value: Int) -> String {
        value > 0 ? "+\(value)" : value < 0 ? "−\(-value)" : "0"
    }
}

private struct StepButton: View {
    let systemImage: String
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage).frame(width: 14, height: 14)
        }
        .buttonStyle(.borderless)
        .help(help)
    }
}

/// The track's peaks, the loop as a band, the playhead as a line. A
/// click jumps there, a drag lays a new loop over what it covered.
struct WaveformView: View {
    @EnvironmentObject private var model: PlayerModel
    /// The loop being dragged out, in seconds.
    @State private var dragged: (start: TimeInterval, end: TimeInterval)?

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let duration = max(model.duration, 0.001)
            Canvas { context, size in
                let mid = size.height / 2
                if let waveform = model.waveform {
                    drawLevels(waveform, in: &context, size: size, played: model.currentTime / duration)
                } else {
                    var line = Path()
                    line.move(to: CGPoint(x: 0, y: mid))
                    line.addLine(to: CGPoint(x: size.width, y: mid))
                    context.stroke(line, with: .color(.secondary.opacity(0.4)))
                }
                let band = dragged.map { (min($0.start, $0.end), max($0.start, $0.end)) }
                    ?? model.practice.loop.map { ($0.start, $0.end) }
                // A switched-off loop stays in view, grey.
                let tint: Color = dragged == nil && model.practice.loopOff ? .secondary : .accentColor
                if let (start, end) = band {
                    let rect = CGRect(
                        x: start / duration * size.width, y: 0,
                        width: max(1, (end - start) / duration * size.width), height: size.height)
                    context.fill(Path(rect), with: .color(tint.opacity(0.18)))
                    for x in [rect.minX, rect.maxX] {
                        context.fill(Path(CGRect(x: x - 0.5, y: 0, width: 1, height: size.height)),
                                     with: .color(tint.opacity(0.7)))
                    }
                }
                if let mark = model.loopStartMark {
                    let x = mark / duration * size.width
                    context.fill(Path(CGRect(x: x - 0.5, y: 0, width: 1, height: size.height)),
                                 with: .color(.accentColor.opacity(0.7)))
                }
                let x = model.currentTime / duration * size.width
                context.fill(Path(CGRect(x: x - 1, y: 0, width: 2, height: size.height)), with: .color(.primary))
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard abs(value.translation.width) > 3 else { return }
                        dragged = (time(value.startLocation.x, width, duration), time(value.location.x, width, duration))
                    }
                    .onEnded { value in
                        if let dragged, let loop = PracticeLoop(start: dragged.start, end: dragged.end) {
                            model.setLoop(loop)
                        } else if dragged == nil {
                            model.seek(to: time(value.location.x, width, duration))
                        }
                        dragged = nil
                    }
            )
        }
        .help("Click to jump there, drag to loop a passage")
        .disabled(model.current == nil)
    }

    private func time(_ x: CGFloat, _ width: CGFloat, _ duration: TimeInterval) -> TimeInterval {
        min(max(Double(x / max(width, 1)), 0), 1) * duration
    }

    /// Bars of the slices' energy (RMS squared), scaled to the loudest
    /// slice. The RMS of a mastered song stays within a few dB, so bars
    /// of it are nearly level; its square spreads quiet and loud apart
    /// and keeps their order, and the shape of the song shows: what has played in the accent colour,
    /// the rest grey, with a gap between bars so that they read as bars
    /// and not as one surface.
    private func drawLevels(_ waveform: Waveform, in context: inout GraphicsContext, size: CGSize, played: Double) {
        let levels = waveform.levels
        guard !levels.isEmpty, size.width > 0 else { return }
        let pitch: CGFloat = 3
        let columns = max(1, Int(size.width / pitch))
        let scale = 1 / waveform.maximumLevel
        let mid = size.height / 2
        let playedX = CGFloat(played) * size.width
        var done = Path()
        var ahead = Path()
        for column in 0..<columns {
            let from = column * levels.count / columns
            let to = min(max(from + 1, (column + 1) * levels.count / columns), levels.count)
            let level = levels[from..<to].max() ?? 0
            let relative = level * scale
            let height = max(2, CGFloat(relative * relative) * (size.height - 2))
            let x = CGFloat(column) * pitch
            let bar = Path(roundedRect: CGRect(x: x, y: mid - height / 2, width: pitch - 1, height: height), cornerRadius: 1)
            if x + pitch / 2 < playedX { done.addPath(bar) } else { ahead.addPath(bar) }
        }
        context.fill(done, with: .color(.accentColor))
        context.fill(ahead, with: .color(.secondary.opacity(0.45)))
    }
}
