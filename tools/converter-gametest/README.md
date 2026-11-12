# converter-gametest

Purpose: run the fluid GameTests `ConverterGameTests` (a liquid converter converting both ways through its Forge port, a gas converter taking its valves) alone on the review server, which filters by batch (`ReviewBatch`), without the whole fluid GameTest suite.

Batch `2026-11-12-fluid-conversion` ([README](../../documentation/2026-11-12-fluid-conversion/README.md)). Status: in use since 2026-11-12. Not a gate: the gate is `ConverterGameTests` itself, in the fluid GameTests of the final lane.

```bash
bash tools/converter-gametest/run.sh
```

Entry points:

- `run.sh`: `runReviewWorldServer -PreviewBatch=converter`, at most 4 workers.
- `converter.init.gradle` attaches `src/` to the `fluidGameTest` source set for one invocation.
- `src/.../ConverterRun.java`: the two cases in the `icheme_review` namespace (batches `converter-liquid`, `converter-gas`, both selected by `converter`).
- Log: `tmp/fluid-conversion/converter-gametest.txt`; the script prints the `converter_gametest` lines and the GameTest summary.
- The review world persists in `run/review-world`; a failed run leaves its plant standing there.

## History

- 2026-11-12: created (W3).
