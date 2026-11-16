# A larger air cooler bay (build notes)

Date: 2026-11-15. Batch `2026-11-15-equipment-visual-design`, A-W1 to A-W3 of `AIR_COOLER_BAY_PLAN.md` (on the batch
branch). Owner: "make max size single AC model larger (as well as their duty) and correspond to realistic scale".
Built on `claude/relief-sprites-10dd59` on top of R-W4. Not merged.

## What was built

- **Sizes** (`AirCoolerDesign`, `ProcessUnit.MAX_BAY_LENGTH = 12`): bays 1.5 to 6 m wide, 0.5 m steps, to 12 m long.
  A width's lengths run from `minLength` to the new `maxLength`: every length between keeps fans over 0.4 of the face.
  74 bays are listed, 14 of them from before.
- **Ratings** (`UnitRatings.finWidth`, `bundles`, `ONE_BUNDLE = 3`): above 3 m a bay holds two bundles and loses 0.12 m
  more of fin width; `bundleFace` and `HeatSides.airCoolerPassage` use it. No rating constant changed.
- **Support** (`minSupport`, `defaultSupport`): max(3, ceil(0.6 D + 0.2 + 0.4 D + 0.35 min(D, 0.9))) and max(4, least).
- **Fan speed** (`fanSpeed`, `SPEED_FAN = 2.35`): fans above 2.35 m turn at its tip speed, on whole turns in
  `MOTION_PERIOD` (`ProcessUnit.shown`). The fan sound reads no shown speed (`UnitDesign.ratedRpm` is 0 for an air
  cooler), so no sound changes.
- **Model** (`build`): a bay above 3 m has two bundles, each with its own header boxes, fins and side frames, under the
  bay's one plenum and set of fans and motors. Each bundle's header is a column: two or more columns take a manifold
  (inlet on top, outlet under the outlet manifold), as parallel trains did; stubs and crossovers are sized by a bundle's
  width. A bay longer than 6 m (`POST_SPAN`) has a post line at its middle. Above 3 m the posts are 0.3 m square
  (`WIDE_POST`), and the beams at the bundle are 0.3 m deep. The motor beams keep today's 0.2 m.
- **Layout table**: three new rows (6 by 12, 3 by 12, 6 by 6). Today's three rows and every other row are unchanged.
  `EquipmentLayoutGoldenTest` now lists the air cooler's bays explicitly (the three old ones, then the three new ones)
  instead of min, mid and max, so the old rows keep their place.
- **Drawing scenes** (`EngineeringExport`, the air cooler line only): `AIR_COOLER` stays today's 2.5 by 5 m, now stated
  explicitly. New: `AIR_COOLER_3X4`, `AIR_COOLER_LONG` (3 by 12), `AIR_COOLER_MAX` (6 by 12),
  `AIR_COOLER_BANK_MAX` (6 by 12, two in parallel). `rendering.py` already colours the materials.
- **Corpus image**: `src/test/resources/compat/equipment/shapes-12-air-cooler-bays.snbt` holds a 6 by 12 m bay, a 3 by
  12 m bay and a 2 by 3 bank of 6 by 12 m bays.
- **Tests**: new `AirCoolerBayTest`. Updated tests: `ProcessUnitTest` (new out-of-range cases; the bay count counts the
  old range; supports follow `minSupport`), `EquipmentLayoutGoldenTest` and `CrudeUnitSampleTest` (the moved results
  below). Code guide: `equipment-designs.md`.

## The plan's table as measured

| Bay | Face, m² | Fans | Bare tube, m² | Duty at 20 K | Fan power | Least / default support | Fan speed, rev/s |
|---|---|---|---|---|---|---|---|
| 3 by 6 (largest before) | 12.2 | 2 of 1.90 m | 61 | 0.86 MW | 12.2 kW | 3 / 4 m | 1.5 |
| 3 by 12 | 28.9 | 3 of 2.35 m | 145 | 2.02 MW | 28.9 kW | 3 / 4 m | 1.5 |
| 6 by 6 | 24.9 | 1 of 3.80 m | 125 | 1.74 MW | 24.9 kW | 5 / 5 m | 0.925 |
| 6 by 12 (largest now) | 58.9 | 2 of 4.55 m | 294 | 4.12 MW | 58.9 kW | 6 / 6 m | 0.775 |

Lengths by width: 1.5 m, 3 to 6 m (as before); 2 m, 3 to 9; 2.5 m, 4 to 10; 3 m and 3.5 m, 4 to 12; 4 to 5 m, 5 to 12;
5.5 and 6 m, 6 to 12. The largest bank, 6 by 4 bays of 6 by 12 m, has a box of 37 by 8 by 49 blocks and 1,413 m² of face.
The bank limits are unchanged.

