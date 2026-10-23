// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.schliweb.crate.PlayerViewModel
import de.schliweb.crate.core.PracticeLoop
import de.schliweb.crate.core.PracticeSettings
import de.schliweb.crate.core.timeText
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The track's energy as bars (what has played in the accent colour, the
 * rest grey), the loop as a band, the playhead as a line. Bars of the
 * slices' RMS squared, scaled to the loudest slice: a mastered song's
 * peaks or even its RMS are nearly level, its energy shows the verses,
 * choruses and breaks, the same as on the Mac. A tap
 * jumps there, a horizontal drag lays a new loop over what it covered;
 * vertical movement is left alone, so the sheet around it can scroll.
 * At the top of the practice sheet.
 */
@Composable
fun WaveformBar(model: PlayerViewModel, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val bars = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val playhead = MaterialTheme.colorScheme.onSurface
    var dragged by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val duration by rememberUpdatedState(model.duration.coerceAtLeast(0.001))
    fun time(x: Float, width: Int) = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f) * duration

    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { model.seek(time(it.x, size.width)) } }
            .pointerInput(Unit) {
                var start = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start = it.x },
                    onHorizontalDrag = { change, _ ->
                        if (abs(change.position.x - start) > 6) {
                            dragged = time(start, size.width) to time(change.position.x, size.width)
                        }
                    },
                    onDragEnd = {
                        dragged?.let { (a, b) -> PracticeLoop.of(a, b)?.let(model::setLoop) }
                        dragged = null
                    },
                    onDragCancel = { dragged = null },
                )
            },
    ) {
        val mid = size.height / 2
        val waveform = model.waveform
        if (waveform != null && waveform.levels.isNotEmpty()) {
            val levels = waveform.levels
            val step = 3.dp.toPx()
            val columns = (size.width / step).toInt().coerceAtLeast(1)
            val scale = 1f / waveform.maximumLevel
            val playedX = (model.currentTime / duration * size.width).toFloat()
            val radius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
            for (column in 0 until columns) {
                val from = column * levels.size / columns
                val to = maxOf(from + 1, (column + 1) * levels.size / columns).coerceAtMost(levels.size)
                var level = 0f
                for (i in from until to) if (levels[i] > level) level = levels[i]
                val relative = level * scale
                val height = (relative * relative * (size.height - 2)).coerceAtLeast(2.dp.toPx())
                val x = column * step
                drawRoundRect(
                    if (x + step / 2 < playedX) accent else bars,
                    Offset(x, mid - height / 2), Size(step - 1.dp.toPx(), height), radius,
                )
            }
        } else {
            drawLine(bars, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2f)
        }
        val band = dragged?.let { (a, b) -> minOf(a, b) to maxOf(a, b) } ?: model.practice.loop?.let { it.start to it.end }
        // A switched-off loop stays in view, grey.
        val tint = if (dragged == null && model.practice.loopOff) bars else accent
        if (band != null) {
            val x0 = (band.first / duration * size.width).toFloat()
            val x1 = (band.second / duration * size.width).toFloat()
            drawRect(tint.copy(alpha = 0.2f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2f), size.height))
            drawLine(tint, Offset(x0, 0f), Offset(x0, size.height), strokeWidth = 3f)
            drawLine(tint, Offset(x1, 0f), Offset(x1, size.height), strokeWidth = 3f)
        }
        model.loopStartMark?.let {
            val x = (it / duration * size.width).toFloat()
            drawLine(accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
        val x = (model.currentTime / duration * size.width).toFloat()
        drawLine(playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
    }
}

/** Tempo, key, tuning and the loop of the track playing; everything is
 *  remembered for that file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeSheet(model: PlayerViewModel, onDismiss: () -> Unit) {
    val practice = model.practice
    // Fully open: half open, the loop row sat under the screen's edge.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls: in landscape the sheet is taller than the screen.
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Practice", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = model::resetSound, enabled = practice.changesSound) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Original")
                }
            }

            // On a phone on its side, two columns: one column was taller
            // than the screen and hid the key, tuning and loop below.
            val landscape = LocalWindowInfo.current.containerSize.let { it.width > it.height }
            val sound: @Composable () -> Unit = {
                WaveformBar(model, Modifier.fillMaxWidth().height(64.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeText(model.currentTime), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(timeText(model.duration), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Label("Tempo", "${(practice.tempo * 100).roundToInt()} %")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stepper(Icons.Default.Remove, "Slower", practice.tempo > PracticeSettings.TEMPO_MIN) { model.changeTempo(-1) }
                    Slider(
                        value = practice.tempo.toFloat(),
                        onValueChange = { model.setTempo(it.toDouble()) },
                        valueRange = PracticeSettings.TEMPO_MIN.toFloat()..PracticeSettings.TEMPO_MAX.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Stepper(Icons.Default.Add, "Faster", practice.tempo < PracticeSettings.TEMPO_MAX) { model.changeTempo(1) }
                }
            }
            val rest: @Composable () -> Unit = {
                // One row each: two side by side did not fit a half-width
                // column on a phone on its side ("0 ct" lost its "t").
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowTitle("Key")
                    Stepper(Icons.Default.Remove, "Key down", practice.semitones > PracticeSettings.SEMITONE_MIN) { model.transpose(-1) }
                    Value(signed(practice.semitones), Modifier.width(64.dp))
                    Stepper(Icons.Default.Add, "Key up", practice.semitones < PracticeSettings.SEMITONE_MAX) { model.transpose(1) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowTitle("Tuning")
                    Stepper(Icons.Default.Remove, "Tune down", practice.cents > PracticeSettings.CENT_MIN) { model.tune(-1) }
                    Value("${signed(practice.cents)} ct", Modifier.width(64.dp))
                    Stepper(Icons.Default.Add, "Tune up", practice.cents < PracticeSettings.CENT_MAX) { model.tune(1) }
                    Text(
                        "A %.1f".format(practice.referencePitch),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                // The loop: the repeat icon is its title and its switch,
                // coloured while the loop plays, grey while it is kept but off.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = model::toggleLoop, enabled = practice.loop != null, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = if (practice.loopOff) "Turn the loop on" else "Turn the loop off",
                            tint = if (practice.activeLoop != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val small = Modifier.defaultMinSize(minWidth = 44.dp)
                    OutlinedButton(onClick = model::setLoopStart, modifier = small, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("A") }
                    OutlinedButton(onClick = model::setLoopEnd, modifier = small, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("B") }
                    val loop = practice.loop
                    val mark = model.loopStartMark
                    Text(
                        when {
                            loop != null -> "${timeText(loop.start)}–${timeText(loop.end)}"
                            mark != null -> "${timeText(mark)}–…"
                            else -> "No loop"
                        },
                        fontFamily = if (loop != null || mark != null) FontFamily.Monospace else null,
                        fontSize = 14.sp,
                        color = if ((loop != null && !practice.loopOff) || mark != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = model::clearLoop, enabled = loop != null || mark != null, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear the loop")
                    }
                }
            }
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { sound() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { rest() }
                }
            } else {
                sound()
                rest()
            }
            Spacer(Modifier.navigationBarsPadding().height(4.dp))
        }
    }
}

@Composable
private fun Label(title: String, value: String?, quiet: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value, maxLines = 1,
                fontWeight = if (quiet) FontWeight.Normal else FontWeight.Medium,
                fontFamily = if (quiet) null else FontFamily.Monospace,
                fontSize = if (quiet) 13.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The name in front of a −/+ row, all names the same width so the
 *  buttons line up. */
@Composable
private fun RowTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
}

/** The value between a − and a + button. */
@Composable
private fun Value(text: String, modifier: Modifier) {
    Text(
        text, modifier = modifier, maxLines = 1, softWrap = false,
        fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun Stepper(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = description) }
}

private fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    value < 0 -> "−${-value}"
    else -> "0"
}
