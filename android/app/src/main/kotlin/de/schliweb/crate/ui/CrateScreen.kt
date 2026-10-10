// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import de.schliweb.crate.PlayerViewModel
import de.schliweb.crate.PlayerViewModel.Selection
import de.schliweb.crate.core.Album
import de.schliweb.crate.core.Track
import de.schliweb.crate.core.countText
import de.schliweb.crate.core.lengthText
import de.schliweb.crate.core.timeText

/**
 * The whole app on one screen, laid out like the Mac window when there
 * is room (album grid left, track list right) and as two pages when
 * there is not (grid, then the album with a back gesture). The player
 * bar sits at the bottom whenever something is loaded.
 */
@Composable
fun CrateScreen(model: PlayerViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(model::addFolder)
        }
        var searching by remember { mutableStateOf(false) }
        var showingPrivacyPolicy by rememberSaveable { mutableStateOf(false) }
        if (showingPrivacyPolicy) PrivacyPolicyDialog(onDismiss = { showingPrivacyPolicy = false })
        val showingDetail = !wide && model.selection != null && model.folders.isNotEmpty()
        BackHandler(enabled = showingDetail || searching) {
            if (searching) {
                searching = false
                model.searchText = ""
            } else {
                model.selection = null
            }
        }
        // With both panes visible there is always a selection, like on the
        // Mac; on a phone the grid is the start page.
        LaunchedEffect(wide, model.albums) {
            if (wide && model.selection == null) model.albums.firstOrNull()?.let { model.selection = Selection.AlbumId(it.id) }
        }

        Scaffold(
            topBar = {
                TopBar(
                    model = model,
                    wide = wide,
                    searching = searching,
                    onSearching = { searching = it },
                    onBack = if (showingDetail) ({ model.selection = null }) else null,
                    onPickFolder = { pickFolder.launch(null) },
                    onShowPrivacyPolicy = { showingPrivacyPolicy = true },
                )
            },
            bottomBar = { if (model.current != null) PlayerBar(model) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                model.lastError?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            IconButton(onClick = { model.lastError = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
                when {
                    model.folders.isEmpty() -> EmptyLibrary(model, onPickFolder = { pickFolder.launch(null) })
                    wide -> Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxSize()) {
                            AlbumGrid(model, Modifier.weight(1f))
                            StatusLine(model)
                        }
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxSize()) { Detail(model) }
                    }
                    model.selection != null -> Detail(model)
                    else -> {
                        AlbumGrid(model, Modifier.weight(1f))
                        StatusLine(model)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    model: PlayerViewModel,
    wide: Boolean,
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    onPickFolder: () -> Unit,
    onShowPrivacyPolicy: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    TopAppBar(
        title = {
            if (searching) {
                LaunchedEffect(Unit) { focus.requestFocus() }
                TextField(
                    value = model.searchText,
                    onValueChange = { model.searchText = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            } else {
                val title = when {
                    onBack != null -> model.selectedAlbum?.title ?: if (model.selection == Selection.AllTracks) "All Tracks" else "Crate"
                    else -> "Crate"
                }
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (onBack != null && !searching) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = {
            if (model.folders.isNotEmpty()) {
                if (searching) {
                    IconButton(onClick = { onSearching(false); model.searchText = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Close search")
                    }
                } else {
                    IconButton(onClick = { onSearching(true) }) { Icon(Icons.Default.Search, contentDescription = "Search") }
                    IconButton(onClick = {
                        if (model.detail == Selection.AllTracks || (!wide && model.selection == null)) {
                            model.play(model.filteredTracks)
                        } else {
                            model.selectedAlbum?.let { model.play(it) }
                        }
                    }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
                }
            }
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Add Folder…") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    enabled = !model.isScanning,
                    onClick = { menuOpen = false; onPickFolder() },
                )
                if (model.folders.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Rescan") },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        enabled = !model.isScanning,
                        onClick = { menuOpen = false; model.rescan() },
                    )
                    for (folder in model.folders) {
                        DropdownMenuItem(
                            text = { Text("Remove ${folder.name}") },
                            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                            enabled = !model.isScanning,
                            onClick = { menuOpen = false; model.removeFolder(folder) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (model.isShuffled) "Shuffle: On" else "Shuffle: Off") },
                        leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null) },
                        onClick = { menuOpen = false; model.setShuffle(!model.isShuffled) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (model.repeatMode) {
                                    Player.REPEAT_MODE_ALL -> "Repeat: All"
                                    Player.REPEAT_MODE_ONE -> "Repeat: One"
                                    else -> "Repeat: Off"
                                },
                            )
                        },
                        leadingIcon = {
                            Icon(if (model.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, contentDescription = null)
                        },
                        onClick = { menuOpen = false; model.cycleRepeat() },
                    )
                }
                // Google Play's User Data policy wants the privacy policy
                // reachable from inside the app, not only from the listing;
                // the text comes from the assets, so the app stays offline.
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Privacy Policy") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    onClick = { menuOpen = false; onShowPrivacyPolicy() },
                )
            }
        },
    )
}

@Composable
private fun EmptyLibrary(model: PlayerViewModel, onPickFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.LibraryMusic, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Pick a folder with music.", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Crate reads the files where they are. Nothing is copied or uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFolder, enabled = !model.isScanning) { Text("Choose Folder…") }
        if (model.isScanning) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(model.statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Scan feedback lives here, not in a dialog: what the last scan did,
 *  or simply how big the library is. */
@Composable
private fun StatusLine(model: PlayerViewModel) {
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (model.isScanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            model.statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AlbumGrid(model: PlayerViewModel, modifier: Modifier = Modifier) {
    val albums = model.filteredAlbums
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        item(key = "all") {
            Tile(
                selected = model.selection == Selection.AllTracks,
                onClick = { model.selection = Selection.AllTracks },
                title = "All Tracks",
                subtitle = countText(model.filteredTracks.size, "track"),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
        items(albums, key = { it.id }) { album ->
            Tile(
                selected = model.selection == Selection.AlbumId(album.id),
                onClick = { model.selection = Selection.AlbumId(album.id) },
                title = album.title,
                subtitle = album.artist,
            ) {
                Artwork(model, album, placeholder = initials(album.title))
            }
        }
    }
}

@Composable
private fun Tile(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    subtitle: String,
    picture: @Composable () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) { picture() }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun initials(title: String): String =
    title.split(' ').filter { it.isNotEmpty() }.take(2).map { it.first() }.joinToString("").uppercase()

/** Square cover, or the placeholder text while there is none. */
@Composable
private fun Artwork(model: PlayerViewModel, album: Album, placeholder: String = "") {
    val image by produceState<Bitmap?>(null, album.id) { value = model.artwork(album) }
    ArtworkImage(image, placeholder)
}

@Composable
private fun ArtworkImage(image: Bitmap?, placeholder: String = "") {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (placeholder.isNotEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Detail(model: PlayerViewModel) {
    when (model.detail) {
        Selection.AllTracks -> AllTracks(model)
        is Selection.AlbumId -> model.selectedAlbum?.let { TrackList(model, it) }
            ?: Placeholder("Select an album")
        null -> Placeholder("Select an album")
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackList(model: PlayerViewModel, album: Album) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(6.dp))) { Artwork(model, album) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(album.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${countText(album.tracks.size, "track")} · ${lengthText(album.duration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(album.tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(track, playing = track.id == model.current?.id, onClick = { model.play(album, index) })
        }
    }
}

@Composable
private fun TrackRow(track: Track, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            if (playing) {
                Icon(Icons.Default.GraphicEq, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            } else {
                Text(track.trackNumber?.toString() ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val artist = track.artist
            if (artist != null && artist != track.displayArtist) {
                Text(artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(timeText(track.duration), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every track as one flat list; the search field narrows it. A tap
 *  plays from that row through the end of the list. */
@Composable
private fun AllTracks(model: PlayerViewModel) {
    val tracks = model.filteredTracks
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            Row(
                Modifier.fillMaxWidth().clickable { model.play(tracks, index) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(18.dp)) {
                    if (track.id == model.current?.id) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(track.artist, track.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(timeText(track.duration), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlayerBar(model: PlayerViewModel) {
    val track = model.current ?: return
    Surface(tonalElevation = 3.dp) {
        // Below the bar is the system's gesture area; keep the slider out of it.
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))) { ArtworkImage(model.currentArtwork) }
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(track.artist, track.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = model::previous) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous") }
                IconButton(onClick = model::togglePlayPause) {
                    Icon(
                        if (model.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (model.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = model::next) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val duration = model.duration
                Text(timeText(model.currentTime), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = model.currentTime.toFloat().coerceIn(0f, maxOf(duration, 1.0).toFloat()),
                    onValueChange = { model.seek(it.toDouble()) },
                    valueRange = 0f..maxOf(duration, 1.0).toFloat(),
                    modifier = Modifier.weight(1f),
                )
                Text(timeText(duration), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
