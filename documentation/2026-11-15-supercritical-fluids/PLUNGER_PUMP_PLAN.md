# A reciprocating plunger pump: plan

Batch 2026-11-15-supercritical-fluids, stage S8. Main thread, 2026-11-16. Basis:
[PD_PUMP_RESEARCH_REVIEW.md](PD_PUMP_RESEARCH_REVIEW.md); the owner accepted its recommended options ("Accept all"):
a plunger pump of its own, the existing displacement law without a clearance term, the work as flow times rise, no
chilled head, a "Plunger pump" type on the simulator's sheet with no minimum-density row.

## 1. What it is

A triplex reciprocating plunger pump (API 674): a power end (crankcase), a liquid end with three plungers, one suction
and one discharge nozzle, a shaft end for a driver. It delivers its displacement at its train's speed against whatever
rise the system needs, up to its relief rise. It is the pump for what the centrifugal pump cannot do: one dense fluid
lighter than its critical density, and small flows to high pressures. On a liquid it is a pump; on a gas it moves the
gas's volume, as the reciprocating compressor does.

## 2. Lanes and order

- Lanes touched (COLLABORATION.md section 2): machines (`science/machine`, `ShaftTrains`), equipment
  (`EquipmentShape`, `UnitDesign`, `world/**`), client (the constructor, the device screen), flowsheet (the sizing).
  Hot files: `EquipmentShape`, `ShaftTrains`, `FluidDeviceScreen`, `SheetSizing`.
- S5 writes the client and the sizing now, so this stage starts after S5 is checked.
- Main is at 0.33.0 (`d756e121a`: the pollution system, fluid interfaces and equipment visual design batches). The
  owner first chose "Pump first, merge last"; after the numbers below were found, "uh merge main first" (2026-11-16).
  So main is merged into the branch before this stage, and the pump is built on main's numbers.
- Numbers. This plan first said none was needed: wrong. A casing's law is saved in the fluid facts by type name
  (`FluidFacts.control`, the machine record: `centrifugal_pump`, `centrifugal_compressor`, `vacuum_pump`) and in a
  solved graph by a tag (`FluidCheckpointCodec`, tags 5 and 8); the vacuum pump's law took facts schema 19 and
  checkpoint 44. The plunger pump's law takes **fluid facts schema 28** (the type `plunger_pump` in the record's
  existing fields: the displacement in the rated flow's place, the relief rise in the head's, the efficiency, the
  rated speed) and **checkpoint state format 51** (a new tag). Both are reserved on `main`, with protocol `icheme-4`
  in case the new kind changes a constructor payload (`66a87f9d5`, a documentation-only commit; a row not used is
  removed). A corpus image goes under `src/test/resources/compat/fluid/schema-28/`. No shape field: the kind is saved
  by name, at the end of the enum.
- Why not the set-flow `FlowControl.Pump`, which needs no number: its rise limit is a head, scaled by the suction's
  density over the reference's (decision D11 A2). 25 MPa would be about 8 MPa on carbon dioxide at reduced density
  0.7, against the pump's purpose.

## 3. The law

`science/machine/PlungerPump.java`, a record that implements `MachineLaw` (added to its `permits`), on the pattern of
`VacuumPump`, which already wraps `PositiveDisplacement`:

- `PlungerPump(displacement, reliefRise, ratedSpeed)`: the suction volume flow at rated speed and no rise (m³/s), the
  rise at which its relief valve passes the whole delivery back (Pa), rpm.
- `liquid()` is true: it draws through the liquid suction rules (the suction limit E at a real flash, p_v, the dry
  suction refusal).
- `rise` and `riseSlope`: `PositiveDisplacement(displacement, reliefRise)` at the train's speed. No density, no
  pressure and no `k` enter: the slip (0.4 % per relief rise) and the relief line only.
- `fluidPower = Q ΔP` (mass flow over the suction density, times the rise). `shaftPower`: the whole displacement at
  the speed times the rise, over a mechanical efficiency of 0.88 (the plungers displace against the rise whether the
  fluid is delivered or relieved); finite at no flow.
- No head share: `FlowControl.Machine.withHeadShare` leaves this law as it is (the gas entrainment law is the
  centrifugal pump's). No NPSH3 of its own in this stage (its suction limit is E and p_v with no breakdown term).
- No clearance term and no real isentropic work (research review options 2 and 3): the delivery is 0.4 to 4.5 % high
  and the work 2 to 21 % high on a dense fluid, both stated in the javadoc.
- Wear: no new mode. The centrifugal pump's low-flow and gas-lock readers must not fire on it (they test the law's
  type today: check each).

