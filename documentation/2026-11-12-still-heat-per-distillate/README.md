# Batch still: heat per mole of distillate

Status: **In progress since 2026-11-12**. Branch `claude/still-heat-per-distillate-d5ec04`; carried onto `claude/still-on-aqueous-fdba0c`
(the W4 column) by merge d908aa617. Not merged to main, not pushed.

## Scope

Found 2026-11-12 while a batch run was recorded for the tutorial: a 500 L pot still charged with medium crude is refused
part way through its run ("no positive latent heat", then "Invalid still amount"). The still's split normalised its rates
on hVapour(y) − hPot(z), a difference of the enthalpies of two mixtures, which falls through zero on a wide-boiling
charge. Fix: the split's rates per mole of distillate; the law's bracket on the pot balance's own slope.

## History

- 2026-11-12: reproduction `BatchStillTest.aMediumCrudePotStillRunsToItsLimit` (795f671f6, fails on `main` at law step
  97); fix A with the slope seed (f89d733a4); the medium crude run ends at OVERHEAT, 143 steps, 41.8 % of the mass
  distilled. Filtered runs, all pass: BatchStillTest 12, StillHeadTest 3, StillRuntimeTest 7, BatchStillUnitTest 8,
  StillSizingTest 4, FluidFactsCompatTest 20. Review: STILL_REFUSAL_REVIEW.md.
- 2026-11-12: the fix carried onto the W4 column (merge d908aa617 on `claude/still-on-aqueous-fdba0c`): e = (R + 1) H1 - R hR,
  the closed valve per mole of top vapour (d = 1 / V1), W4's step ledger kept. Filtered runs, all pass: BatchStillTest 19,
  StillHeadTest 5, StillRuntimeTest 11, BatchStillUnitTest 9, StillSizingTest 4, EthanolMainDataTest 5. Crude run on the
  aqueous branch's equation of state: OVERHEAT at 350.00 C, 143 steps, 41.76 % of the mass and 66.29 % of the moles
  distilled, 3.04 % vented (main: 41.78, 66.43, 2.90). The condenser with light ends diagnosed with a probe (removed):
  CONDENSER_LIGHT_ENDS_REVIEW.md, options a to c, c recommended; nothing built.

## Documents

- [STILL_REFUSAL_REVIEW.md](STILL_REFUSAL_REVIEW.md): expected behaviour, the baseline, the fix built and options A, B, C.
- [CONDENSER_LIGHT_ENDS_REVIEW.md](CONDENSER_LIGHT_ENDS_REVIEW.md): the condenser on a vapour with light ends (a trivial root,
  a bubble point under the water), the engine-flash reference at three states, options a to c.
- Scratch (local, git-ignored): `tmp/still-merge/` in the aqueous worktree (`distillation-failure-mode-control-db2da5`): the
  merge's edit scripts and test logs, the probe (`CondenserLightEndsProbeTest.java.txt`) and its output (`probe2-out.txt`).
- Scratch (local, git-ignored): `tmp/still-refusal/` in the batch worktree: baseline and after-fix logs and results, the
  probe that compared the still's numbers on `main` and on the fix (`StillPinProbeTest.java.txt`).

## Code areas

- `science/fluid/network/StillColumn`: `Split` per mole of distillate (closed valve: per mole of top vapour);
  `solve(pot, stages, reflux, warm)`; `energyPerDistillate` stored.
- `science/fluid/network/BatchStill`: `boiled`'s bracket seed (`TRIAL_SHARE`), the rate basis for the flood and the
  condenser's need.
- `science/fluid/network/StillHead`: the F-factor per watt over the condenser's duty.

## Open items

1. Owner: the runtime's flood cap (heat times F per watt of the condenser's duty) trips early on crude (review, section 3).
2. Owner: the condenser with light ends (CONDENSER_LIGHT_ENDS_REVIEW.md): option a or c (c recommended) and the outlet's
   approach over the water (5 K proposed). Until then, on medium crude, the sheet sends C1 to C3 gas to the spout at 26 to
   50 C, vents everything at 57 to 78 C and condenses everything from about 90 C; the world divides by critical temperature
   at that false condensate.
3. Final lane: the runtime classes that load `StillHead` or `BatchStill` through the coordinator (58 by
   `tools/merge-lane/affected.mjs`, 2 of them in `holdGates`) and the fluid GameTests (`BatchStill.java` changed; no
   GameTest runs a still).
4. Merge preparation (CHANGELOG, version, code guide `fluid-science.md` still section, `BATCH_STILL_LAW.md`'s column
   line): not started, by instruction.
