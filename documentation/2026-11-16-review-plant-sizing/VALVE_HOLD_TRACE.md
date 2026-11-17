# Mechanism M2 of the valve hold, traced (2026-11-17)

Follows [VALVE_HOLD_REVIEW.md](VALVE_HOLD_REVIEW.md), section 4. Line A2: supply 200 kPa, pump `CentrifugalPump(.02,50,.7,1475)`,
charge valve 5 kg/s, a parallel pair, inlet valve closed then 10 kg/s, void 200 kPa, bore 0.1 m, water. Instrumentation:
`tools/valve-hold/trace.patch` (one dump per Newton solve, plus the balance's iterations), run by
`tools/valve-hold/ValveHoldTraceScratchTest.java`, read by `analyse_trace.py`; dumps in `tmp/valve-hold-trace/`. No mod code
changed. The reduced graph: n0 supply, e0 pipe to n1, e1 pump to n2, e2 charge to n3, the pair n3-n4-n5-n6 (e3 3>5,
e4 3>4, e5 4>5 the cross-link, e6 4>6, e7 5>6), e8 to n7, e9 inlet to n8 void. All nodes but n0, n8 are junctions.

## 1. Expected

The charge valve holds 5 kg/s: pump head 579 kPa, charge throttle 579 kPa, n3 to n7 at 200.18 to 200.06 kPa, pair 2.03
and 2.97 kg/s, cross-link at rest. The carried solve reaches exactly this point.

## 2. Pass sequence of the failing (fresh) solve

| Pass | Modes | Start | End |
|---|---|---|---|
| 0 | both valves open | the solve's balance (converged) | converged in 10 iterations (31.29 to 2.2e-11): 51.22 kg/s, pump head 31.8 kPa, pair 30.15 and 21.07, n2 219.03, n3 215.38, n7 205.48 kPa |
| 1 | charge regulating, inlet open | node states: pass 0's end; flows: a new balance on these modes (`PassiveStepSolver` lines 695-702); heads: 0 | stalls at iteration 0, residual 14.932 |

The rate seed, the step and every refined substep (1, 0.5, 0.25, 0.125 s, ...) repeat both passes to the bit. Pass 1
does not start from pass 0's flows: it starts from the balance's, which did not converge (section 4).

## 3. The stalling pass, iteration 0 (fresh Jacobian, no preconditioner)

| Item | Value |
|---|---|
| Norm (largest row; no taut vessel, so every weight is 1) | 14.932 |
| By group | junction mass balances 14.93; pump law row 2.23; line rows 0.995; energy rows 0.045; both valve rows 0 |
| Largest rows | n1 balance -14.932, n2 balance +14.932, n3 balance 4.986, pump law -2.231, n7 balance 1.765, inlet line row 0.995 |
| Start flows, kg/s | e0 5.00; pump 19.93; charge 5.00; pair 0.0056, 0.0089, -0.0026, 0.0027, 0.0026; e8 -4e-8; inlet law flow -14.98, carried -1.765 |
| Jacobian | sigma max 2.19, min 7.4e-10, cond 2.9e9 (next: 2.0e-6, 2.4e-6, 0.155) |
| Near-null column | inlet law flow +0.93, charge head -0.25, ln P of n3 to n7 +0.12 each |
| Newton direction | inlet law flow +1.5e9 kg/s, ln P of n3 to n7 +1.9e8, charge head -4.1e8 (x 1e5 Pa) |
| Line search | 24 trials, alpha 1 to 1.2e-7, all outside the property domain: "invalid prepared temperature" (alpha >= 0.031), "not finite" (0.016 to 1.5e-5), P = 1.6e15 to 1.3e163 Pa (<= 1.9e-6). Then the stall |

## 4. Where it parts from the controls: the balance at the switch

`BalanceStart.balanceStart` on the regulating modes (charge prescribed at 5 kg/s), started from pass 0's pressures:

| Iteration | n3 / n7, kPa | Largest net, kg/s | Step |
|---|---|---|---|
| 0 | 215.38 / 205.48 | 46.22 (n3: 5 in, 51.2 out) | n2 +964, n3 to n7 -27.2 to -9.7 kPa; 0.41 of it taken |
| 1 | 204.24 / 201.50 | 21.9 | half |
| 2 | 200.92 / 200.32 | 7.67 | full: -1.01 to -0.35 kPa |
| 3 | 199.91 / 199.97 | 8.26 | below the void: the inlet is one-way (`BalanceNet.prepare`, line 349) and passes nothing; no Newton descent; one sweep |
| 4 | 199.94 / 199.93 | 4.77 | Jacobian row of n7 at its floor; step 2.6e13 kPa, 1.5e-11 of it taken |
| 5 to 7 | 0.20 (the hull's floor) to 1.33 / 1.20 | 5.0, then 2.37 | three more sweeps; unconverged |

Round 2 ends at 1.92, n3 to n7 at 0.94 to 0.97 kPa. `balanceStart` returns `converged=false`; line 701 takes its law
flows anyway: `solver.lawFlow` on that graph puts the inlet at -14.98 kg/s (the void at 200 kPa into 0.94 kPa) and the
pair near rest. Pass 1 lays these flows over pass 0's states (n7 at 205.48 kPa).

| Case | Balance at the switch | Pass 1 start: inlet, pair | sigma min, cond | Pass 1 |
|---|---|---|---|---|
| A2 fresh | unconverged, n3 to n7 at 0.94 kPa | -14.98; about 0.005 | 7.4e-10, 2.9e9 | stalls at iteration 0 |
| A2 carried | 13 iterations from 791 kPa, from above: 200.18 to 200.06 kPa | 5.000; 2.03, 2.97 | 3.0e-5, 7.4e4 | 11 iterations: 14.93, 11.86, 3.65, 0.79 ... 2.7e-10 |
| No pair, fresh | crosses to 199.18 kPa; the sweep puts n3 at 200.064 | 5.000 | 0.099, 18.7 | 13 iterations: 14.93, 10.80, 3.29 ... 1.4e-12 |
| Pair, charge 30, fresh | 3 iterations, no crossing (n3 205.43) | 30.0; 12.31, 17.69 | 1.4e-4, 1.5e4 | 11 iterations: 10.07, 2.86, 0.66 ... 6.6e-11 |

Every control starts pass 1 with the pump at 19.93 kg/s and both heads at 0, as A2 does, and converges.

## 5. Mechanism

1. Pass 1's start flows come from a balance that does not converge, and the pass loop uses them without reading
   `Start.converged`.
2. The balance fails in two steps. Its Newton walks the downstream junctions from the open line's pressures down the
   square-root law and crosses below the void's 200 kPa. A Newton step on q ~ sqrt(dp) from q0 to q lands at
   dp0 (2 q / q0 - 1), below zero when q < q0 / 2: 5 against 51.2 crosses, 30 does not. Below the void the inlet passes
   nothing. The sweep then moves one junction at a time, and each one settles at its neighbours' pressure (n6 and n7
   both at 1.19593 kPa). Only the whole group of n3 to n7 moving together would reach the inlet. Without the pair, the
   inlet's own junction is fed directly; its bisection crosses 200 kPa and finds the root. Carried, the balance starts
   at 791 kPa, above the root, and never crosses.
3. In the pass, the inlet's law flow -14.98 kg/s stands 8.5 times beyond its reverse cap (1.77 kg/s). There the
   saturated law's slope is (1 + 8.5^8)^(-9/8), about 4e-9. So the downstream pressure level, the inlet's law flow and
   the charge's throttle form a near-null direction (sigma 7.4e-10). The step along it is 1e9, and no halving gets back
   inside the property box.
4. Independent of the interval: the balance and the junction rows hold nothing. Independent of the inlet's target: the
   inlet stays open. The pump is needed because it makes the open line 51 kg/s, more than twice the 5 kg/s target.

What the 14.932 is: the mass balance row (kg/s) of the pump's two junctions. The start puts the pump at its rated
20 L/s, 19.932 kg/s: `PassiveStepSolver.lawFlow` gives a mover its rated flow, while the balance passed 5.0 through it.
The rest of the line starts at 5 kg/s. Every control starts with this number (10.07 at a charge of 30) and reduces it at
once. It is in the message only because the stall takes no step. The 14.40 of the world's case H is not this start: the
direct replay of H converges, so its start was not reproduced.

## 6. Hypotheses

- (a) A pair connection at zero flow, where the friction slope vanishes: ruled out. The near-null direction has no
  component on a pair flow, and the carried solve converges with the cross-link at -6e-7 kg/s.
- (b) The throttle at 0 with the line at runout: ruled out as the cause. Every control starts the same way and converges
  in 11 to 13 iterations.
- (c) The buffers of the pair's junctions: ruled out. Their balance rows start below 0.01; the large ones are n1, n2, n3, n7.
- (d) Residual scaling: the norm is an unweighted maximum, set by a start mismatch that every case shares. It names
  the stall but does not cause it.
- Holds: a start built from an unconverged balance (section 5).

## 7. M1 (case L: one valve regulating at 3, set to 60; carried), one trace

Iteration 0 (0.95, the valve row (3 - 60)/60) puts every flow at 60 kg/s. Iteration 1 sets the pump law row:
pump head -161 kPa past its runout, valve head -211 kPa. From iteration 2 only the pump's discharge moves: 236.7, 85.8,
30.3, 9.9, 2.6, 0.24 kPa, then to about 1 Pa, the property box's floor. The pump's and the valve's line rows stay at
+-0.00449 (449 Pa against the 1e5 Pa scale), sigma min falls from 9.4e-3 to 5.3e-7, and iteration 14 stalls with every
trial below 1 Pa. This is a different stall. M1 has no root: at 60 kg/s the pump would need a discharge below zero, so
the Newton walks to the box's edge. M2 has a root, which the carried start finds, and the Newton never takes a step.

## 8. Options (none built)

| Option | Stage | Removes / adds | Normal-path cost | Does not cover |
|---|---|---|---|---|
| A. The other start on a failed solve, before refinement | interval solver (`PassiveIntervalSolver`, `RetainedSolver`) | every measured hold (each case solves from one start) / one rule | none; one extra solve on a failure | a solve with one start only (first job after a load or a structure change: A2 then holds); both starts failing; leaves both mechanisms in place |
| B. A regulating flow valve whose pass fails is tried open (as D11, `openStarved`) | pass loop failure branch (`PassiveStepSolver` near 585; hot file) | M1 at its root: L opens at 51 kg/s | none; one pass on a failure | M2: its failing pass follows a converged open pass, so opening repeats pass 0 and the cycle key ends the solve |
| C1. A balance step does not cross the pressure where an open one-way connection to a fixed end stops | `BalanceStart.BalanceNet.solveOnce` (line search, as `maximumStep` does for amounts) | M2's crossing | none where no step crosses; balances that cross and recover today may move (moved references stated) | other ways a balance fails |
| C2. A sweep lifts a group of junctions with no outflow together | `BalanceNet.sweep` | the plateau after a crossing | none on a converging balance | as C1 |
| C3. Pass 1 takes the balance's law flows only when `converged`; else pass 0's own flows | `PassiveStepSolver` lines 700-701 (hot file) | the unconverged start | one boolean | unverified: whether the regulating pass converges from 51 kg/s (lines 695-699 record a choked steam line where that start failed) |
| C4. A mover's start flow from the balance, not its rated flow | `BalanceStart.balanceStart` law loop | the 14.93 mismatch | saves 1 to 3 iterations a regulating pass | nothing of M2 alone |

Recommended, for the owner: C1 with C3 for M2 (the balance belongs to the start stage, and an unconverged start is
refused). B for M1. A only as a backstop. C3 and B touch the solute batch's hot file and wait for its slot.
