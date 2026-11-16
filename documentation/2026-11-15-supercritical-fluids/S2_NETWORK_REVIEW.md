# S2 network review: S2a (traits on a phase) and S2b (the dense-fluid stream laws)

Batch [2026-11-15-supercritical-fluids](README.md), stages S2a and S2b of [SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md)
("S2 as carried out"). Sub-thread work, 2026-11-15. S2c is not part of this review.

## Summary

- S2a (`6dd9cd57c`): `HydrocarbonModel.Phase` carries the `PhaseTraits` of its root; `FluidThermodynamics.State` gains
  `oneFluidSlot()`, `dense()` and `reducedDensity()`. No value moved.
- S2b, three law commits (`ca321ef23`, `99b3fe095`, `db03dc17e`) and `DenseFluidGatesTest` (`e55bdf53a`):
  - viscosity: one slot rule; a dense fluid reads dilute plus the Jossi-Stiel-Thodos residual, weighted from a quarter
    of its critical density;
  - film: the heat curve's film follows a one-fluid state's liquid-like weight;
  - velocity limit and heat capacity ratio: a dense liquid slot follows its fluid.
- The slot-rule edit (brief step 5) was not made. The comparison found 11 nitrogen states inside the old ranges where
  the two rules disagree, a stop condition. The owner kept O2 A; S2c carries it out (section 6).
- One S3 defect found and fixed (`1b0fc7770`): the identity bound ([S3_DATA_REVIEW.md](S3_DATA_REVIEW.md) section 4).
- Tests: every class that loads a changed file, green (section 8). No existing test value of a dense state moved; no
  value of a state that is not dense moved.
- Three owner options, with numbers: the onset (section 2), the film bounds and the film seam (section 3).

## 1. Expected behaviour

A fluid above its critical temperature is one phase at every pressure. Its density runs from a gas's to a liquid's,
and its viscosity, sound speed and film coefficient follow the density. Nothing happens at a line in between. Carbon
dioxide at 319.33 K (`viscosity-reference.json`): dilute 15.91 uPa s, 32.96 at 10 MPa, 89.35 at 30 MPa. Its isothermal
sound speed falls to about 22 m/s at 305 K and the critical density (section 4). An ordinary gas (nitrogen, methane
at ambient and a few MPa) is also "dense" by the traits (above its `Tc`). Its laws must stay today's until its density
matters.

## 2. Viscosity

What the code did:

- The vapour slot read Wilke's dilute value at every density.
- A liquid slot read the log-mixing rule over each compound's liquid table. A compound above its `Tc` contributes its
  dilute value (`liquidSource`, VAPOR_DATA).
- So a dense fluid in the vapour slot was dilute at any pressure. At the slot line it stepped to the liquid slot's rule.
  At carbon dioxide's `Tc` above `Pc` the step was ×5.02 (76.74 to 15.30 uPa s, liquid table to dilute, at Pr 1.1,
  1.5 and 3). The brief's estimate was "about three".

What changed (`NetworkStreams.slotViscosity`, one rule for every stream and node):

- The vapour slot, and a liquid slot whose root is on a supercritical isotherm of its own composition
  (`PhaseTraits.supercriticalIsotherm()`), read `MixtureViscosity.dense` at the slot's molar density.
  `dense = vapor + w(rho_r) × residual`, with `rho_r = rho × sum(x_i Vc_i)`.
- `w = smoothstep((rho_r − 0.25)/0.25)`: 0 at or below 0.25, 1 from 0.5, C1 at both ends (`DENSE_ONSET`, `DENSE_FULL`).
  At `w = 0` the residual is not evaluated, so `dense` is `vapor` bit for bit.
- Any other liquid slot reads `liquid` as before.
- `vaporViscosity`, `phaseViscosity` (the liquid term inside the slurry correction, which stays),
  `phaseCarrierViscosity`, `PassiveStepSolver`, `SolidMobility` and the simulator's `StreamProps` all read it.
- The revision gains `:dense-laws-v1`. Saved state rebuilds once from the facts. No facts field, corpus image or
  `FORMATS.md` row.

The viscosity at `Tc` above `Pc`: liquid table below (Tc − 0.05 K), slot rule above (Tc + 0.05 K), in uPa s.

