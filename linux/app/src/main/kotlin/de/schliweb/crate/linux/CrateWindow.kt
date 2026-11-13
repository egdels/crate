// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.linux

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.schliweb.crate.core.Album
import de.schliweb.crate.core.Track
import de.schliweb.crate.core.countText
import de.schliweb.crate.core.lengthText
import de.schliweb.crate.core.timeText
import kotlinx.coroutines.delay

@Composable
fun CrateWindow(model: PlayerModel, chooseFolder: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(model, chooseFolder)
            HorizontalDivider()
            if (model.albums.isEmpty() && !model.scanning) {
                EmptyState(chooseFolder, Modifier.weight(1f))
            } else {
                Row(Modifier.weight(1f)) {
                    Column(Modifier.weight(0.45f).fillMaxHeight()) {
                        AlbumGrid(model, Modifier.weight(1f))
                        Text(
                            model.status, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    VerticalDivider()
                    Box(Modifier.weight(0.55f).fillMaxHeight()) {
                        model.selected?.let { AlbumDetail(model, it) }
                    }
                }
            }
            model.lastError?.let { ErrorLine(it) { model.lastError = null } }
            HorizontalDivider()
            PlayerBar(model)
        }
    }
}

@Composable
private fun Toolbar(model: PlayerModel, chooseFolder: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Crate", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = chooseFolder) { Text("Add Folder…") }
        OutlinedButton(onClick = model::rescan, enabled = model.hasFolders && !model.scanning) { Text("Rescan") }
    }
}

@Composable
private fun EmptyState(chooseFolder: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Your music, by album.", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Add the folder your music is in.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = chooseFolder) { Text("Add Folder…") }
    }
}

@Composable
private fun ErrorLine(text: String, dismiss: () -> Unit) {
    // Some failures have no later success that would clear them.
    LaunchedEffect(text) {
        delay(8000)
        dismiss()
    }
    Text(
        text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().clickable(onClick = dismiss).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun AlbumGrid(model: PlayerModel, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(model.albums, key = { it.id }) { album ->
            AlbumTile(model, album, selected = album.id == model.selected?.id)
        }
    }
}

@Composable
private fun AlbumTile(model: PlayerModel, album: Album, selected: Boolean) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(background)
            .clicks(onClick = { model.selected = album }, onDoubleClick = { model.play(album) })
            .padding(6.dp),
    ) {
        Cover(model, album, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            album.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Cover(model: PlayerModel, album: Album, modifier: Modifier) {
    val image by produceState(model.artwork.cached(album), album.id) { value = model.artwork.cover(album) }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun AlbumDetail(model: PlayerModel, album: Album) {
    val discs = album.discs
    var selectedTrack by remember(album.id) { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(model, album, Modifier.size(120.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(album.artist, album.year?.toString(), album.genre).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${countText(album.tracks.size, "track")} · ${lengthText(album.duration)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { model.play(album) }) { Text("Play") }
                }
            }
            HorizontalDivider()
        }
        for (disc in discs) {
            if (discs.size > 1) item(key = "disc${disc.number}") {
                Text(
                    "Disc ${disc.number}", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(disc.tracks, key = { it.id }) { track ->
                TrackRow(
                    track, playing = track.id == model.current?.id, selected = track.id == selectedTrack,
                    select = { selectedTrack = track.id }, play = { model.play(album, track) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, playing: Boolean, selected: Boolean, select: () -> Unit, play: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(background).clicks(onClick = select, onDoubleClick = play)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            track.trackNumber?.toString() ?: "", modifier = Modifier.width(32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            track.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
        )
        Text(timeText(track.duration), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayerBar(model: PlayerModel) {
    val track = model.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(Glyph.PREVIOUS, enabled = track != null, onClick = model::previous)
        TransportButton(if (model.isPlaying) Glyph.PAUSE else Glyph.PLAY, enabled = true, onClick = model::togglePlay)
        TransportButton(Glyph.NEXT, enabled = track != null, onClick = model::next)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                track?.displayArtist ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SeekRow(model, track)
        }
        Spacer(Modifier.width(16.dp))
        Text("Volume", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = model.volume.toFloat(), onValueChange = { model.changeVolume(it.toDouble()) },
            modifier = Modifier.width(120.dp),
        )
    }
}

@Composable
private fun SeekRow(model: PlayerModel, track: Track?) {
    // While dragging, the slider shows the drag, not the clock.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = track?.duration ?: 0.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(timeText(dragging?.toDouble() ?: model.position), style = MaterialTheme.typography.labelSmall)
        Slider(
            value = (dragging ?: model.position.toFloat()).coerceIn(0f, maxOf(duration.toFloat(), 0.001f)),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { model.seek(it.toDouble()) }; dragging = null },
            valueRange = 0f..maxOf(duration.toFloat(), 0.001f),
            enabled = track != null,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(timeText(duration), style = MaterialTheme.typography.labelSmall)
    }
}

/** Transport symbols drawn as paths: no icon font or icon artifact needed. */
private enum class Glyph { PLAY, PAUSE, NEXT, PREVIOUS }

@Composable
private fun TransportButton(glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    IconButton(onClick = onClick, enabled = enabled) {
        Canvas(Modifier.size(20.dp)) { drawGlyph(glyph, color) }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    fun triangle(left: Float, right: Float, pointsRight: Boolean) = Path().apply {
        if (pointsRight) { moveTo(left, 0f); lineTo(right, h / 2); lineTo(left, h) }
        else { moveTo(right, 0f); lineTo(left, h / 2); lineTo(right, h) }
        close()
    }
    when (glyph) {
        Glyph.PLAY -> drawPath(triangle(w * 0.15f, w * 0.9f, true), color)
        Glyph.PAUSE -> {
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.22f, h))
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.6f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.22f, h))
        }
        Glyph.NEXT -> {
            drawPath(triangle(0f, w * 0.75f, true), color)
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.78f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.14f, h))
        }
        Glyph.PREVIOUS -> {
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.08f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.14f, h))
            drawPath(triangle(w * 0.25f, w, false), color)
        }
    }
}

/**
 * A click and a double click on the same element. `combinedClickable`
 * holds the click back until the double-click time has passed; here the
 * click runs at once and a second one within that time is the double
 * click. So [onClick] also runs on the first click of a double click and
 * should only select.
 */
private fun Modifier.clicks(onClick: () -> Unit, onDoubleClick: () -> Unit): Modifier = composed {
    val last = remember { longArrayOf(0L) }
    clickable {
        val now = System.nanoTime()
        if (last[0] != 0L && now - last[0] <= DOUBLE_CLICK_NANOS) {
            last[0] = 0L
            onDoubleClick()
        } else {
            last[0] = now
            onClick()
        }
    }
}

/** The desktop's double-click time; AWT knows it on Linux, half a second otherwise. */
private val DOUBLE_CLICK_NANOS: Long =
    ((java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 500) * 1_000_000L
