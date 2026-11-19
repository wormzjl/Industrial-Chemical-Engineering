# Device GUI label review

**56 existing English label entries changed, covering 42 distinct old phrases.** The unit of counting is one localization key in the GUI before this change. Repeated uses of a key count once. A shared label split by device or failure mode counts once for the original. New furnace readings, hovers and tutorial sentences are outside the count.

The review started with the equipment device and furnace panels, then searched the English GUI catalog for repeated terms such as “Rate”, “Life”, “Remaining”, “Inlet”, “Drop” and “Duty”. The table includes the constructor, column operation and related machine and process-simulator labels where the same issue occurred. Labels were checked against the displayed quantity, its units and the surrounding group.

| GUI key (after `gui.icheme.`) | Previous label | Current label |
|---|---|---|
| `assembly.operation.group.integrity` | Integrity | Corrosion |
| `assembly.operation.wall_left` | Wall left | Corrosion allowance left |
| `assembly.operation.wall_rate` | Wall rate | Corrosion rate |
| `equipment_constructor.duty.exhaust` | Pressure | Exhaust pressure |
| `equipment_constructor.repair.wall` | Wall %s %% left | Corrosion allowance remaining: %s %% |
| `flowsheet.door.drop` | Drop | Pressure drop |
| `flowsheet.door.duty` | Duty | Heat duty |
| `flowsheet.door.rise` | Rise | Pressure rise |
| `flowsheet.limit.air_cooler_drop` | Drop | Air cooler pressure drop |
| `flowsheet.materials.life` | Life | Estimated service life |
| `flowsheet.results.fired` | Fired | Fired heat duty |
| `flowsheet.sizing.passage.drop` | Drop | Pressure drop |
| `fluid.burner_fans` | Air fans | Air fan power |
| `fluid.converter_group.rate` | Rate | Transfer rate |
| `fluid.device_group.integrity` | Integrity | Corrosion |
| `fluid.duty` | Duty | Heat duty |
| `fluid.duty_available` | Rated duty | Radiant duty limit |
| `fluid.efficiency` | Efficiency | Hydraulic efficiency |
| `fluid.fouling_rf` | Fouling | Fouling resistance |
| `fluid.fuel_fired` | Fired | Burner firing duty |
| `fluid.fuel_flow` | Fuel | Fuel mass flow |
| `fluid.gradient` | Pressure drop | Pressure drop per length |
| `fluid.gradient_highest` | Highest drop | Highest pressure gradient |
| `fluid.gradient_lowest` | Lowest drop | Lowest pressure gradient |
| `fluid.head` | Head | Pump head |
| `fluid.health_left` | Remaining | Machine health left |
| `fluid.heat_field.bypass` | Bypass | Bypass flow fraction |
| `fluid.heat_field.duty` | Duty | Heat duty |
| `fluid.heat_field.fans` | Fans | Fan speed |
| `fluid.heat_field.low_limit` | Low limit | Minimum outlet temperature |
| `fluid.heat_field.outlet` | Outlet | Outlet temperature |
| `fluid.heat_side.convection` | Convection | Convection coil |
| `fluid.heat_side.radiant` | Radiant | Radiant coil |
| `fluid.life_left` | Life | Time to machine failure / Time to wall failure / Time to trim failure |
| `fluid.pressure_change` | Pressure change | Pressure drop / Pressure rise |
| `fluid.relief_pressure` | Pressure | Protected pressure |
| `fluid.set_temperature` | Set point | Radiant outlet target |
| `fluid.speed_share` | Speed | Rated speed fraction |
| `fluid.still_field.reflux` | Ratio | Reflux ratio |
| `fluid.still_level` | Level | Pot liquid level |
| `fluid.throughput` | Flow | Through-flow rate |
| `fluid.trim_health` | Remaining | Valve trim health |
| `fluid.ua` | UA | Thermal conductance |
| `fluid.ua_left` | UA left | Clean conductance remaining |
| `fluid.velocity` | Speed | Flow velocity |
| `fluid.velocity_highest` | Highest speed | Highest flow velocity |
| `fluid.velocity_lowest` | Lowest speed | Lowest flow velocity |
| `fluid.wall_left` | Wall | Corrosion allowance left |
| `fluid.wall_mechanism` | Cause: %s | Wall damage mechanism: %s |
| `fluid.wall_rate` | Rate | Corrosion rate |
| `machine.exhaust` | Exhaust | Steam exhaust pressure |
| `machine.inlet` | Inlet | Steam inlet pressure |
| `machine.output` | Output | Electric output power |
| `machine.output_limit` | Output | Output power limit |
| `machine.ratio` | Ratio | Gear ratio |
| `machine.ratio_now` | Ratio | Operating gear ratio |
