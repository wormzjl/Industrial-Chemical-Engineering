# Batch still: heat per mole of distillate

Status: **In progress since 2026-11-12**. Branch `claude/still-heat-per-distillate-d5ec04`. Not merged, not pushed.

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

## Documents

- [STILL_REFUSAL_REVIEW.md](STILL_REFUSAL_REVIEW.md): expected behaviour, the baseline, the fix built and options A, B, C.
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
2. Owner: the single stage's condensate of a vapour with light ends lands on a trivial K = 1 root (review, section 2).
3. Final lane: the runtime classes that load `StillHead` or `BatchStill` through the coordinator (58 by
   `tools/merge-lane/affected.mjs`, 2 of them in `holdGates`) and the fluid GameTests (`BatchStill.java` changed; no
   GameTest runs a still).
4. Merge preparation (CHANGELOG, version, code guide `fluid-science.md` still section, `BATCH_STILL_LAW.md`'s column
   line): not started, by instruction.
