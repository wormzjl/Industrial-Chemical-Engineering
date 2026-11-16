# P4: the plunger pump's own sound (item 3)

Package P4 of [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md) section 6. Sub-thread, 2026-11-16, Opus medium. Base `2fab7e561`.

## Summary

1. **Expected.** A triplex plunger pump at its rated 300 rpm (every `PlungerPumpDesign` model) turns its crank 5 times a
   second; three throws 120° apart give 15 strokes a second. Each throw seats its discharge valve at top dead centre and
   its suction valve at bottom dead centre: 30 valve seatings a second, the discharge ones harder (they close against the
   full pressure rise). A seating is a short metallic knock, the valve plate and cover ringing for a few ms. The delivery
   ripples three times a turn, so the piping thumps at 15 Hz and its harmonics. The crossheads clack softly at each
   reversal. Under it the reduction gearbox (a 1,500 rpm motor, 5 : 1, a 23-tooth pinion: mesh 575 Hz) and the crank's
   bearings rumble. The character is a steady, low "clack-clack-clack" over a rumble, not a hum or a whine.
2. **What the mod played.** `MoverSounds` gave `PLUNGER_PUMP` the reciprocating compressor's `compressor_running`: a gas
   rush with a faint 450 Hz tone pulsing six times a second. Its pitch is `UnitDesign.soundPitch(shape, share)`: the
   plunger states no `ratedRpm` (that reading covers pumps, motors, generators and engines only), so the pitch is the
   speed share itself, clamped to 0.5 to 2, and 1 at the rated point. The S8 review's "300 rpm clamps to 0.5" does not
   match the code: no plunger path divides 300 rpm by a loop speed. It changes nothing here.
3. **What was done.** `tools/machine-sounds/plunger_candidates.py` synthesises three 4 s loops; candidate A is installed as
   `plunger_pump_running.ogg`, registered (`ModBlocks.PLUNGER_PUMP_RUNNING`, `sounds.json`) and picked by
   `MoverSounds.casingLoop(kind, compressor)`, a new static that names each casing's loop (plunger first, then the old
   compressor/pump rule unchanged). The loop is authored at 300 rpm, the rated speed, so the existing pitch law (share,
   0.5 to 2) plays it true at the rated point and scales the knock rate with speed: **the pitch law and its clamp are
   kept, unchanged** (no `science/**` edit). The subtitle reuses `subtitles.icheme.pump_running` ("Pump running"), as the
   three pipe-burst sounds share one key: **no `en_us.json` change**.

## The recipe chosen (A, valve_knock)

48 kHz mono Vorbis, 4 s = 20 crank turns, 60 strokes, 120 seatings; every tone a multiple of 0.25 Hz, noise shaped over
the whole loop, knocks wrapped round the end; the loop is then rolled so its first sample sits at the quietest
zero-crossing between knocks (a roll keeps it seamless).

- Valve knocks: damped modes (skill, "Damped mode"). Discharge 1,150 / 1,870 / 2,950 Hz at 6 / 4 / 3 ms; suction 760 /
  1,320 Hz at 7 / 5 ms, 0.55 of the discharge. Each throw's valves sit a few % apart in frequency (×0.93 to 1.06) and
  strength, the modes jitter 0.6 % per seating, time jitter 1.5 % of a stroke, level jitter 3 %: no plain buzz.
- Crosshead clacks: 240 / 410 Hz at 12 / 8 ms, six a turn, 0.35.
- Delivery ripple: 15, 30, 45, 60 Hz (0.5, 0.6, 0.4, 0.2) and a once-per-turn 5 Hz throb, 0.55 of the knocks.
- Gearbox: 575 Hz mesh with ±5 Hz and ±25 Hz sidebands and its harmonic, modulated 30 % at 5 Hz, 0.12 of the knocks.
- Floor: rumble bands at 70 and 160 Hz pulsing at 15 Hz (0.22) and a 1.2 kHz valve wash pulsing at 30 Hz (0.12).
- Level: RMS set to the pump loop's 0.0926 after a soft limit (tanh at 3 × RMS).

