# Process sounds

Purpose: authors the mod's original process loops in `assets/icheme/sounds/`: `flare_roar.ogg` and `flare_pilot.ogg` (an elevated flare), `burner_roar.ogg` (a still's pressure-jet burner, a fired heater's burners), `fire_draught.ogg` (a still's solid-fuel firebox), `pot_boil.ogg` (a boiling still pot, a kettle's pool), and for the plant's other sources (`PROCESS_SOUNDS_REVIEW.md`, table 1) `valve_gas.ogg` (a gas or steam valve's and a vent's hiss), `fan_whoosh.ogg` (axial fans), `water_fall.ogg` (falling water at a tower's inlets), `gear_whine.ogg` (a gearbox) and the one-shot `pipe_knock.ogg` (a water hammer). A plain liquid valve's crackle is `valve_liquid.ogg`, the former `pump_cavitation.ogg` of `tools/machine-sounds/`; `valve_candidates.py` made the owner's picks of 2026-11-10: `pump_cavitation.ogg` (candidate L2, a cavitating valve and a cavitating pump) and `valve_flashing.ogg` (F1, a flashing valve's hiss and plug rattle). Authoring only; nothing here runs in the mod.

Batch: `documentation/2026-11-10-custom-shader-system/` (W7, decision X8 A: synthesised loops, no recordings).

## How the loops are made

The `tools/machine-sounds/` route: periodic tones whose cycles fit the loop exactly, noise shaped in the frequency domain over the whole loop (so it is periodic and the loop has no seam), and short bursts that wrap round the loop's end. Level envelopes are smooth periodic noise too. Mono 24 kHz Ogg Vorbis, 4 s. No recordings, samples or external audio.

- Flare roar: broadband combustion noise, most of its energy from 40 to 400 Hz (a lift near 110 Hz), a slow irregular flutter in level (0.5 to 2 a second), and a faint jet hiss about 3.5 kHz on top.
- Flare pilot: the pilots' gas hiss, a steady 2 to 6 kHz band, at a third of the others' RMS.
- Burner roar: combustion roar from 80 to 600 Hz (a lift near 250 Hz), the combustion-air fan's blade pass, 2,900 rpm x 12 blades = 580 Hz, with its 1,160 Hz harmonic and a faint fan rush about 1.8 kHz, and the 2-pole 50 Hz fan motor's faint 100 Hz hum.
- Fire draught: the draught's low roar, 30 to 150 Hz, with about 25 crackles a second (3 to 15 ms bright bursts about 3 kHz and 900 Hz) and about 2 longer pops a second, heavy-tailed (Pareto) in strength, rounded by a soft limit (tanh at 3.2 times the RMS) so they do not clip.
- Pot boil: about 25 bubble bursts a second (decaying sines 150 to 800 Hz, rising a quarter as each bubble shrinks), about 2.5 low thumps a second (40 to 80 Hz) and a faint simmer, soft-limited at 3.0 times the RMS.
- Valve gas: decompression hiss, broadband 1 to 4 kHz (a lift near 2.5 kHz), a faint 150 to 600 Hz pipe rush, almost no flutter.
- Fan whoosh: 40 to 400 Hz rush swelling at the blade pass (300 rpm x 4 blades = 20 Hz), its 20 and 40 Hz tones, a faint 1.5 kHz tip hiss.
- Water fall: a steady 500 Hz to 4 kHz rain rush and about 600 tiny splash bursts a second in the same band.
- Gear whine: the mesh tone (30 teeth at 1,500 rpm = 750 Hz) and its 1,500 and 2,250 Hz harmonics, 25 Hz shaft-rate side bands, a faint 200 Hz to 3 kHz bearing rush.
- Pipe knock (one-shot, 0.8 s, peak 0.7): a 70 Hz thud and the pipe's ring (180 Hz, 470 Hz, 1.1 kHz), a 4 ms strike click, and two weaker echoes at 90 and 180 ms.

Each loop keeps the pump loop's RMS (0.0926, `tools/machine-sounds/`), the pilot a third of it. In game the effect model sets gain and pitch (plan section 4.5).

## Run

From the worktree root, with the authoring Python and the codec packages in `../pump-compressor-controls/python-deps/` (see that README):

```bash
"C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" -I tools/process-sounds/make_loops.py tmp/process-sounds
```

Then copy the `.ogg` files into `src/main/resources/assets/icheme/sounds/`. The script prints, per loop, its bytes, frames, rate, RMS, peak, the step across the loop's seam and the mean step between samples (the seam step should be of the order of the mean step or below). Ogg stream serials differ per run, so a re-run changes the bytes; copy only loops whose numbers changed.

## Entry points

`make_loops.py <output folder>`; `valve_candidates.py <output folder>` (the valve candidates, 48 kHz).

## History

- 2026-11-10 (W7): the five loops, 96,000 frames at 24 kHz each:
  - `flare_roar`: 27,020 bytes, RMS 0.0933, peak 0.58, seam step 0.0148 against a mean step of 0.0103 (75th percentile of the steps).
  - `flare_pilot`: 26,149 bytes, RMS 0.0340, peak 0.16, seam step 0.071 against 0.034 (90th percentile; a 2 to 6 kHz hiss, codec noise at the seam).
  - `burner_roar`: 26,192 bytes, RMS 0.0936, peak 0.40, seam step 0.0108 against 0.0164.
  - `fire_draught`: 28,317 bytes, RMS 0.0927, peak 0.34, seam step 0.0030 against 0.0069.
  - `pot_boil`: 28,050 bytes, RMS 0.0941, peak 0.40, seam step 0.0070 against 0.0220.
- 2026-11-10 (phase 2, fx-noise): four loops and the knock; the five loops above re-run unchanged:
  - `valve_gas`: 27,175 bytes, RMS 0.0990, peak 0.45, seam step 0.0097 against 0.0658.
  - `fan_whoosh`: 26,807 bytes, RMS 0.0929, peak 0.50, seam step 0.0046 against 0.0080.
  - `water_fall`: 27,151 bytes, RMS 0.0987, peak 0.64, seam step 0.0817 against 0.0642 (a dense 0.5 to 4 kHz splash train).
  - `gear_whine`: 26,517 bytes, RMS 0.0952, peak 0.38, seam step 0.0661 against 0.0372 (750 Hz tones, codec phase at the seam).
  - `pipe_knock`: 5,828 bytes, 19,200 frames (0.8 s), RMS 0.104, peak 0.71, first sample 0.009, last 0.0001.
- 2026-11-10: owner, "valve sound is not good": `valve_candidates.py` (gas A/B/C, liquid L1/L2, flashing F1/F2). Owner: gas stays; L2 for a cavitating valve and pump (copied over `pump_cavitation.ogg`, 48,873 bytes; the old file kept as `valve_liquid.ogg`); "F1 moderate" for a flashing valve (`valve_flashing.ogg`, 48,712 bytes). The loop is chosen by σ (owner: option A, `PassiveStepSolver.cavitation`).
