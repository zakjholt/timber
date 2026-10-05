package dev.timber.app.ui.sequencer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.timber.app.domain.midi.MidiEndpointRef
import dev.timber.app.domain.midi.MidiTrack
import dev.timber.app.engine.midi.MidiEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackRoutingSheet(
    track: MidiTrack,
    outputs: List<MidiEngine.PortInfo>,
    onDismiss: () -> Unit,
    onApply: (MidiEndpointRef, Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var channel by remember(track.id) { mutableIntStateOf(track.outputChannel.coerceIn(1, 16)) }
    var selectedKey by remember(track.id, track.output) {
        mutableIntStateOf(
            outputs.indexOfFirst {
                it.deviceId == track.output.deviceId && it.portIndex == track.output.portIndex
            }.coerceAtLeast(0),
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(track.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            Text("Output", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (outputs.isEmpty()) {
                Text(
                    "No USB MIDI outputs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                outputs.forEachIndexed { index, port ->
                    Text(
                        text = port.label + if (port.isModxFamily) " · MODX" else "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedKey = index }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (index == selectedKey) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Channel", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..16).forEach { ch ->
                    Text(
                        text = ch.toString(),
                        modifier = Modifier
                            .clickable { channel = ch }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ch == channel) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        onApply(outputs.getOrNull(selectedKey)?.toRef() ?: track.output, channel)
                    },
                ) { Text("Apply") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordInputSheet(
    current: MidiEndpointRef,
    listenChannel: Int?,
    inputs: List<MidiEngine.PortInfo>,
    onDismiss: () -> Unit,
    onApply: (MidiEndpointRef, Int?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedKey by remember(current) {
        mutableIntStateOf(
            inputs.indexOfFirst {
                it.deviceId == current.deviceId && it.portIndex == current.portIndex
            }.coerceAtLeast(0),
        )
    }
    var channelChoice by remember(listenChannel) { mutableIntStateOf(listenChannel ?: 0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Record input", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            Text("Input", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (inputs.isEmpty()) {
                Text(
                    "No USB MIDI inputs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                inputs.forEachIndexed { index, port ->
                    Text(
                        text = port.label + if (port.isModxFamily) " · MODX" else "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedKey = index }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (index == selectedKey) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Listen channel", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Omni",
                modifier = Modifier
                    .clickable { channelChoice = 0 }
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (channelChoice == 0) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..16).forEach { ch ->
                    Text(
                        text = ch.toString(),
                        modifier = Modifier
                            .clickable { channelChoice = ch }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ch == channelChoice) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        onApply(
                            inputs.getOrNull(selectedKey)?.toRef() ?: current,
                            channelChoice.takeIf { it in 1..16 },
                        )
                    },
                ) { Text("Apply") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
