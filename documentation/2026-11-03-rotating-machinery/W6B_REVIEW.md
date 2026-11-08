# W6b: Create coupling, review

The commits are `23c634d1f` (the engine, the compat block and the GameTests) and the page commit that follows this review. They implement `W6B_BUILD_PLAN.md` form A (D40) and the page as prototyped (D41), on top of W6a, which was merged into this branch (`d16ecab51`). Two Opus medium subagents built them, and they are reviewed here.

## 1. What was built

- **Driver.** `ShaftNetwork.Kind.CREATE_COUPLING` and `ShaftTrain.Drive.CREATE` use the creative motor's limited step.
  - The set speed is Create's |rpm|. The limit is (the network's spare stress plus the coupling's own draw) × W per su, where W per su is 20 × J per FE / su per FE. That is 1.465 W per su at the defaults, with the config `create.stressPerFE` defaulting to 34.13.
  - When the train is short of power it slips and shows `POWER LIMITED`. When Create is at rest it shows `NO ROTATION`.
  - A gearbox on the coupling's own line takes ratios up to 64. Every other gearbox keeps 20.
- **Inputs.** `CreateCouplingBlockEntity.report()` runs from `initialize`, `onSpeedChanged`, `updateFromNetwork` and chunk unload. When a value moves past its deadband, it queues `ShaftTrains.CreateInput` (|rpm| and the stress it may take). The engine applies the input at its drain and steps the train.
- **Impact.** The step computes `CreateCoupling.impact`: min(demand, limit) / (W per su × rpm). It goes to the block entity through `Host.stress` and `ShaftParts.Coupling`, and the block entity caps it again to the spare at the moment it lands, then calls `updateStressFor`. `calculateStressApplied` only returns the stored value. Nothing is computed on the tick.
- **Guard.** `IChemE` has one entry point: `if (isLoaded("create")) CreateCompat.register(...)`. No class outside `compat/create/**` imports a Create, Ponder, Flywheel or Catnip type, which a grep of both the sources and the compiled classes confirms. The recipe, loot table and pickaxe tag entry are conditional.
- **Page (D41).** `MachineColumn.Kind.CREATE_COUPLING` is not settable: there is no Apply and no Enter key.
  - `MachineView.Coupling` carries Create rpm, the stress the coupling may take, the impact, the train speed and W per su. It is a wire change: the protocol becomes `machine-2`.
  - The view is filled by `MachineView.of` from engine state only.
- **Facts.** Unchanged. The new enum constant is saved by name, and the gearbox ratio setting now accepts values up to 64.

## 2. Tests

- **Unit tests run by the subagents:** CreateCouplingTest 6, ShaftNetworkTest 6, ShaftTrainTest 10, ShaftCouplingTest 5, MachineColumnTest 7, FluidPacketCodecTest 23, FluidFactsCompatTest 13, ElectricStoreTest 7, PumpHealthTest 2 and DeterministicMathSourceTest 2. All pass.
- **Affected set run here:** 340 tests on `23c634d1f` and 41 classes (281 tests) for the page, all passing (`tmp/rotating-machinery/w6b/`).
- **Fluid GameTests:** 45 of 45 with Create (runs `w6b-create-1`, `-2` and `w6b-page-1`) and 45 of 45 with `-PwithoutCreate` (runs `w6b-nocreate-1` and `w6b-page-nocreate-1`).
  - Running: the train turns at 2950 rpm, geared from Create's 256 rpm, draws 23.4 kW and carries 15,953 su.
  - With the supply cut to 128 su: `POWER LIMITED`, 128.0 su drawn of 128, and the Create network is not overstressed.
- **No campaign.** The solver is unchanged, and the campaign has no couplings.

## 3. Readings (A142 to A157)

- **A142:** the coupling's design speed is 256 rpm, so an auto gearbox for 2950 rpm takes 11.52.
- **A143:** the deadbands are 1e-3 rpm for speed; 1 % and at least 1 su for spare stress; 0.1 % for impact, or any start or stop.
- **A144:** the coupling takes at most 99.99 % of the spare, a margin for Create's float sums.
- **A145:** the ratio 64 applies only to a gearbox stepping up on the coupling's own line. The settings and the command accept up to 64.
- **A146:** with no spare at all, the train stands at 0 rpm. The creative motor's step would restart it at full speed every slice.
- **A147:** Create's inputs are engine state, never saved. A new input re-sends the impact, so a reloaded block entity gets it again.
- **A148:** a world opened without Create removes its coupling parts, like any lost Create block, so a leftover part cannot stand a train as a second driver.
- **A149:** with Create's stress system off, the spare is unlimited (1e9 su).
- **A150:** an unloaded coupling, or one on a network overstressed by something else, reports 0 rpm. Its train stands until Create runs again.
- **A151, A152, A156:** the readings travel as one nullable sub-record. The spare reads "—" while Create is at rest or its stress is off. `MachineNetwork.machineView` is public for the GameTest.
- **A153:** the train speed is that of the driven machine with the lowest identity.
- **A154:** `POWER LIMITED` draws in the screen's warning tone. The approved sheet drew it muted.
- **A155:** Apply is hidden by `settable()`, not by reader mode.
- **A157:** "stress drawn" is the impact the engine sent. While limited, Create may hold a value cut lower when it lands, within 2 % in the GameTest.
- **Look:** the model reuses the motor, guard, steelwork and shaft textures. The recipe is 2 Create shafts, 1 andesite casing and 2 iron. Create draws its own half shaft on the Create face.
- **Lag:** the first slice after a start sends 0 impact, because the train has not yet booked a demand.

Code guide: no change yet. A Create-coupling section in the machine chapter is due at the merge refresh.
