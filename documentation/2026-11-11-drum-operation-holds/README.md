# Drum operation holds

Status: **In progress since 2026-11-11**. Branch `claude/nice-archimedes-0816d4`, cut from `claude/minecraft-time-delta-eval-997e82` (`f9a493084`, not merged). Defects 2, 3 and 5 fixed; 1 and 4 wait on the owner's options.

## Scope

Owner, 2026-11-11: the drum-operation defects the time-factor batch (`2026-11-11-minecraft-time-delta`) found while testing a level-controlled drum. They occur at factor 1, `main`'s behaviour, so they get their own batch. Already fixed there and not part of this batch: the regulating flow valve on a dry supply (option 1) and the level law's first decision.

1. The gas-vent setup hold (an empty drum between a 1 atm nitrogen generator and a void).
2. "HELD: round deadline or worker failure" without the worker's failure.
3. The cold start under a gas-filled drop leg (`DrumDrainTest` case 3).
4. A liquid-full drum that stays full (finding 3).
5. The server crash on a restored certificate (`IslandCoordinator.materialise` re-entered).

## Documents

- [DRUM_HOLDS_REVIEW.md](DRUM_HOLDS_REVIEW.md): defects 2, 3 and 5. Expected behaviour, what the code did, the fix and its proof.
- [GAS_VENT_REVIEW.md](GAS_VENT_REVIEW.md): defects 1 and 4. Diagnosis and the options for the owner.
- Tool: [tools/drum-operation-holds/](../../tools/drum-operation-holds/README.md), the headless gas-vent rig.

## History

- 2026-11-11, `9c05850bd` (defect 5): `IslandCoordinator.Ports.decide()` at the engine's decision points, as `Shafts` has; `Ports.booked` records only, and `ColumnOperation` runs a waiting cycle in `decide()`. `ColumnRunTest.aHorizonPassedBeforeItsDeadlineFiresWithACycleDueBehindIt` failed before with the server's stack; Column*, `IslandCoordinatorTest`, `StillRuntimeTest`, `SteamEjectorRuntimeTest` pass.
- 2026-11-11, `aac765065` (defect 2): a completion without a result carries why (`completed(attempt, result, failure)`): "worker failure: Type: message" with its stack logged, the terminal status, or "round deadline". `WorkerFailureStatusTest` fails with the old text.
- 2026-11-11, `e0a82728f` (defect 3): `BalanceNet.rest` moves a junction that nothing flows through back toward its own pressure. `DropLegColdStartTest` holds without it. Chain-100 exact, the 33 junction lines equal, hold gates pass.
- 2026-11-11, `9af97a3e6` (defect 3): `rest` narrowed to a junction the hull's clamp left on its bound (cold seed 65, new in the first quick check, E5). Quick check 1-200 at factor 1: the batch's recorded run exactly (cold 197, bundled 199, defaults 200). Chain-100 exact, the junction lines equal.
- 2026-11-11 (defects 1 and 4, no code): level loop at factor 1 with the generator vent, defects 2, 3 and 5 fixed: the same setup holds. Defect 1 is a mint convention (the feed's water seeded through an empty drum); options (a) to (c). Defect 4 is correct on a void vent (a rigid full drum cannot drain without gas), and a generator vent only gives gas; options (A) to (C). Written up in GAS_VENT_REVIEW.md.
- 2026-11-11 (defect 1, no code): the leg's segment read from the dump. Pass 0 decides it at q0 = 0, so the drum's 60 µL of trace water is marginal and the leg runs at water's density (470 to 485 kg/s); a failed pass never re-decides it, and `drainedSeeds` reads only pinned phases. Not forced by D11 as written: option (d), next to (a). `GasVentSequenceTest` (the setup's sequence, headless) is clean in 3 cases; it lacks the dump's drum water and 70.7 kPa foot.
- 2026-11-11 (defect 1, option (d), owner "Yes, option (d)"): `Equations.failedSegments` re-decides the segments from a failed iterate, before `runsDry`. 115 changed-class tests, hold gates 58, chain-100 exact, the 33 junction lines equal. No step-level gate (a rig from the dump commits without the rule). The level loop on the gas vent still holds: all three cases now "round deadline"; the leg still asks 46 to 782 kg/s under every segment, driven by the foot's water and the open outlet. Quick check not run. Committed for the owner's keep-or-revert (`186c36d96`).
- 2026-11-11 (defect 1): (d) reverted (owner "Revert (d), build (a)"): no gate test, the hold remained, a failed step cost more passes. `PassiveStepSolver` as at `9af97a3e6`.

## Code areas

`runtime/fluid/IslandCoordinator` (`Ports.decide`, `completed` with a failure, `missing`), `ColumnOperation` (`booked`, `decide`), `MinecraftFluidRuntime` (`failure`); `science/fluid/network/PassiveStepSolver` (`BalanceNet.rest`, `still`, `solveOnce`). Tests: `ColumnRunTest`, `EventPathWorld`, `WorkerFailureStatusTest`, `DropLegColdStartTest`.

## Open items

- Defect 1: option (a) (owner, 2026-11-11), in progress.
- Owner: defect 4, option (A), (B) or (C) for the rig; recommended (A), after defect 1. `LevelLoopGameTests` keeps its fill at a tenth and its ramp until then.
- Not run: the fluid and column GameTests (the final lane's); the kept crash world `world-crash-147499` (the JUnit reproduces its stack).
- Seen, not this batch's: `IslandCoordinator.budgetHold` counts a missing result as a budget hold whatever its cause, so a worker exception is retried as one.
- Before the merge: CHANGELOG, version, translation (no lang change), code guide (`fluid-runtime.md`: Ports.decide; `fluid-science.md`: BalanceNet.rest), the final lane. The time-factor batch merges first or with it.
