# psv-flare

Batch `2026-11-11-gas-blanketing`. Runs the fluid GameTest `ReliefFlareGameTests` (a blocked-in drum relieving through its pressure safety valve to a flare stack) alone on the review server, which filters by batch (`ReviewBatch`).

```bash
bash tools/psv-flare/run.sh
```

- `psv-flare.init.gradle` attaches `src/` to the `fluidGameTest` source set for one invocation.
- `src/.../ReliefFlareRun.java`: the test in the `icheme_review` namespace, batch `psv-flare`.
- Log: `tmp/psv/psv-flare.txt`; the script prints the `psv_flare result` line and the GameTest summary.
- The review world persists in `run/review-world`; a failed run leaves its plant standing there.

## History

- 2026-11-11: created. Measured: methane 5 bar, 0.15 m discharge, 0.3 m tip: drum at its set pressure, 0.612 kg/s flared. A 0.1 m tip holds the island (PSV_REVIEW.md section 4).
