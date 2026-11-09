# Fired heater fuel, combustion-air power and alarms

Status: **In progress since 2026-11-09**. Branch `claude/furnace-fuel-power-requirements-f7a46e`. Not committed, not merged.

## Scope

Owner, 2026-11-09: wire the fuel requirement into the furnace, working with the radiant/convection coil model; combustion air from internal fans with an electricity requirement and a power port; power failure stops the heater; short fuel reduces its duty; an alarm system on the data and control system that controllers can read.

## Documents

- [FURNACE_FUEL_POWER_PLAN.md](FURNACE_FUEL_POWER_PLAN.md): physics, proposal, owner decisions F1 to F4 and what was built.

## Code areas

- Burner law: `science/fluid/network/Burners`, `FiredCoil.limit`, `ShaftPlan.Kind.BURNERS/FUEL`, `FlowControl.ValveSource.BURNER`.
- Runtime coupling: `runtime/fluid/FiredHeaters` (on the shaft-train hooks), `ElectricStores` kind `BURNERS`, `FluidWorldAuthority` (burner registration, views), `PhysicalFluidTopology` (burner device, the run into it), `EquipmentStructure` (placing and refreshing burners), `TerminalEnergy`, `PowerPorts`, `EquipmentServices.powered`, `ShapeRasterizer` (FUEL node, heater terminal cell).
- Material data: `combustion` sub-record in 45 records and the water, `MaterialCatalog.Combustion`, `heatingValues`; tool `tools/heating-values/`.
- Alarms: `Sensor.Kind.ALARM`, `BuiltInInputs` alarm channels, `ControlPublication` alarm list, `ControlColumns.alarms`, `ControlPanelScreen`; heater page rows in `FluidDeviceScreen`.
- Formats: fluid checkpoint state format 41 (coil limit); the layout table gains the heaters' terminal cells (cells and pipes unchanged).

## History

- 2026-11-09: plan; owner answers F1 (a), F2 prebuilt LHV in the material DB, F3 (b), F4 (a). Built W1 to W4; LHV tool run. Tests: `FiredHeaterFuelTest` (new), `BurnersTest` (new), `FiredCoilTest`, `MaterialThermochemistryTest`, `HeatSidesTest`, `ShapeRasterizerTest`, `EquipmentLayoutGoldenTest` (table updated). Targeted runs: 283 quick-suite and 65 equipment tests; fixed the test rig (one solver per island) and the layout table; `FiredHeaterFuelTest` 4/4: stiff supply coil 3500 kW (its duty) on 6278 kW fired, fans 5.1 kW; 2.016 bar supply fires 2836 kW, coil 1598 kW, LOW FUEL.

## Open items

- O1 owner: the `combustion` sub-record is in no identity (as `thermochemistry`): an LHV edit changes the next commit's firing, never saved contents.
- O2 owner: a fresh solver's first step has no carried flows, so a radiant coil plans full firing for it (pre-existing W6 rule, "fires fully where its end draws nothing"). After a restructure the coil can take more than its fuel gives for one step.
- O3: the burners' VOID device opens a void page when clicked; the heater's coil pages carry the fuel and fan rows.
- O4 owner: GUI renders sent 2026-11-09 (`GuiPrototypes` gained `firedHeater()` and the alarms group on the control panels; the tool needs the Gson jar on its class path): approval open.
- O5: the process simulator's heater report does not show fuel or fan power yet.
- O6: facts corpus image for a world with burners; translations; code guide; CHANGELOG; version.
