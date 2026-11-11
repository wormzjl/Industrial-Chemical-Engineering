# Flare stack

Status: **Implemented 2026-11-10** (merged as 0.24.0 with the batch still). Branch `claude/furnace-fuel-power-requirements-f7a46e`: it reads the `combustion` sub-records of [2026-11-09-furnace-fuel-power](../2026-11-09-furnace-fuel-power/README.md).

## Scope

Owner, 2026-11-09: a scalable flare stack, capacity by size (research first); flared gas needs a fuel value; inert gas may be flared with fuel gas while the combustion temperature holds; status and flow sensor on the data network; solids block it.

## Documents

- [FLARE_STACK_PLAN.md](FLARE_STACK_PLAN.md): research (heating-value floor, tip velocity, radiation height), proposal W1 to W5, owner decisions X1 to X5, as built (section 6).

## History

- 2026-11-09: research and plan; owner answers X1 flame temperature at 1700 K, X2 vent unburnt, X3 one size, X4 limit to rating, X5 blocked while solids arrive; working animation added (W5).

- 2026-11-09: built W1 to W5 (plan section 6). Targeted tests pass: FlareTest, FlareRuntimeTest (new), FiredHeaterFuelTest, FlowsheetPaletteCoverageTest, SizingCoverageTest, SimulatorFrameTest, FluidPacketCodecTest, ConstructorColumnTest; equipmentGates EquipmentLayoutGoldenTest (3 flare rows added), ProcessUnitTest, ShapeRasterizerTest, EquipmentShapeCompatTest. ProcessUnitTest also needed the furnace batch's heater terminal boxes listed (a gap from 59278550b). `Flare*` joined the equipment gate group. GUI prototypes rendered (`flare-stack-flaring`, `flare-stack-flame-out`, `equipment-constructor-flare`, `equipment-constructor-choose-heat`).
- 2026-11-09: owner report (in game): the data port floated beside the stack and the gas inlet stood out of the thin riser at 4 m. The foot is now a plinth to 2 m; the inlet leaves it 1 m up and the data socket sits on its side (the flare had no service plan before, so its port fell back to a loose mount). equipmentGates ProcessUnitTest, ShapeRasterizerTest, EquipmentShapeCompatTest, EquipmentServicesTest, ConduitGeometryTest pass; EquipmentLayoutGoldenTest table re-recorded (the 3 flare rows only).
- 2026-11-09: owner report (in game): the data port still off centre, the pipe still not joined. Causes: the inlet nozzle's centre stood on a block boundary (1 m, before 4 m) while its pipe block's centre line runs at 1.5 m, so the drawn nozzle and pipe never met; the socket's service case did not fit beside the plinth inside the one-block frame and was slid to the next block. Fixes: inlet centre at 1.5 m; the junction box set into the plinth, its gland on the bottom block's face centre. New gates: ProcessUnitTest checks every nozzle's face on its block face and its centre line on its pipe block's (all kinds but the delayed coker); EquipmentServicesTest checks the flare's gland at every tip and heading. Layout table re-recorded (the 0.1 and 0.35 m flares lose one shell cell; nozzle pipe blocks unchanged). equipmentGates ProcessUnitTest, ShapeRasterizerTest, EquipmentLayoutGoldenTest, EquipmentShapeCompatTest, EquipmentServicesTest, ConduitGeometryTest pass.
- 2026-11-10: merge preparation with the batch still (0.24.0): the datasheet duty line "burns while its flame is at least %s K" (it measured 417 of 411 px), translations, code guide; review in [../2026-11-09-batch-distiller/MERGE_REVIEW.md](../2026-11-09-batch-distiller/MERGE_REVIEW.md).
- 2026-11-11 (batch 2026-11-11-gas-blanketing, D6): X4 changes meaning. Owner: "Flare flowrate should vary with backpressure". The tip no longer limits the flow: it is an open line, its bore the run's last section, shut only while solids arrive (X5 kept). The two limits are the flame's status: above the stable exit velocity FLAME OUT (unburnt, relights below), above the rated heat OVER CAPACITY (burns on); the page's "Most flow" reads "Stable flow". Owner decision, 2026-11-11: "Yes, keep it" (the flame goes out above its stable exit velocity). See ../2026-11-11-gas-blanketing/PSV_REVIEW.md section 4.

## Code areas

`science/fluid/network/Flare`, `runtime/fluid/Flares`, `EquipmentAlarm`, `ShaftTrains`, `FluidWorldAuthority` (placeFlare, flare view, flare watch), `PhysicalFluidTopology`, `FlowControl`, `ShaftPlan`, `MaterialCatalog.formulas`, `science/equipment/FlareStackDesign`, `ProcessUnit.flareStack`, `client/FlareFlames`, `network/FluidNetwork` (flare-1), `science/flowsheet/unit/FlareUnit`, `sizing/FlareSizing`, flowsheet tile, door, symbol.

## Open items

- Owner review of the renders and the as-built deviations (inlet on a plinth 1 m up; no ladder; W3 heat and NHV inputs left out; vented gas not booked apart).
- Delayed coker (found by the new nozzle gate, left out of it): FEED and WATER centres 0.05 m above their pipe's centre line, STEAM 0.35 m low and 0.15 m aside; owner to decide whether to redraw them here or in the big-structure rework.
- No in-game check of the flame animation (offer only on the owner's request).
