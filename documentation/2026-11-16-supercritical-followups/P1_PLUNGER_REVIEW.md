# P1: the plunger pump's clearance and work

Batch 2026-11-16-supercritical-followups, package P1 (items 1 and 4 of [FOLLOWUPS_PLAN.md](FOLLOWUPS_PLAN.md)), sub-thread,
2026-11-16. Branch `claude/gallant-leavitt-f29f1e`, from the plan commit `3a48e08b4`. Main-thread decisions on the stop
(2026-11-16): 1 A, 2 A narrowed (section 4), 3 both readers.

## Summary

**Expected.** A plunger displaces a fixed suction volume each stroke. The fluid left in its clearance (c = 0.10 of the
displacement) re-expands on the suction stroke before the suction valve opens, so it delivers
`η_v = 1 − c (ρ_d/ρ_s − 1)` of its displacement: about 0.999 on water, 0.97 to 0.98 on dense carbon dioxide, 0.83 to 0.86
on a gas at a ratio of 3 to 4. The work it puts into a compressible fluid is the isentropic `∫ v dP`, which is less than
`Q ΔP`: by 0.1 % on water, 9 to 15 % on dense carbon dioxide, about 40 % on a gas.

**What the code did.** `PlungerPump` delegated to `PositiveDisplacement` (slip and relief only). η_v was 0.998 on every
fluid, and the work was `Q ΔP`. The law's `k` argument was 1.4 for every liquid mover, and the law did not use it. The
simulator's run gave a pump tile sized as a plunger the efficiency and power of a centrifugal pump.

**What P1 does.**
- **The exponent.** `NetworkStreams.isentropicExponent` (forwarded by `FluidThermodynamics`) gives `n = K_s/P` of the phase
  a plunger draws.
  - It uses `(∂v/∂P)_s = (∂v/∂P)_T + T (∂v/∂T)_P²/c_p`. `(∂v/∂T)_P` and `c_p` are central differences of the slot's own
    phase at `T (1 ± 10⁻⁴)`, on the same root and amounts, with water's share included. `Phase` carries neither value.
  - Fallbacks, in this order: the isothermal `1/(κ_T P)`, then 1.4. The result is floored at 1.05.
- **The solve.** `PassiveStepSolver.suctionKappa` gives a plunger that exponent, and `kappa()` no longer returns 1.4 early
  for it. The transport's fallback (`ConservativeTransport`, line 597) and the device view's limit (`ViewBuilder`, line 178)
  now read `suctionKappa` too.
- **The law.**
  - `flow(ΔP) = C (share(ΔP/Δp_r) − c (r − 1))`, with `r = ((p + ΔP)/p)^(1/n)` in `expm1`/`log1p` form.
  - `rise` is its inverse, a safeguarded Newton inside a bracket, at most 60 steps. The bracket's upper end is the old law's
    rise.
  - `riseSlope = 1 / flow'`. The row stays the flow form.
  - `fluidPower = Q w_s`, with `w_s = p/a expm1(a ln((p+ΔP)/p))` and `a = 1 − 1/n`, so `w_s` is ΔP itself as n grows.
  - The shaft takes `C (1 − c (r − 1)) w_s / 0.88`. `maximumShaftPower` keeps the liquid bound.
  - No field of the record changed: the facts, the checkpoint and `equals` are untouched.
- **The simulator.**
  - `Passages.pumpType()` carries the pump's type from `SizedPassages`: the player's type, else the kept size's.
  - `MoverUnit` runs a plunger at `Q w_s` on its inlet's exponent, with efficiency 0.88.
  - `PumpSizing.Load` gains `pressure` and `exponent`. NaN gives the old `Q ΔP` and no clearance.
  - `plungerDuty` reads `Q w_s`, and `PUMP_RATED` rates the flow against `capacity · η_v`.
- **Tests.** `PlungerPumpTest` (14), `PlungerPumpMoverTest` (4), `PlungerSizingTest` (+1), `FlowsheetUnitsTest` (+1), and
  the dense GameTest's expectation (`DenseFluidGameTests`, not run).

**Errors against the reference rows.** Every η_v is within 0.35 %. The CO2 row at 310 K, 8.5 → 25 MPa reads 0.967 against
0.966: within 0.1 %, where the plan expected about 2.5 % low. Every work ratio is within 1.5 % (section 1).

**Found on the way.** The model's exponent for CO2 at 310 K and 8.5 MPa is 3.79. The main thread's bound was 2 to 3, which
is CoolProp's local value, about 2.4. PR78 is stiffer near the critical point, and with that one exponent at the suction the
isentrope meets CoolProp's endpoint ratio (n = 3.72 fits 1.336). The test asserts 3 to 4.5 (section 2).

