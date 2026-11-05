# PH/UV free-water round-trip miss (P1, 2026-11-05)

`FluidTpEquilibriumPhUvTest.freeWaterRoundTripsAndBoiling` fails since O7 step 1 (570c8441c, methane ideal-gas Cp only) at 460 K, 0.5 MPa: UV pressure off by 1.31e-8 P, allowed 1.70e-9 P. Probes on 8396d8d98; logs, probe source and patches in the session worktree's `tmp/phuv-roundtrip/`.

## 1. Expected behaviour

- The failing state is ratio 1.0: 1.05 mol of water beside 1.05 mol of crude + N2. The water is all steam (p_w 347 kPa, saturation 1.17 MPa). Gas 72 % of the moles, hydrocarbon liquid 28 %, no free water. Ratio 0.01 passes (dP/P 3.9e-12).
- U and V are smooth functions of (T, P) here. Isochoric stiffness T(dP/dT)_V/P = 1.70: the test's estimate 1.703, independent central differences 1.702 (h 1e-4) and 1.693 (h 1e-6). The estimator is right.
- So UV should give back T and P to about the stop rule, 1e-11. That holds only if each TP answer solves its own equations to near rounding. A UV answer cannot be more exact than the TP answers it inverts: dlnP is about dlnV_TP / |dlnV/dlnP|_T, here 1.08.

## 2. What the code did

- The TP answer is not smooth at the 1e-8 level. When all the water is steam, `solveWet` finds the hydrocarbon partial pressure from pc + p_w(V_gas(pc)) = P. It uses up to 3 fixed-point steps, then a bisection that stops at |residual| < `PARTIAL_PRESSURE_TOLERANCE` P = 1e-8 P.
- At ratio 1.0 the fixed point diverges: its slope is about -n_w/n_hc,vap = -2.3. The bisection decides every call, and every TP answer carries a residual anywhere in ±1e-8 P. That residual is constant over each set of states that take the same halving path. V(T, P) is therefore a staircase with jumps up to 2e-8 in ln V.
  - Probe at the reference state: steps of +1e-10 and +1e-9 in ln T give dlnV = +4.3e-10, then -1.22e-8 (a step to another piece).
- The reference TP (460 K, 0.5 MPa) closed at r/P = -4.86e-9. The UV answer's TP closed at r/P = +7.75e-9. The difference, 1.26e-8, is the whole miss: dP/P = -1.307e-8, dT/T = -2.5e-10.
- The UV solver did its job:
  - At its answer a fresh TP gives F = (-1.0e-14, -5.6e-16). An independent Jacobian gives J^-1 F = 4e-17.
  - Exit path: Newton, 30 TP calls. Jacobian: differences at the start, then Broyden. At eval 19 (F about 3e-8, the noise floor) the line search fails on Broyden's Jacobian. A refresh by differences follows, then 4 Broyden steps. The run exits through `met && size <= tolerance` on Broyden, with step 2.8e-16.
- The old Cp is the same code on a different path. It also refreshes at eval 19, then exits after 29 calls on the reference's own piece (r/P equal to 2e-15). That gives dP/P 2.3e-15. O2's pass was luck: the Cp change moved U by 0.013 % and moved the path to the next piece.
- Ratio 0.01: the fixed point converges (slope -0.045), so its answers are smooth.

## 3. Proposal (options for the owner)

| Option | Changes | This class (scratch) | Cost | Classes that load it |
|---|---|---|---|---|
| A: close pc to rounding | solver, TP stage (`solveWet`) | 8/8. Free water: worst dP/P 1.04e-9 (the liquid-water state, as at O2), dT/T 8.78e-12. Failing state 8.1e-14 | UV TP calls: mean 17.8 → 16.1, max 39 → 39; PH mean 6.4 → 6.1. Failing UV: 30 → 20 TP calls, kernel evaluations 42,509 → 7,805; ratio 0.01 at 460 K, 0.5 MPa: 32,222 → 11,330 | see below |
| B: no convergence on a Broyden step | solver (`newtonUv`) | not run | none | `newtonUv` callers |
| C: test tolerance | test only | 8/8 at head. The allowed P miss on all-steam wet states gains + 2 `PARTIAL_PRESSURE_TOLERANCE` P | unchanged: UV mean 17.8, max 39 | `FluidTpEquilibriumPhUvTest` only |

Option A, form A1 (`optionA1-closure-falsi.patch`):
- An Illinois regula falsi inside the existing bisection bracket. It runs to 1e-13 P or a bracket of 4 ulp. The old 1e-8 still decides "root found" against "the bracket closed on a step of V_gas".
- The fixed point accepts only at 1e-13.
- On the failing state the TP answer is smooth again: steps of 1e-11 to 1e-8 in ln T give dlnV in exact proportion.

Option B is rejected by measurement: the UV equations are solved at the wrong answer, so no stop rule can see the error.

Option C (`optionC-test-tolerance.patch`):
- It records the TP closure as the UV limit.
- It keeps the noise floor and its cost: the line-search failure and refresh, and about 27 hydrocarbon flashes for each wet TP call.

Recommendation: option A. It is the structural fix in the stage that makes the noise. Physics forces the equation, and only the stop is numerical.
- What it deletes: the fixed-point special case. The secant can start from that point's guess.
- It moves wet all-steam TP answers by up to 1e-8 relative, so references can move. The final lane must re-check `FluidSolverRegressionTest` (chain-100) and the fluid GameTests.
- Classes that can load it, not run: `FluidTpEquilibriumTest`, `FluidTpEquilibriumP3Test`, `FluidTpEquilibriumPhUvTest`, `FreeWaterIceTest`, `NetworkPhaseEngineTest`, `LegacyNetworkPathPinTest`, `SpineNetworkPathTest`, `RangeContinuationGateTest`, `FluidSolverRegressionTest`, the fluid GameTests and the hold-fuzz campaign.
- `solveWetWithCrystals` (line 2945) closes pc with the same 1e-8 fixed point. One shared closure routine would serve both.

Owner choices: (a) A or C; (b) A's closure, 1e-13 P (measured) or rounding; (c) whether the crystal path joins in the same batch. Code guide: no change (review only).

Owner, 2026-11-05 (E21): option A. The closure stops at 1e-13 P, the fixed-point special case goes, and the crystal path shares the routine.
