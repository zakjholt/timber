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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(track.name, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Output device · port · channel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("Device / port", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (outputs.isEmpty()) {
                Text(
                    "No USB MIDI outputs yet. Connect a device (MODX expected).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                outputs.forEachIndexed { index, port ->
                    val selected = index == selectedKey
                    Text(
                        text = port.label + if (port.isModxFamily) "  · MODX" else "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedKey = index }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..16).chunked(8).forEach { rowChannels ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowChannels.forEach { ch ->
                            Text(
                                text = ch.toString(),
                                modifier = Modifier
                                    .clickable { channel = ch }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (ch == channel) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
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
                        val port = outputs.getOrNull(selectedKey)
                        val endpoint = port?.toRef() ?: track.output
                        onApply(endpoint, channel)
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
    // 0 = Omni, 1..16 = channel
    var channelChoice by remember(listenChannel) {
        mutableIntStateOf(listenChannel ?: 0)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Record input", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Global USB MIDI in + listen channel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("Input device / port", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (inputs.isEmpty()) {
                Text(
                    "No USB MIDI inputs yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                inputs.forEachIndexed { index, port ->
                    val selected = index == selectedKey
                    Text(
                        text = port.label + if (port.isModxFamily) "  · MODX" else "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedKey = index }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        val port = inputs.getOrNull(selectedKey)
                        val endpoint = port?.toRef() ?: current
                        val listen = channelChoice.takeIf { it in 1..16 }
                        onApply(endpoint, listen)
                    },
                ) { Text("Apply") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