## Levels and length against the skill's rules

| Candidate | Bytes | Length | RMS | Peak | Seam step / mean step | Floor under pulses | Largest bin (share) | Largest bin above 100 Hz |
|---|---|---|---|---|---|---|---|---|
| **A valve_knock** (chosen) | 44,992 | 4.0 s, 48 kHz | 0.0918 | 0.397 | 0.0011 / 0.0043 | 13.4 dB | 30 Hz (21.5 %) | 120 Hz (1.0 %) |
| B crank_heavy | 34,683 | 4.0 s | 0.0912 | 0.312 | 0.00004 / 0.0023 | 12.7 dB | 30 Hz (31.7 %) | 150 Hz (3.1 %) |
| C clean | 51,511 | 4.0 s | 0.0921 | 0.389 | 0.0002 / 0.0025 | 19.8 dB | 30 Hz (23.0 %) | 210 Hz (2.7 %) |

Rules (`.claude/skills/models-and-sfx/`): length 3 to 4 s (all 4.0 s); new loops 48 kHz (yes); RMS the pump loop's 0.0926
(within 1 % after Vorbis); seam step of the order of the mean step or below (all below); broadband floor 12 to 15 dB under
the pulses (A 13.4, B 12.7; C below on purpose); pulses as damped modal rings, not noise bursts (all). "No pure tone that
tires the ear": no audible bin holds more than 1 % (A) of the power; the largest bins are the 15 to 60 Hz piping ripple,
felt more than heard. The asset is 45 kB (well under the 1 MB limit); the candidates sit in the main checkout's
`research/2026-11-16-supercritical-followups/sounds/`, the recipes in `tools/machine-sounds/plunger_candidates.py`.

## Candidates not chosen

- **B crank_heavy:** the crank and crossheads forward (170 / 300 / 520 Hz at 25 / 15 / 8 ms), valves quieter, the rumble
  louder. Deeper and darker; the valve knocks, the triplex's signature cue, recede, and the floor sits at the edge of the
  rule (12.7 dB). Kept as the step if the owner finds A too bright.
- **C clean:** A's knocks with half the jitter and no broadband except a faint rumble (the owner's second "cleaner" step,
  19.8 dB). Kept ready for a "too dirty" or "cleaner" verdict; A is the first step the skill's taste asks for.

A was chosen by the skill's criteria: clean modal pulses, the floor inside 12 to 15 dB, the low end kept, secondary sources
(mesh, wash) lower, and the valves' knock as the cue a listener names.

## Files

`src/main/resources/assets/icheme/sounds/plunger_pump_running.ogg` (new), `sounds.json`, `registry/ModBlocks.java`,
`client/MoverSounds.java` (`casingLoop`), `client/MoverSoundsTest.java` (new), `tools/machine-sounds/plunger_candidates.py`
(new) and its README, the skill's tools line, the code guide (`client-rendering.md`, `shaft-trains.md`).

## Runs

| Run | Classes | Tests | Result |
|---|---|---|---|
| `p4-1`, `test --offline --max-workers 4 --tests` | `MoverSoundsTest` (new), `PollutionDataTest`, `ColumnTemplatesTest`, `CrudeOilFluidTest`, `EquipmentInfoCoverageTest` | 27 | 0 failures |

Selection: `affected.mjs select --since 2fab7e561` named the four classes that read `sounds.json`, plus eight classes
with no load set that do not exist on this branch (stale classes of the worktree's earlier batch in `build/`); those were
left out. `MoverSoundsTest` is new. `ModBlocks` changed, so the GameTest step is due in the final lane (not run here, per
the plan). `LanguageFilesTest` not run (no lang change).

## Open

- The owner hears the loop in the in-game check (item 17); B and C are the fallbacks.
