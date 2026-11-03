# W2 forms review: the set-flow movers' law (F1 or F2b) and the machines on their curves

Batch `2026-11-03-rotating-machinery`, W2, 2026-11-04. Branch `claude/realistic-pump-compressor-6fdfd9`: `218f9c5ff` (W2,
forms F1 and F2 behind `icheme.fluid.cornerLaw`), `ffae9fc4d` (F2b). Plan 3.3, 4.2, 4.5; owner decision D1: take F2 if
the quick check measures it no worse than F1.

## 1. Expected behaviour

- A positive-displacement mover (the in-line and legacy movers, a reciprocating compressor) delivers its displacement
  whatever the rise until its relief valve lifts. A relief valve opens over about 10 % overpressure, so the delivery falls
  to zero across the last tenth of the rise: the corner m = 16 gives 98.7 % at 90 % of the relief rise, 88.8 % at 99 %,
  none at 100 %. With the suction above the discharge, a running machine passes a little more than its displacement.
- A centrifugal machine follows its curve (W1): the head falls from 1.21 H_r (pump) or 1.145 of the rated head (compressor).

## 2. What the code did

- **F1** (today's switch, `PUMP_TARGET`, `PUMP_HEAD_LIMIT`, `CLOSED`): `CompressorTest`'s 5 s transfer stops 6.87 Pa short of
  r_max P_s (tolerance 3 Pa). The head-limit pass's trickle loss is under the 0.01 Pa band, so the switch latches `CLOSED`
  on the start state. W2's polytropic ratio limit moved the numbers onto this latch; the latch is the switch's own.
- **F2** (the corner as a norm residual, ‖(u⁺, v⁺)‖₁₆ − 1): a new E2 hold, cold seed 200, singular Jacobian. At no flow and
  no rise, where a mover that cannot beat its discharge starts, the residual has no gradient: the mover's row is empty.
- **F2b** (`ffae9fc4d`): the corner as a rise, finite at every flow: the relief rise at or below no flow, the corner up to
  0.999 of the displacement, then a line with the corner's slope there (−47.9 relief rises per displacement), zero at
  1.0151. The row is head − rise for every mover, head derivative one; 49 lines net deleted. The transfer lands 1.5e-6 Pa
  from r_max P_s (`CompressorTest` with the switch set: three asserts fail, on F1's mode names only).

## 3. Quick check (200 seeds per distribution, 8 workers; baseline: the 0.19.2 lane's seeds 1 to 200)

| Run | Holds: cold / bundled / defaults | Cold: Newton its, Jacobians, backtracks | Bundled: its, backtracks | Defaults: its, backtracks |
|---|---|---|---|---|
| base | E1 85, E6 101, F 118 / F 6 / none | | | |
| F1 | = base / none (6 gone) / none | 346,349, 105,861, 251,657 | 132,825, 35,437 | 13,133, 3,324 |
| F2 | + E2 200 / F 6 / none | +0.5 %, +2.1 %, +0.8 % | +0.3 %, +4.2 % | −19 %, −53 % |
| F2b | = base / F 6 / none | +1.0 %, +3.2 %, +9.8 % | +0.2 %, +4.1 % | −17 %, −55 % |
| Q3 | = base (cold F 6 clean alone) / none / none | +3.4 %, +9.5 %, +34 % | +0.2 %, +4.1 % | −15 %, −69 % |

Percentages against F1; wall sums are not compared (Q3 overlapped a dev client, another session's Gradle run was found
running when F2b ended). Bundled seed 6 (island 41, wall deadline) is held by the baseline, F2 and F2b and cleared by F1
and Q3 (24.2 s against 20.3 s): a baseline hold, not replayed. Q3 places each in-line mover as a centrifugal machine on
its curve (F1 otherwise): it holds as the baseline, with 34 % more cold backtracks, which the full campaign measures.

## 4. Proposal (owner)

- **A. F2b (recommended under D1).** Holds as the baseline, cost within a few per cent on random worlds and lower on the
  defaults; the transfer lands. Deletes `PUMP_TARGET`, `PUMP_HEAD_LIMIT`, R2 to R4, the movers' `yieldPrescribed`, the
  `atShutoff` latch, the head-limit start flow and `carriedMoverLimit` (plan 4.2). A set-flow mover gives 98.7 % of its
  set flow at 90 % of its limit (not 100 % up to it) and past its displacement blows through along the knee line (+1.5 %
  at no rise) instead of holding its set flow. The knee (0.999, the corner's slope) is not forced by the physics.
- **B. F1.** The switch stays and the corner code goes. The transfer latch needs its own fix in the head-limit pass.
- Then: delete the other form, one full campaign of the chosen form, the turbine connection (plan 3.4), W3.
