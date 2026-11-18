# Pollution bench

Purpose: time the pollution field's step and the ground's step on fixed fields, and hold the field's bits while the step is optimised. Batch: [`2026-11-18-pollution-propagation`](../../documentation/2026-11-18-pollution-propagation/README.md).

## How to run

From the worktree root, after `./gradlew compileJava`. A timed run runs alone: no other Gradle run, test worker or dev client on the machine.

```
java -cp build/classes/java/main tools/pollution-bench/Bench.java air-4096 60 60
java -cp build/classes/java/main tools/pollution-bench/Bench.java counts
java -cp build/classes/java/main tools/pollution-bench/Digest.java
```

## Entry points

- `Bench.java <case> [warm-up s] [measured s]`: steps per second and allocated bytes per step (the thread's allocation counter). Each step starts from the same field, so the work per step is constant.
  - `air-512`, `air-4096`, `air-8192`: `PollutionField.step` on a block of 8 × 8 × 8, 16 × 16 × 16 and 32 × 16 × 16 cubes (80 % open, 12 % enclosed, 5 % thin, 3 % closed), every cube above the cutoff, two releases; the 8192 case stands at the default cap.
  - `wanted-8192`: the server thread's share of a pass at the cap: `PollutionEngine.core()` and `wanted()`, the sets the environment's cells are built from (`PollutionService.environment`).
  - `ground-4096`: `GroundField.step` on 64 × 64 columns with compounds on a third of them, two water columns, one deposit.
  - `digest`: the bits of five chained steps of each air case (the large front and the cap at 8 192), for comparing an optimised step with the old class compiled apart (`javac -d <folder>` of the old `PollutionField.java`, that folder first on the class path).
  - `counts`: one step of each air case, with the environment's `cell` calls counted.
- `Digest.java`: the digest of `PollutionDriftTest.digest` (the same scenario and hash) for riseShare 0 and 0.12. With the settings of before the drift (nine fields) it prints the rise-0 digest only.

## History

- 2026-11-18: made for P1 and P2 of the propagation batch: the rise-0 digest `6450680631e3021f` taken from the field before the drift (the compiled classes of `65fee5618`), the default digest `5ae9aaa2c9acc5c5` after it.
- 2026-11-18: the air cases' digest (`d777d6a3223eeb28`, `963b630fa76779af`, `d6578d868ef2a9a6` for 512, 4 096 and 8 192, the same before and after the optimisation) and the `wanted-8192` case; the before and after table is in the batch's review.
