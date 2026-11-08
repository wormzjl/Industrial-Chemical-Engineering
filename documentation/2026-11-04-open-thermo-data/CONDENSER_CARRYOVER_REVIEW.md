# Condenser carry-over of tray 1's free water (E31)

Status: built 2026-11-05, tested on lane 2 (`--max-workers=4`), committed 2026-11-08 in `81e8aafae`. Open: the warm route (section 6, item 4); `ColumnSolveTest` fails until it is settled. Owner choices: carry-over over a top-tray decant draw; above the entrainment limit, "Warn (a)".

## 1. Expected behaviour

- A cold top tray below the water dew point of its overhead condenses water. On a real crude tower that water is entrained with the vapour and decants in the reflux drum's boot. Tray 1 sits on its saturation line: the carried water is the excess of the steam that reaches it.
- The water balance is unchanged at the drum, because all the steam still arrives there. The latent heat moves from the condenser to tray 1: tray 1 books `C·(h_v - h_l)`, and the condenser duty falls by the same amount.
- The vapour can lift only so much liquid. Above that, the tray floods by entrainment.

## 2. What the code did

- Free water on any tray fell to the tray below: `W_n = S(≤n) + F_(n-1) - F_R`. The top tray of a wet block has no lever on its own SAT row: `W_n` does not depend on `F_n` (slowGates `V3StageContinuationTest`: d ln(ratio)/dF = 1.85e-08 per kmol/h; `V3InteriorWetBlockTest` for an interior block).
- So the literature preset and the heavy and dilbit grades (ratio about 1.13 to 1.16 on tray 1) published DRY_SUPERSATURATED with a dew-point warning.

## 3. What was built

- `V3ColumnProblem`: on a decanting drum (regime FREE_WATER), F_1 is the carried water. `W_1 = S - F_1` and nothing falls to tray 2 (`freeWaterFalls`, `trayWaterVaporFlow`). A drum without free water (ALL_VAPOR, NONE) keeps the falling water and its warning. `W_1 ≤ 0` is a rejected trial step, not a hold.
- Residuals and the Jacobian: tray 1's energy row books the carried water as liquid leaving it; the local derivatives gain three F_1 terms in node 1's own block, so the band does not widen. The DOF ledger gives node 1's SAT and equilibrium rows an F_1 reference.
- `V3ColumnDutyLedger` and `V3AcceptanceAuditor`: the arriving energy is vapour + `F_1·h_l(T_1)`. The water, global and condenser energy balances close on the carried water. One CARRY_OVER check is added.
- `V3FreeWaterContinuation` admits tray 1 (cap `(1 - margin)·W_1`, first trial at the excess water). Main thread: a secant inside one end's margin steps to that margin instead of bisecting, unless the last margin step missed. The heavy grade had spent its 12-solve budget on seven bisections; it closes in 10.
- Revisions: `v3-wet-mesh-r32-steam` to r34, r33 to r35 (heat), `WET_ASSUMPTIONS_REVISION` r2 to r3. `withDewPointAdvisory` becomes `withCheckWarnings`. No term was removed: the falling water still serves trays 2 and below.
- Fixture `tjl20-wet-boundary.json` re-made: condenser 333.85278 to 333.55005 K at F_1 = 1 mol/s. `WetBoundaryTeacherProbe` now releases at the prescribed drop the test reads with (at 8 m hydraulics the root was 14.05 mol/s).

## 4. The entrainment limit (decided: warn, option (a))

- Form: `limit = f·G/M_w`, f the Fair flood fraction, G tray 1's vapour mass, water included. No diameter, no limit. The rule is `V3TrayHydraulics.entrainmentLimitMolPerSecond` and `carryOverWarning`; the auditor calls it once. Above the limit the column publishes with the warning "tray N carries X kmol/h ... above the Y kmol/h its vapor can lift at Z % of flood ...". Option (b), failing as flooded, is rejected.
- Measured: heavy crude at 8 m carries 148.5 kmol/h, 5 % of its 3,047.2 kmol/h limit at 25 % flood; dilbit 98.4 kmol/h, 1 % of 13,836.8 at 51 % flood. The hand estimate (f ≈ 0.24, about 3,060 kmol/h) holds. The warning fires only in a far oversized column.
- Alternative form: Fair's fractional entrainment ψ(FP, f), `e = ψ/(1-ψ)·L`: tighter, and it would warn on today's acceptance cases.

## 5. Measured effects

- Literature preset (prescribed drop): WET_EQUILIBRIUM, no warning, 88.5 kmol/h carried, condenser -43.62 MW; distillate 697.72 to 708.47 kmol/h. The condensing water's heat lifts tray 1 to its dew point and sends more naphtha overhead.
- Sample sheet (medium grade): NAPHTHA line drop 0.175 to 0.193 bar/100 m, naphtha sphere 14 to 15 m, condenser 522 to 508 m², air condenser 1327 to 1265 m² (still two banks), PA 1 need 202 to 198 m², reflux pump 74 to 78 % of rated; OFFGAS is decided by speed again. 22 smaller readings move; no other size changes.
- `column-draw-volume-targets.json`: heavy crude and dilbit re-captured (up to 8.5e-3 relative); light and medium unchanged.
- Warm route: `ColumnSolve.run` drops seeds with a wet tray and `retargetWarmSeed` refuses them, so a carry-over column re-solves cold: about 3.6 s (heavy), 11 s (dilbit), against 0.17 to 0.57 s warm (C1). `ColumnSolveTest.aStartSolvesTheDesign...` fails: route `cold/stage-continuation/...`.

## 6. Open owner choices

1. The limit's form: `f·G` (built) or the Fair ψ chart.
2. A drum without free water keeps tray 1's falling water and its warning (built).
3. Generalisation: the top tray of any wet block carries its water up to the tray above.
4. The warm route: (A) extend it to seeds whose only wet tray is a carry-over tray 1 (recommended), or (B) keep cold re-solves and change `ColumnSolveTest`.

## 7. Tests (main thread, lane 2)

- New classes `V3InteriorWetBlockTest`, `V3CondenserCarryOverTest`, `V3CarryOverEntrainmentTest`, `V3GradePresetCarryOverTest` (presets from `d72caf1e0`) pass after four construction fixes.
- Broad run: 78 columnGates, 8 holdGates and 46 test classes that load the changed code. Failed: the literature distillate pin and the RegroupedCrude draw targets (both re-pinned, pass), 15 sample-sizing methods, `ColumnSolveTest` (section 5).
- Soft pins (`O4SoftPins.mjs`): 243 pins, 28 moved. Re-pinned; the 15 methods pass (holdGates, 1 min 30 s). The final lane runs once per batch. Code guide at merge: column-v3 chapter.
