// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Finds the cover image for one audio file: the picture embedded in its
/// tags, or failing that an image file lying next to it. Returns the
/// encoded image bytes (JPEG, PNG, ...) untouched; `ArtworkCache` does the
/// decoding and scaling. nil means the file has no cover, which is not an
/// error.
public protocol ArtworkReader: Sendable {
    func artwork(for url: URL) async throws -> Data?
}
