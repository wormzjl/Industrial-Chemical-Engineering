# Drum operation holds: headless rigs

Batch: `2026-11-11-drum-operation-holds` ([README](../../documentation/2026-11-11-drum-operation-holds/README.md)). Status: in use since 2026-11-11.

| File | What |
|---|---|
| `src/.../GasVentDrainTest.java` | Defect 1's headless check (GAS_VENT_REVIEW.md): `DrumDrainTest`'s drum, feed and outlet over `EventPathWorld`, built at once with the feed at 3 bar, its vent on a 1 atm nitrogen void or generator, the outlet valve at 10 kg/s or an open leg. Prints `GAS_VENT` lines and `GAS_VENT_RESULT` (held or clean); asserts nothing. `-Ddrain.case=<label prefix>` runs one case. |
| `src/.../GasVentSequenceTest.java` | The level loop's setup sequence headless: the feed generator at 42.6 kPa behind its valve at 19.81 kg/s, `-Ddrain.settle` ticks (default 1), then 300 kPa; prints `GAS_VENT_SEQ_BEFORE` (the island) and `GAS_VENT_SEQ_RESULT`. Asserts nothing. |
| `rigs.init.gradle` | Attaches `src/` to the test source set for one invocation. Not part of any gate. |

Run:

```bash
./gradlew --offline --no-configuration-cache --init-script tools/drum-operation-holds/rigs.init.gradle test --tests '*GasVentDrainTest'
```

The failure probe (`tools/minecraft-time-delta/patches/probe-feed-step.patch`, applied for a run and reverted) prints the rows of each failed Newton when `icheme.probe.failures` reaches the test JVM.

## History

- 2026-11-11: written for defect 1. All three cases ran clean on `9af97a3e6`: the level loop's hold needs its setup sequence.
- 2026-11-11: `GasVentSequenceTest`. Clean in all three cases; its drum holds no water at the charge, unlike the dump's.