| Compound | Pr | Below | Above, before | Above, now | Step before | Step now |
|---|---|---|---|---|---|---|
| CO2 | 1.1 | 76.74 | 15.30 | 45.60 | ×5.02 | ×1.68 |
| CO2 | 1.5 | 76.74 | 15.30 | 58.23 | ×5.02 | ×1.32 |
| CO2 | 3 | 76.74 | 15.30 | 80.21 | ×5.02 | ×0.96 |
| Propane | 1.1 / 1.5 / 3 | refused | refused | 30.46 / 38.90 / 53.57 | — | — |

- On the gates' isobar (10 MPa) the step from 304 to 305 K is ×0.701 (76.74 to 53.82). It is the one accepted step.
- Propane: its pure liquid below `Tc` is refused ("no pure-liquid claim for conditional solutes"), an existing behaviour
  that S2b does not change. Above `Tc` it now has a value; before, the same refusal applied in the liquid slot.

**Owner option: the onset.** Residual as a share of the dilute value, gas at 300 K:

| Gas | 0.1 MPa | 0.4 MPa | 2 MPa | 7 MPa | 10 MPa |
|---|---|---|---|---|---|
| N2, residual / dilute | 0.050 % | 0.205 % | 1.194 % | 6.019 % | 10.164 % |
| N2, weight now | 0 | 0 | 0 | 0.0001 (rho_r 0.2516) | 0.391 (rho_r 0.357) |
| CH4, residual / dilute | 0.076 % | 0.316 % | 1.930 % | 11.548 % | 21.543 % |
| CH4, weight now | 0 | 0 | 0 | 0.165 | 0.950 |

- With the onset (implemented): nitrogen keeps Wilke bit for bit to 6.9 MPa (rho_r 0.2481), every bundled-campaign
  gas (0.1 to 0.4 MPa) keeps its lines.
- With no onset: every gas moves by the residual, 0.05 % at ambient, and every line that reads a gas viscosity can move.
  It deletes `denseOnset` and the two constants, and loses the bit-exact gas.
- The brief's test range for nitrogen ran to 7 MPa. The gate stops at 6.9 MPa, because 7 MPa is above the onset.

## 3. Film

What the code did: the film was 100 (gas) or 2000 (liquid) W/m²K by vapour mass fraction (`HeatCurve.Films.at`). A
dense fluid's film stepped where its slot changed.

What changed:

- `HeatCurve.liquidLikeWeight(state) = smoothstep((delta − 0.5)/1.0)` for a one-fluid state (NaN for more than one
  phase). `delta` is `State.reducedDensity()`.
- The point's vapour coordinate is `1 − w`, so one fluid is never bisected as a phase change.
- `Films.at(x, w)`: gas at `w ≤ 0`, liquid at `w ≥ 1`, else `gas + w(liquid − gas)`; NaN keeps `at(x)`.
- Deviation from the brief: an inserted knot interpolates the weight only between two points of one continuous fluid
  (the same slot, or both dense). The brief's form interpolated between any two one-fluid points. On a pure component's
  plateau (a liquid point beside a vapour point), that form put a weight on the plateau's knots and lost the two-phase
  film. `HeatCurveTest` ("two knots inside the plateau carry 5000 W/m²K") and `HeatSideNetworkTest` failed. With the
  rule, both are green with no test edit.

**Owner option: the film bounds** (0.5/1.5 implemented). Carbon dioxide at 320 K, film in W/m²K:

| Pressure | 6 MPa | 8 MPa | 10 MPa | 12 MPa | 20 MPa |
|---|---|---|---|---|---|
| delta | 0.342 | 0.570 | 1.016 | 1.384 | 1.878 |
| Bounds 0.5/1.5 | 100 | 127 | 1094 | 1929 | 2000 |
| Bounds 0.75/1.25 | 100 | 100 | 1139 | 2000 | 2000 |

**Owner option: the film seam.** With the weight on every one-fluid state (implemented), a compressed liquid near its
bubble point has `w < 1`. Its film is below the 2000 of the two-phase side's liquid end:

- carbon dioxide: 1881 at Tr 0.99, 1685 at 0.995;
- propane: 1999 at 0.98, 1864 at 0.99, 1668 at 0.995;
- the full 2000 at Tr ≤ 0.97.

