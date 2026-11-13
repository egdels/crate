// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.linux

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.schliweb.crate.core.JsonLibraryStore
import de.schliweb.crate.core.Library
import de.schliweb.crate.core.LocalFileSource
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.Version
import java.io.File
import javax.swing.JFileChooser
import kotlin.system.exitProcess

/** `crate [folder...]`: folders given are added to the library, which is
 *  how a test adds one without the picker. */
fun main(args: Array<String>) {
    try {
        Gst.init(Version(1, 16), "Crate")
    } catch (e: Throwable) {
        System.err.println("Crate needs GStreamer 1.16 or later: ${e.message}")
        exitProcess(1)
    }
    val library = Library(JsonLibraryStore(File(dataDirectory(), "library.json")), LinuxMetadataReader(), LocalFileSource())
    val model = PlayerModel(library)
    model.start(args.map { File(it).absoluteFile }.filter { it.isDirectory })
    val dark = prefersDark()
    nameWindowClass()

    application {
        Window(
            onCloseRequest = { model.dispose(); exitApplication() },
            title = "Crate",
            state = rememberWindowState(width = 1100.dp, height = 720.dp),
        ) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CrateWindow(model) { model.pickFolder(::chooseFolder) }
            }
        }
    }
}

/**
 * The window class a desktop matches against its menu entries. AWT on X11
 * derives it from the thread that started the toolkit ("java-lang-Thread"
 * here), so the dock showed a nameless second icon beside the pinned one.
 * The installed entry is `crate-Crate.desktop` (jpackage: package-launcher).
 * A private field of the X11 toolkit; where it is missing or closed off
 * the class simply stays what it was.
 */
private fun nameWindowClass() {
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, WINDOW_CLASS)
    }
}

private const val WINDOW_CLASS = "crate-Crate"

/** `$XDG_DATA_HOME/crate`, the index and everything else next to it. */
private fun dataDirectory(): File {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".local/share").path
    return File(base, "crate").apply { mkdirs() }
}

/** GNOME's dark style; other desktops get the light one for now. */
private fun prefersDark(): Boolean = runCatching {
    val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").start()
    process.inputStream.bufferedReader().readText().contains("dark")
}.getOrDefault(false)

/** The desktop's own folder dialog through zenity where it exists (GNOME
 *  ships it), Swing's otherwise. */
private fun chooseFolder(): File? {
    runCatching {
        val process = ProcessBuilder("zenity", "--file-selection", "--directory", "--title=Add Folder").start()
        val path = process.inputStream.bufferedReader().readText().trim()
        return if (process.waitFor() == 0 && path.isNotEmpty()) File(path) else null
    }
    val chooser = JFileChooser(System.getProperty("user.home")).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Add Folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
