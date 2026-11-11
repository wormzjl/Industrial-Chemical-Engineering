# psv-flare

Purpose: run the fluid GameTest `ReliefFlareGameTests` (a blocked-in drum relieving through its pressure safety valve to a flare stack) alone on the review server, which filters by batch (`ReviewBatch`), without the whole fluid GameTest suite.

Batch `2026-11-11-gas-blanketing` ([README](../../documentation/2026-11-11-gas-blanketing/README.md), PSV_REVIEW.md). Status: in use since 2026-11-11. Not a gate: the gate is `ReliefFlareGameTests` itself, in the fluid GameTests of the final lane.

```bash
bash tools/psv-flare/run.sh
```

Entry points:

- `run.sh`: `runReviewWorldServer -PreviewBatch=psv-flare`, at most 4 workers.
- `psv-flare.init.gradle` attaches `src/` to the `fluidGameTest` source set for one invocation.
- `src/.../ReliefFlareRun.java`: the three cases in the `icheme_review` namespace (batches `psv-flare`, `psv-flare-small`, `psv-flare-inert`, all selected by `psv-flare`): methane to a 0.3 m tip, methane to a 0.1 m tip, nitrogen to a 0.1 m tip.
- Log: `tmp/psv/psv-flare.txt`; the script prints the `psv_flare result` lines and the GameTest summary.
- The review world persists in `run/review-world`; a failed run leaves its plant standing there.

## History

- 2026-11-11: created. Measured: methane 5 bar, 0.15 m discharge, 0.3 m tip: drum at its set pressure, 0.612 kg/s flared. A 0.1 m tip held the island (PSV_REVIEW.md, D6).
- 2026-11-11, D6 form A2 (`36aab2d14`, later deleted): the methane and nitrogen 0.1 m cases added; the drum rose to 4.93 and 4.87 bar.
- 2026-11-11, D6 (the flare follows its back pressure, `d647d331d`): the 0.1 m cases renamed; no hold. 0.3 m tip 0.612 kg/s FLARING; 0.1 m tip at set, 0.593 kg/s FLAME OUT; nitrogen 0.1 m at set, 0.936 kg/s FLAME OUT.
- 2026-11-11, merge preparation: checked; nothing moved.
