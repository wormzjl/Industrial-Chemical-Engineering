# valve-hold-probes

Purpose: reproduce a column outlet line's first flowing slices in a unit test, from the states the review world dumps.

Batch: `documentation/2026-11-02-valve-hold/`.

## How to run

In a scratch worktree that merges `claude/valve-hold` and the column branch:

1. Apply `held-dump.patch`. It logs every island on `HELD: Substep` as `HELD_DUMP`, with node states and pipes.
2. Run `./gradlew runReviewWorldServer` and save the log as `tmp/review/run2.log`, and a later one as `tmp/review/run3.log`.
3. Copy `OutletProbeTest.java` to `src/test/java/com/wormzjl/icheme/runtime/fluid/`.
4. Run `./gradlew test --tests '*OutletProbeTest*'`.

## Entry points

- `OutletProbeTest.probe`: one slice from an idle nitrogen line, at 0.1 and 1 kg/s, and a line laid with the product in it. Writes `tmp/review/outlet-probe.txt`.
- `OutletProbeTest.secondSlice`: two slices, with a new target, a new composition, or neither. Writes `tmp/review/second-slice.txt`.

It needs the column branch's `PassiveStepSolver.ports` and `PortPlan`, so it does not compile on `main`.

## History

- 2026-11-02: written for the valve hold; found that the liquid outlets held, and that the cause was a set point read as a new network. Never a tracked test.
- Logs: `tools/valve-hold-probes/logs/valve-hold-logs-2026-11-02.tar.gz` in the main checkout.
