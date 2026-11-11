# 2026-11-11 valve follow-ups

Status: Implemented 2026-11-12, merged to `main` as 0.27.1 (not pushed); built on `claude/valve-followups-build-e04fd4`. Branch `claude/valve-followups`, cut from `main` at `324ce9284` (0.27.0).

Purpose: the items left open when 0.27.0 merged (owner, 2026-11-11: "fix the issues").

## Items

| # | Item | Source | State |
|---|---|---|---|
| 1 | A blanketed drum started empty on the design feed holds ("Newton line search stalled", active-set cycle) | [GAS_BLANKET_REVIEW.md](../2026-11-11-gas-blanketing/GAS_BLANKET_REVIEW.md) section 5, item 1 | done: not reproduced on `178d7e2f4`; gates `level-loop-blanket-direct`, `BlanketDrainWorldTest` (`a095caa25`) |
| 2 | The blanketing valve's vent lags a fast fill (171.8 kPa against a 127.4 kPa vent pressure) | same, item 2 | done: one law for pad and vent, the vent with the pad's forecast (`dc1d80f2b`) |
| 3 | A relief run into a firebox's fuel valve, a blanketed vessel or a set-flow mover keeps two regulators around a junction and can hold | [PSV_REVIEW.md](../2026-11-11-gas-blanketing/PSV_REVIEW.md) section 4 | done: the lower flow wins in the pass order, one change per pass (`8a3d2927b`) |
| 4 | A relief run straight into a flare has no solids (X5) block | same | done: the flare's plan closes the line (`7e8ed0a8c`) |
| 5 | The lane's campaign runs at time factor 1; the game's default is 4 | [FINAL_LANE_REVIEW.md](../2026-11-11-gas-blanketing/FINAL_LANE_REVIEW.md) | done: harness default `SimulationTime.DEFAULT_FACTOR` (4); `compare.mjs` campaign baseline (`ea8c63ef6`) |
| 6 | `AGENTS.md` said simulated time is "a nominal 20 TPS" (W7 of the time factor plan) | [TIME_SCALE_PLAN.md](../2026-11-11-minecraft-time-delta/TIME_SCALE_PLAN.md) | done |
| 7 | No in-game check of either valve or of the flare's back pressure | both reviews | the owner starts it |

## History

- 2026-11-11: batch opened. `AGENTS.md`: simulated time is online ticks at a nominal 20 TPS, times the time factor (`fluid.timeScale`, default 4). No test run (a document).
- 2026-11-11, item 1 (`a095caa25`): the direct blanketed start runs clean at factor 4 on `178d7e2f4` (121.3 to 159.6 kPa, 50.00 % at the step and the end); the held run predates two valve moves. Gates: GameTest batch `level-loop-blanket-direct`, `BlanketDrainWorldTest` (drum full at 309.8 kPa, drained wide open). Ran `BlanketDrainWorldTest`, `compileFluidGameTestJava`.
- 2026-11-11, item 2 (`dc1d80f2b`): `GasBlanket.gap`, one law for pad and vent; the vent takes the pad's inflow forecast (`GasBlanket.inflows`, `PassiveStepSolver.blanketInflows`). Fast fill 157.52 kPa against a 157.50 kPa vent pressure (264.2 before); level loop at 4, both starts, 121.3 to 127.4 kPa (171.8 before). Ran 8 classes (53 tests) and `level-loop/run.sh 4` twice.
- 2026-11-11, item 3 (`8a3d2927b`): `ControlConstraints.series` and `PassiveStepSolver.regulatesInSeries`: a flow regulator in a safety valve's series regulates first and opens the valve; A272 kept, no saved field. Six sinks in `ReliefValveWorldTest`, all clean. Ran 17 classes (148 tests) and two hold gates (48).
- 2026-11-11, item 4 (`7e8ed0a8c`): `Flares.plans` plans the safety valve's line into a flare; `ShaftPlan.apply` closes it on its fitting mask while solids arrive. Relief 0, drum 300.0 to 500.1 kPa in 20 s, BLOCKED. Ran 4 classes (26 tests).
- 2026-11-11, item 5 (`ea8c63ef6`): `SimulationTime.DEFAULT_FACTOR` (4) for the setting and both harnesses; SUMMARY `factor=`; `compare.mjs` third folder; the factor-4 campaign archived (main checkout, `tools/minecraft-time-delta/logs/time-scale-campaign4-logs-2026-11-11.tar.gz`). Ran `SimulationTimeTest`.
- 2026-11-11, quick check at factor 4 (seeds 1 to 200, all distributions, `factor.sh vf4` on `ea8c63ef6`): bundled 198 clean (197 in W6's q4; 118 F fixed), cold 196 (the same four holds), defaults 200; no new hold, nothing replayed. VALVE_FOLLOWUPS_REVIEW.md.
- 2026-11-11, merge preparation (owner, through the orchestrator: "To main as 0.27.1"; `0f38afa3b`): CHANGELOG entry, code guide (fluid-science, fluid-runtime, verification) at `ea8c63ef6`, the probe patch to `tools/minecraft-time-delta/patches/`; no translation commit (`en_us.json` unchanged); `main-reach.pl` lists only the three optional Iris/DH bridges of earlier batches.
- 2026-11-12, final lane on `0f38afa3b` (FINAL_LANE_REVIEW.md): 2680 tests pass, chain-100 exact, 33 junction lines identical, 94 GameTests pass, campaign at 4 clean 991 / 990 / 1000 against campaign4 (bundled 431 replayed alone: E6 as before); version 0.27.1, merged to `main`.

## Documents

- [VALVE_FOLLOWUPS_REVIEW.md](VALVE_FOLLOWUPS_REVIEW.md): per item the physics, what the code did, the fix, the numbers and what ran; the quick check.
- [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md): the final lane against the 0.27.0 lane and the factor-4 campaign.

## Code areas

- `science/fluid/network`: `GasBlanket` (`gap`, `inflows`, `plan`), `OpenVessel.releases`, `PassiveIntervalSolver.run`, `PassiveStepSolver` (`blanketInflows`, `series`, `regulatesInSeries`), `ControlConstraints.series`, `ShaftPlan.apply`. `science/fluid/SimulationTime.DEFAULT_FACTOR`, `IChemE` (the setting).
- `runtime/fluid/Flares.plans`.
- Tests: `BlanketDrainWorldTest` (new), `GasBlanketTest`, `ReliefValveWorldTest`, `LevelLoopGameTests`.
- Tools: `tools/unified-thermo-port-forms` (harness default, `factor=`), `tools/merge-lane/compare.mjs`; READMEs of those and of `tools/minecraft-time-delta`.

## Open items

- Item 7: the in-game check, which the owner starts.
- Item 3 is a pass-order rule (the owner may prefer another form); item 4 rests on the compile leaving a relief line into a flare free of check valves.
- This batch's lane (`tools/merge-lane/logs/valve-followups-final-lane-logs-2026-11-11.tar.gz`, main checkout) is the next merge's baseline, its campaign at factor 4 with `factor=` recorded.
- Not in this batch unless the owner asks: the valve item's icon, the Accessory tab on a drum that already shows four tabs, a hand-placed supply pipe beside the rear nozzle's on a 4 m lying drum, no valve on a 1.5 m standing drum.
