# Fired heater fuel, combustion-air power and alarms

Status: **Implemented 2026-11-10** (merged as 0.24.0 with the flare stack and the batch still). Branch `claude/furnace-fuel-power-requirements-f7a46e`.

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

- 2026-11-09: plan; owner answers F1 (a), F2 prebuilt LHV in the material DB, F3 (b), F4 (a). Built W1 to W4; LHV tool run. Tests: `FiredHeaterFuelTest` (new), `BurnersTest` (new), `FiredCoilTest`, `MaterialThermochemistryTest`, `HeatSidesTest`, `ShapeRasterizerTest`, `EquipmentLayoutGoldenTest` (table updated). Targeted runs: 283 quick-suite and 65 equipment tests; fixed the test rig (one solver per island) and the layout table; `FiredHeaterFuelTest` 4/4: stiff supply coil 3500 kW (its duty) on 6278 kW fired, fans 5.1 kW; 2.016 bar supply fires 2836 kW, coil 1598 kW, LOW FUEL. Committed `59278550b`.
- 2026-11-09: owner: O1 accepted (combustion in no identity), O2 fix it, "Most duty" replaced. A segment that starts with no flows known and plans a fired coil or controlled air cooler now plans from one rate solve's flows (`PassiveIntervalSolver`, `PassiveStepSolver.plansHeat`). The heater row reads "Rated duty" with a hint naming what lowers the firing.
- 2026-11-10: merge preparation with the flare and batch still batches (0.24.0): translations, code guide, CHANGELOG; review in [../2026-11-09-batch-distiller/MERGE_REVIEW.md](../2026-11-09-batch-distiller/MERGE_REVIEW.md).

## Open items

- O1 closed 2026-11-09: the `combustion` sub-record stays in no identity (owner: yes).
- O2 fixed 2026-11-09: a fresh solver's first step planned the coil at no flow (full firing) and overshot its duty and fuel by about 10 %; it now plans from one rate solve's flows.
- O3: the burners' VOID device opens a void page when clicked; the heater's coil pages carry the fuel and fan rows.
- O4 owner: GUI renders sent 2026-11-09 (`GuiPrototypes` gained `firedHeater()` and the alarms group on the control panels; the tool needs the Gson jar on its class path): "Most duty" renamed "Rated duty" (owner); approval of the rest open.
- O5: the process simulator's heater report does not show fuel or fan power yet.
- O6 closed 2026-11-10: no facts image is owed (the burners are registrations in the existing facts schema; the batch changed state format 41 only); translations, code guide, CHANGELOG and version in the 0.24.0 merge.
