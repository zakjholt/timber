package dev.timber.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.timber.app.ui.mixer.MixerScreen
import dev.timber.app.ui.sequencer.SequencerScreen
import dev.timber.app.ui.session.SessionViewModel
import dev.timber.app.ui.session.TimberTab
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
                TimberAppScaffold(
                    state = state,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun TimberAppScaffold(
    state: dev.timber.app.ui.session.TimberUiState,
    viewModel: SessionViewModel,
) {
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
                "Connect MODX M via USB-C OTG"
            } else {
                state.midiDevices.joinToString()
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        TransportBar(
            transport = state.transport,
            positionTicks = state.positionTicks,
            tempoBpm = state.session.song.tempoBpm,
            onPlay = viewModel::play,
            onStop = viewModel::stop,
            onToggleRecord = viewModel::toggleMidiRecord,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            when (state.tab) {
                TimberTab.Sequencer -> SequencerScreen(
                    part = state.session.song.parts.firstOrNull(),
                    onArmTrack = viewModel::armMidiTrack,
                    onToggleMute = viewModel::toggleMidiMute,
                )
                TimberTab.Mixer -> MixerScreen(
                    mixer = state.session.mixer,
                    meters = state.meters,
                    masterMeter = state.masterMeter,
                    audioRunning = state.audioRunning,
                    audioRecording = state.audioRecording,
                    deviceNotes = state.deviceNotes,
                    onToggleEngine = viewModel::toggleAudioEngine,
                    onToggleRecord = viewModel::toggleAudioRecord,
                    onGain = viewModel::setChannelGain,
                    onMute = viewModel::toggleChannelMute,
                    onSolo = viewModel::toggleChannelSolo,
                    onArmStem = viewModel::toggleStemArm,
                    onArmMaster = viewModel::toggleMasterArm,
                    onMasterLevel = viewModel::setMasterLevel,
                )
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = state.tab == TimberTab.Sequencer,
                onClick = { viewModel.selectTab(TimberTab.Sequencer) },
                icon = { Text("SEQ") },
                label = { Text("Sequencer") },
            )
            NavigationBarItem(
                selected = state.tab == TimberTab.Mixer,
                onClick = { viewModel.selectTab(TimberTab.Mixer) },
                icon = { Text("MIX") },
                label = { Text("Mixer") },
            )
        }
    }
}
