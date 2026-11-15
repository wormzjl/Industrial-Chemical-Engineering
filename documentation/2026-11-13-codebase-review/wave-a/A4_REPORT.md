# A4 Sheet against world: report

Branch `claude/review-wave-a4-flowsheet-8e60b8`. Findings P3-01 and P3-04 of `FLOWSHEET_CONTROL_REVIEW.md`. Status: done; the named tests pass.

## P3-01 Ejector sized on the world's capacity

1. Expected: a sheet-sized set takes its load in the world. The world's set takes `Q = f(P_m) A v(P_s)` (`SteamEjector.capacity`), and `f(P_m) = (P_m / 1 MPa) / motiveFactor(P_m)`.
2. What the code did: `EjectorSizing.Size.passes` rated the bore as `A v(P_s)`, which is `f = 1` at every motive. The review's numbers check out against `SteamEjector:37-39` and `EjectorLaw.motiveFactor`: pcf(500 kPa) = 1.2351, pcf(1000) = 1.0101, pcf(1500) = 0.9351. So f = 0.409 at 5 bar, 1.000 at 10 bar and 1.620 at 15 bar. On 5 bar steam the sheet gave the set 2.44 times what the world gives it.
- Commit `d6dd084e2`.
3. Fix: `Size.capacity(load)` = `new SteamEjector(bore).capacity(Ps, motivePressure, 1)` x 3600, used by Auto's spare-first order. The rating's `VACUUM_SPEED` reading becomes `v(P_s) x flow / capacity`: the load's speed through the bore at 10 bar, and higher on weaker steam. `passes` is deleted.
   `EjectorSizing.Load` gets `motivePressure` (Pa), and `Load.of` takes it.
   - Callers, outside the lane: `SheetSizing` passes the tile's `motive` param (default `EjectorUnit.MOTIVE_PRESSURE`). `BlockSizing` passes `door.ejector().motive()`.
   - `SteamEjectorTest:60` builds a `Load` directly: it passes `RATED_MOTIVE`.
- Tests: `SheetSizingTest.anEjectorIsSizedOnTheWorldsCapacityAtItsMotive` (new). At 10 and 5 bar, Auto's bore is the smallest whose world set holds the spared load. At 10 bar the capacity equals the old area x speed. At 5 bar the bore is wider. Also `EjectorTest` and `SteamEjectorTest` (unchanged expectations, 10 bar).
- Values moved: at 10 bar none (f = 1 exactly; the speed reading equals the old one to rounding). Stored references: the only corpus sheet with an ejector (`compat/flowsheet/format-2-ejector.json`) is at 1 MPa. Every test sheet and column door uses the 10 bar default. So no sizing reference moves. A player sheet at 5 bar gets a bore about 1.56 times wider (√(1/0.409), before the grid rounds it); at 15 bar, about 0.79 times (√(1/1.62)).

## P3-04 One still fill threshold

1. Expected: one limit for a pot that boils over into its neck.
2. What the code did: the sizing's yellow read `BatchStill.FILL` = 0.70, and the run's `still.full` warning read `BatchStillUnit.PUKE` = 0.75.
3. Fix: `PUKE` is deleted, and the run warns past `BatchStill.FILL`.
- Commit `070a03f1d`. Files: `flowsheet/unit/BatchStillUnit.java`, `BatchStillUnitTest`.
- Tests: `BatchStillUnitTest.aChargeOverTheLawsFillWarns` (new). The usual charge (fill 0.4 to 0.7) does not warn; a 72 % charge does. Also `StillSizingTest` (unchanged; it already reads `BatchStill.FILL`).
- Values moved: the warning appears from 70 % fill instead of 75 %. No reading or size moves.

## Decisions

- The parent took option A and widened the lane by `SheetSizing` (the `Load.of` call), `BlockSizing` (the `Load.of` call) and `SteamEjectorTest` (the extra argument). No form defaults to 10 bar: a caller without the motive pressure does not compile.

## Tests run (after "lane free", each once, `--max-workers 4`)

- `compileJava compileTestJava`: passed.
- `test --tests` with five classes, all passed, 0 skipped: `SheetSizingTest` 23, `EjectorTest` 9, `SteamEjectorTest` 3, `BatchStillUnitTest` 10, `StillSizingTest` 4.
- References moved: none. Old and new are equal at 10 bar, and every stored sheet and door is at 10 bar.
- For the final lane: `BlockSizing` changed, so the column-ejector sheets (`ColumnEjectorTest`, a gate group in `build.gradle:600`) can load it. That test was not in the brief and did not run here; it runs at 10 bar, so nothing should move.
