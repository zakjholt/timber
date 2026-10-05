package dev.timber.app.ui.sequencer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.timber.app.domain.midi.MidiTrack
import dev.timber.app.domain.midi.Part

@Composable
fun SequencerScreen(
    part: Part?,
    onArmTrack: (Int) -> Unit,
    onToggleMute: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = part?.name ?: "No Part",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "${part?.lengthBeats ?: 0} beats · 8 tracks · realtime + modifiers",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(part?.tracks.orEmpty(), key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onArm = { onArmTrack(track.id) },
                    onMute = { onToggleMute(track.id) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: MidiTrack,
    onArm: () -> Unit,
    onMute: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .then(
                if (track.armed) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.clickable(onClick = onArm)) {
            Text(track.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (track.events.isEmpty()) "Empty" else "${track.events.size} events",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (track.muted) "MUTE" else "ON",
            modifier = Modifier
                .clickable(onClick = onMute)
                .background(
                    if (track.muted) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    RoundedCornerShape(999.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
