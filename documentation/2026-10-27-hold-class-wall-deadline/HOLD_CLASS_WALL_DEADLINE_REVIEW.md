# HoldClassWorldsTest wall deadline review

## Expected behaviour

The closure world where a tank drains to a void should carry the no-flow closure across the interval start and keep the island up without a hold.

## Observed behaviour

The screenshot's `slowGates` report recorded `HELD: wall deadline` at ticks 4175 and 4275. The failing case took 28.625 s inside a 40-test class; the configured island wall budget is 2 s.

## Verification

The exact class was rerun alone with:

```text
./gradlew slowGates --tests com.wormzjl.icheme.runtime.fluid.HoldClassWorldsTest --no-daemon
```

It passed all 40 tests (`failures=0`, `errors=0`) in 1m28s. The failure is therefore not reproduced as a deterministic physics or closure regression.

## Proposal

Keep the 2 s wall budget and the assertion. If the failure repeats in an isolated lane, add timing diagnostics around the island solve at ticks 4175/4275 and inspect host contention; do not hide it by weakening the hold classification.
