# After the final lane: the two fixes, and a hold they uncovered

2026-11-12, main thread (owner: "Fix both here"), on `236164771`. Follows [FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md).
Outputs: `tmp/final-lane/fix/`. No second lane was run; the campaign and the suite stand from the lane.

## 1. A dry island reloads on its own model (`b20e25501`, state format 50)
- The model key carries the water choice: `FluidCheckpointCodec.PackageKey.wet`, one byte after an island's compound
  list, a package record's `Dry` (written only for a list without water; absent reads as with water). A whole list is
  always with water. `IslandCoordinator.entry` and the facts rebuild pass the island's own choice.
- State only: a format-49 state is dropped and rebuilt on the engine's schedule. No fact, schema or corpus image moved.
- Gate: `FluidCheckpointCodecTest.anIslandWithoutWaterComesBackOnItsOwnModel` (the key round-trips; the reloaded model
  is the saved one; the list with water has another revision).
- Ran: 22 save and load classes, 288 tests, all pass. The load map names 228 classes for these three files (every
  class that loads the coordinator); the 22 are the ones that save, load or rebuild.
- GameTest `aSavedRestingTankReloadsCertifiedWithItsStatusLineAndNoSolve`: passes (2 runs of 2).

## 2. chain-100 captured again (`e616970c9`)
Cause: the batch's equation of state (derived pairs, water in the cubic); the reference dated from `77152ecaf`. Exact
replay after the capture: 0 on moles, temperature, phase fraction and flow.

## 3. New: the blanketed drum of the level loop holds at its start
Fluid GameTests after the fixes: 91 of 94 pass. `aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence` (blanket, not
direct, factor 4) fails, and two tests after it fail on what it leaves in the world
(`aLostDrumIsRebuiltFromItsRecordWithWhatItHeld`: the world's event queue never empties;
`registeredPipesAndReservoirsKeepSimulatingAcrossUnloadedChunks`: the cleanup). Those two are not verified apart.

1. Expected: an empty blanketed drum on a tenth of its design feed (19.8 kg/s of water) fills to 25 %; the pad
   holds the gas near its 1.2 bar set from the 3 bar nitrogen line. The lane's run did this (first reading 10.9 %,
   0.447 m3 of liquid under 7.5 m3 of gas) and ended at 50.03 %.
2. What the code did: the drum's island held on its first commit, before any fill reading: "Substep refinement
   exhausted: Newton line search stalled at residual 916.9984604961398; active-set pass 1" (15 stalls, 5 iteration
   limits). Three runs of three on an idle machine, the same residual to the last digit; the run inside the lane
   (machine loaded by the lane's earlier steps) passed.
3. Not the save fix: one run with the old key (`IslandCoordinator.entry` passing `true`) held the same way, with
   the reload test failing as in the lane (`gametests-ab-oldkey.gradle.txt`).
4. Reading: the plant is set up over several ticks while workers commit on wall time, so the order of the setup
   events and the first commit depends on the machine's load. One order gives a first interval the solver cannot
   close. main's valve batch recorded the same kind of hold on the direct variant as not reproduced
   (`VALVE_FOLLOWUPS_REVIEW.md` item 1) and main's standalone runs were clean, so the three-slot engine is the
   first suspect, not proved.
5. Proposal: reproduce it outside the server (the level loop's plant over `EventPathWorld`, as
   `BlanketDrainWorldTest`, with the setup events in each order), read the stalled row, then fix at its stage. A hold
   diagnosis: a sub-thread on Opus high, on the owner's approval.

## Open after this
- The hold of section 3; the two tests behind it.
- From the lane, unchanged: the cost findings, bundled 575 (E2), `LanguageFilesTest` until the translation commit.
