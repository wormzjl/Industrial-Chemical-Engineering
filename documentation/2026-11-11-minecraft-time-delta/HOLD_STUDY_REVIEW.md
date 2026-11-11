# Holds at 72x: study of every failed case

Owner, 2026-11-11: "why 2s budget at all? The engine iteration time is 5s", then "yes, study all the failed cases". Follows [TIME_SCALE_REVIEW.md](TIME_SCALE_REVIEW.md), form C (72x, steps 72 s / 1440 s), seeds 1-200. Probes and outputs: `tools/minecraft-time-delta/` (`study.sh`, `results/study/`). Nothing in `src/main` changed.

## 1. Expected behaviour

- An island at a steady flow should cross a 360 s slice in a few steps: nothing changes, so the step grows to the slice.
- A fast transient (a phase appearing or vanishing in a vessel) needs small steps for as long as it lasts in process time. A slice that holds 72 times the process time holds up to 72 times that work.
- The wall budget only has to keep an island ahead of its own slice. The slice is 5 s of real time at any factor.

## 2. What the code did, case by case

| Seed | Hold at 72x | Cause | Depends on the step |
|---|---|---|---|
| bundled 15 | wall deadline | steady island; the Newton ends at 1.1e-9 to 5e-9 on one junction row, above its 1e-9 tolerance, at every step over 2 to 4 s | yes |
| bundled 123 | wall deadline, then substep limit | the same pattern on a gas junction row: near misses of 6e-9 to 1.3e-8, and 1e-5 to 2e-5 at the doubled step | yes |
| bundled 77 | wall deadline | cost only: clean with a 5 s budget | no |
| bundled 118 | wall deadline | load of the 8-thread run: clean alone | no |
| bundled 47 | wall deadline | crude liquid drying out in a gas tank: residuals near 1 at steps of 0.04 to 0.15 s, active-set cycles, singular passes | no |
| bundled 164 | substep limit | first the near miss (2.5e-9 to 3e-9 at 0.035 s); with it cleared, a gas phase vanishing in a water junction fed from a vapour port at a flow near zero, which fails down to 3e-4 s | partly |
| cold 72 | non-finite matrix | LNG let down to 1 atm at 111 K: a fugacity row's derivative is not finite, in the rate solve and at every step | no |
| cold 118 | wall deadline | held at 1x too | no |

- **The near miss (15, 123).** The island is steady to 1e-8. The residual at a step's start grows in proportion to the step (2e-7 at 144 s, 6e-9 at 4.5 s on seed 15), and 30 iterations bring it to a few 1e-9, not under 1e-9. Row 19 of 35 of a two-phase crude junction is the stuck one: by the layout's order the continuity or the enthalpy row (not verified which). Stating the junction's mixing rows over the step's inflow instead of the holdup did not move it.
- **The step controller makes it expensive.** After a failure at 2h it accepts h, sees no change, doubles, and fails again: one failed 30-iteration Newton per accepted step. On seed 15, 209 of 517 attempts were rejections and the accepted step fell from 4.5 s to 1.1 s; a 360 s slice then needs hundreds of both.
- **Exposure, not the step (47, 164, cold 72).** These fail at steps far below today's 1 s. The world reaches the state because it runs 12 h of process time instead of 10 min; a 1x world left running would meet it too.
- **The 2 s budget.** It is `fluid.wallBudgetMilliseconds`, a guard on a round's length and an edit's wait, not a number derived from the 5 s schedule. At 5 s: 77 is clean, 15 still holds, 47 runs 8300 more ticks and then holds, 123 turns into the substep limit.

## 3. Hypothesis runs (probe switches, not fixes)

| Switch | 15 | 47 | 77 | 123 | 164 |
|---|---|---|---|---|---|
| 5 s wall budget | held | held later | clean | held | - |
| Newton tolerance 1e-8, equation gate 1e-7 | clean | held later | clean | clean | held (real failure, 6e-5) |
| No step growth for 8 accepted steps after a Newton failure | held | held later | clean | held | held |
| Junction rows over the step's inflow | held | held | clean | held | held |

The tolerance switch on all 200 seeds at 72x: bundled 197 clean (47, 164; 118 under load), cold 199 clean (72). Newton iterations 121 678 and 286 527, against 131 782 and 274 320 at 1x. So at 72x with that tolerance the fuzz holds no more worlds than at 1x (198, 197), for 12 h of process time instead of 10 min.

## 4. Proposal for the owner

1. **The near miss is one defect with two parts**, and 1x is not safe from it (seed 15 fails at 4.5 s, inside today's 20 s cap; a 5 s slice stays under it by its 1, 2, 2 s steps). Options, since the physics forces none:
   - A. A Newton that ends within the equation gate hands its iterate to the reconstruction, and the gate (1e-8, after exact booking) decides. Removes the gap between the two tolerances. Not measured in this form.
   - B. Tolerance 1e-8 and gate 1e-7 everywhere: measured above at 72x; at 1x it moves chain-100 and the junction lines.
   - C. Keep both, and stop the controller's ping-pong only. Measured: it clears none of the five.
2. **Wall budget**: raise the default to the slice (5 s) only together with item 1; alone it clears one seed of five.
3. **47, 164, cold 72** are hold classes the campaign already knows (budget, E6, B). They belong to their own batches, with or without a time factor; a longer process time per world finds them sooner.
4. Measured since ([OPTION_A_REVIEW.md](OPTION_A_REVIEW.md)): A clears nothing at 72x; the stuck row is the junction's enthalpy row, and scaling the junction holdup with the time factor clears 123, 77 and 164. Recommendation as first written: A, measured on the 200-seed quick check at 1x and 72x before a campaign. The time-factor decision does not wait on it, but 72x without it holds the two near-miss worlds.
