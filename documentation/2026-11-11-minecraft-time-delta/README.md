# Minecraft time as the simulation step

Status: **Concluded 2026-11-11** (evaluation; no code change). Branch `claude/minecraft-time-delta-eval-997e82`, from `ff6f6892f` (0.26.0).

## Scope

Owner, 2026-11-11: evaluate running the simulation on Minecraft time (72 process seconds per real second) while the calculation interval stays 5 s of real time, and whether so much change in one step breaks convergence.

## Documents

- [HOLD_STUDY_REVIEW.md](HOLD_STUDY_REVIEW.md): every hold of the 72x quick check, its cause, the hypothesis runs and the options on the Newton tolerance and the wall budget.
- [TIME_SCALE_REVIEW.md](TIME_SCALE_REVIEW.md): the expected behaviour in numbers, what the code does with a slice, the 200-seed quick check at 72x in three step forms, the impact outside the island solve, and the options for the owner.

## History

- 2026-11-11: review written. Measured with a temporary probe (`tools/minecraft-time-delta/patches/probe-time-scale.patch`, applied and reverted; `src/main` unchanged):
  - 1x baseline and three 72x forms on the bundled, cold and defaults distributions, seeds 1-200, 8 threads, alone (11 runs);
  - one replay alone of form C's five new wall-deadline holds (bundled 15, 47, 77, 118, 123): four stand, 118 is clean.
- 2026-11-11: owner "why 2s budget at all? The engine iteration time is 5s" and "yes, study all the failed cases". Every held case traced with a single-seed study probe (step trace, stack sampler, the rows a failed Newton ends on); four hypothesis switches on the five bundled seeds; the tolerance switch on seeds 1-200 at 72x (bundled 197, cold 199 clean). Written up in HOLD_STUDY_REVIEW.md.
- No test of the suite ran: no production or test file changed.

## Code areas (read, not changed)

`runtime/fluid/IslandClock`, `IslandCertificate`, `IslandCoordinator`, `ColumnOperation`, `Stills`, `ElectricStores`, `PumpHealth`; `runtime/control/ControlLaw`, `InventoryControl`; `science/fluid/network/PassiveIntervalSolver`; `science/machine/ElectricStore`, `Wear`.

## Open items

- Owner: the factor (72, an intermediate one such as 12, or 1) and, for a factor above 1, whether FE, wear and fuel follow process time or play time.
- Owner: the near-miss tolerance (HOLD_STUDY_REVIEW.md section 4: option A, B or C). It holds bundled 15 and 123 at 72x and is reachable at 1x.
- Hold classes the longer process time reaches, for their own batches: bundled 47 (liquid drying out in a gas tank), 164 (gas vanishing in a water junction), cold 72 (non-finite fugacity derivative at 111 K).
- Not measured: the running column, stills, control loops, heat sides in two islands, shaft trains.
- The run reports are in this worktree's `tmp/time-scale/` (git-ignored); their summary lines are tracked in `tools/minecraft-time-delta/results/summary.txt`.