`MachineRatings.machineControl`: `case PLUNGER_PUMP -> new FlowControl.Machine(UnitDesign.plungerPump(shape), 0, true)`,
as the vacuum pump casings. `FlowControl` itself does not change.

## 4. The equipment

The vacuum pump casing is the worked precedent (batch `documentation/2026-11-10-vacuum-pump/`; the files that name
`DRY_SCREW_PUMP`): `EquipmentShape` (the kind, `mover()`), `UnitDesign` and a `PlungerPumpDesign` beside
`VacuumPumpDesign`, `VesselPlacement`, `ConstructorColumn`, `MachineRatings`, the flowsheet unit, the tests
`ProcessUnitTest`, `SizingCoverage`, `FlowsheetCatalog`. `ColumnBlueprint` does not apply.

- Models by nominal displacement at the design speed, as the vacuum pumps are chosen: 3, 10, 30 and 100 m³/h at
  300 rpm, relief rise 25 MPa (the network's envelope is 30 MPa). The largest takes about 790 kW at its relief rise.
  A motor at 1,500 rpm drives it through the gearbox.
- Layout: a baseplate, the power end, the liquid end with the suction nozzle low and the discharge nozzle on top,
  the shaft end at the side. `EquipmentLayoutGoldenTest` gets its rows.
- Model, moving parts and sound by `.claude/skills/models-and-sfx/SKILL.md`. The form (owner, 2026-11-16: "Form A",
  from three scratch forms rendered side by side: A monoblock, B valve pots and bottles, C vertical): a horizontal
  triplex. Its crankcase has the crank along the shaft line and bearing covers at both ends; three crosshead guides
  leave its nose; an open cradle shows the three plungers in their stuffing boxes; the liquid end is one forged block
  with a bolted cylinder cover in front of each plunger and a valve cover on top of each, overhanging the skid; the
  suction manifold lies under the block; a bladder dampener stands on the discharge. The plungers slide and the shaft
  end turns with the train's speed; a reciprocating sound. The main thread draws it (owner: "model the pump head
  here"); the stage's thread builds the rest round it.
- Drawings by `tools/equipment-drawings/README.md` (`update.ps1 -Check`, then `-Only`).
- Texts in `en_us.json` only: the constructor's entry and hint, the vessel title and kind line, the assembly's
  "wanted" line, the tutorial's equipment entry.

## 5. The simulator

- `UnitType.PLUNGER(EquipmentShape.Kind.PLUNGER_PUMP)`; `FlowsheetCatalog`: MODELLED; a palette tile (the pump's
  tile may hold both kinds); `FlowsheetPaletteCoverageTest`.
- `PumpSizing`: the type is the player's or Auto's. Auto proposes the plunger pump where the flow is within its range
  (at most 100 m³/h) and either the rise is above what one centrifugal casing gives or the suction is one dense fluid
  below reduced density 0.86 (S5's "Suction density" row would light for a centrifugal pump there). Rows: flow
  against the model's displacement, rise against the relief rise. No suction density row, no NPSH row.
- The sheet's power: flow times rise over 0.88.

## 6. Tests and checks

- `PlungerPumpTest`: the law's points (the displacement at no rise, the slip, the relief line), the power at no flow,
  the same answer at any density.
- On carbon dioxide at 310 K and reduced densities 0.6, 0.8, 1.0 and 1.6: the delivered volume is the displacement
  less the slip at every one of them, where the centrifugal pump's head share is 0.60, 0.99, 1 and 1 (S4b).
- A dense GameTest in the `icheme_dense_test` namespace: a plunger pump on a gearbox and a motor lifts carbon dioxide
  at reduced density about 0.7 from 8 to 20 MPa; ledgers closed; run alone.
- The equipment's gates (`equipmentGates`), the layout table, the palette coverage, the drawing check, the software
  renders. Chain-100: deviation 0 (no mover in it is a plunger pump).
- An in-game check is offered to the owner at the end, not started.

## 7. Open for the owner after the build

Whether Auto's rule for the sheet's type is right; the clearance term and
the real work as their own batch, with the reciprocating compressor.
