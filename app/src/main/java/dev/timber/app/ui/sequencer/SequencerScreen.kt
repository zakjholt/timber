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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.timber.app.domain.midi.MidiEndpointRef
import dev.timber.app.domain.midi.MidiTrack
import dev.timber.app.domain.midi.Part
import dev.timber.app.engine.midi.MidiEngine

@Composable
fun SequencerScreen(
    part: Part?,
    recordInputLabel: String,
    recordListenChannel: Int?,
    availableInputs: List<MidiEngine.PortInfo>,
    availableOutputs: List<MidiEngine.PortInfo>,
    recordInput: MidiEndpointRef,
    onArmTrack: (Int) -> Unit,
    onToggleMute: (Int) -> Unit,
    onSetTrackOutput: (trackId: Int, endpoint: MidiEndpointRef, channel: Int) -> Unit,
    onSetRecordInput: (MidiEndpointRef, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var routingTrackId by remember { mutableStateOf<Int?>(null) }
    var showRecordInput by remember { mutableStateOf(false) }
    val routingTrack = part?.tracks?.firstOrNull { it.id == routingTrackId }

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
            text = "${part?.lengthBeats ?: 0} beats · loop · thru always on",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        RecordInputSummary(
            label = recordInputLabel,
            listenChannel = recordListenChannel,
            onClick = { showRecordInput = true },
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(part?.tracks.orEmpty(), key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onArm = { onArmTrack(track.id) },
                    onMute = { onToggleMute(track.id) },
                    onOpenRouting = { routingTrackId = track.id },
                )
            }
        }
    }

    if (routingTrack != null) {
        TrackRoutingSheet(
            track = routingTrack,
            outputs = availableOutputs,
            onDismiss = { routingTrackId = null },
            onApply = { endpoint, channel ->
                onSetTrackOutput(routingTrack.id, endpoint, channel)
                routingTrackId = null
            },
        )
    }

    if (showRecordInput) {
        RecordInputSheet(
            current = recordInput,
            listenChannel = recordListenChannel,
            inputs = availableInputs,
            onDismiss = { showRecordInput = false },
            onApply = { endpoint, listen ->
                onSetRecordInput(endpoint, listen)
                showRecordInput = false
            },
        )
    }
}

@Composable
private fun RecordInputSummary(
    label: String,
    listenChannel: Int?,
    onClick: () -> Unit,
) {
    val channelLabel = listenChannel?.let { "Ch $it" } ?: "Omni"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("Record in", style = MaterialTheme.typography.labelLarge)
        Text(
            text = "$label · $channelLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackRow(
    track: MidiTrack,
    onArm: () -> Unit,
    onMute: () -> Unit,
    onOpenRouting: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val routingSummary = buildString {
        val name = track.output.displayName.ifBlank { "No out" }
        append(name.take(28))
        append(" · Ch ")
        append(track.outputChannel)
    }
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
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.name, style = MaterialTheme.typography.titleMedium)
                if (track.armed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ARM",
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Text(
                text = if (track.events.isEmpty()) "Empty" else "${track.events.size} events",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = routingSummary,
                modifier = Modifier
                    .clickable(onClick = onOpenRouting)
                    .padding(top = 2.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (track.armed) "ARMED" else "ARM",
                modifier = Modifier
                    .clickable(onClick = onArm)
                    .background(
                        if (track.armed) MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (track.muted) "MUTE" else "ON",
                modifier = Modifier
                    .clickable(onClick = onMute)
                    .background(
                        if (track.muted) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
