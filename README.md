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

## Install with Obtainium (sideload updates)

The repo is **public**. Install and update Timber from GitHub Releases with [Obtainium](https://github.com/ImranR98/Obtainium) — no Play Store.

1. Install Obtainium from its GitHub Releases (or F-Droid).
2. **Add App** → source **GitHub**.
3. Repo URL: `https://github.com/zakjholt/timber`
4. Suggested options:
   - **APK filter:** `timber-.*-release\.apk` (or `\.apk$`)
   - **Include prereleases:** off (unless you want hyphenated tags like `v0.2.0-rc.1`)
   - Release / tag tracking: default GitHub Releases
5. Add → install the latest Release APK → Obtainium will offer updates when new `v*` Releases appear.

Release asset naming: `timber-<versionName>-release.apk` (e.g. `timber-0.1.0-release.apk`). Tags: `v0.1.0`.

**Signing:** keepable installs need a stable upload keystore in GitHub Actions secrets. Generate and back it up **before** the first install you care about keeping — see [docs/RELEASE.md](docs/RELEASE.md).

## Build

Open in Android Studio (or use the Gradle wrapper once the Android SDK/NDK are installed):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
```

Required on the machine: Android SDK 35, NDK (for `libtimber_audio`), and a device/emulator with USB host for MODX testing.

Release / CI signing details: [docs/RELEASE.md](docs/RELEASE.md).

## Next implementation slices

1. MIDI I/O + Part looper + per-track device/port/channel routing (Slice 1)
2. Song arrangement playback + editor (Slice 2)
3. Sequencer depth + persistence + stage UX harden (Slice 3)
4. MIDI clock master/slave — V1.x (Slice 4)
5. In-app audio / Path B stems — deferred (external mixer for now)
