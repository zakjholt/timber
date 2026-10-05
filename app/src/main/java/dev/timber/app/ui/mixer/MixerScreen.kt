package dev.timber.app.ui.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier.modifier
import androidx.compose.ui.unit.dp
import dev.timber.app.domain.audio.ChannelStripState
import dev.timber.app.domain.audio.MasterState
import dev.timber.app.domain.audio.MeterPeak
import dev.timber.app.domain.audio.MixerState

@Composable
fun MixerScreen(
    mixer: MixerState,
    meters: List<MeterPeak>,
    masterMeter: MeterPeak,
    audioRunning: Boolean,
    audioRecording: Boolean,
    deviceNotes: String,
    onToggleEngine: () -> Unit,
    onToggleRecord: () -> Unit,
    onGain: (Int, Float) -> Unit,
    onMute: (Int) -> Unit,
    onSolo: (Int) -> Unit,
    onArmStem: (Int) -> Unit,
    onArmMaster: () -> Unit,
    onMasterLevel: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Text("Mixer", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = deviceNotes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onToggleEngine) {
                Text(if (audioRunning) "Audio On" else "Start Audio")
            }
            TextButton(onClick = onToggleRecord) {
                Text(if (audioRecording) "Stop Rec" else "Rec Stems")
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(mixer.channels, key = { it.id }) { channel ->
                val meter = meters.find { it.channelId == channel.id }
                ChannelStrip(
                    channel = channel,
                    meter = meter?.peakL ?: 0f,
                    onGain = { onGain(channel.id, it) },
                    onMute = { onMute(channel.id) },
                    onSolo = { onSolo(channel.id) },
                    onArm = { onArmStem(channel.id) },
                )
            }
            item {
                MasterStrip(
                    master = mixer.master,
                    meter = masterMeter.peakL,
                    onLevel = onMasterLevel,
                    onArm = onArmMaster,
                )
            }
        }
    }
}

@Composable
private fun ChannelStrip(
    channel: ChannelStripState,
    meter: Float,
    onGain: (Float) -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onArm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .fillMaxHeight(0.85f)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(channel.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Meter(level = meter, modifier = Modifier.weight(1f))
        Slider(
            value = channel.gainDb,
            onValueChange = onGain,
            valueRange = -48f..12f,
            modifier = Modifier.height(120.dp),
        )
        FilterChip(selected = channel.recordArmed, onClick = onArm, label = { Text("R") })
        FilterChip(selected = channel.muted, onClick = onMute, label = { Text("M") })
        FilterChip(selected = channel.solo, onClick = onSolo, label = { Text("S") })
    }
}

@Composable
private fun MasterStrip(
    master: MasterState,
    meter: Float,
    onLevel: (Float) -> Unit,
    onArm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(100.dp)
            .fillMaxHeight(0.85f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Master", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Meter(level = meter, modifier = Modifier.weight(1f))
        Slider(
            value = master.levelDb,
            onValueChange = onLevel,
            valueRange = -48f..12f,
            modifier = Modifier.height(120.dp),
        )
        FilterChip(selected = master.recordArmed, onClick = onArm, label = { Text("R") })
    }
}

@Composable
private fun Meter(level: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(level.coerceIn(0f, 1f))
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
        )
    }
}
