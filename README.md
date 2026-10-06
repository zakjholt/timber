# Timber

Phone-first Android MIDI session brain for Yamaha MODX M (and other USB MIDI): realtime record/loop with stage-safe portrait UX. Live mix is an external physical mixer for now.

## Stack

- Kotlin + Jetpack Compose (`dev.timber.app`)
- `android.media.midi` for USB MIDI
- Foreground service keeps the MIDI engine alive with the screen off

## MODX M setup

1. Phone ↔ MODX **USB TO HOST** via USB-C OTG.
2. Set **Local Control = off** on the MODX. Timber thru is always on while ports are open; Local Control on doubles notes.
3. Prefer 44.1 kHz when you care about the full USB audio channel map later.

### USB audio map (reference)

At **44.1 kHz**: **10 USB outs / 4 USB ins** from the keyboard’s view.

| Host inputs (from MODX) | Role |
|---|---|
| 0–1 | Main L/R (system + master FX) |
| 2–9 | Assignable USB 1–8 |

| Host outputs (to MODX) | Role |
|---|---|
| 0–1 | Digital In L/R |
| 2–3 | Extra return if the OS exposes 4 outs |

## V1 MIDI

- Song → Parts → 8 tracks
- Record/overdub with count-in into the armed track
- Global record input (device/port) + listen channel 1–16 or Omni
- Per-track output device + port + channel
- Mute / arm; thru always on; panic on stop
- Modifiers on playback/thru (transpose, velocity, filters, force channel)

## Project layout

```
app/src/main/java/dev/timber/app/
  domain/     MIDI, audio, session models
  device/     ModxMProfile
  engine/     MidiEngine, AudioEngine, foreground service
  ui/         Sequencer, Transport, routing sheets
app/src/main/cpp/
  audio/      AudioGraph (stub)
```

## Install (Obtainium)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add App → GitHub → `https://github.com/zakjholt/timber`
3. APK filter: `timber-.*-release\.apk` · Include prereleases: off
4. Install / update from GitHub Releases (`timber-<version>-release.apk`, tags `v0.1.0`).

Publishing a release (keystore secrets, first tag): [docs/RELEASE.md](docs/RELEASE.md).

## Build

```bash
./gradlew :app:assembleDebug
```

Needs Android SDK 35, NDK, and a USB-host device for MODX testing.

### Debug APK (sideload + MIDI logcat)

Debug builds enumerate **MIDI 1.0 and UMP (MIDI 2.0)** devices, attach receivers on all open input ports, and log under tag `TimberMidi`. MODX defaults prefer UMP ports; apps like MidiTapLatencyTester are de-prioritized.

**Local**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**CI:** Actions → **Debug APK** → Run workflow (or open a PR that touches MIDI). Download the `timber-debug` artifact (`timber-debug.apk`), then:

```bash
adb install -r timber-debug.apk
```

**Capture logs** (OTG + MODX connected, Local Control off; quit MidiTapLatencyTester if open):

```bash
adb logcat -c
adb logcat -s TimberMidi:I '*:S'
```

Then in Timber: look for `MODX UMP: YES openMode=OPEN_UMP` (Timber opens **either** UMP **or** MIDI 1.0 for MODX — never both; dual-open crashed on Pixel SDK 37). Prefer Record in `… · UMP · …` + **Omni** → arm → **Record** → play. Expect `IN … UMP1/UMP2`. If a prior UMP open crashed the app, logs show `blocking UMP auto-open` and MIDI1 is used until app data is cleared.

**Fatal crash capture** (if it still dies):

```bash
adb logcat -b crash -b main '*:E' AndroidRuntime:E TimberMidi:I '*:S'
```

## Manual test (Pixel 11 Pro + MODX)

1. Install debug APK; MODX **Local Control off**.
2. OTG → device name under Timber title.
3. **Record in** → MODX input + Omni.
4. Track routing line → MODX out + Part channel.
5. Arm → **Record** → count-in → play → **Stop** or Record again to punch to Play.
6. **Play**; mute; screen-off should keep the session notification.

## Next

1. Song arrangement — Slice 2
2. MIDI clock out (master), modifiers polish, persistence — Slice 3
3. In-app audio / Path B when needed
