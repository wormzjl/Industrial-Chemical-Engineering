# Flare stack

Status: **In progress since 2026-11-09** (built, targeted tests pass, not committed). Branch `claude/furnace-fuel-power-requirements-f7a46e`: it reads the `combustion` sub-records of [2026-11-09-furnace-fuel-power](../2026-11-09-furnace-fuel-power/README.md).

## Scope

Owner, 2026-11-09: a scalable flare stack, capacity by size (research first); flared gas needs a fuel value; inert gas may be flared with fuel gas while the combustion temperature holds; status and flow sensor on the data network; solids block it.

## Documents

- [FLARE_STACK_PLAN.md](FLARE_STACK_PLAN.md): research (heating-value floor, tip velocity, radiation height), proposal W1 to W5, owner decisions X1 to X5, as built (section 6).

## History

- 2026-11-09: research and plan; owner answers X1 flame temperature at 1700 K, X2 vent unburnt, X3 one size, X4 limit to rating, X5 blocked while solids arrive; working animation added (W5).

- 2026-11-09: built W1 to W5 (plan section 6). Targeted tests pass: FlareTest, FlareRuntimeTest (new), FiredHeaterFuelTest, FlowsheetPaletteCoverageTest, SizingCoverageTest, SimulatorFrameTest, FluidPacketCodecTest, ConstructorColumnTest; equipmentGates EquipmentLayoutGoldenTest (3 flare rows added), ProcessUnitTest, ShapeRasterizerTest, EquipmentShapeCompatTest. ProcessUnitTest also needed the furnace batch's heater terminal boxes listed (a gap from 59278550b). `Flare*` joined the equipment gate group. GUI prototypes rendered (`flare-stack-flaring`, `flare-stack-flame-out`, `equipment-constructor-flare`, `equipment-constructor-choose-heat`).

## Code areas

`science/fluid/network/Flare`, `runtime/fluid/Flares`, `EquipmentAlarm`, `ShaftTrains`, `FluidWorldAuthority` (placeFlare, flare view, flare watch), `PhysicalFluidTopology`, `FlowControl`, `ShaftPlan`, `MaterialCatalog.formulas`, `science/equipment/FlareStackDesign`, `ProcessUnit.flareStack`, `client/FlareFlames`, `network/FluidNetwork` (flare-1), `science/flowsheet/unit/FlareUnit`, `sizing/FlareSizing`, flowsheet tile, door, symbol.

## Open items

- Owner review of the renders and the as-built deviations (inlet at 4 m; no ladder; W3 heat and NHV inputs left out; vented gas not booked apart).
- Final lane (MaterialCatalog reaches most tests), translation commit, code guide, CHANGELOG at merge.
- No in-game check of the flame animation (offer only on the owner's request).
