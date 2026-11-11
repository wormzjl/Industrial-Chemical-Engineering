# Option A and the junction holdup: prototype and measurement

Owner, 2026-11-11: "Yes, option A" (prototype it and measure it at 1x and 72x). Follows [HOLD_STUDY_REVIEW.md](HOLD_STUDY_REVIEW.md). Prototype: switches of `tools/minecraft-time-delta/patches/probe-time-scale.patch`, off by default; nothing in `src/main` changed. Seeds 1-200, 8 threads, alone.

## 1. Expected behaviour

- A junction is a mixing point. Its buffer (`HOLDUP_TAU`, 0.05 s of its largest line's cap flow) is a numerical regularisation, not inventory (D46).
- Over a step that carries many buffers through it, the junction's outlet is the inflow's mix. An error in a stream's enthalpy should count against what flows through, not against the buffer.

## 2. What the code did

- **The stuck row is the junction's enthalpy row** in every near-miss case (15, 123, and 164's two junctions). `PhaseLayout.junctionRows` states it in amount form over the buffer: the relative enthalpy difference times `1 + Q dt / m_J` (`junctionInflow`). The form is deliberate: the reconstruction books the step's flows against the buffer, so a difference d in the outlet's enthalpy lands in the buffer as `(Q dt / m_J) d`.
- At a 5 s step the weight is up to 100; at 360 s up to 7200. The Newton reaches a few 1e-9 on that row at steps of seconds and less as the step grows, so 1e-9 is out of reach.
- **Option A (a Newton that ends within the 1e-8 gate hands its iterate on) does not clear it.** The residual it can reach grows with the step, so a fixed level only moves the ceiling: on seed 15 the largest step that passes is 1 to 4 s at 1e-9, 9 s at 1e-8, 18 s at 1e-7, and the controller ping-pongs at each.
- Accepting after a failure also pays the failed solve first: seed 15 at 1e-7 took 25 014 Newton iterations with option A and 5 188 with the Newton's own tolerance at 1e-8.

## 3. Measurement

| Form | Clean: bundled, cold, defaults | Newton iterations: bundled, cold, defaults | Held |
|---|---|---|---|
| 1x today | 198, 197, 200 | 131 782, 274 320, 10 902 | bundled 6, 200; cold 85, 101, 118 |
| 1x, option A | 199, 197, 200 | 133 594, 274 275, 10 902 | bundled 200; cold 85, 101, 118 |
| 72x form C | 194, 198, 200 | 147 652, 290 542, 10 722 | bundled 15, 47, 77, 118, 123, 164; cold 72, 118 |
| 72x, option A | 194, 198, 200 | 131 276, 294 073, 10 722 | the same |
| 72x, tolerance 1e-8 and gate 1e-7 | 197, 199, not run | 121 678, 286 527 | bundled 47, 118, 164; cold 72 |
| 72x, holdup 3.6 s | 197, 197, 200 | 109 839, 269 144, 10 204 | bundled 15, 47, 118; cold 6, 72, 118 |
| 72x, holdup 3.6 s and gate 1e-7 | 197, 197, not run | 107 589, 283 453 | bundled 15, 47, 118; cold 6, 72, 183 |

- Option A at 1x accepted 25 near misses (bundled), 5 (cold) and none (defaults, counters identical). At 72x it accepted 468 and 34, and the same worlds hold.
- **Holdup 3.6 s** is `HOLDUP_TAU` times the time factor, tolerances untouched: the step carries the same number of buffers as today (360 s / 3.6 s = 5 s / 0.05 s). It clears 123, 77 and 164, and the Newton work falls below 1x.
- Bundled 118 is a load hold (clean alone). Cold 6 and 183 are new under the larger buffer (a line-search stall and a wall deadline); not traced.
- **Seed 15 stays.** With the larger buffer its Newton converges, and the equation gate refuses the reconstructed point by 1.0e-8 to 1.7e-8 on the junction's first fugacity row at every step over 2.25 s. The gate at 1e-7 does not clear it either; that last cause is not traced.

## 4. Proposal for the owner

1. **Drop option A.** It is harmless at 1x and clears nothing at 72x.
2. **For a time factor, scale `HOLDUP_TAU` with it**, like the step settings (form C). It is a time constant of the numerics, and its mixing lag stays 1 % of a slice. Effect at 72x: bundled 123, 77 and 164 clear, at 0.83 times the 1x Newton work on bundled and 0.98 on cold. It changes no result at factor 1.
3. **The tolerance change (1e-8, 1e-7)** clears seed 15 where the holdup does not, but it moves every reference at 1x. Present only as an option: A, both; B, the holdup alone and seed 15's gate miss to its own batch (my recommendation: one world of 600, a two-phase crude junction).
4. Open after this: seed 15's last cause, cold 6 and 183 under the larger buffer, and the hold classes of the first study (47, cold 72).
