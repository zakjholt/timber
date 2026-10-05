# Architecture notes for Timber audio/MIDI

## Session clock
- MIDI engine owns musical time (PPQN=96).
- Audio recorders stamp against the same transport start.
- Future: sample-accurate MIDI→audio offset calibration for the MODX M USB round-trip.

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
