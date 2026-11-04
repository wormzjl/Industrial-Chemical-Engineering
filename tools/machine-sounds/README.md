# Machine sounds

Purpose: authors the mod's original running loops in `assets/icheme/sounds/`: `pump_running.ogg` and `compressor_running.ogg` (the casings), `motor_running.ogg` (motor and generator), `turbine_running.ogg` (steam turbine), and a pump's `pump_cavitation.ogg` and `pump_gas.ogg` (W10c), played over its casing loop. Authoring only; nothing here runs in the mod.

Batch: `documentation/2026-11-03-rotating-machinery/` (W4, decision D26).

## How the loops are made

The same route as `tools/pump-compressor-controls/make_loops.py`, which made the first pump and compressor loops on 2026-10-23: periodic tones whose cycles fit the 3 s loop exactly, plus noise shaped in the frequency domain over the whole loop (so it is periodic and the loop has no seam), mono 24 kHz Ogg Vorbis. No recordings, samples or external audio: the project is non-commercial and the loops are its own.

- Pump casing: the liquid's wash (the 2026-10-23 noise spectrum, from 90 Hz, peak about 700 Hz) with a three-per-second ripple, and the vane pass (2,950 rpm x 6 vanes, 300 Hz) with its harmonics. No motor hum: the motor has its own loop (D26).
- Compressor casing: the gas rush (the same spectrum plus a band about 1.5 kHz) and a faint 450 Hz tone, pulsing six times a second. No motor hum.
- Motor: a 50 Hz, 4-pole TEFC induction motor. Magnetic hum at twice the line frequency (100 Hz) and its harmonics, a faint rotor-slot whine (1,150 Hz), the cooling fan's blade pass (25 Hz x 10 blades, 250 Hz) and its broadband rush around 700 Hz, a once-per-revolution pulse.
- Pump cavitation (W10c, decision D35): "gravel in the pump", a train of about 140 sharp random clicks a second (short bright bursts around 4 kHz and 1.2 kHz, heavy-tailed in strength) over a faint broadband hiss, rounded by a soft limit (tanh at 2.2 times its RMS) so the snaps do not clip. Each click wraps round the loop's end. In game its volume is `I_c(σ)` (`Wear.intensity`) times the casing's gain.
- Pump gas churn (W10c, D35): low irregular surges, a noise band about 110 Hz (50 to 200 Hz, with a little at 60 Hz) under a smooth random envelope swelling one to four times a second. In game its volume is `GasEntrainment.churn(α/α_T)` times the casing's gain.
- Steam turbine: steam hiss through nozzles and blading (broad, about 3 kHz, with a lower band about 800 Hz), a blade-passing tone at 2,400 Hz with harmonics, a low rotor rumble at 50 and 150 Hz, a slow two-per-second steam surge.

Each casing loop keeps the 2026-10-23 loop's RMS (pump 0.0926, compressor 0.1109); the drivers take the pump's. In game the volume law `UnitDesign.soundGain(shape, speed)` sets the loudness and `UnitDesign.soundPitch(speed)` the pitch.

## Run

From the worktree root, with the authoring Python and the codec packages in `../pump-compressor-controls/python-deps/` (see that README):

```bash
"C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" -I tools/machine-sounds/make_loops.py tmp/machine-sounds
```

Then copy the `.ogg` files into `src/main/resources/assets/icheme/sounds/`. The script prints, per loop, its bytes, frames, rate, RMS, peak, the step across the loop's seam and the mean step between samples (the seam step should be of the order of the mean step or below).

## Entry points

`make_loops.py <output folder>`.

## History

- 2026-11-04 (owner: "Rotation machine sound should also scale with their rpm"): the pitch law reads the loops' reference speeds, `UnitDesign.PUMP_LOOP_RPM` 2950 rpm for `pump_running` (its 300 Hz vane pass, 2950 rpm x 6 vanes; the pump's crackle and churn share it) and `UnitDesign.MOTOR_LOOP_RPM` 1500 rpm for `motor_running` and the generator (250 Hz fan blade pass, 25 Hz x 10 blades). `compressor_running` and `turbine_running` state no speed and sound at their machine's rated speed. Pitch = n/n_loop, 0.5 to 2.
- 2026-11-04 (W10c): `pump_cavitation.ogg` (22,261 bytes, RMS 0.101, peak 0.47, seam step 0.054 against a mean step of 0.092) and `pump_gas.ogg` (7,864 bytes, RMS 0.093, peak 0.57, seam step 0.0007 against 0.0021); the other four loops re-render with the same numbers and were not copied (Ogg stream serials differ per run).
- 2026-11-04: motor and turbine loops (motor RMS 0.093, seam step 0.0004 against a mean step of 0.012; turbine RMS 0.099, seam step 0.008 against 0.074).
- 2026-11-04: owner "yes" to the casings without the motor hum and to pitch with speed: pump and compressor loops re-authored here (pump RMS 0.095, peak 0.43, seam step 0.030 against a mean step of 0.023; compressor RMS 0.114, peak 0.52, seam step 0.024 against 0.034).
