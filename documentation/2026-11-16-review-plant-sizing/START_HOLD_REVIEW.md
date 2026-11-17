# The crude island's hold at the column's start, on a small line (2026-11-17)

Asked by the main thread after `PUMP_DRIVE_FLOW_REVIEW.md` finding 3 (branch `claude/pump-drive-flow`): at a 1.5 bar crude
supply the island holds when the column's feed valve opens ("line search stalled at residual 295.89; active-set pass 0").
Diagnosis only: no change under `src/main`. Probe `tools/valve-hold/StartHoldScratchTest.java`, trace
`tools/valve-hold/StartHoldTraceScratchTest.java` with `trace.patch` (applied for one run, reverted). Logs and dumps in
`tmp/start-hold/` (git-ignored).

## Summary

**Line.** Tia Juana crude (test catalog) at 300 K: 859.8 kg/m3, bubble point 138.2 kPa (the world's gas edge is about
128 kPa). Generator, a 0.4 m suction pipe, P-101's law (`CentrifugalPump(.20279, 170.98, .75, 2950)`, full speed, no set
flow), six pipes of bore 0.11232 m, the column-sensor flow valve, a pipe, a void. The bore is set so the open line without
a pump passes 318.85 kg/s on 7.5 bar; the same line passes 162.5 kg/s on 2.07 bar and 302.3 on 6.77 bar (quadratic).
The valve stands shut for 800 ticks (pump dead-headed at 1894.5 kPa), then gets its target.

**Expected (physics).** The brief's expectation needs two corrections:
1. The curve is `H = Hr (1.21 - 0.06 q - 0.15 q^2)`: at 2 x design (q = 1.83) 102.2 m, 8.6 bar, not 89 m.
2. The world's pump law has rated speed 2950 rpm (`MachineRatings.machineControl` -> `UnitDesign.designSpeed`), so its NPSH3
   is `(2950 sqrt(0.20279) / 200)^(4/3)` = **12.5 m at rated flow**, 11.3 m at design. The 3.30 m the sizer reports is the
   in-line pump's duty design (`PumpSizing.requiredNpsh` -> `Npsh.forDuty`, 1087 rpm), not the machine the world runs.
At 150 kPa the suction (149.7 kPa, 0.3 kPa line loss) meets the breakdown floor `pv + 0.85 rho g NPSH3(Q)` (pv 31.3 kPa
here) at **216.5 kg/s = 1.36 x design**. So: a target above 216.5 kg/s gives the valve open at 216.5 kg/s with the pump on
its suction limit; a target below it regulates. Measured open flows (valve placed open): 216.46 (a), 221.52 (weak curve),
399.74 (1000 kPa supply), 320.25 (400 kPa supply, sink 500).

**What the code did.**

| Case | Target kg/s | Expected | World path (edit) | Direct carried / fresh |
|---|---|---|---|---|
| a 150 kPa | 318.85 | open, 216.46, suction limit | HELD, 320.17, "pass 0" | fails 320.18 / fails 2.784 |
| b 150 kPa | 167.39 | regulates | HELD, same | fails / fails |
| c1 150 kPa | 205.63 (0.95 open) | regulates | HELD, same | fails / fails |
| c2 150 kPa | 227.28 (1.05 open) | open, 216.46 | HELD, same | fails / fails |
| d 1000 kPa | 318.85 | regulates | regulates, pump on law | ok / ok |
| e 150 kPa, weak curve | 318.85 | open, 221.52 | open, 221.52, suction limit | ok / ok |
| f 400 kPa, sink 500 | 318.85 | regulates (open 320.25) | regulates | ok / ok |

**Hypotheses.** H1 (a flow valve regulating towards a target the line cannot pass) is **ruled out**: b and c1 are reachable
and hold the same way, and the valve is never regulating in any failing pass. H2 (the suction limit) is **the cause**: d and
f, with the suction far above the floor, solve; every 150 kPa case with the strong curve holds. H3 (the start) is **the second
half**: the "pass 0" in the message belongs to the retries, which start from a mode set with no root.

