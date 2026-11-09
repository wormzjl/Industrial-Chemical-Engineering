# E36: the cold furnace sheet's stage-30 stall (2026-11-09)

Owner decision E35, "Fix V3 first": before the merge, trace the stall and make every probed duty solve. Code at `06052fc6e` (main merged in `0d0499f11`). Probes, traces and the forms patch: `tools/open-thermo-data/e36/`; runs: the session worktree's `tmp/v3-cold-band/`.

## 1. Expected behaviour

- The sheet's first pass feeds T-101, the 40-stage medium-crude column, at 567.4 to 569.4 K for H-101 duties of 69.9 to 70.9 MW. At every duty the column has one steady state, and it moves smoothly with the duty.
- The classical path's 30-stage rung is a dry surrogate: no draws, no pumparounds, the steam as a 12.5 MW reboiler, feed tray 28. It also has one root: a cool pinch near 413 K on trays 8 to 20, with the heavy front between trays 20 and 27. The converging duties, and the full-support solves below, agree on it.

## 2. What the code did

- HEAD solves 3 of 7 duties: 69.9 F, 70.2 F, 70.35 S, 70.4 F (the pinned duty), 70.45 F, 70.6 S, 70.9 S. Main's D42 made the crude 0.08 K warmer, which put 70.4 MW on the failing side. This is a basin effect, not physics.
- Every failure stops in the 30-stage rung, at a scaled residual of 1.4e-4 to 3.4e-4 (tray 10's energy row, -10 to -25 kW).
- The rung starts from the 15-stage state, stretched to 30 stages, at a residual of 6.2. Its local-block steps move the heavy front (T8: 427, then 460, then 422 K). On the failing duties, two of those steps in a row are rejected at iterations 7 to 13, and local blocks are then off for the rest of the attempt.
- By then the tops of the heavy tails have sunk 5 to 12 e-folds below their floor: PC04 liquid in the condenser, and PC05 to PC09 on trays 8, 14, 18, 21 and 23. Nothing live lies beyond them, so form H leaves them free.
- Their Jacobian columns shrink from 3e-3 to 8e-6, against order one elsewhere. At 70.4 MW the LU is singular from iteration 9; at 69.9 MW no Armijo step exists along the Newton direction. Damped Gauss-Newton steps take the remaining 96 to 119 iterations.
- The descent settles in a false valley:
  - the front sits on trays 8 to 12;
  - trays 12 to 22 are 46 K hotter than at the root (T15 459 against 413 K);
  - the internal flows are 220 mol/s lower.
- The refresh then drops the dead tails (904 to 834 unknowns). But the refreshed attempt (16 iterations) and the bubble-point recovery (128) both start in the valley, and their local steps of 2^-18 never leave it.
- At 70.35 MW the local steps continue instead. H holds the cut paths while the front moves down (T15: 459 to 412 K), releases after 12 held iterations, and the refreshed attempt converges.
- Check: from the stuck state, the same rung with every point as an unknown converges to the root at 69.9, 70.2 and 70.45 MW. No removed point carries 10 floors there. The support was right; the descent over dead coordinates is the defect.

## 3. Forms and measurement

All forms act in `V3SimultaneousColumnSolver` and release to the existing refresh path (`STALLED_SUPPORT_FLOOR`). The grids use the classical start at ten feed temperatures from 622 to 644 K; polls are control polls.

| Form | Furnace duties | Medium grid | Tia Juana grid | light / heavy / dilbit |
| --- | --- | --- | --- | --- |
| HEAD | 3/7 | 8/10, 5,904k (636, 644 K fail) | 10/10, 5,523k | ok 555k / 587k / 1,865k |
| S1: release on a singular LU with a sunk point (E30's G2, kept to sunk points, now beside H) | 6/7 (69.9 fails) | 10/10, 3,852k | 10/10, 3,272k | ok 555k / 587k / 568k |
| S2: S1, and on a Newton direction with no Armijo step | 7/7 | 10/10, 2,715k | 10/10, 3,174k | ok 555k / 587k / 532k |

- The roots equal HEAD's to 4 decimals wherever HEAD converges.
- The new medium points lie on the monotone trend: 636 K gives distillate 9.1073, bottoms 96.2824 kg/s and condenser -33.0215 MW; 644 K gives 10.4974, 93.7710 and -36.3514. This closes E30's open class (636 and 644 K).
- S2 at 69.9 MW: three releases, then the rung converges. A release spends one of the cold path's three support refreshes, and no measured case needed a fourth.
- Rejected: form A (local blocks off after 12 iterations that do not halve the residual) changed no outcome. The full-support re-solve (experiment B) is not a rule: it diverged at 70.4 MW, where H holds the paths to the floor-lifted traces.

## 4. Proposal (landed for validation)

- S2, as H's complement. A free point that sank below its floor is not an unknown. When the Newton step fails while one is in the system, the attempt goes to the support refresh, which removes it. A point that began the solve below its floor (kept for a structural reason) or sits on the feed tray never releases, since the refresh would keep it.
- Deletes: nothing. The damped descent stays for a failing Newton step with no sunk point.
- The full-budget steam resume stays. With S2, E30's `O10FixResumeProbe` reaches it in 1 of 5 cases (steam ×1.5) against 3 of 5 before, and all 5 converge.
  - The half-steam wet preset, the case it was built for (a zero pivot), now closes on its first sweep.
  - So `V3StageContinuationTest` checks the resume's contract on steam ×1.5, and holds half steam to the first-sweep contract.
- Risk: a solve that needs more than three releases now fails where the damped descent might have crept to a root (none in the measured set).
- Tests: see MERGE_REVIEW.md. Code guide: the column-v3 chapter, at the merge.
