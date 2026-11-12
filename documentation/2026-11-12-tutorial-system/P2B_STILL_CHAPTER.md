# P2b: chapter 2, the still

Batch `2026-11-12-tutorial-system`, 2026-11-12. Commits: A 5e5d3e25a, B 8922ffd73, C 05c2e81da, D a90748849, E 7d1cbd1f1.

## What was built
- A: `StillRunRecorder` runs the still law offline on 500 L of medium crude, 60 % full, solid fire at full firing,
  design cooling water at 25 °C. `tools/tutorial-recordings/StillRun` writes `still_run.json` (97 points).
  `StillRunRecordingTest` checks the mass balance, the order of the lines, and that a rerun equals the file.
- B: the film tracks Label, Legend, Flame, Level, Readout (two at most), Pointer, and Dots by a Series; `Labels.place`,
  `Bodies` (liquids, flames), `FilmOverlay`. `Scene.at` stays pure.
- C: `still_inside` reworked: labels, legend, liquid, flame; stop 3 opens on the fire, then rises to a 3 px dot.
  `still_build` has 4 stops, its connection labels taken from the shape. `still_run` replays the recording in 5 stops.
- D: the card `pot_temperature` plots the pot's temperature against the share distilled. The area is coloured by the
  part coming over. The card marks two cuts, "Second drum" and "Fire out", and is done at the slider's end.
- E: the chapter `still` has the steps build, charge, water, watch, fire, first, cut, switch and stop. Terms: vapour,
  condenser, distillate, cut, boil dry. The criterion `still_state` fires in `FluidNetwork.deliver` (the scheduled send
  and the replay; rule `StillCondition`). `still_stopped` fires in `FluidNetwork.edit` when the firing is set to 0 and
  the cached view is firing. `diverter_switched` fires when `FluidDeviceBlock` turns a diverter by hand.

## Derived lines
| line | process s | pot K | play min (factor 4) |
|---|---|---|---|
| first drop | 220.4 | 312.9 | 0.92 |
| end of naphtha | 3905.4 | 505.8 | 16.27 |
| end of run | 4227.3 | 521.2 | 17.61 |
Kerosene's end is not reached: the run ends REFUSED at 521 K ("no positive latent heat"), 20 % distilled (52.5 kg), 1.8
kg vented; most points report COOLING_LIMITED. The recording is provisional (parent: build on it; set-up `ended_at`:
`law_refusal`). Re-record when the law's fix merges. Every number sits behind a gate, so a re-record changes only the asset.

## Decisions
1. first and cut accept DISTILLING or COOLING_LIMITED. fire and stop accept those two, HEATING and BOILING. Reason: a
   limited condenser still distils, and the recording reports COOLING_LIMITED for most of its run.
2. The cut is held at 506 K, the end of the naphtha rounded. The step text gives no temperature, because the unit is
   the player's choice. The card shows the temperature.
3. The films say "cooler"; the build step introduces "condenser" as a marked term. TutorialTextTest lets a professional
   word stand where it is marked, in the term's entry, or in a film caption shown after the term's step.
4. `switch` exists because a diverter turns by hand. The charge step names its source through
   `tutorial.icheme.guide.still.source`; `Guide.Chapter.text` fills the `%s`.
5. The stop step reads `world.view(id).still()` before the setting applies: a cached read, with no solver run.
6. A CLIENT step `water` (new media `read`: its key "Got it" reports it) stands between charge and watch. The code
   shows a still with no water condenses only part of its vapour (`Stills` lines 33-35, 364-383). A built-in condenser
   always registers its water side (`FluidWorldAuthority` ~758). The duty goes to that side's node (`owe`), and
   `condensed()` keeps the share at 1 only while the owed heat stays within HOLD_CYCLES. Then it falls to what the
   water took, and line 437 raises COOLING_LIMITED. Line 377 (no water side, share 0) is never reached with a condenser.

## Tests and open items
21 tutorial classes (93 tests) pass, with HeatDiagramTest, KitLayoutTest, DrawCallTest and the 7 classes naming
FluidNetwork or FluidDeviceBlock. Final lane: FluidPresentationGameTests, DcsConsoleGameTests, FluidServerBenchmark.
Past the cut, the card shows mostly naphtha, because the run ends before kerosene leads. In-game check: the film labels,
the still_run drums, the triggers with the still screen open, the diverter step, and an unwatered still going COOLING LIMITED.
