// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// "3:38" or "1:02:15". Hand-rolled because `Duration.formatted` needs
/// macOS 13 and the app runs on 12.
func timeText(_ seconds: TimeInterval) -> String {
    let total = seconds.isFinite ? Int(seconds.rounded(.down)) : 0
    let hours = total / 3600, minutes = (total % 3600) / 60, secs = total % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, secs)
        : String(format: "%d:%02d", minutes, secs)
}

/// "1 hr, 12 min" for an album, "3 min" for a short one.
func lengthText(_ seconds: TimeInterval) -> String {
    let formatter = DateComponentsFormatter()
    formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute] : [.minute]
    formatter.unitsStyle = .abbreviated
    return formatter.string(from: max(seconds, 60)) ?? "0 min"
}
