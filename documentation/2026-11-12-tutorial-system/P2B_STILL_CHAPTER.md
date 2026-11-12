# P2b: chapter 2, the still

Batch `2026-11-12-tutorial-system`, 2026-11-12. Commits: A 5e5d3e25a, B 8922ffd73, C 05c2e81da, D a90748849, E 7d1cbd1f1;
water step 362008a6b; re-record on the fixed law 97e1a949f, chapter on the new run dcfbe7eb1.

## What was built
- A: `StillRunRecorder` runs the still law offline on 500 L of medium crude, 60 % full, solid fire at full firing,
  design cooling water at 25 °C. `tools/tutorial-recordings/StillRun` writes `still_run.json` (144 points).
  `StillRunRecordingTest` checks the mass balance, the order of the lines, the overheat ending and that a rerun equals the file.
- B: film tracks Label, Legend, Flame, Level, Readout (two at most), Pointer, Dots by a Series; `Labels.place`,
  `Bodies`, `FilmOverlay`. `Scene.at` stays pure.
- C: `still_inside` reworked; `still_build` (4 stops, connection labels from the shape); `still_run` (4 stops: first
  drop, end of naphtha, end of kerosene, and the run's end, where the still cuts its own fire and the heavy part stays).
- D: card `pot_temperature`: pot temperature against the share distilled, the area coloured by the part coming over,
  cut lines "Second drum" (end of naphtha) and "Fire out" (end of kerosene); past the fire out the curve runs on,
  dimmed, to the run's end ("Fire left on").
- E: chapter `still`: build, charge, water, watch, fire, first, cut, switch, stop; terms vapour, condenser, distillate,
  cut. Criteria `still_state` (`FluidNetwork.deliver`; rule `StillCondition`), `still_stopped` (`FluidNetwork.edit`,
  firing set to 0 on a firing still), `diverter_switched` (`FluidDeviceBlock`, a diverter turned by hand).

## The recording (on the law of batch 2026-11-12-still-heat-per-distillate)
| line | process s | pot K | °C | play min (factor 4) |
|---|---|---|---|---|
| first drop | 220.4 | 312.92 | 39.8 | 0.92 |
| end of naphtha | 3905.4 | 505.79 | 232.6 | 16.27 |
| end of kerosene | 5019.6 | 560.21 | 287.1 | 20.91 |
| end (law's overheat cut) | 6220.7 | 623.15 | 350.0 | 25.92 |
143 law steps; of 259.47 kg charged, 108.43 kg distilled (41.8 %), 1.77 kg vented, 149.27 kg left in the pot. Statuses
of the 144 points: DISTILLING 124 (86 %), COOLING_LIMITED 17 (12 %), OVERHEAT 2 (1 %), HEATING 1. The recording is
final (`ended_at`: `overheat`). Up to the end of naphtha it equals the first recording: the old law went wrong only
past 248 °C.

## Decisions
1. first and cut accept DISTILLING or COOLING_LIMITED; fire and stop those two, HEATING and BOILING.
2. Cut at 506 K (end of naphtha, rounded); fire out at the end of kerosene, its 21 minutes of play in the stop step.
   Both read from the recording by gates. Step texts name no temperature (the unit is the player's).
3. Films say "cooler"; the build step marks "condenser". TutorialTextTest lets a professional word stand where it is
   marked, in its term's entry, or in a film caption shown after the term's step.
4. `switch`: a diverter turns by hand. `water` (media `read`, key "Got it"): `Stills` lines 364-383 and 437 show a
   still with no water condenses only part of its vapour. The charge step names its source by one key.
5. The term "boil dry" is gone: on the new run the pot overheats with 38 % of its volume left; the stop step says
   "before the pot overheats".
6. The stop step reads `world.view(id).still()` before the setting applies: a cached read, no solver run.

## Tests and open items
21 tutorial classes (93 tests) pass, with HeatDiagramTest, KitLayoutTest, DrawCallTest and the 7 classes naming
FluidNetwork or FluidDeviceBlock. Final lane: FluidPresentationGameTests, DcsConsoleGameTests, FluidServerBenchmark.
COOLING_LIMITED falls on the early, light-end points, the condensate root defect the law batch names; a further
recording after the condenser fix if the owner approves it. In-game check: film labels, drums, the triggers with the
still screen open, the diverter step, an unwatered still going COOLING LIMITED.
