# Valve follow-ups review (items 1 to 5)
Branch `claude/valve-followups-build-e04fd4` from `178d7e2f4`. One commit per item. Times not recorded (no performance task).

## 1. Direct blanketed start (`a095caa25`)
- **Expected.** An empty drum on its design feed fills to its level setpoint; the pad holds the gas at set while the drum fills or drains.
- **Code.** Not reproduced. The level loop's direct start on `178d7e2f4` at factor 4 (`level-loop/run.sh 4`, blanket and direct switches) runs clean: 121.3 to 159.6 kPa, 50.00 % at the step and the end. The hold of the gas blanketing batch's 16:51 run (`tmp/gas-blanket/level-loop-4x2-blanket-direct-held.txt`) does not reproduce: since that run the valve and its supply line moved twice (`4c3130472`, `9777378a4`) and the relief commits changed the compile. Not searched for (orchestrator: keep option A).
- **Fix.** Gates only (the orchestrator chose option A): GameTest batch `level-loop-blanket-direct`; `BlanketDrainWorldTest` (factor 4), where the drum fills shut to 309.8 kPa and then drains wide open to 0.2 % with no hold.
- **Ran.** `BlanketDrainWorldTest`, `compileFluidGameTestJava`. The GameTest runs in the final lane.

## 2. Vent lag on a fast fill (`dc1d80f2b`)
- **Expected.** The liquid's inflow compresses the gas over the step: at 0.2 m³/s into 0.8 m³ of room, a quarter of the gas per second. A vent that answers only the start state lags by one response.
- **Code.** The pad forecast the room the liquid vacates. The vent did not forecast the room the liquid takes.
- **Fix.** `GasBlanket.gap` is one law for both ends of the band: the gas the vessel lacks, or holds in excess, closed over the response divided by gamma, plus the gas of the room its liquid vacates or takes. The per-step plan computes the forecast once (`GasBlanket.inflows`) and gives it to the vent's release terms (`PassiveStepSolver.blanketInflows`). There is no new mode, unknown or saved field.
- **Numbers.** Fast fill (`GasBlanketTest`): peak 157.52 kPa against the 157.50 kPa vent pressure (264.2 before). Moles booked within 1e-9.
  - Level loop at factor 4: 121.3 to 127.4 kPa in both the standard and the direct start (171.8 before).
  - Drain: 121.3 to 122.9 kPa; the gate moved from 0.95 to 0.97 of set.
- **Ran.** 8 classes (53 tests) and `level-loop/run.sh 4`, twice.

## 3. Relief run into a second regulator (`8a3d2927b`)
- **Expected.** "Lower target wins": a sink that takes less than the relief sets the flow. The PSV stands wide open and the drum rises; otherwise the PSV holds set.
- **Code.** With both regulating, the junction pressure between them was in no row. The pass was singular (probe: PSV regulating, fuel valve 0.90 kg/s through a 0.5 kg/s target) and the island held.
- **Fix.** No compile change and no lag were needed. `ControlConstraints.series` groups a pressure valve with the flow regulators in its junction-reduced series. In the pass loop, the flow regulator regulates first and opens the pressure valve. A pressure valve beside a closed set-flow mover stands open or reseats. One active-set change per pass (A272); no saved field. This is a pass-order rule, so the owner may choose another form.
- **Numbers** (drum set 300 kPa, 5 bar feed; drum pressure, relief): burners 0.5 kg/s 495.5 kPa, 0.500 kg/s; burners 3 kg/s 300.0 kPa, 1.111 kg/s; blanketed drum drawn 0.3 kg/s 498.4 kPa, 0.292 kg/s; drawn 3 kg/s 300 kPa, 1.11 kg/s; compressor 0.05 m³/s 498.6 kPa, 0.270 kg/s; 1 m³/s 300.0 kPa, 1.111 kg/s. All six clean.
- **Ran.** 17 classes (148 tests); `holdGates` `HoldClassWorldsTest` and `JunctionSeedingGateTest` (48 tests).

## 4. Solids block on a relief run into a flare (`7e8ed0a8c`)
- **Expected.** Solids plug the tip (X5), so nothing reaches the flare and the drum rises above set.
- **Code.** The flare's plan covered only its own tip valve. A safety valve's single line (D6) had no block.
- **Fix.** `Flares.plans` plans that line too.
  - `ShaftPlan.apply` closes it both ways on its fitting mask (`forbiddenDirections` 3) while the plan is blocked, and sets it back to 0 when the plan opens.
  - A solids closure (`blockedDirections`) would not work: an interval start lifts it (D33). The compile leaves the fitting mask free on this line, because its two ends are the vessel and the flare, not check valves.
- **Numbers.** Relief 0; the drum rises from 300.0 to 500.1 kPa in 20 s; the flare reads BLOCKED. When clean again, the line opens.
- **Ran.** `ReliefValveWorldTest`, `FlareRuntimeTest`, `ShaftPlanTest`, `FiredHeaterFuelTest` (26 tests).

## 5. Campaign factor (`ea8c63ef6`)
- **Fix.** `SimulationTime.DEFAULT_FACTOR` (4) is the default of `fluid.timeScale` and of both harnesses. The init scripts forward `-PholdFuzzTimeScale` only when it is given, and SUMMARY records `factor=`.
  - `compare.mjs <base> <head> [<campaign base>]` prints and checks both factors.
  - `lane.sh` is unchanged.
- **Baseline.** The W6 campaign at 4 (clean 991 / 990 / 1000) is archived in the main checkout at `tools/minecraft-time-delta/logs/time-scale-campaign4-logs-2026-11-11.tar.gz`. It predates `factor=`, so it reads as "not recorded".
- **Ran.** `SimulationTimeTest`; `compare.mjs` (0.27.0 lane against campaign4). The campaign itself did not run.

## Quick check (factor 4, seeds 1 to 200, all distributions)
`bash tools/minecraft-time-delta/factor.sh vf4 1-200 "bundled cold defaults" 4` on `ea8c63ef6`, against the stored W6 q4 reports (TIME_SCALE_BUILD_REVIEW.md): bundled clean 197 to 198 (118 F fixed; 145 E9, 47 F as before), Newton solves 28 921 to 29 129, iterations 133 067 to 133 849; cold 196 to 196, the same four (85 E1, 181 E2, 101 E5, 118 F), solves 65 187 to 65 194; defaults 200 to 200, counters identical. Refusals 0. No new hold, so no new C or F hold to replay.