**Left for the final lane.** The dense GameTest's plunger case (its expectation is updated, about 5 % below the old one), the
campaign, `holdGates` (ALL), `fluidSolverRegression` and chain-100 (selected because `FluidThermodynamics` changed),
`UnitTextTest`, `LanguageFilesTest` and `NativeColumnSolverTest`.

## 1. The reference rows (FOLLOWUPS_PLAN.md section 3; PD_PUMP_RESEARCH_REVIEW.md section 2, CoolProp, c 0.1)

The exponent is the model's own on its flash of the suction state (`theClearanceLossAndTheWorkMeetTheReferenceRows`).

| Fluid, suction → discharge | n (model) | η_v law | η_v ref | error | w_s/ΔP law | ref | error | bound (η_v) |
|---|---|---|---|---|---|---|---|---|
| water 300 K, 1 → 21 MPa | 2,256.8 | 0.9999 | 0.999 | +0.0009 | 0.9990 | 0.996 | +0.0030 | ±0.005 |
| CO2 310 K, 10 → 30 MPa | 6.530 | 0.9817 | 0.983 | −0.0013 | 0.9066 | 0.912 | −0.0054 | ±0.005 |
| CO2 310 K, 8.5 → 25 MPa | 3.788 | 0.9670 | 0.966 | +0.0010 | 0.8485 | 0.834 | +0.0145 | −0.025 / +0.005 |
| nitrogen 300 K, 15 → 30 MPa | 1.752 | 0.9515 | 0.955 | −0.0035 | 0.8072 | 0.815 | −0.0078 | ±0.005 |
| CO2 gas 300 K, 1 → 3 MPa | 1.283 | 0.8645 | 0.862 | +0.0025 | 0.6215 | 0.619 | +0.0025 | ±0.005 |
| nitrogen gas 300 K, 0.1 → 0.4 MPa | 1.401 | 0.8310 | 0.831 | +0.0000 | 0.5672 | 0.567 | +0.0002 | ±0.005 |

**The plan's point 6, corrected from these rows.** The plan expected the CO2 row at 310 K, 8.5 → 25 MPa to read about
0.944 (2.5 % low) and every other row within 0.5 %. Measured, the CO2 row reads 0.967 (+0.1 %) and the largest error of any
row is −0.35 % (nitrogen at 15 MPa). The plan's 0.944 used CoolProp's local exponent at the suction, about 2.4. PR78's is
3.79. With that one exponent held from the suction, the isentrope meets CoolProp's endpoint ratio: n = 3.72 fits 1.336.

Water's η_v is 7.7e-4 high. The law holds n constant, and n = K_s/p falls as the pressure rises, while a liquid's K_s
stays about constant. So the law gives `ln(21)/2200` for the density ratio where the real one is `20 MPa/K_s`. It stays
inside 0.5 %.

## 2. The exponent

`theExponentIsGammaForAGasAndLargeForWater` prints these values:

