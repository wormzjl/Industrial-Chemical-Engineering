# Minecraft time as the simulation step

Status: **In progress since 2026-11-11** (W1-W6 built and measured; W7 and the merge open). Branch `claude/minecraft-time-delta-eval-997e82`, from `ff6f6892f` (0.26.0).

## Scope

Owner, 2026-11-11: evaluate running the simulation on Minecraft time (72 process seconds per real second) while the calculation interval stays 5 s of real time, and whether so much change in one step breaks convergence.

## Documents

- [TIME_SCALE_PLAN.md](TIME_SCALE_PLAN.md): the implementation plan for a time factor (`fluid.timeScale`, 1 to 72, default 4): work packages W1-W7, the owner's decisions T1-T6, saves, the known holds at 72.
- [OPTION_A_REVIEW.md](OPTION_A_REVIEW.md): option A prototyped and measured at 1x and 72x (it clears nothing), the stuck row (a junction's enthalpy row, stated over its 0.05 s buffer), the holdup scaled with the time factor, and the options.
- [HOLD_STUDY_REVIEW.md](HOLD_STUDY_REVIEW.md): every hold of the 72x quick check, its cause, the hypothesis runs and the options on the Newton tolerance and the wall budget.
- [TIME_SCALE_BUILD_REVIEW.md](TIME_SCALE_BUILD_REVIEW.md): W1-W6 as built: factor 1 against the old engine, the quick checks at 1, 4 and 72, the campaign at 4 against the 0.26.0 lane, the headless worlds, the fluid GameTests, the owner options.
- [TIME_SCALE_REVIEW.md](TIME_SCALE_REVIEW.md): the expected behaviour in numbers, what the code does with a slice, the 200-seed quick check at 72x in three step forms, the impact outside the island solve, and the options for the owner.

## History

- 2026-11-11: review written. Measured with a temporary probe (`tools/minecraft-time-delta/patches/probe-time-scale.patch`, applied and reverted; `src/main` unchanged):
  - 1x baseline and three 72x forms on the bundled, cold and defaults distributions, seeds 1-200, 8 threads, alone (11 runs);
  - one replay alone of form C's five new wall-deadline holds (bundled 15, 47, 77, 118, 123): four stand, 118 is clean.
- 2026-11-11: owner "why 2s budget at all? The engine iteration time is 5s" and "yes, study all the failed cases". Every held case traced with a single-seed study probe (step trace, stack sampler, the rows a failed Newton ends on); four hypothesis switches on the five bundled seeds; the tolerance switch on seeds 1-200 at 72x (bundled 197, cold 199 clean). Written up in HOLD_STUDY_REVIEW.md.
- 2026-11-11: owner "Yes, option A". Prototyped as a probe switch and measured on seeds 1-200 at 1x and 72x: harmless at 1x, the same worlds hold at 72x. The stuck row identified (junction enthalpy, amount form over the buffer); `HOLDUP_TAU` scaled with the factor (3.6 s) clears bundled 123, 77, 164 at 72x with the tolerances untouched; seed 15 stays (an equation-gate miss of 1.0e-8 to 1.7e-8 on a fugacity row). Written up in OPTION_A_REVIEW.md.
- 2026-11-11: owner "Holdup only" (the junction holdup scales with the factor; no tolerance or gate change). Plan written in TIME_SCALE_PLAN.md; nothing implemented.
- 2026-11-11: owner "Make the time scale configurable, 4x as default, 72x max; equipment wear should be in line with scale, adjust the default rate if too fast; others as recommended". Recorded as T1-T6 in the plan: default 4; wear on process time with default lives 8 h and 40 min of process time (2 h and 10 min of play at 4, as today) under new config keys; FE at the play-time rate; fuel on process time; level control on the cadence; the column's cycle counts kept.
- No test of the suite ran: no production or test file changed.
- 2026-11-11, W1 (`c7a0e4ddd`): `SimulationTime` (one conversion of ticks to process seconds), `fluid.timeScale` (1 to 72, default 4, read at server start), the factor on the model and the coordinator, in the model revision (`:time=<f>` when not 1) and in the checkpoint (`TimeScale`; another factor at load takes the facts route). 218 tests in 22 classes, chain-100 exact, the 33 junction lines equal the 0.26.0 lane.
- 2026-11-11, W2 (`6a3642338`): the interval's first step and step cap and `HOLDUP_TAU` scale with the factor; the hold-fuzz switch `-PholdFuzzTimeScale`. Chain-100 exact, junction lines equal, 7 tests.
- 2026-11-11, W3 (`ad0c21634`): the column's inlet aim, buffer and condenser hold, the still's hold and refill, the electric stores' windows as counts of cycles (T6). 127 tests.
- 2026-11-11, W4 (`a64fcc57d`): FE at the play-time rate (an FE carries joulesPerFE x factor), wear on process time with default lives 8 h and 40 min under `machines.wear.cavitationProcessHours` and `dryProcessMinutes` (T3), fuel on process time. 44 tests.
- 2026-11-11, W5 (`93985cfa5`): status lines say "s of play", the pump page shows its life left, the store's "Lasts" is play time. 155 tests.
- 2026-11-11, W6: the quick checks at 1, 4 and 72, the campaign at 4 (clean equal to the 0.26.0 lane in every distribution; three new wall-deadline holds, clean alone), the column and heat-pair worlds for 1 h of process time at 4 and 72, the fluid GameTests at 4 (88 pass, after `FluidCheckpointGameTests`' restarted coordinator took the factor). Written up in TIME_SCALE_BUILD_REVIEW.md. Test-rig hooks: `EventPathWorld.time` and `ExchangerCouplingTest` on the model's factor (factor 1 unchanged; `ExchangerCouplingTest` passes).

## Code areas

Changed in W1-W5: `science/fluid/SimulationTime` (new), `FluidThermodynamics`, `IChemE` (config), `runtime/fluid/FluidWorldAuthority`, `IslandCoordinator`, `IslandClock`, `IslandCertificate`, `FluidCheckpointCodec`, `FluidCheckpointStore`, `ColumnOperation`, `Stills`, `FiredHeaters`, `PrimeMovers`, `PumpHealth`, `ShaftTrains`, `ElectricStores`; `runtime/control/ControlLaw`; `science/fluid/network/PassiveIntervalSolver`, `PassiveStepSolver`; `science/machine/ElectricStore`, `Wear`; the device and machine views, `FluidDeviceScreen`, `en_us.json`. Read in the evaluation: `runtime/fluid/IslandClock`, `IslandCertificate`, `IslandCoordinator`, `ColumnOperation`, `Stills`, `ElectricStores`, `PumpHealth`; `runtime/control/ControlLaw`, `InventoryControl`; `science/fluid/network/PassiveIntervalSolver`; `science/machine/ElectricStore`, `Wear`.

## Open items

- Decided: T1-T6 of TIME_SCALE_PLAN.md section 3. Built: W1-W6. Open: W7 and the merge.
- Owner options from W6 (TIME_SCALE_BUILD_REVIEW.md section 6): the column's outlet buffer at high factors (2.4 h of production at 72), the heat-coupling startup lag on unequal cadences.
- Not built: the level loop on a drum (needs a server; no GameTest builds one).
- Decided: `HOLDUP_TAU` scales with the factor, alone (owner "Holdup only"). Option A is dropped; the tolerance change stays an option in OPTION_A_REVIEW.md.
- Not traced: bundled 15's last cause under the larger buffer; cold 6 and 183, new under it.
- Hold classes the longer process time reaches, for their own batches: bundled 47 (liquid drying out in a gas tank), 164 (gas vanishing in a water junction), cold 72 (non-finite fugacity derivative at 111 K).
- Not measured in a world: stills, control loops, shaft trains (their windows have unit tests at 4 and 72).
- The run reports are in this worktree's `tmp/time-scale/` (git-ignored); their summary lines are tracked in `tools/minecraft-time-delta/results/summary.txt`.