## The simulator's moved results

Both moved results have fewer bays and less face than before.

| Unit | Before | After |
|---|---|---|
| Crude sample `E-102` (residue cooler) | 6 × 3 bays of 2.5 by 6 m, 18 bays, 180.6 m², 903 m² bare, 0.045 bar | 1 × 3 bays of 6 by 12 m, 3 bays, 176.6 m², 883 m² bare, 0.218 bar |
| Crude sample column, air condenser | 2 banks of 5 × 4 bays of 2.5 by 5 m, 40 bays, 310 m², 1,550 m² bare | 1 bank of 2 × 4 bays of 5.5 by 9 m, 8 bays, 305 m², 1,527 m² bare |

The residue path to `RESIDUE` loses 0.550 bar instead of 0.374; 0.98 bar is available, so its rating stays clear. The
sizer takes the least face within each unit's own drop: one train of three 12 m bays has 36 m of tube in series. The
column test's "door of one bank" case became "a door of half the bank", since one bank is now the sized size. No other
example or sizing test moved (`SheetSizingTest`, `UnitSizingTest`, `CoolerSizingTest`, `SizingCoverageTest`,
`FlowsheetUnitsTest`, `ColumnBlueprintTest`, `ColumnComponentsTest`, `UtilityRegulationTest`). The simulator's size list
grows from 14 to 74 bays, about five times the list it searches.

## Polygons (drawing export, NORMAL)

| Scene | Polygons | Largest materials |
|---|---|---|
| `AIR_COOLER` 2.5 by 5 (today) | 2,597, the same polygons as before | AC_RING 1,032, AC_FAN 476, AC_MOTOR 416 |
| `AIR_COOLER_3X4` | 1,595 | AC_RING 516, AC_FAN 238, AC_MOTOR 208, BOLT 192 |
| `AIR_COOLER_LONG` 3 by 12 | 3,675 | AC_RING 1,548, AC_FAN 714, AC_MOTOR 624 |
| `AIR_COOLER_MAX` 6 by 12 | 3,102 | AC_RING 1,032, AC_FAN 476, BOLT 448, AC_MOTOR 416 |
| `AIR_COOLER_BANK_MAX` | 5,744 | AC_RING 2,064, AC_FAN 952, AC_MOTOR 832, BOLT 704 |

The new nut polygons are the stubs' and crossovers' `flangeBolts` only. The fans' Blender parts dominate every bay.

## What the plan had wrong

- **Today's largest fan is 2.35 m, not 1.9 m.** The 3 by 5 m bay has one 2.35 m fan, and the 3 by 4 m bay one of 2 m. The
  speed cut-over is 2.35 m, so every bay before keeps its speed. The support rule still gives 3 m for 2.35 m.
- **A narrow bay cannot reach 12 m.** With three fans at most, a 1.5 m bay longer than 6 m covers less than 0.4 of its
  face, and `check` refused it while the tests walked every length to `MAX_BAY_LENGTH`. `maxLength` now stops each width
  at its last covered length.
- **The layout table generates min, mid and max sizes**, so a larger maximum would have moved today's 2.5 and 3 m rows. They
  are now listed explicitly.
- **Speed must stay on whole turns**: a plain 1.9 / D speed breaks `MOTION_PERIOD`'s loop; `ProcessUnit.shown` rounds it.
- **No cross beam at the middle post line.** It would run inside the plenum, where the two fans' hoppers meet. The bundle
  rests on the side beams, which the middle posts carry.
- **Posts and the plenum.** A 0.3 m post at the bay's side passes about 0.3 m into the plenum's corner sheets at the
  middle line (the plenum starts 0.11 m in from the bay's edge). Moving the posts outside the bay would push the box out
  by one cell on odd widths. Left as built, visible only close up.
- **The constructor's starting bay moves** from 2.5 by 5 m to 4 by 9 m, the middle of the new size list
  (`ConstructorDefaults`). Saved and imported designs keep their sizes.

## Views

`tmp/relief-sprites/views/ac/` (local): iso, +X and -Z of `AIR_COOLER_MAX`, `AIR_COOLER_LONG`, `AIR_COOLER_BANK_MAX`
and today's `AIR_COOLER_3X4`. Sprite renders from below: `sprite-6x12-low.png`, `sprite-3x4-low.png`. The owner has
not seen them yet.

## Open

- Access steel (walkways, ladders) on the large bays: the owner's open item 1, not built.
- The constructor's starting bay (4 by 9 m): the owner may prefer today's 2.5 by 5 m.
- E-102's 0.22 bar on one long train: the sizer's least-face rule picks it. A drop-weighted choice is a sizing-rule
  question for the owner.
