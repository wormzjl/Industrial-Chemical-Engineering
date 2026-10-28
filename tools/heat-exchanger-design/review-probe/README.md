# review-probe

Purpose: reproduce the owner's test of the heat review world on a GameTest server (batch [`2026-10-27-heat-exchanger-design`](../../../documentation/2026-10-27-heat-exchanger-design/README.md), [`OWNER_TEST_REVIEW.md`](../../../documentation/2026-10-27-heat-exchanger-design/OWNER_TEST_REVIEW.md)). Two modes of `HeatExchangerReviewWorld` on the `reviewWorldServer` run:

- `true` (probe): build the review plant as `runReviewWorldServer` does, run it 60 s, then apply the owner's changes of 2026-10-28 in two stages and log every unit every 5 s (`review_probe` lines):
  - stage 1: the fired heater's feed generator at 25 °C and 100 kPa, its void still at 10 bar; 40 reports;
  - stage 2: the heater's void at 100 kPa and its pump at 0.01 m³/s; 40 reports, with the coupling and certificate counters.
- `owner`: build nothing; watch the world already in `run/review-probe/world` (a copy of the owner's save). It logs every heat side, generator, void and pump with its settings once, then their status every 5 s for 150 s (`review_owner` lines). The column review plant does not build in this mode.

Both run in `run/review-probe/`, not in `run/review-world/`, so the review world build output is not overwritten.

Not a gate. Removed from the tracked paths in `f20ceefa` (the batch's tooling cleanup). The plain builder stays in `HeatExchangerReviewWorld` and `runReviewWorldServer` (`REVIEW_WORLD.md`).

## Files

- `HeatExchangerReviewWorld.java`: the builder with both modes, as in `f20ceefa^` (`bf9865a1`). `git show f20ceefa^:src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest/HeatExchangerReviewWorld.java` gives the same bytes.
- `review-probe.patch`: `git diff f20ceefa f20ceefa^ -- build.gradle src/fluidGameTest`. It re-attaches the three parts against `f20ceefa`:
  - `build.gradle`, run `reviewWorldServer`: the Gradle property `reviewProbe` (`false`, `true` or `owner`), the game folder `run/review-probe` for `true` and `owner`, and the system property `icheme.review.probe`;
  - `HeatExchangerReviewWorld`: `PROBE_MODE`, `PROBE`, `OWNER`, `watchOwnerWorld`, `Rig.probe`, `Rig.probeDone`, `Rig.respec`, the positions they read, and the timeout 12000 ticks (3000 without the probe);
  - `ColumnReviewWorld`: the owner-mode hook (the column plant does not build).

## How to run

1. Re-attach, from the worktree root of a checkout at `f20ceefa` or later (later commits may need hand merging):

   ```bash
   git show <commit>:tools/heat-exchanger-design/review-probe/review-probe.patch | git apply
   ```

   `<commit>` is any commit that holds this folder. Read the patch from git, not from the working copy: on an autocrlf checkout the working copy has CRLF line endings, and `git apply` rejects it for the LF source files.
2. Probe mode: `./gradlew runReviewWorldServer -PreviewProbe=true`. Owner mode: first copy the owner's save folder to `run/review-probe/world`, then `./gradlew runReviewWorldServer -PreviewProbe=owner`.
3. Read the `review_probe` or `review_owner` lines in `run/review-probe/logs/latest.log`. Holds show in the engine's own warnings.
4. Undo with `git checkout -- build.gradle src/fluidGameTest`.

## History

- 2026-10-28, `1a339b18`: written for the owner's test (OWNER_TEST_REVIEW.md sections 1 to 4). It reproduced the fired heater's hold at a 100 kPa generator (section 3, cause found by A/B with one rule reverted) and the exchanger's wake ping-pong (section 4). It measured F1 (section 1): 701.5 kW and 41.8 °C out; wet steam at 180.6 °C from the convection coil.
- 2026-10-28, `968a6bf7` and `627d4c47`: the fixes and F1 checked with it: stage 1 steady at no flow in 81 s, stage 2 steady at 9.97 kg/s, no hold; no coupling wakes in the first 170 s.
- 2026-10-28, `342c6d22`: the main 0.16.2 sync replaced the one-block pump with the In-line Pump in the plain builder; the probe reads the pump on its pipe.
- 2026-10-28, `f20ceefa`: detached to this folder (tooling cleanup).
- Logs and A/B scripts of these runs are scratch in the batch worktree's `tmp/owner-fixes/` (`probe-f1.log`, `probe-fixes.log`, `probe-ab-oldjunction.log`, `ab-revert.js`). They are not archived.
