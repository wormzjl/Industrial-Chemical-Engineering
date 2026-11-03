# Driver sounds

Purpose: authors the original motor and steam-turbine running loops, `assets/icheme/sounds/motor_running.ogg` and `turbine_running.ogg`. Authoring only; nothing here runs in the mod.

Batch: `documentation/2026-11-03-rotating-machinery/` (W4, decision D13).

## How the loops are made

The same route as `tools/pump-compressor-controls/make_loops.py`, which made `pump_running.ogg` and `compressor_running.ogg`: periodic tones whose cycles fit the 3 s loop exactly, plus noise shaped in the frequency domain over the whole loop (so it is periodic and the loop has no seam), mono 24 kHz Ogg Vorbis. No recordings, samples or external audio: the project is non-commercial and the loops are its own.

- Motor: a 50 Hz, 4-pole TEFC induction motor. Magnetic hum at twice the line frequency (100 Hz) and its harmonics, a faint rotor-slot whine (1,150 Hz), the cooling fan's blade pass (25 Hz x 10 blades, 250 Hz) and its broadband rush around 700 Hz, a once-per-revolution pulse.
- Steam turbine: steam hiss through nozzles and blading (broad, about 3 kHz, with a lower band about 800 Hz), a blade-passing tone at 2,400 Hz with harmonics, a low rotor rumble at 50 and 150 Hz, a slow two-per-second steam surge.

Each loop is scaled to the pump loop's RMS; the volume law (`UnitDesign.soundGain(shape, speed)`) sets the loudness in game.

## Run

From the worktree root, with the authoring Python and the codec packages in `../pump-compressor-controls/python-deps/` (see that README):

```bash
"C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" -I tools/driver-sounds/make_driver_loops.py tmp/driver-sounds
```

Then copy the two `.ogg` files into `src/main/resources/assets/icheme/sounds/`. The script prints, per loop, its bytes, frames, rate, RMS, peak, the step across the loop's seam and the mean step between samples (the seam step should be below it).

## Entry points

`make_driver_loops.py <output folder>`.

## History

- 2026-11-04: first loops (motor RMS 0.093, seam step 0.0004 against a mean step of 0.012; turbine RMS 0.099, seam step 0.008 against 0.074).