| State | slot | ρ, kg/m³ | n | isothermal `1/(κ_T P)` | n / isothermal |
|---|---|---|---|---|---|
| water 300 K, 1 MPa | aqueous | 996.97 | 2,256.8 | 2,229.4 | 1.012 (water's γ) |
| CO2 310 K, 8.5 MPa | liquid (δ 1.113) | 457.8 | 3.788 | 0.236 | 16.1 |
| nitrogen 300 K, 0.1 MPa | vapour | 1.12 | 1.4011 | 0.9997 | 1.4014 |

- Nitrogen at 1 kPa gives its ideal-gas ratio within 1e-3. At 1 bar it reads 1.5e-3 above `heatCapacityRatio` (1.39952),
  which is its real-gas deviation; the test allows 3e-3 there.
- Near the critical point PR78's `κ_T` is very large (an isothermal exponent of 0.24) and `c_p/c_v` is 16.
  - The guards' fallback, the isothermal exponent, would read below the 1.05 floor there. It is reached only if both
    differences fail, which no tested state does.
  - The main thread said the fallback errs on the high-loss side. Here that is a gas-like loss on a dense fluid.

## 3. What moved (asserted and printed values, old → new)

All of these are the new law, not drift. The bound for each is its test's stated tolerance. No reference of another
subsystem moved: every non-plunger path is the same code (the centrifugal and compressor branches of `MoverUnit`, and
`suctionKappa` for every other mover give the same numbers as before).

**`PlungerPumpMoverTest`, the S8 probe (PP-30, CO2 at 310 K, 10 MPa rise; `PLUNGER_RUN` / `PLUNGER_GAS` lines).**

| δ | n (end state) | share of displacement, S8 → P1 | η_v of the law | flow m³/h | work over `Q ΔP` |
|---|---|---|---|---|---|
| 0.30 | (gas) | 0.99838 → 0.876854 | | 29.951 → 26.306 | |
| 0.45 | (gas) | 0.99838 → 0.912571 | | 29.951 → 27.377 | |
| 0.60 | 1.7429 | 0.99838 → 0.934994 | 0.936616 | 29.951 → 28.050 | 1 → 0.7632 (83.3 kW → 59.5 kW) |
| 0.80 | 2.2836 | 0.99838 → 0.955706 | 0.957328 | 29.951 → 28.671 | 1 → 0.8217 |
| 1.00 | 3.1361 | 0.99838 → 0.969704 | 0.971327 | 29.951 → 29.091 | 1 → 0.8694 |
| 1.60 | 7.3240 | 0.99838 → 0.989103 | 0.990727 | 29.951 → 29.673 | 1 → 0.9524 |

The asserted line "the displacement less the slip" (5e-5) became the law's own flow at its rise (1e-6), plus "below the
slip-only delivery" and "the fluid takes `Q w_s`" (1e-6). The row reads the exponent of the junction's end state, as a
compressor reads its k. The start state's exponent differs by at most 2e-3 relative (asserted). A probe run showed the
law's flow on the start state's exponent missed the delivered flow by 4e-5, and on the end state's it matched exactly.

**`PlungerPumpTest`.** The S8 cases now read a liquid suction (water, p 1 MPa, n 2,200):
- no-rise delivery: 1e-4, unchanged;
- slip: tolerance 5e-5 → 2e-4, since the clearance costs 1.2e-4 at half the relief rise;
- the no-flow rise: the relief rise to 1e-9 → to 1e-4 (it falls by about 1.5e-5 relative: `c (r − 1)` 1.48e-4 over the
  relief line's slope 10);
- shaft power at no flow and at 10 MPa: `Q ΔP/0.88` to 1e-9 → to 2e-3 (water's work ratio is 0.9989 at 24 MPa);
- the fluid's power at n 1.3: `Q ΔP` → `Q w_s`.
"Delivery does not read the density" now holds p and k fixed: the old case also varied p and k, which the law now reads.

**Simulator (`FlowsheetUnitsTest`, CO2 at 310 K, 8.5 → 25 MPa, `PLUNGER_SHEET`).** Typed or kept as a plunger:
- hydraulic 158.6 kW (`Q ΔP`) → 134.6 kW;
- power 269.7 kW (centrifugal at 0.588) → 152.9 kW;
- efficiency 0.588 → 0.880.

With no type the run is the centrifugal pump's, as before.

**Point 7, the rise at no flow.**
- Nitrogen from 0.1 MPa stops at 2.77 MPa: `p ((1 + share/c)^n − 1)`, asserted.
- A liquid's no-flow rise moves under 1e-4 relative (water from 1 MPa: about 1.5e-5).
- A liquid's delivery at a rise moves by `c (r − 1)`: 1.4e-4 at 20 MPa from 1 MPa. The plan's "water moves under 1e-4"
  holds up to 8 MPa from 1 MPa.

**Bundled examples.** None has a plunger pump (S8 section 4), so every example takes the unchanged code path:
- a pump's passages are `Passages.TYPED` as before (`SizedPassages.of` builds its own only for a plunger), so `pumpType()`
  is null, `plunger` is false and `MoverUnit`'s arithmetic is the old line for line;
- `PumpSizing.rate(Load, Size)` does not read the new fields;
- `SizedPassages.reads` for a pump is `[false]` both before and after Size all.

No example run can move. They were not re-run.

## 4. The simulator's type (decision 2, narrowed)

The main thread's rule was: with no type, the run applies Auto's rule on its inlet. That would make
`SizedPassages.reads` carry the resolved type. A pump's first centrifugal size, from Size all, would then change reads from
null to `CENTRIFUGAL_PUMP` and re-run the sheet for nothing. `SizingPageTest` asserts the opposite ("pumps and lines move no
run, so the run stands").

Built instead, with the main thread's agreement (option A): a pump with no type and no kept size runs as a centrifugal pump,
as every pump did before. `reads` is `[type == PLUNGER]`. It is exact:
- Size all re-runs only when Auto proposes a plunger, or the player fixes one;
- fixing a centrifugal type on a pump that has none moves no run.

**Known limit.** Before the first Size all or type key, a pump that Auto would size as a plunger shows centrifugal readings in
the run. The first Size all that keeps the plunger re-runs the sheet with it.

No saved sheet or wire payload changes. `reads` is computed and compared in memory (`SheetState.greyed`, `Proposal.moves`).

## 5. The calls that hand a fixed 1.4 or pressure to a mover's law (decision 3)

| Call | What it passes | A plunger? |
|---|---|---|
| `InlineMovers.ratedShaftPower`, line 57 | a machine's law at its rated flow, p 1e5, k 1.4 | Read only by `ElectricStores.ratedDraw` (kind `INLINE`, in-line movers) and `ColumnPower.pump` (a column's held casing). A plunger is neither. If reached, its rise at the displacement is ≤ 0, so 0 W, as before P1 |
| `InlineMovers.ratedShaftPower`, line 60 | a set-flow mover's `riseLimit` and `shaftPower`, p 1e5, k 1.4 | not a `Machine`, so never a plunger |
| `MoverUnit.kappa`, line 106 | 1.4 for the `FlowControl.Pump` closure | the closure's `power` ignores k; a plunger tile takes its own branch (`isentropicExponent`) |
| `VacuumPump`, lines 193 and 197 | its own law at 1.4 | the vacuum pump's own law |
| `InlineMachines`, line 29 | a centrifugal compressor's design at 1.4 | not a plunger |
| `PassiveStepSolver.kappa` and `suctionKappa` | 1.4 for every liquid mover but a plunger | a plunger reads `isentropicExponent` |
| `ShaftTrains` (lines 760, 771, 776), `ElectricStores` (line 503), `ViewBuilder` (line 178), `ConservativeTransport` (line 597) | `suctionKappa` | read the exponent |

## 6. Runs

Filtered: `./gradlew test --tests … --max-workers 4 --offline`. Logs and results are in `tmp/followups-p1/runs/`.

| Run | Classes | Result |
|---|---|---|
| 1 | `PlungerPumpTest`, `PlungerPumpMoverTest` | 18 tests, 1 failed: the law's flow at its rise read on the start state's exponent (4e-5); diagnosed by run 2's probe |
| 2 | `PlungerPumpMoverTest` (with a probe) | the row reads the end state's exponent; test changed |
| 3 | `PlungerPumpMoverTest` | pass |
| 4 | `PlungerPumpMoverTest`, `PlungerSizingTest`, `FlowsheetUnitsTest`, `PlungerPumpTest` | 73 tests, 1 failed: nitrogen at 1 bar against the ideal-gas ratio (1.5e-3, its real-gas deviation); the others pass |
| 5 | `PlungerPumpMoverTest` | pass |
| 6 | the affected selection (section 7) | the first attempt did not compile: the dense GameTest's new expectation read a variable out of its scope; fixed, then run 6 below |

## 7. The affected selection

`node tools/merge-lane/affected.mjs select` on the final code (`tmp/followups-p1/affected2.txt`): `test` 341/504,
`equipmentGates` 21/31, `columnGates` 64/89, `holdGates` all 10, `fluidSolverRegression` all, regression and GameTest
steps due. Run by `tmp/followups-p1/runs.sh` in batches of 80 filters, one task at a time, 4 workers. Logs and the XML
results are in `tmp/followups-p1/runs/run6/`.

| Task | Classes | Tests | Failures |
|---|---|---|---|
| `test` (four batches) | 341 | 2,172 | 0 |
| `equipmentGates` | 20 | 193 | 0 |
| `columnGates` | 63 | 382 | 0 (up to date: its gate inputs, the copy of what its tests load, did not change; counts as run) |
| `holdGates` (rerun alone) | 10 (14 result files with the nested groups) | 136 | 0 |

Left out, as the brief and AGENTS.md say:
- `UnitTextTest` and `LanguageFilesTest`: the translation commit;
- `EngineDigestTest`;
- `NativeColumnSolverTest`: native, in `nativeSmoke`;
- `fluidSolverRegression` and chain-100;
- the GameTests: the dense GameTest's plunger case is due, with its new expectation.

**A process slip.** Stopping the failed first attempt left its script running (the Windows background-stop problem).
From about 17:03 to 17:12 it ran `columnGates` (unfiltered) and `holdGates` in this worktree beside the second attempt's
`test` batches. That made three Gradle invocations on the machine with another batch's. The script was then killed,
and `holdGates` was run again alone. No result above comes from the stray script.
