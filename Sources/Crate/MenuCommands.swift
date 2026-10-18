// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Combine
import CrateCore
import SwiftUI

/// What the menu bar shows, and nothing else. SwiftUI rebuilds the menus
/// whenever an object they observe changes; observing the whole model
/// rebuilt them several times a second during loudness analysis (its
/// progress) and playback (the clock), and an open submenu such as
/// Remove Folder was replaced under the pointer, so its items did not
/// respond.
struct MenuSnapshot: Equatable {
    var opensAudioFiles = false
    var folders: [URL] = []
    var isScanning = false
    var unreadableCount = 0
    var hasCurrent = false
    var isPlaying = false
    var hasNext = false
    var isShuffled = false
    var repeatMode = PlayQueueRepeat.off
    var currentIsFavorite = false
    var queueIsEmpty = true
    var showsUpNext = false
    var showsLyrics = false
    var hasLyrics = false

    init() {}

    @MainActor
    init(_ model: PlayerModel) {
        opensAudioFiles = model.opensAudioFiles
        folders = model.folders
        isScanning = model.isScanning
        unreadableCount = model.unreadableFiles.count
        hasCurrent = model.current != nil
        isPlaying = model.isPlaying
        hasNext = model.queue.hasNext
        isShuffled = model.queue.isShuffled
        repeatMode = model.queue.repeatMode
        currentIsFavorite = model.current.map(model.isFavorite) == true
        queueIsEmpty = model.queue.isEmpty
        showsUpNext = model.showsUpNext
        showsLyrics = model.showsLyrics
        hasLyrics = model.lyrics != nil
    }
}

/// Publishes a new `MenuSnapshot` only when it differs from the last one.
@MainActor
final class MenuState: ObservableObject {
    @Published private(set) var snapshot = MenuSnapshot()
    private var subscription: AnyCancellable?

    func follow(_ model: PlayerModel) {
        snapshot = MenuSnapshot(model)
        // objectWillChange fires before the change, so the snapshot is
        // taken a turn later, when the new values are in.
        subscription = model.objectWillChange.sink { [weak self, weak model] _ in
            Task { @MainActor in
                guard let self, let model else { return }
                let next = MenuSnapshot(model)
                if next != self.snapshot { self.snapshot = next }
            }
        }
    }
}

struct CrateCommands: Commands {
    let model: PlayerModel
    @ObservedObject var menu: MenuState

    var body: some Commands {
        let state = menu.snapshot
        // In the app menu, under About: a one-time command, not a
        // setting. Greyed out once it is done.
        CommandGroup(after: .appInfo) {
            Divider()
            Button("Use Crate for Audio Files") { Task { await model.makeDefaultForAudioFiles() } }
                .disabled(state.opensAudioFiles)
        }
        CommandGroup(replacing: .newItem) {
            Button("Open Folder…") { model.chooseFolder() }
                .keyboardShortcut("o")
            Button("Rescan Library") { Task { await model.rescan() } }
                .keyboardShortcut("r")
                .disabled(state.folders.isEmpty || state.isScanning)
            // One entry per watched folder; no confirmation, because
            // nothing on disk changes and Open Folder brings it back.
            Menu("Remove Folder") {
                ForEach(state.folders, id: \.self) { folder in
                    Button(folder.path) { Task { await model.removeFolder(folder) } }
                }
            }
            .disabled(state.folders.isEmpty || state.isScanning)
            Divider()
            Button(state.unreadableCount > 1
                   ? "Show \(state.unreadableCount) Unreadable Files in Finder"
                   : "Show Unreadable File in Finder") {
                model.showUnreadableFilesInFinder()
            }
            .disabled(state.unreadableCount == 0)
        }
        // SwiftUI adds "Crate Help" to the Help menu whether or not the
        // app has one; left alone it opens a "Help isn't available"
        // alert. HelpWindow.swift is what it opens instead.
        CommandGroup(replacing: .help) {
            Button("Crate Help") { Help.show() }
                .keyboardShortcut("?", modifiers: .command)
            Divider()
            Button("Crate on GitHub") { Help.openRepository() }
        }
        CommandMenu("Playback") {
            Button(state.isPlaying ? "Pause" : "Play") { model.togglePlayPause() }
                .keyboardShortcut(.space, modifiers: [])
                .disabled(!state.hasCurrent)
            Button("Next") { model.next() }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .disabled(!state.hasNext)
            Button("Previous") { model.previous() }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .disabled(!state.hasCurrent)
            Divider()
            Toggle("Shuffle", isOn: Binding(get: { state.isShuffled }, set: { model.setShuffle($0) }))
                .keyboardShortcut("s", modifiers: [.command, .shift])
            Picker("Repeat", selection: Binding(get: { state.repeatMode }, set: { model.setRepeat($0) })) {
                Text("Off").tag(PlayQueueRepeat.off)
                Text("All").tag(PlayQueueRepeat.all)
                Text("One").tag(PlayQueueRepeat.one)
            }
            Divider()
            Button(state.currentIsFavorite ? "Remove from Favorites" : "Add to Favorites") {
                model.toggleCurrentFavorite()
            }
            .keyboardShortcut("l")
            .disabled(!state.hasCurrent)
            Button("Show in Finder") { model.showCurrentInFinder() }
                .keyboardShortcut("r", modifiers: [.command, .shift])
                .disabled(!state.hasCurrent)
            Divider()
            Button(state.showsUpNext ? "Hide Up Next" : "Show Up Next") { model.showsUpNext.toggle() }
                .keyboardShortcut("u")
                .disabled(state.queueIsEmpty)
            Button(state.showsLyrics ? "Hide Lyrics" : "Show Lyrics") { model.showsLyrics.toggle() }
                .keyboardShortcut("l", modifiers: [.command, .option])
                .disabled(!state.hasLyrics)
        }
    }
}
