package dev.timber.app.ui.transport

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import dev.timber.app.domain.midi.PPQN
import dev.timber.app.domain.midi.TransportState

@Composable
fun TransportBar(
    transport: TransportState,
    positionTicks: Long,
    tempoBpm: Float,
    countInBeatsRemaining: Int,
    countInPulse: Int,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onToggleRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val beat = (positionTicks / PPQN) + 1
    val tickInBeat = positionTicks % PPQN
    val isCountIn = transport == TransportState.CountIn
    val isRecording = transport == TransportState.Recording

    val pulseScale by animateFloatAsState(
        targetValue = if (isCountIn && countInPulse % 2 == 0) 1.08f else 1f,
        animationSpec = tween(120),
        label = "countInPulse",
    )
    val statusColor by animateColorAsState(
        targetValue = when {
            isRecording -> MaterialTheme.colorScheme.secondary
            isCountIn -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(160),
        label = "transportStatus",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.scale(pulseScale)) {
            Text(
                text = String.format("%03d:%02d  %.1f", beat, tickInBeat, tempoBpm),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = when {
                    isCountIn -> "COUNT IN · $countInBeatsRemaining"
                    isRecording -> "RECORDING"
                    transport == TransportState.Playing -> "PLAYING"
                    else -> "STOPPED"
                },
                style = MaterialTheme.typography.labelLarge,
                color = statusColor,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledIconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop")
            }
            FilledIconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            }
            FilledIconButton(
                onClick = onToggleRecord,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = when {
                        isRecording -> MaterialTheme.colorScheme.secondary
                        isCountIn -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = "Record MIDI")
            }
        }
    }
}
