# P2b: chapter 2, the still

Batch `2026-11-12-tutorial-system`, 2026-11-12. Commits: A 5e5d3e25a, B 8922ffd73, C 05c2e81da, D a90748849, E 7d1cbd1f1;
water step 362008a6b; fixed law 97e1a949f, dcfbe7eb1; on the merged base (still law per mole of distillate, partial
condenser, PR78 kernel): compile 29d05694c, recording c074b11a9, chapter 49175330f.

## What was built
- `StillRunRecorder` runs the still law offline: 500 L solid-fired pot, built-in condenser, no column, 60 % full of medium
  crude flashed at 25 °C, firing 1, design water at 25 °C (outlet floor 30 °C). `tools/tutorial-recordings/StillRun`
  writes `still_run.json`; `StillRunRecordingTest` holds it to the law (a rerun equals the file within 1e-9).
- Films `still_inside`, `still_build`, `still_run` (4 stops), card `pot_temperature`, chapter `still` (9 steps).

## The recording (merged base)
| line | process s | pot K | °C | play min (factor 4) |
|---|---|---|---|---|
| first drop (first DISTILLING point) | 293.9 | 317.74 | 44.6 | 1.22 |
| trickle (0.1 % of the charge's mass at the spout) | 1041.4 | 363.31 | 90.2 | 4.34 |
| end of naphtha | 3907.3 | 505.85 | 232.7 | 16.28 |
| end of kerosene | 5021.4 | 560.27 | 287.1 | 20.92 |
| end (law's overheat cut) | 6324.4 | 623.15 | 350.0 | 26.35 |

145 law steps, `ended_at` overheat, no refusal. Of 259.51 kg charged: 106.32 kg distilled (40.97 %), 3.88 kg vented
(1.49 %), 149.32 kg left (57.54 %). Statuses of the 146 points: HEATING 1, BOILING 2 (gas to the vent only), DISTILLING
139 (95 %), OVERHEAT 4; no COOLING_LIMITED. The first drop is a trace (0.03 % of the mass leaving that step is liquid;
14 % at 81 °C, 48 % at 103 °C, 91 % at 128 °C). The old run: first drop 0.92 min at 39.8 °C (light gas the old condenser
sent to the spout), 17 points COOLING_LIMITED, 143 steps, 41.8 % distilled.

## What changed in the chapter
1. `first` and `cut` count liquid at the spout: criterion field `liquid` (the view's distillate flow above zero), no
   status list. A head that only vents gas reads BOILING in the world (`Stills.status`: DISTILLING only where liquid
   reached the spout's account) and gives no distillate flow, so neither fires on it.
2. `first` step: "after about 4 minutes of play ... in a steady trickle": the trickle line (0.26 kg in the drum,
   5.9 g/s of play), not the trace at 1.2 min. Gate: the text's minutes = the trickle line's, rounded.
3. Cut at 506 K (end of naphtha, unchanged); stop step at 21 minutes of play (end of kerosene, unchanged).
4. Film: stops on the new lines (45, 233, 287, 350 °C); the drums fill with the spout's liquid (the vented gas no
   longer counts); the vent is not shown (it needs a new word and a stop). The part coming over (colours, dots) stays
   the band with the most mass in what leaves the pot, spout and vent together.
5. Card: the cursor line breaks round "Second drum", "Fire out" and "Fire left on" (it crossed two of them).
6. Charge step: "Pipe crude into the pot from a Fluid converter (another mod's crude oil) or Fluid Generator until it is
   half full." The bundled rules turn `#c:crude_oil` (TFMG, PneumaticCraft) and `ad_astra:oil` into medium crude at
   ambient. A survival player without one of those mods has no crude source.

## Tests
29 classes, 222 tests, all pass (`--tests`, `--max-workers 4`): the 22 under `science/tutorial` and
`client/gui/tutorial` (EquipmentInfoCoverageTest included) and the 7 naming FluidNetwork or FluidDeviceBlock. Not run:
the still law's own classes (the final lane's on this base), LanguageFilesTest, UnitTextTest.

## Open
In-game check: film labels, drums, the triggers with the screen open (BOILING, then DISTILLING at a trace), the diverter,
an unwatered still. `first` fires at the trace (about 1 min), its text names the trickle (4): option, a least flow.