**Mechanism (traced, case a and b carried, a fresh, e carried as control).**
- M3, the suction-limit row is flat on both sides of a narrow window. The row (`StepEquations` line 1518) states
  `P_suction = min(max(E, pv + bd(Q)), P_supply,0)` (`MoverSuction.suctionLimit` line 68 and `ReleasePressure.limit` line 98).
  Its slope in Q exists only between 200.4 kg/s (where `pv + bd` reaches E = 138 kPa) and 216.8 kg/s (where it reaches the
  supply's 150 kPa). Above the window the row reads `P = 150 kPa`: with the suction line's row that is a double root at
  Q = 0. Below it the row reads `P = E`: the 0.4 m suction pipe then wants about 1250 kg/s. On either plateau the Jacobian is
  singular in the common flow (sigma min 2e-6 to 4e-6, row pair: suction-limit row 0.93 and suction line 0.36).
- Carried pass sequence (a and b alike): pass 0 all closed, converges; pass 1 valve open, pump closed, converges; pass 2
  pump on its law, converges at 320.2 kg/s with the suction at 149.47 kPa; `nextMode` puts the pump on its suction limit
  before the valve (one change per pass, edge order); pass 3 starts at 320.2, inside the upper plateau. Newton walks 232.8,
  220.6 (still above 216.8), jumps to 159.9 (lower plateau), then 1267, 689, 579, 421, 193, ... between the plateaus, never
  landing in the 16 kg/s window: "Newton iteration limit at residual 484.3" after 30 iterations. Nothing in the failure branch
  changes (`cavitates` needs MOVER_LAW, option B needs a regulating valve), so the attempt throws. In b the valve would have
  regulated at 167.4 one pass later; the suction-limit change was taken first.
- The weak curve (e) starts its suction-limit pass at 273.7 kg/s, lands at 221.9, inside its 204.9 to 222.8 window, and
  converges in 5 iterations. That is luck of the step, not a different law.
- M4, the retries cannot succeed. The interval solver retries at 0.5, 0.25, ... 2e-6 s. Each retry starts with the pump on
  PUMP_SUCTION_LIMIT, carried from the failed attempt's own pass 2 (`PassiveStepSolver` line 456 reads `carry.modes()`, set at
  line 713 inside the failed attempt), and the valve CLOSED, from the committed interval (lines 418 to 441). That set is
  structurally singular (sigma min 2e-18): the pump's head and the closed valve's head are both free and nothing fixes the
  discharge junction's pressure; the closed valve is not counted as sealing the discharge because it has a target
  (`sealedWithout` line 1807), so D58 does not close the pump. The start puts the pump's carried 320.18 kg/s into a junction
  whose outlet is shut: residual 320.18, the n2 balance row, at every step size. The world's 295.89 is this number on the
  world's line. The message names the last retry's pass 0, not the first failure.
