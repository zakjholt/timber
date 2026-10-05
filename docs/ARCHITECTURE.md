# Architecture notes for Timber audio/MIDI

## Session clock
- MIDI engine owns musical time (PPQN=96).
- Transport: Stopped → Playing; Record enters **CountIn** then **Recording**; stop panics all open outs.
- Audio recorders (deferred) will stamp against the same transport start.
- Future: sample-accurate MIDI→audio offset calibration for the MODX M USB round-trip.

## MIDI routing (Slice 1)
- Open ports on **any** USB MIDI device (`android.media.midi`).
- **Global record input:** device/port + listen channel (1–16 or Omni) → armed track.
- **Per-track output:** device + port + channel; thru always forwards to the armed track’s out (modifiers applied).
- MODX **Local Control off** is a user setup step (see README) so keys don’t double with thru.

## Audio graph (native)
Channel: input → insertChain* → stemTap → sendTaps* → mute/solo → pan/gain → master sum
Master: sum → insertChain* → masterTap → device output
* empty / bypass in v1

## Recording
- Stem tap: post-insert, pre-fader (DAW-common default)
- Master tap: post-master fader
- Writer thread drains lock-free ring buffers to WAV (24-bit or float32)
- Manifest: take_YYYYMMDD_HHMMSS/take.json

## MODX M routing tips
- For a stereo class-compliant link: leave Parts on Main L/R; Timber records Main.
- For multichannel (when OS allows): set Part Output Select to USB 1/2, 3/4, …
- Return Timber's monitor mix into MODX Digital In; set Digital In Output Select to Main L/R to hear it.
