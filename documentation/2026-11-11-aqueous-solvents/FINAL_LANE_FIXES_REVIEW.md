# After the final lane: the two fixes, and the hold of the level loop's drum

2026-11-12, main thread (owner: "Fix both here", "Diagnose here"), on `236164771`. Follows [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md).
Outputs: `tmp/final-lane/fix/`. No second lane was run; the campaign and the suite stand from the lane.

## 1. A dry island reloads on its own model (`b20e25501`, state format 50)
- The model key carries the water choice: `FluidCheckpointCodec.PackageKey.wet`, one byte after an island's compound
  list, a package record's `Dry` (written only for a list without water). `IslandCoordinator.entry` and the facts
  rebuild pass the island's own choice. State only: a format-49 state is dropped and rebuilt. No fact or image moved.
- Gate: `FluidCheckpointCodecTest.anIslandWithoutWaterComesBackOnItsOwnModel`. Ran: 22 save and load classes, 288
  tests pass. GameTest `aSavedRestingTankReloadsCertifiedWithItsStatusLineAndNoSolve` passes.

## 2. chain-100 captured again (`e616970c9`)
Cause: the batch's equation of state; the reference dated from `77152ecaf`. Exact replay after the capture: 0.

## 3. The blanketed drum of the level loop holds at its start: two mechanisms, one fixed
GameTest `aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence` (blanket, not direct, factor 4); two tests after it fail
on what a hold leaves. Expected: the drum, a slack bag never filled (nitrogen at 101.325 kPa), takes a tenth of its
feed (19.8 kg/s of water) and the pad's gas, its open bottom outlet passes what arrives, and it fills to 25 % once
the level valve has its law.

**Mechanism 1 (fixed, `PassiveStepSolver.stockSeeds`).** Found on a rig of the plant over `EventPathWorld`, 96 setup
orders: main 0.27.1 holds 0, the branch held 6. A failed 2 s pass folded the drum's 2.9 m3 of gas out of its seed;
the next failed pass drained it and decision D54 found it running dry. Its junction was laid out on the folded seed,
water alone: 83.8 kPa, not above the ambient, so the emptying was confirmed. With its gas it stands at 104.7 kPa and
keeps its contents; booked empty, the drum had no form at the next step (a vessel that drains, a junction at 143 kPa).
Fix: a vessel found running dry is laid out as a junction on its own start state, the rule of every junction's seed.
Gate: `LevelLoopBlanketStartTest` (4 held orders; fails with the rule disabled). Rig: 0 of 96. Ran: 24 classes that
name the emptying rule, 134 tests pass; chain-100 exact replay 0.

**Mechanism 2 (the GameTest's own hold; located, not fixed).** Captured in the server by solving the held interval
again in place with the failure probe (`gametests-probe5.gradle.txt`). The level valve's first law recompiles the
island 0.4 s after the feed comes on. The drum then holds 7.9 L of water and 1.7 m3 of gas; the junction under it
(1 L) is full of water at 84.9 kPa, below both its neighbours. The cold solve's balance sends 789 kg/s down the
outlet. Pass 0 stalls at residual 3.57 on that junction's rows at every step size down to 1.5e-6 s (start norm 3.7:
789 kg/s in, 793 out), its iterate reversing the outlet to -65 kg/s with the junction all vapour; the failed iterate's
check then folds the junction's liquid (the collapse rule) and pass 1 states 1 kg of water as vapour: residual 917.
- Two forms tried and withdrawn: the flow valves regulating after a failed open pass (1 of 5 runs passed), and the
  balance deciding them at a cold start (2 of 5). With both valves regulating the same hold occurs to the digit
  (`gametests-fix4-b.gradle.txt`): the valves are not the cause.
- Frequency (runs of the 94 fluid GameTests): branch before the fix 4 holds of 5; with the fix 2 holds and 1
  overflow (99.71 %, machine loaded) of 6; main 0.27.1 1 hold of 8 (residual 264, pass 1, at the same start). The
  hold predates the batch (`VALVE_FOLLOWUPS_REVIEW.md` item 1 recorded one as not reproduced).

## Open after this
- Mechanism 2: the liquid-full outlet junction under a nearly dry drum (no root from the cold balance; the collapse
  rule folding a junction's bulk phase on a diverged iterate). The GameTest fails in about 1 run of 3 on the branch.
- The level loop's overshoot depends on the machine's load (high 63 % to 99.71 %; main 66 % to 87 % in 7 runs).
- An empty slack bag with more gas arriving than its outlet passes at the ambient has no form (mechanism 1's end state).
- From the lane, unchanged: the cost findings, bundled 575 (E2), `LanguageFilesTest` until the translation commit.
