# Timber

Phone-first Android session brain: RK-008-class MIDI sequencing plus a live mixer and stem/master recorder, aimed at a Yamaha MODX M over USB.

## Stack

- Kotlin + Jetpack Compose (`dev.timber.app`)
- `android.media.midi` for USB MIDI
- Native AAudio/Oboe graph (`libtimber_audio`) for multichannel I/O, metering, and recording
- Shared session transport across MIDI + audio

## MODX M

At **44.1 kHz** the MODX M can expose **10 USB outs / 4 USB ins** (keyboard perspective). Timber sees those as host inputs (stems) and host outputs (return).

| Host inputs (from MODX) | Role |
|---|---|
| 0–1 | Main L/R (includes system + master FX) |
| 2–9 | Assignable USB 1–8 (parts without sys/master FX) |

| Host outputs (to MODX) | Role |
|---|---|
| 0–1 | Digital In L/R (route in MODX Performance → Audio In) |
| 2–3 | Extra return if the OS exposes 4 outs |

**Android class-compliant caveat:** many phones only negotiate **stereo** I/O. Timber detects channel counts at runtime and falls back to Main L/R labeling. For discrete Part stems, route MODX Parts to USB 1–8 and confirm the OS exposes those channels; otherwise keep Parts on Main for a stereo capture.

Connect phone ↔ MODX M **USB TO HOST** with a USB-C OTG cable. Prefer 44.1 kHz on both sides when you want the full channel map.

## V1 scope

**Sequencer**
- Song → Parts → 8 tracks
- Realtime MIDI record / overdub, mute, arm, transport, thru
- Non-destructive modifiers modeled (quantize / swing / transpose / filters); mixdown UI next

**Mixer / record**
- Per-input gain, mute, solo, meter
- Master level + meter
- Arm + record **stems** and **master** into `filesDir/takes/take_*/`

**Architected, not built yet**
- Mix buses + sends
- Insert FX chains (`AudioEffect` / `EffectChainState`)
- In-app FX suite (EQ, compressor, delay, reverb, saturation)

## Project layout

```
app/src/main/java/dev/timber/app/
  domain/     MIDI, audio, session models
  device/     ModxMProfile
  engine/     MidiEngine, AudioEngine, foreground service
  ui/         Sequencer, Mixer, Transport
app/src/main/cpp/
  audio/      AudioGraph (native stub → full duplex + WAV writers)
```

## Build

Open in Android Studio (or use the Gradle wrapper once the Android SDK/NDK are installed):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
```

Required on the machine: Android SDK 35, NDK (for `libtimber_audio`), and a device/emulator with USB host for MODX testing.

## Next implementation slices

1. Wire Oboe/AAudio duplex callbacks + real meters
2. Non-blocking WAV writers for armed stems + master + `take.json` manifest
3. MIDI output port open/send + event playback on the clock thread
4. Part length / quantize / swing sheets
5. Song arrangement mode
6. First insert FX (EQ) behind the existing chain slots