The alternative puts the weight on supercritical isotherms only. Then the seam moves to `T = Tc`. Carbon dioxide at
Tc + 0.05 K has films 761 / 1908 / 1992 / 2000 / 2000 at Pr 1.0 / 1.05 / 1.1 / 1.2 / 1.3, against 2000 below `Tc`.
Propane is similar (781, 1909, ...). The brief's estimates were 1525 at Tr 0.99 and 1050 to 1700; the computed values
replace them.

## 4. Velocity limit and heat capacity ratio

What the code did:

- A liquid slot's velocity limit was the configured maximum, 100 m/s.
- A state with no gas had the heat capacity ratio 1.4.

What changed:

- A liquid slot that holds the state's one dense fluid takes `min(maximum, acoustic(V, phase mass with its solids,
  V × kappa))`, the vapour's law on its own slot.
- A state with no gas that is `dense()` takes the ideal-gas formula on that fluid's amounts. The `mover.liquid() ? 1.4`
  branches stay.

Carbon dioxide's isothermal sound speed `1/sqrt(rho kappa)`, m/s, and the limit now:

| T | delta 0.8 | delta 1.0 | delta 1.2 |
|---|---|---|---|
| 305 K | 49.0 (vapour slot) | 21.8 (liquid slot; was 100) | 51.4 (liquid; was 100) |
| 310 K | 70.4 | 58.6 (was 100) | 77.8 (was 100) |
| 320 K | 100.2 → limit 100 | 96.5 (was 100) | 113.1 → limit 100 |

The vapour-slot column was already the acoustic law. At 320 K the slot line is at 9.8719 MPa (delta 0.9817).

## 5. The gates test

`DenseFluidGatesTest`, pure carbon dioxide on `icheme:refinery` by `flashTP`, bounds 1.5 times the largest neighbour
change measured on each sweep (`tmp/supercritical-s2/probe/sweeps.txt`):

| Sweep | Largest change measured: viscosity | Velocity limit | Film weight | Slot change |
|---|---|---|---|---|
| A, 10 MPa, 280-400 K by 1 K | 1.742e-6 Pa s (281 K, liquid table) | 1.854 m/s (318 K) | 0.070 (319 K) | 320 to 321 K |
| B, 320 K, 2-30 MPa by 0.25 MPa | 1.809e-6 Pa s (10 MPa) | 2.918 m/s (10.25 MPa) | 0.0998 (10 MPa) | 9.75 to 10 MPa |

- Sweep A asserts the one step at `Tc` as ×0.701 ± 0.01, and exactly one step.
- Sweep B asserts one slot change. Bisected to 1e-12, the laws on the two sides of the slot line agree to 1e-9:
  measured 2.0e-7 viscosity, 5.7e-8 limit, 3.3e-7 weight at the probe's 1e-7 bisection.
- Nitrogen at 300 K, 0.1 to 6.9 MPa: Wilke viscosity, the gas film and the vapour's acoustic limit, all bit for bit.
- No hold on either sweep.

## 6. The slot rule (brief step 5): not made

Before the edit, the brief asked for a comparison of the two rules. Today's rule is the phase identification parameter;
O2 A puts a supercritical root in the liquid slot when its reduced density is at least 1.

- Digest: 10 FLUID phases of converged answers, all on a supercritical isotherm, 0 disagree.
- Grid of pure nitrogen, methane and carbon dioxide, Tr 1.0 to 3.0 by 0.05, Pr 0.1 to 9 by 0.1: 11,070 states, 2,144
  disagree (N2 797, CH4 816, CO2 531). In every one today says liquid and O2 A says vapour (Z < 1, delta 0.6 to 1.0,
  PIP 1.00 to 1.29).
- 13 lie inside the old pressure ranges:
  - 11 nitrogen states, Tr 1.15 to 1.40 (145 to 177 K), 6.1 to 9.8 MPa. Examples: Tr 1.15 at 6.112 MPa, delta 0.951,
    PIP 1.014, Z 0.506; Tr 1.30 at 9.169 MPa, delta 0.9999, PIP 1.253.
  - 2 methane states (Tr 1.15 at 8.28 MPa, Tr 1.20 at 9.66 MPa), below methane's 293.15 K data floor.
- `PhaseIdentification`'s class comment claimed none inside the old ranges. It is corrected with these numbers (a
  comment-only edit, in the documents commit).

The first liquid at an ambient isotherm:

| Fluid | Tr (T) | Today | O2 A |
|---|---|---|---|
| N2 | 2.40 (302.9 K) | 16.37 MPa (delta 0.611) | 28.87 MPa |
| CH4 | 1.55 (295.4 K) | 15.12 MPa (delta 0.822) | 18.44 MPa |
| CO2 | 1.05 (319.4 K) | 9.79 MPa (delta 0.984) | 9.85 MPa |

This was a stop condition. The main thread chose not to edit; the owner then decided, 2026-11-15, **"Keep O2 A"**:
the slot rule by density stands and S2c carries it out, with the moved states listed. S2b leaves
`FluidThermodynamics.placed` as it is. The probe sources stay in `tmp/supercritical-s2/probe/` (row in
`tmp/INDEX.md`) for S2c to run again.

The S2b laws do not depend on the slot: section 5 shows them continuous across today's slot line, so the slot rule
moves only what reads the slot itself (layering, heads, draws, the display row).

## 7. Findings

- **Jacobian.** The step's Jacobian is a block sweep that re-evaluates the rows. No viscosity or velocity-limit
  derivative exists (no `dViscosity`, `viscositySlope` or cap derivative), so the dense branch needs none.
- **Tangent-built states.** `Tangent.at` carries the base phase's traits. Tangent states appear only in the Jacobian's
  perturbed columns; no state of the network is built from them, and the residual rows use exact states. A flag that
  is stale for the perturbed column (the base on one side of the supercritical isotherm, the column on the other) can
  only bend that Jacobian entry, near the isotherm, as `vaporLike` already can. It cannot move an answer.
- **The cold campaign.** Its nitrogen sources at 126 to 170 K up to 6 MPa are dense supercritical nitrogen
  (delta 0.3 to 1.8). Their lines can move under S2b; the bundled campaign's (0.1 to 0.4 MPa) cannot. The campaign was
  not run (the final lane does). The distribution names that hold these sources were not found in the code read.
- **`PreparedRefillTest`** is green: `dense` takes `prepared` as `vapor` does.
- **M4** (`G3F11SupercriticalRegionTest`) is unchanged: dense −15.9 / +12.7 %. No DECLARED row was re-set. Of its 60
  states, in the onset ramp / below the onset: CO2 2 / 3, N2 1 / 0, CH4 1 / 1, ethane 3 / 1, propane 1 / 1.

## 8. Tests

The classes that load a changed file (`node tools/merge-lane/affected.mjs select --since HEAD` on the main checkout's
load map), one invocation each time, `--max-workers 4`:

- Run 2, the first S2b form: 1,554 tests in `test`, holdGates 15 classes, equipmentGates 3 classes. 6 failed:
  `HeatCurveTest` and `HeatSideNetworkTest` (the film's plateau, fixed in the code, section 3); `FluidPacketCodecTest` and
  `FluidDeviceSpecDomainTest` (S3's envelope, fixed in `1b0fc7770`); `ColumnSolveTest` (S3's identity bound).
- Run 3, the final S2b code: `test` 149 classes (952 tests), holdGates 19 classes (175 tests), equipmentGates 2 classes
  (13 tests). All green but `ColumnSolveTest` (before the bound). `DenseFluidGatesTest` 3/3, `HeatCurveTest` 10,
  `HeatSideNetworkTest` 4 green. `MixtureViscosityTest` (5), `PreparedRefillTest` (1) and
  `G3F11SupercriticalRegionTest` (9) passed in run 2; nothing they load changed before run 3.
- Run 4, after the identity bound: `ColumnSolveTest` 11, `MaterialCatalogTest` 15, `FluidThermodynamicsTest` 4,
  `IslandCertificateTest` 23, `FluidPacketCodecTest` 27, `FluidDeviceSpecDomainTest` 3, green.

Stored results: `tmp/supercritical-s2/results1` to `results3` and `run4.log`. Test values that moved: none of an
existing test for S2b. The envelope values are S3's (S3_DATA_REVIEW.md section 5). Not run: the whole suite, the lane,
chain-100 (`fluidSolverRegression`), the campaigns, the GameTests, `LanguageFilesTest`, `UnitTextTest`. No `en_us.json`
edit.

## 9. What S2c still has to do

- The slot rule O2 A in `FluidThermodynamics.placed`, with the moved states listed (section 6).
- The typed "no saturation" for a fluid above its critical temperature, suction, the level head and boil-up (plan, "S2
  as carried out").
- The GameTests and the cold campaign lines that S2b can move, in the final lane.
