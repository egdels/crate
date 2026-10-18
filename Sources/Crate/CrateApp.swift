// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import SwiftUI

/// Receives the files Finder opens with Crate. They can arrive before the
/// window exists (the app was launched by the double-click), so they are
/// held until the model is attached.
///
/// The "open documents" Apple event is taken straight from the event
/// manager rather than through `application(_:open:)`: by the time that
/// method runs, SwiftUI's WindowGroup has already decided to open a new
/// window per file, which is not what a player wants. Claiming the event
/// first keeps it to the one window.
///
/// Main actor: AppKit calls the delegate on the main thread, and the
/// windows it looks at and the model it hands files to live there too.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    var onOpen: (([URL]) -> Void)? {
        didSet { flush() }
    }
    /// Quit: the model writes down where playback stands.
    var onTerminate: (() -> Void)?
    private var pending: [URL] = []

    func applicationWillFinishLaunching(_ notification: Notification) {
        NSAppleEventManager.shared().setEventHandler(
            self, andSelector: #selector(handleOpenDocuments(_:replyEvent:)),
            forEventClass: AEEventClass(kCoreEventClass), andEventID: AEEventID(kAEOpenDocuments))
    }

    @objc private func handleOpenDocuments(_ event: NSAppleEventDescriptor, replyEvent: NSAppleEventDescriptor) {
        guard let list = event.paramDescriptor(forKeyword: keyDirectObject)?.coerce(toDescriptorType: typeAEList) else {
            return
        }
        var urls: [URL] = []
        for index in stride(from: 1, through: list.numberOfItems, by: 1) {
            if let url = list.atIndex(index)?.fileURLValue {
                urls.append(url)
            }
        }
        pending.append(contentsOf: urls)
        flush()
    }

    /// Fallback for anything that still arrives the AppKit way.
    func application(_ application: NSApplication, open urls: [URL]) {
        pending.append(contentsOf: urls)
        flush()
    }

    /// An app launched by opening a document gets no initial window from
    /// AppKit, and SwiftUI on macOS 12 follows that; a player wants its
    /// window either way. Checked a moment after launch, not at launch,
    /// because the WindowGroup creates its window asynchronously.
    func applicationDidFinishLaunching(_ notification: Notification) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            if !NSApp.windows.contains(where: { $0.canBecomeMain && $0.isVisible }) {
                self.showWindow()
            }
        }
    }

    private func showWindow() {
        if let window = NSApp.windows.first(where: { $0.canBecomeMain && $0.title == "Crate" }) {
            window.makeKeyAndOrderFront(nil)
        } else {
            NSWorkspace.shared.open(URL(string: "crate://main")!)
        }
    }

    /// A player keeps playing with its window closed; the Dock icon or
    /// the media keys bring it back.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    func applicationWillTerminate(_ notification: Notification) {
        onTerminate?()
    }

    /// Dock icon clicked with the window closed: bring it back. A closed
    /// WindowGroup window is gone for good, and the openWindow action
    /// needs macOS 13, so the app opens its own crate://main URL, which
    /// the WindowGroup below handles by showing a window.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        if !hasVisibleWindows {
            showWindow()
        }
        return false
    }

    private func flush() {
        guard let onOpen, !pending.isEmpty else { return }
        let urls = pending
        pending = []
        onOpen(urls)
    }
}

@main
struct CrateApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    // Held, not observed: an App that observes the model re-evaluates its
    // scenes, menus included, on every change of it. The views observe it
    // through the environment, the menus through `model.menu`.
    @State private var model = PlayerModel.live()

    init() {
        // `swift run` starts a bare executable with no bundle; without this
        // it would get no Dock icon, no menu bar and no key window.
        NSApplication.shared.setActivationPolicy(.regular)
    }

    var body: some Scene {
        // WindowGroup, because the single-window `Window` scene needs
        // macOS 13. handlesExternalEvents makes the existing window take
        // the files Finder hands over instead of opening one per file.
        // Closing the window keeps the app running; the Dock icon brings
        // it back.
        WindowGroup("Crate") {
            ContentView()
                .environmentObject(model)
                .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
                .frame(minWidth: 820, minHeight: 520)
                .onOpenURL { _ in }  // crate://main: showing the window is all it means
                .task {
                    NSApp.activate(ignoringOtherApps: true)
                    delegate.onOpen = { [model] urls in Task { await model.open(urls) } }
                    delegate.onTerminate = { [model] in model.savePlayback() }
                    await model.start()
                }
        }
        // No handlesExternalEvents(matching:) on the scene: on macOS 12 a
        // group marked that way opens only for matching events, not at
        // launch, and the app came up without a window.
        .commands { CrateCommands(model: model, menu: model.menu) }
    }
}
