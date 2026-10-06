package dev.timber.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.timber.app.ui.sequencer.SequencerScreen
import dev.timber.app.ui.session.SessionViewModel
import dev.timber.app.ui.session.TimberUiState
import dev.timber.app.ui.theme.TimberTheme
import dev.timber.app.ui.transport.TransportBar

class MainActivity : ComponentActivity() {
    private val viewModel: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimberTheme {
                val state by viewModel.ui.collectAsStateWithLifecycle()
                TimberAppScaffold(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun TimberAppScaffold(
    state: TimberUiState,
    viewModel: SessionViewModel,
) {
    val part = state.session.song.parts.firstOrNull {
        it.id == state.session.transport.currentPartId
    } ?: state.session.song.parts.firstOrNull()
    val recordLabel = state.recordInput.displayName.ifBlank {
        state.availableInputs.firstOrNull()?.label ?: "No MIDI in"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Text(
            text = "Timber",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            text = if (state.midiDevices.isEmpty()) {
                "Connect USB MIDI (MODX) via USB-C OTG"
            } else {
                state.midiDevices.joinToString()
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val pathLabel = buildString {
            append("MIDI path: ").append(state.userspaceMode.label)
            if (state.userspaceActive) append(" · active")
            if (state.userspaceStatus.isNotBlank()) append(" · ").append(state.userspaceStatus)
            append(" (tap to cycle)")
        }
        Text(
            text = pathLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { viewModel.cycleUserspaceMidiMode() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        TransportBar(
            transport = state.transport,
            positionTicks = state.positionTicks,
            tempoBpm = state.session.song.tempoBpm,
            countInBeatsRemaining = state.countInBeatsRemaining,
            countInPulse = state.countInPulse,
            onPlay = viewModel::play,
            onStop = viewModel::stop,
            onToggleRecord = viewModel::toggleMidiRecord,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            SequencerScreen(
                part = part,
                recordInputLabel = recordLabel,
                recordListenChannel = state.recordListenChannel,
                availableInputs = state.availableInputs,
                availableOutputs = state.availableOutputs,
                recordInput = state.recordInput,
                onArmTrack = viewModel::armMidiTrack,
                onToggleMute = viewModel::toggleMidiMute,
                onSetTrackOutput = viewModel::setTrackOutput,
                onSetRecordInput = { endpoint, listen ->
                    viewModel.setRecordInput(endpoint)
                    viewModel.setRecordListenChannel(listen)
                },
            )
        }
    }
}
