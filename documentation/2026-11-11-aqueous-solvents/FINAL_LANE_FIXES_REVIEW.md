# After the final lane: the two fixes, and the hold of the level loop's drum

2026-11-12, main thread (owner: "Fix both here", "Diagnose here", "Fix hold first"), on `236164771`. Follows
[FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md). Outputs: `tmp/final-lane/fix/`. No second lane was run.

## 1. A dry island reloads on its own model (`b20e25501`, state format 50)
The model key carries the water choice (`FluidCheckpointCodec.PackageKey.wet`). State only: a format-49 state is
dropped and rebuilt. Gate: `FluidCheckpointCodecTest.anIslandWithoutWaterComesBackOnItsOwnModel`; 22 classes, 288 tests.

## 2. chain-100 captured again (`e616970c9`)
Cause: the batch's equation of state; the reference dated from `77152ecaf`. Exact replay after the capture: 0.

## 3. The blanketed drum of the level loop holds at its start: two mechanisms, both fixed
GameTest `aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence`. Expected: the drum, a slack bag never filled (nitrogen
at 101.325 kPa), takes its feed (19.8 kg/s of water) and the pad's gas, and fills to 25 % under its level valve.

**Mechanism 1 (`4f2c922ec`, `PassiveStepSolver.stockSeeds`).** On a rig of the plant over `EventPathWorld`, 96 setup
orders: main 0.27.1 holds 0, the branch held 6. A failed 2 s pass folded the drum's 2.9 m3 of gas out of its seed; the
next failed pass drained it and decision D54 found it running dry, its junction laid out on the folded seed: water
alone at 83.8 kPa, so the emptying was confirmed. With its gas it stands at 104.7 kPa and keeps its contents.
Fix: a vessel found running dry is laid out as a junction on its own start state. Gate: `LevelLoopBlanketStartTest`
(fails with the rule disabled). Rig: 0 of 96. 24 classes, 134 tests pass; chain-100 exact 0.

**Mechanism 2 (the GameTest's own hold; `PassiveStepSolver.initialModes` and `stocked`).** The server dumped the held
interval's input; replayed offline it holds to the digit (residual 917, pass 1). The level valve's first law
recompiles the island 0.4 s after the feed comes on, so the solve is cold. Expected: the level valve, set to no flow,
passes nothing; the drum (7.9 L of water, 1.7 m3 of gas) takes its feed; the 1 L junction under it, froth behind the
shut valve (0.15 % nitrogen, 72 % gas by volume, 84.9 kPa), keeps its contents. What the code did:
- A cold solve started every flow valve open and closed one set to zero only after an open pass converged. Open, the
  outlet drew 790 kg/s from the drum.
- The cold balance seeded the junction as what flows through it: the drum's liquid, 1e-7 of its volume as gas. Its
  backward-Euler rows state it as its stock and what arrives; asked for the froth back, the Newton crossed to all
  vapour at every step size (residual 3.57), and the collapse rule then stated its 1 kg of water as vapour (917).

Fix, both at the cold start: a flow valve set to no flow starts closed unless it stands open behind a lower target
(a mover's rule, and what `nextMode` decides after a converged pass); a junction that keeps at least half its stock
over the step is seeded with its stock's mixture. The interval is then one accepted step, none rejected. The seed
rule alone: 5 steps, 4 rejected. The valve rule alone: held (residual 5.74).
- Gate: `DrumFillColdStartTest` (the dumped island as literals; fails with either rule disabled). Ran: 32 valve,
  blanket, junction-start and cold-start classes, 113 tests; 3 slow hold gates, 58 tests; all pass; chain-100 exact 0.
  Fluid GameTests after the fix: one run, 94 of 94, no hold, level high 74.2 %.
- Withdrawn: the flow valves regulating after a failed open pass; the balance deciding them at a cold start.
- Before the fix the branch held in about 1 GameTest run of 3, main 0.27.1 in 1 of 8 (same start): the hold predates
  the batch (`VALVE_FOLLOWUPS_REVIEW.md` item 1). The owner stopped further repeated runs ("stop repeating").

## Open after this
- The level loop's overshoot depends on the machine's load (high 63 % to 99.71 %, limit 99 %; main 66 % to 87 %).
- An empty slack bag with more gas arriving than its outlet passes at the ambient has no form (mechanism 1's end state).
- The two cold-start rules have not been through a campaign: the next final lane's.
- From the lane, unchanged: the cost findings, bundled 575 (E2), `LanguageFilesTest` until the translation commit.
