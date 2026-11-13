# A5: the wave A final lane's GameTest failures

The five commits touch only `src/fluidGameTest/**`. Cherry-pick them in this order: fixes 3 to 5 use fix 2's helper. No Gradle has run; the lane thread's one GameTest run checks them.

| Fix | Commit | Test | Cause | Change |
|---|---|---|---|---|
| 1 | 68bcad7a9 | `ColumnOperationGameTests.aMenuReadAdvancesNoIsland` | The test expected `world.view` to materialise the resting island. That is the defect R5 removed | Asserts that the view leaves the committed tick unchanged and shows the island at now (the preview's tick is past the committed one) |
| 2 | 0278d66b4 | `FluidPumpStartupGameTests.heatedCrudePumpStartsIntoNitrogenReservoir` | Unpowered in-line pump. It rested on the defect | New helper `powerInlineMover(level, pump[, face])` puts a creative power source beside the mover; its FE reaches the mover's port through the block's energy capability on every face. The test uses it and removes the source at its end |
| 3 | c1c601af8 | `FluidPhasePortGameTests.aPumpOnAGasTankMovesTheGasWithNoRefusal` | Same | Same helper and cleanup |
| 4 | 640e17893 | `SolidPhaseGameTests.filterCaptureRecoveryPersistenceAndBreakDropConserveOwnedSolids` | Same | Same helper and cleanup |
| 5 | 97f86225e | `HeatExchangerReviewWorld` (found by the survey) | The fired heater's in-line feed pump had no FE, so the owner's review world would show the heater with no feed | Powered on a free side; the source is added to the plant's devices for teardown |

**The unpowered pump, confirmed in the code.**
1. `ElectricStores.sync` gives a new in-line mover a metered, empty store and sets `unpowered = needPower()`.
2. `ElectricStores.plans` closes it: `FAILED 0` for a set-flow pump, `SPEED 0` for a `Machine`.
3. Before wave A, `PumpHealth.plans` put `FAILED 1` over that close, so the pump ran on alternate dispatches (M-04).
4. A3's change to `ShaftTrains.failures` (lines 677 to 688) keeps the store's close, so these pumps never ran.

M-04 is not weakened and `machinesNeedPower` is left on.

**`FluidHarnessGameTests.registeredPipesAndReservoirsKeepSimulatingAcrossUnloadedChunks`: leftovers of the earlier failed tests.** It needs no change of its own. The evidence:
- The test has no mover: a generator, 127 pipes and a reservoir.
- Its failure message is its last check. It passed phases 0 to 2, every `world.view` poll included, so R5 is not the cause.
- That last check is the only one in the GameTests that requires the whole world empty: `world().active()`, `world().events()` and `checkpoint().islands()`.
- In the lane log, its batch `fluid-world-unloaded` started at 14:52:26. That was after the three pump tests had failed: 14:43:25, 14:51:37 and 14:52:21. Each failed at its own deadline check, before its block removal, so its generator, pipes, pump and tanks stayed registered.
- The log also shows islands 1575, 1745, 1763 and 1767 still live at 14:51:37 (`HELD: property data changed`).
- It should pass once fixes 2 to 4 let those tests run and clean up. Not verified: the GameTests have not been run.

**Survey of every in-line mover in the GameTests, the tutorial and `examples/`.**

| Place | Verdict |
|---|---|
| `PumpHealthGameTests.anInlinePumpPublishesItsCrackle` | Powered: it feeds FE through the pump's energy capability every tick. It passed in the lane |
| `PumpHealthGameTests.aCavitatingPumpLosesHealthAndAKitRestoresIt` | Not in-line: a casing on a train |
| `DrawnJointGameTests.anInLinePumpInstalledAsTheItemDoesDrawsAsAPumpAndNoBranch` | Needs no flow (drawing and registration) |
| `RegistryFirstGameTests` (in-line pump and compressor states) | Needs no flow (registration round trip) |
| `ConduitLayingGameTests` (in-line pump and compressor ports) | Needs no flow (port geometry) |
| `HeatExchangerReviewWorld` | Rested on the defect: fix 5 |
| `ColumnReviewWorld`, `ColumnOperationReviewWorld`, `ColumnFailureModesReviewWorld` | No in-line mover: casings on motor trains. The failure-modes world places its own creative sources |
| Tutorial lessons, scenes and recordings | No in-line mover. They build no world and run no engine: their pages are text and their recordings are pre-recorded |
| `examples/` | Python scripts only, with no in-line mover |

No tutorial lesson rested on the defect, so no decision is needed.
