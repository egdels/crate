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
                    HelpRow("", "Click the artist's name in an album's header to see that artist's albums: the search field takes \"artist:Name\", which you can type as well. Clear the field to see everything again.")
                    HelpRow("", "Albums come out equally loud: files tagged with ReplayGain play at the level the tags describe, and files without are measured by Crate in the background after a scan (EBU R 128, the way ReplayGain 2.0 asks for it; the line under the grid counts along, the results are kept next to the library index, the files are not touched). Until a file is measured, its iTunes Sound Check tag counts, or it plays as it is. The volume control is on top of that. Playing an album, every track gets the same correction, so a quiet intro stays quiet and the record keeps its dynamics; in Shuffle and All Tracks each track is levelled on its own.")
                    HelpRow("Space", "Play or pause.")
                    HelpRow("⌘→  ⌘←", "Next and previous track.")
                    HelpRow("⌥⌘←  ⌥⌘→", "Back 15 seconds, forward 30. For audiobooks, radio plays, podcasts and tracks over 20 minutes the buttons sit beside Play, and Control Center and the media controls offer the jumps instead of previous and next. A track counts as spoken when it is an .m4b file or its genre says so (Audiobook, Hörbuch, Spoken Word, Podcast…).")
                    HelpRow("", "Audiobooks go on where you left them, also after other music in between: Crate keeps the position of each such file (in a file next to the library index). Playing it again starts there, playing its album starts at the file heard last; a file heard to its end starts from the top next time. After a pause it goes back a few seconds before playing on, more the longer the pause, so you pick up the thread again. Its tempo in the practice panel is the whole book's.")
                    HelpRow("⇧⌘S", "Shuffle. Repeat is in the Playback menu.")
                    HelpRow("⌥⌘L", "Lyrics, when the track has them: an .lrc or .txt file with the track's name beside it or in a lyrics folder, or the lyrics in its tags. A timed .lrc follows the playback. The button appears in the player bar for such tracks.")
                    HelpRow("⌘U", "Up Next: what plays after this track, also behind the list icon in the player bar. Double-click plays an entry now, the × takes it out. Right-click a track or an album for Play Next and Play Last.")
                    HelpRow("⌘L", "Favorite: a heart on the track that is playing. Rows and the album header have the heart too; the Favorites tile plays them. Favorites are kept on this Mac, in a file next to the library index.")
                    HelpRow("", "Recently Added lists the newest files by their date, an album copied in one go together; Recently Played what was played last, newest first, kept in a file next to the index like the favorites. Both tiles play their list, and the search narrows them.")
                    HelpRow("", "The media keys and Control Center's Now Playing work as with any player.")
                    HelpRow("", "Crate remembers where you stopped: the next launch comes up with the same queue, at the same spot, paused.")
                    HelpRow("", "At the end of the queue the last track goes back to its start, paused, so Play plays it again.")
                }

                HelpSection("Practice") {
                    HelpRow("⌥⌘P", "For playing along: the sliders button in the player bar opens a panel above it, for the track playing, and closes it again. The button is coloured while the track does not play as recorded: another tempo, key or tuning, or a loop that is on. Whatever you set is remembered for that file and comes back the next time it plays, in a file next to the library index; the music files are not touched. The keys below work with the panel closed too.")
                    HelpRow("⌘↓  ⌘↑", "Tempo, 50 to 200 %, in steps of 5 % with − and + or with the slider. The key stays where it is. Double-click the percentage for 100 %.")
                    HelpRow("⌥⌘↓  ⌥⌘↑", "Key, up to an octave down or up, in semitones. The tempo stays where it is.")
                    HelpRow("⇧⌥⌘↓  ⇧⌥⌘↑", "Tuning, up to 50 cents either way, for a recording that is not at A = 440 Hz or an instrument that cannot follow it. The panel shows the A it amounts to; double-click it for 440 Hz.")
                    HelpRow("⌘0", "Back to the recording's tempo, key and tuning: the round arrow at the panel's right end. The loop stays.")
                    HelpRow("⇧⌥⌘←  ⇧⌥⌘→", "Back or forward 5 seconds, the round arrows in the panel. Inside a loop that is on, the jumps stay in the loop.")
                    HelpRow("⌘1  ⌘2", "Loop: A marks where it starts, B where it ends, at the spot playing; from then on that passage repeats without a gap and shows as a band on the wave. Or drag over the wave. A click on the wave jumps there. With a loop set, A and B move its ends.")
                    HelpRow("⌘3", "Turn the loop off and on again; A and B stay. The repeat symbol in the panel does the same and is coloured while the loop plays.")
                    HelpRow("⌘⌫", "Clear the loop, the × beside its times: A and B are gone and playback runs on to the end.")
                }

                HelpSection("Finder") {
                    HelpRow("", "Crate > Use Crate for Audio Files makes Crate what a double-click on a music file opens. A file from the library plays with its album queued; any other file plays as it is. A folder dropped on the Dock icon is added to the library.")
                    HelpRow("⇧⌘R", "Show in Finder: the file of the track that is playing. Right-click a track, an album or an Up Next entry for the same.")
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
