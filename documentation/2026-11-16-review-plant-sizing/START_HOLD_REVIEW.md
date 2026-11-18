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

## Built (2026-11-17)

The main thread chose the forms after the options above: D for S1 (the owner approved S1, S2 and S3; D replaces S1's
deletion of the clip, which serves cases the vapour lock does not cover), E1 for b and c1, S2 as planned, F2 for the fresh
start, S3 withdrawn. The owner allowed this batch to edit `PassiveStepSolver` beside the solute batch's edits ("Yes, go
ahead"). Branch `claude/start-hold-7f00cb`, after the merge of `claude/column-testing-sizing-26b5b9` (`248f05a4c`).

| Commit | What it does |
|---|---|
| `4b2bd650b` D | The suction-limit row is `max(-flow, (P_suction - limit(Q))/pressureScale)` with `limit(Q) = max(E, pv + bd(Q))` (`StepEquations` row `PUMP_SUCTION_LIMIT`; `MoverSuction.suctionLimit` without the supplier's no-flow pressure). The pump delivers the flow at which its suction stands at its limit, or nothing where the suction cannot reach the limit even at no flow. The flow term is in the closed-edge row's unit (1 kg/s); at a tie the pressure branch; a reverse flow gives r > 0, so no reverse root. The roots are today's: (i) `P = limit(Q)` with `Q > 0`; (ii) `Q = 0` where the supplier stands at or below `limit(0)` (D17's delivery to zero, now decided on the trial). The flat plateau above the supplier and its double root at no flow are gone. The mode tests (`cavitationMargin` -> `cavitates`, `nextMode`) read the same unclipped limit; their sign decisions differ only where the suction stands above its supplier. The remote-supplier sparsity and sweep (`suctionSuppliers`, `suctionLinks`, `remoteSuctions`), which served only the clip, are removed. |
| `cce21ea8e` E1 | `DeviceModes.lawBelowSuctionLimit`, `ControlConstraints.inSeries`, and one hook where the pass loop applies its device change. A flow valve whose change to `VALVE_REGULATING` is due, at a target (mass flow) below the converged flow of a pump on `PUMP_SUCTION_LIMIT` that carries one flow with it by the junction balances, puts that pump on `MOVER_LAW` in the same change. Derived: `limit(Q)` does not fall as Q falls, and the suction pressure does not fall either (the suction line's loss shrinks; decision A272 changes no other device in the pass), so below the limited flow the suction stands at or above its floor and the pump is on its law. No exception found in the code: every supplier the solver allows (a held end, a vessel, a junction fed through passive lines or by movers on their laws) holds or raises its pressure as the draw falls. |
| `9928016de` S2 | (a) `PassiveStepSolver.solve` wraps the pass loop (`solvePasses`) and restores `carry` on a failure, so a retry at a shorter interval starts from the point the failed attempt started from (the committed interval at a job's first step), never from the failed attempt's modes. (b) `sealedWithout`: the exemption of a closed flow valve with a target is removed. A closed connection seals a mover's side in its current mode; the mover stands closed until a pass opens the valve, and reopens on its rise. |
| `f200628e2` F2 | `BalanceStart.BalanceNet.solve`: the balance decides no pump's mode (the suction-limit decision and the anchor-and-prescribe update removed, with `suctionAnchor`). A pump starts on its law, or closed where vapour-locked (D16 A, unchanged), and the pass loop puts it on its limit with D's row. A pump that arrives on its limit keeps its statement (its junction anchored at `limit(0)`, nothing prescribed through it). |

S3 (refuse an unconverged balance at the first start) is withdrawn and not built: no measured case reached it. In case a
fresh the balance reported `converged=true` on a false point (below). The plan stays: at `PassiveStepSolver` lines 501 to
504, take `start.graph()`, `start.lawFlows()` and `start.modes()` only where `start.converged()`; otherwise keep the input
graph and `initialModes`. It is C3's rule at the solve's first start.

**Seed 181 on paper (asked before the run).** D16 A does not lock it (256 Pa of NPSH at no flow, above the band). Under F2
the balance starts the pump on its law: the law draws about 4.2 kg/s through a 1 cm line that gives 0.03 kg/s at the floor,
so the balance's suction junction falls toward the hull's low end. Those pressures only seed the pass. Pass 0 on the law
either converges with the suction below its floor, and `nextMode` (margin < -band) moves the pump to its limit, or fails
with an iterate in the property domain, where `cavitates` moves it to its limit. The next pass is D's row at the 0.03 kg/s
root. No state is committed from a pass or a rejected trial, so the supplier is not left drained. D15's drained reading
came from the balance's own pressures read for the vapour lock; the lock now reads the step's start states. The run below
is the check: `PumpCavitationTest`'s LNG cases and `HoldClassWorldsTest` pass unchanged.

**Tests** (`affected.mjs select --since 248f05a4c` after `testClasses`; picks through `record.init.gradle`, one run;
LanguageFilesTest and UnitTextTest left out; the left-out methods of `PumpCavitationTest`, `HoldClassWorldsTest`,
`SuctionZoneEdgeTest` and `JunctionSeedingGateTest` put back in):
- test: 295 classes, 1781 tests, 1 failure, the expected `SizingPageTest.theDoorFitsInEveryViewForEveryItemInChinese`;
- holdGates: 9 classes, 105 tests, 0 failures;
- equipmentGates: 1 class, 23 tests, 0 failures;
- columnGates: up to date (its pass stands);
- `fluidSolverRegression` (due by `affected.mjs`), chain-100 exact: max deviation 0 in moles, temperature, phase fraction and flow.
`StartHoldTest` 4 of 4: cases a to f carried, fresh and in the world, and a target crossing the breakdown flow (318.85,
167.39, 318.85 kg/s: open on the limit at 216.46, regulating on the law, open on the limit). `PumpCavitationTest` 6 of 6,
`HoldClassWorldsTest` 41 of 41, `JunctionSeedingGateTest` 7 of 7, `SuctionZoneEdgeTest` 4 of 4.

**Moved references:** none. No test that passed before changed result, and the regression chain is exact. Pass counts were
not compared.

**Line ranges for the solute batch's merge.**
- `PassiveStepSolver`, current numbering: 387-397 (the `solve` wrapper, S2), 674-675 (the E1 hook after
  `modes.set(deviceChange,deviceNext)`), 1811-1813 (the `sealedWithout` comment); removed: old 1806-1807 (the exemption).
- `StepEquations`: the row at 1485-1493; removed: old 150-162, 349-360, 948-950 and 1614-1616 (the remote suctions).
- `BalanceStart`, not a hot file: comment at 514-519; removed: old 372-384 (`suctionAnchor`) and 558-572 (the decision block).
- `MoverSuction` (`suctionLimit`), `DeviceModes` (`lawBelowSuctionLimit`), `ControlConstraints` (`inSeries`).

**Open finding, not fixed: the balance's false root below a one-way outlet.** `BalanceStart.BalanceNet.flow` (line 418)
gives a connection nothing in a refused direction. A junction whose inflow is zero in the balance and whose outlet is an
open one-way connection into a held end (a void, a tower port) therefore has net zero at every pressure below that end. The
damped Newton can step there (the M2 crossing of `VALVE_HOLD_TRACE.md`, `solveOnce`), and `converged` (lines 562 and 586)
accepts it. F2 removes the path case a reached (the balance's prescribed zero through a suction-limited pump). Other
starts can still reach it: a pump that arrives on its suction limit (line 345, nothing prescribed through it), a closed or
vapour-locked mover upstream of an open valve, a regulating flow valve far below its line's open flow (A2's charge valve;
C3 refuses that case only where the balance reports unconverged). C1, which bounded the crossing, was withdrawn.

**Later simplification for the owner, not built.** The pump's law and its suction limit as one complementarity row: the
minimum of the law's residual and the suction margin, bounded at zero flow. `PUMP_SUCTION_LIMIT` would leave the active set
altogether, with E1 and `cavitates` folded into the row.

**Not verified:** the review world (the main thread runs it); fluid GameTests and the campaign, which `affected.mjs` marks
due (BalanceStart changed) and which belong to the final lane; the pass counts of the D14/D15 seeds against their old values.
