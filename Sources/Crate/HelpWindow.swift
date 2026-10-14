// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import SwiftUI

/// The Help menu's "Crate Help". Without this, SwiftUI puts the item there
/// anyway and macOS answers the click with "Help isn't available for
/// Crate". One page in one window, not an Apple Help Book: the app has a
/// handful of things to explain, and a help book needs an indexed HTML
/// bundle for them. Plain AppKit window, because a second SwiftUI scene
/// and `openWindow` need macOS 13.
@MainActor
enum Help {
    static let repository = URL(string: "https://github.com/egdels/crate")!

    private static var window: NSWindow?

    static func show() {
        if window == nil {
            let hosting = NSHostingController(rootView: HelpView())
            let window = NSWindow(contentViewController: hosting)
            window.title = "Crate Help"
            window.styleMask = [.titled, .closable, .miniaturizable]
            window.isReleasedWhenClosed = false
            window.center()
            Self.window = window
        }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    static func openRepository() {
        NSWorkspace.shared.open(repository)
    }
}

struct HelpView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Crate opens and plays. It reads music where it lies; nothing is copied, uploaded or changed.")
                    .foregroundStyle(.secondary)

                HelpSection("Library") {
                    HelpRow("⌘O", "Add a folder, subfolders included.")
                    HelpRow("⌘R", "Rescan: new, changed and removed files are picked up. Unchanged files are not read again.")
                    HelpRow("", "File > Remove Folder takes a folder out of the library. Nothing on disk changes.")
                    HelpRow("", "Files the system cannot decode count as unreadable in the line under the grid. File > Show Unreadable Files in Finder shows which.")
                }

                HelpSection("Playing") {
                    HelpRow("", "Double-click an album to play it, or a track to start there and play on through the album. All Tracks lists everything; a double-click plays from that row to the end of the list.")
                    HelpRow("", "Albums are sorted by artist, ignoring a leading \"The\" and following the sort names in the tags when the files have them, then by year.")
                    HelpRow("", "The search field narrows albums and tracks by title, artist, album, composer and genre. Albums play gapless.")
                    HelpRow("", "Files tagged with ReplayGain or iTunes Sound Check play at the level the tags describe, so albums come out equally loud; the volume control is on top of that. Untagged files play as they are. Playing an album, every track gets the same correction, so a quiet intro stays quiet and the record keeps its dynamics; in Shuffle and All Tracks each track is levelled on its own.")
                    HelpRow("Space", "Play or pause.")
                    HelpRow("⌘→  ⌘←", "Next and previous track.")
                    HelpRow("⇧⌘S", "Shuffle. Repeat is in the Playback menu.")
                    HelpRow("⌘U", "Up Next: what plays after this track, also behind the list icon in the player bar. Double-click plays an entry now, the × takes it out. Right-click a track or an album for Play Next and Play Last.")
                    HelpRow("⌘L", "Favorite: a heart on the track that is playing. Rows and the album header have the heart too; the Favorites tile plays them. Favorites are kept on this Mac, in a file next to the library index.")
                    HelpRow("", "The media keys and Control Center's Now Playing work as with any player.")
                }

                HelpSection("Finder") {
                    HelpRow("", "Crate > Use Crate for Audio Files makes Crate what a double-click on a music file opens. A file from the library plays with its album queued; any other file plays as it is. A folder dropped on the Dock icon is added to the library.")
                    HelpRow("", "Closing the window keeps the music playing. The Dock icon brings the window back.")
                }

                HelpSection("Files and formats") {
                    HelpRow("", "MP3, AAC and M4A (including M4B audiobooks), FLAC, ALAC, WAV, AIFF and CAF. Ogg Vorbis and Opus are not played, because macOS cannot decode them.")
                    HelpRow("", "Covers come from the files or from an image next to them, such as cover.jpg or folder.png.")
                    HelpRow("", "The library index and the cover thumbnails live in ~/Library/Application Support/Crate. Deleting that folder starts over.")
                }

                HelpSection("Source and bug reports") {
                    HelpRow("", "Crate is free software under the GPL. Code, releases and the issue tracker:")
                    HelpRow("") { Link("github.com/egdels/crate", destination: Help.repository) }
                }
            }
            .padding(24)
        }
        .frame(width: 520, height: 700)
    }
}

private struct HelpSection<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            content
        }
    }
}

/// A line of help, with the keyboard shortcut in a column of its own so
/// the eye can scan for keys.
private struct HelpRow<Content: View>: View {
    let key: String
    let content: Content

    init(_ key: String, @ViewBuilder content: () -> Content) {
        self.key = key
        self.content = content()
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(key)
                .font(.body.monospaced())
                .frame(width: 64, alignment: .trailing)
            content
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

extension HelpRow where Content == Text {
    init(_ key: String, _ text: String) {
        self.init(key) { Text(text) }
    }
}
