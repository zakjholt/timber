# Architecture notes for Timber audio/MIDI

## Session clock
- MIDI engine owns musical time (PPQN=96).
- Record: CountIn → Recording; stop panics all open outs.

## MIDI routing
- Any USB MIDI device via `android.media.midi`.
- Global record input: device/port + listen channel (1–16 or Omni).
- Per-track output: device + port + channel; thru to armed track out.
- MODX Local Control off (see README).

## Audio graph (native)
Channel: input → insertChain* → stemTap → sendTaps* → mute/solo → pan/gain → master sum
Master: sum → insertChain* → masterTap → device output
* empty / bypass in v1

## Recording
- Stem tap: post-insert, pre-fader
- Master tap: post-master fader
- Writer thread drains lock-free ring buffers to WAV (24-bit or float32)
- Manifest: take_YYYYMMDD_HHMMSS/take.json

## MODX M routing tips
- Stereo class-compliant: Parts on Main L/R.
- Multichannel (when OS allows): Part Output Select → USB 1/2, 3/4, …
- Return monitor into MODX Digital In → Main L/R to hear it.
