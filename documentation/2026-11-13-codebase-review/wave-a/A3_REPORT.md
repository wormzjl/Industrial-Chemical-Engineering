# A3 Machines: report

Branch `claude/review-wave-a3-machines-294ed8`, from `ba35da565`. Brief: `WAVE_A_BRIEFS.md`, section A3. Review: `MACHINES_REVIEW.md`.

Status: code and tests written and committed; **tests not run yet** (the lane is held for the bench thread).

| Finding | Commit | Files | Test (new case) | Result |
|---|---|---|---|---|
| M-04 | `be8cf5658` | `runtime/fluid/ShaftTrains.java` (`failures`) | `PumpHealthTest.anUnpoweredInlinePumpStaysClosedAndOpensWhenPowered` | pending |
| M-02 | `fb0588863` | `world/item/RepairKitItem.java` (`pumpAt`) | `PumpHealthTest.aFailedDryScrewIsRepairedAndACompressorIsNotAPump` | pending |
| M-19 | `8f5856fe5` | `science/machine/Motor.java` | `MotorTest.twelvePolesReadTheEightPoleRow` | pending |
| M-16, M-17 | `1f51fb914` | `runtime/fluid/ShaftTrains.java` (`view`, comments), `runtime/fluid/PumpHealth.java` (javadoc) | `ShaftCouplingTest.aFailedPumpsCasingShowsFailed` | pending |

## What changed

- **M-04.** `failures` merged the health plans with `putAll`. Now a health `FAILED 1` ("switch back on") skips an id already planned `FAILED 0` (the store's close of an unpowered in-line pump). A health `FAILED 0` still replaces anything. The cited lines matched the code. The single run plan stays with wave G.
- **M-02.** `pumpAt` took only `Kind.PUMP`. Now it takes any mover casing (`EquipmentShape.Kind.mover()`: pump, compressors, vacuum pumps) and returns its fluid identity. The health record decides in the queued repair: `PumpHealth.apply` answers `NOT_A_PUMP` when `pump(id)` is null, which is the same test as `health(id) == null`. The in-line path already worked this way. I kept the kind filter so that non-mover casings (tanks, columns) keep their own use when the player holds a kit; the client cannot read health.
- **M-19.** `poleColumn` maps 12 poles to the 8-pole row. The separate 12-to-8 remap in `generatorEfficiency` is removed because it is now redundant (its values are unchanged). `ratedSpeed(12)` still throws; nothing in this lane calls it with 12.
- **M-16.** New helper `worn(u, status, running)` in `view`: a part whose fluid device has a health word shows `FAILED` in place of every train word. A fault word (`CAVITATION`, `GAS`, `LIQUID`) replaces only the train's own run word. It also applies to a part with no train (`NO DRIVER`). Display only.
- **M-17.** Comments only. `PumpHealth`: wear is booked over process time, which is online ticks times the time factor. `ShaftTrains`: the binding status is `INTERNAL GOVERNOR`. The comment on field 178 and the javadoc of `bind` both said `NO GOVERNOR VALVE`, and both are fixed.

## Deviations from the brief

- M-16 test: `MachineColumnTest` builds a `MachineView` directly and never calls `ShaftTrains.view`. I put the case in `ShaftCouplingTest`, which already tests `view` with a motor, a shaft and a casing.
- M-04 and M-02 cases are in `PumpHealthTest`. Its `Rig` gained a `metered` flag: device 1 is registered as an in-line pump with an `ElectricStore.Facts.EMPTY` store. The default rig is unchanged.

## Values moved

- None in the physics. A 12-pole motor now gets the 8-pole IE3 efficiency instead of throwing. A failed casing's row reads `FAILED` instead of its train's word.

## Lane commands (after "lane free")

1. `./gradlew compileJava compileTestJava`
2. `./gradlew test --tests com.wormzjl.icheme.runtime.fluid.PumpHealthTest --tests com.wormzjl.icheme.runtime.fluid.ShaftCouplingTest --tests com.wormzjl.icheme.science.machine.MotorTest --max-workers 4` (check first in `build.gradle` which task holds each class)

## Open items, outside this lane

- `MachineColumn.warns` (client) does not include `FAILED`, so the casing's page shows `FAILED` in the normal tone. It is a one-line follow-up in the client lane.
- `FluidWorldAuthority` 2493 reads `health.status` for liquid movers only (the second half of M-02). That file belongs to A5.
- Risk in the M-04 test: it needs three dispatches of the closed island within 2000 ticks. If the island certifies rest before that, the assertion `three dispatches seen` fails, and the fix is to the test, not to the code.
