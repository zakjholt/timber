# Timber

Phone-first Android **MIDI session brain** for a Yamaha MODX M (and other USB MIDI devices): RK-008 / Pyramid–class realtime record/loop with stage-safe portrait UX. Live mix/monitor is via an **external physical mixer** for now — in-app audio mixer/stems are deferred.

## Stack

- Kotlin + Jetpack Compose (`dev.timber.app`)
- `android.media.midi` for USB MIDI (any attached device; MODX primary)
- Foreground service keeps the MIDI engine alive with the screen off
- Native audio graph remains in the repo but is **not** on the near-term path

## MODX M setup (required for thru)

1. Connect phone ↔ MODX M **USB TO HOST** with a USB-C OTG cable.
2. On the MODX, set **Local Control = off** (Utility / MIDI settings). Timber keeps **MIDI thru always on** while ports are open; with Local Control on, keys double (local + thru’d notes).
3. Prefer 44.1 kHz on both sides when you also care about USB audio channel maps later.

### USB audio map (deferred / reference)

At **44.1 kHz** the MODX M can expose **10 USB outs / 4 USB ins** (keyboard perspective). Timber’s in-app mixer/recorder is punted; use a physical mixer for monitor/mix until that phase returns.

| Host inputs (from MODX) | Role |
|---|---|
| 0–1 | Main L/R (includes system + master FX) |
| 2–9 | Assignable USB 1–8 (parts without sys/master FX) |

| Host outputs (to MODX) | Role |
|---|---|
| 0–1 | Digital In L/R (route in MODX Performance → Audio In) |
| 2–3 | Extra return if the OS exposes 4 outs |

## V1 MIDI scope (near-term)

**Sequencer / session**
- Song → Parts → 8 tracks
- Realtime MIDI record / overdub **with count-in** into the armed track
- Global record input (USB MIDI device/port) + listen channel **1–16 or Omni**
- Per-track output **device + port + channel** (picker sheet)
- Mute / arm; thru always on; panic (all notes/sound off) on stop
- Non-destructive modifiers applied on playback/thru (transpose, velocity, filters, force channel; quantize/swing started)
- Song arrangement + MIDI clock out (master) land in later slices

**Not in this phase**
- In-app mixer UI, AAudio duplex, Path B stem capture
- MIDI clock slave/follow, song variations, step entry

## Project layout

```
app/src/main/java/dev/timber/app/
  domain/     MIDI, audio, session models
  device/     ModxMProfile
  engine/     MidiEngine, AudioEngine, foreground service
  ui/         Sequencer, Transport, routing sheets (Mixer stub hidden)
app/src/main/cpp/
  audio/      AudioGraph (native stub — deferred)
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

## Manual test (Pixel 11 Pro + MODX)

1. Install a debug APK; set MODX **Local Control off**.
2. Plug OTG → confirm device name under the Timber title.
3. Open **Record in** → pick MODX input + Omni (or a channel).
4. Tap a track’s routing line → pick MODX out + channel (match the MODX Part).
5. Arm a track → **Record** → wait for count-in → play keys → **Stop** (panic) or Record again to punch out to Play.
6. **Play** — loop should emit to the track’s port/channel; mute should silence that track; screen-off should keep the session via the foreground notification.

## Next implementation slices

1. Song arrangement (Parts chain) — Slice 2
2. MIDI clock out (master), modifiers polish, persistence — Slice 3
3. Deferred audio / Path B when monitor returns in-app
