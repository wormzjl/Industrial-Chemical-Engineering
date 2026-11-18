# 2026-11-18-water-intake-outfall

Status: in progress since 2026-11-18, on `claude/water-intake-outfall-0e90c8` (not merged).

Purpose: a water intake that draws from a real water body, and a waste outfall that lets a line out to the open air: its vapour pollutes the air at its cube, the rest is booked to the ground.

## The owner's request (2026-11-18)

"Add in world fluid intake (pumping water) and fluid dump (waste water), hook dump to pollution system." Choices: the intake needs "A real water body" (about 100 connected water blocks, none removed); the dump places "No blocks" (an effect only); the dump's non-evaporating part first "Pollute at the outfall", then "Ground only" (after O1's hand numbers): booked to the ledger's GROUND, never the air.

## Documents

- [WATER_INTAKE_OUTFALL_REVIEW.md](WATER_INTAKE_OUTFALL_REVIEW.md): the build, the physics, the hand numbers, what is saved, the owner items.

## History

- 2026-11-18: build (`476a06b86`, `66a5a2e34`). The water intake (`Placement.waterIntake`, `WaterBody`, `FluidDeviceBlock.waterIntake`), the waste outfall (`Placement.outfall`, `Releases.outfall`, `boundaryHalf` takes a portless passive void's own loss), the falling-water noise, placeholder models (`WaterIntakeGeometry`, `OutfallGeometry`), registration, English lines, the `still/water` guide sentence. No format change. The outfall's remaining liquid (`OUTFALL_YIELD` 1) tripped the brief's stop rule on a heavy cut: sent to the main thread as owner item O1.
- 2026-11-18: "Ground only" (owner): `Releases.outfall` splits the stream into vapour (air) and rest (toxic, corrosive and solids to `PollutionLedger` GROUND through `Sink.ground`, `PollutionService.ground`, `PollutionEngine.ground`); `OUTFALL_YIELD` removed. The intake GameTest waits for a pass on the closed revision before it measures.
- 2026-11-18: stateFor option A (main thread): `FluidWorldAuthority.waterIntake` is the one stored-facts test that picks the water intake block, the status clause and the edit target; `Placement.waterCharge` removed; `FluidFactsCompatTest` shows a saved gas intake keeps every byte.

## Code areas

- Runtime: `runtime/fluid/Placement` (`waterIntake`, `waterCharge`, `outfall`), `WaterBody`, `FluidWorldAuthority` (`placeWaterIntake`, `placeOutfall`, `waterBody`, `waterIntake`, `gasIntake`, `outfall`, `NO_WATER_BODY`, the outfall's noise), `PhysicalFluidTopology.boundaryHalf`, `Releases` (`outfall`, `Sink.ground`); `runtime/pollution/PollutionService.ground`, `PollutionEngine.ground`; `science/pollution/PollutionLedger.grounded`, `ViewBuilder` (the status clause).
- World and client: `FluidDeviceBlock` (`WaterIntake`, `Outfall`, `touchesWaterBody`, `neighborChanged`, the recheck tick, `stateFor`), `FluidNetwork` (edit refusals), `FluidDeviceScreen` (`boundaryBlock`, the status words), `FluidDeviceModel` (`"body"`), `ModBlocks`, `ModItems`, `ModBlockEntities`, `ModCreativeTabs`, `EquipmentInfo.ITEMS_THAT_NEED`; `science/equipment/WaterIntakeGeometry`, `OutfallGeometry`.
- Tests: `WaterBodyTest`, `WaterIntakeOutfallTest`, `OutfallReleasesTest`, `PollutionEngineTest` (the ground booking); GameTests `WaterIntakeOutfallGameTests`.
- Tools: `tools/equipment-drawings/EngineeringExport.java` (the six option scenes).

## Open items

- O1 to O4 of the review (where a player sees the grounded amount, a falling-stream effect, the looks, a waterlogged intake).
- Not checked in a client: the looks, the screens, the sound, the recheck in a live world.
