// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Reads the tags and duration of one audio file. The library depends on
/// this protocol only, so tests run with a fake and the app with the
/// AVFoundation implementation.
public protocol MetadataReader: Sendable {
    func read(_ url: URL) async throws -> TrackMetadata
}