- Fresh start (a): `BalanceStart` decides the pump on its suction limit and ends unconverged (residual 1070, the discharge
  walked from 1894 kPa to the hull's floor, 0.25 kPa); `PassiveStepSolver` lines 489 to 491 take its modes and flows anyway.
  The rate-seed pass starts with the crude at 0.25 kPa and every trial leaves the property domain ("Invalid prepared
  temperature"), residual 2.784. This is option C3's defect at the solve's first start, where C3 does not act.

**Recommendation (owner to choose).** S1 + S2 remove both failures at their stage; S4 is the physics question behind it.
Not verified: no option was built; the world's own first failure (before the retries) was not traced; the world's crude
pv (the brief's 11.03 m implies about 56 kPa, which puts the world's breakdown near 182 kg/s, 1.14 x design) is not read.

## Options

| Option | What it changes, where | Removes / simplifies | What could move | Test |
|---|---|---|---|---|
| S1. The suction-limit row keeps its slope | `MoverSuction.suctionLimit` (line 63): drop the `min` with `suctionSupply` from the row; the supplier's no-flow pressure stays in the vapour lock (D16 A, `vapourLocks`), which already closes a pump that cannot draw at no flow | the upper plateau and its double root at Q = 0; one rule (D15/D17's clip) where D16 A now decides the same case | islands with a pump on its suction limit whose root lies on the supply clip today (D14/D15 hold seeds, `PumpCavitationTest`); none of the probe's converged points (all lie in the window) | the probe's cases as a test (a and c2 open at 216.46, b and c1 regulate), `PumpCavitationTest`, holdGates via `affected.mjs` |
| S1'. Flow form of the row | `StepEquations` line 1518: `Q - Q_break(P_suction)` with `Q_break` the inverse of `pv + bd(Q)`, zero at or below E | both plateaus: the row's slope in Q is 1 everywhere | as S1, plus the lower plateau's roots (suction held at E with the line setting the flow) change form; needs an owner reading of what E means for a pump (section 17) | as S1 |
| S2. No carried mode from a failed attempt; no suction limit into a shut discharge | `PassiveStepSolver.solve`: a retry starts from the committed interval's modes (`replayStart`), not from the failed attempt's `carry`; and `relieveSealedPockets` / `sealedWithout` line 1807: a closed flow valve with a target does not exempt a pump on its suction limit (its head is free; only MOVER_LAW states the discharge) | the singular start of every retry; the retries then repeat the first attempt (a cleaner failure) | carried solves that today recover on a retry from a failed attempt's mode (none measured) | probe cases a to c2 carried; any selected test reading `carry` |
| S3. Unconverged balance refused at the first start | `PassiveStepSolver` lines 489 to 491: take the balance's modes and flows only where `Start.converged`, otherwise the input graph's states with `initialModes` (C3's rule at the solve's start) | the fresh failure (crude laid out at 0.25 kPa) | fresh solves whose balance ends unconverged and recover today | probe cases fresh; `JunctionSeedingGateTest` (it moved under C1) |
| S4. Physics, for the owner: the world's P-101 NPSH | The constructed pump's NPSH3 at 2950 rpm is 12.5 m; the sizer reads 3.30 m (`Npsh.forDuty`). Either the sizer reads the machine's own NPSH (`CentrifugalPump.npsh(speed)`), or the constructed pump's NPSH design point is its duty speed | the sizer and the world reading one NPSH | the sizer's margins for every pump; the review world's charge flow at 1.5 bar | sizer tests; the review world (the `claude/pump-drive-flow` thread's files) |
| S5. Device change order | `PassiveStepSolver` pass loop near line 640: where a pump would go on its suction limit and a flow valve in its series would regulate, the valve goes first (as `regulatesInSeries` does for a pressure valve) | b and c1 never try the suction limit | passes of islands with both changes due | as S1; covers reachable targets only, not a |

S1 and S2 improve existing stages (the suction law's row, the retry start) and delete a clip and an exemption; S3 extends C3
to the start it misses; S5 is a narrower rule and is not needed if S1 holds. Each moves results only where today's solve
fails or sits on the clipped plateau; a build states any moved reference with old and new values.

## Numbers

| Item | Value |
|---|---|
| Crude (test catalog TJ, 300 K) | 859.84 kg/m3; bubble 138.19 kPa; solver pv about 31.3 kPa (from the open point) |
| NPSH3_r, strong / weak curve | 12.48 m / 12.86 m (S = 200, 2950 rpm) |
| Window of the suction row's slope, strong / weak | 200.4 to 216.8 / 204.9 to 222.8 kg/s |
| Open-law flow at 150 kPa (no NPSH), strong | 320.2 kg/s, suction 149.47 kPa, discharge 391.6 kPa |
| Suction-limited open flow, strong / weak | 216.46 kg/s, discharge 316.78 kPa / 221.52 kg/s, 319.80 kPa |
| Dead-headed discharge, strong / weak | 1894.49 / 1170.29 kPa |
| Failing pass, carried | PUMP_SUCTION_LIMIT + VALVE_OPEN: iteration limit at 484.3; sigma min 2.3e-6 to 4.2e-6 on the flow direction |
| Retries, carried | PUMP_SUCTION_LIMIT + CLOSED: residual 320.18 (n2 balance), sigma min 2e-18 (two heads and n2's pressure) |
| Fresh | balance unconverged (1070), n2 at 0.25 kPa; rate seed 2.784, 24 trials out of the property domain |

Dumps: `tmp/start-hold/trace/{a-carried,a-fresh,b-carried,e-carried}/`, readings `tmp/start-hold/an-*.txt`, probe lines
`tmp/start-hold/run-1.txt`, trace lines `tmp/start-hold/trace-1.txt`. One run of each class; no case repeated.
