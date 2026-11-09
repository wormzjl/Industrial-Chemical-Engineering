# Batch still: the page, the constructor options and the animation (W6)

Batch 2026-11-09-batch-distiller, phase 3, written on `claude/still-gui-80f9b4` from a8f7a9c68; compiled, fixed and
tested on the main thread, 2026-11-09 (renders in `tmp/still-gui-renders/`).

## 1. The still's page (the pot's own screen)

- The pot's screen (`FluidDeviceScreen`, Overview) leads its door column with `StillDoor` (Minecraft-free): **Firing**
  (plate Damper, Burners or Power by heater; Off and On, then Opening, Firing or Power in %), **Reflux valve** (a still
  with a column only: Closed and Open, then Ratio), **Fuel** (solid only: Load and Take out, the item, Items, Fuel left in
  min). A line "Controller: x" stands under a setting a data-network controller holds (`Stills.firing`/`reflux` differ
  from the `StillUnit` setting). Then the tank's Phases and Level, then the readings: Pot, Head, Level (m), Heat (kW),
  Distillate, Vent, Condensed (%), Reflux (the column's effective ratio). Status and alarms stay on the message display.
- Data: `FluidView.Still` (fluid protocol `fluid-20`), built at the bucket from `Stills.view`, the facts and the pot cell's
  fuel slot (`FluidWorldAuthority.stillView`, a read). Opening replays the last snapshot, as every fluid page does.
- Apply: `FluidNetwork.Controls.still` (`StillControls`: firing 0..1, ratio 0..100, closed). The edit handler's admission
  as before, then `FluidWorldAuthority.stillSettings` (a fact; wakes the islands); the reply comes with the next bucket.
- Load and Take out: `StillFuelPayload` (registrar `still-1`), an item move at once, as a hopper's; the slot wakes the pot.

## 2. Constructor options

W2 left the constructor building the default still. Now: Heater (Solid, Liquid, Electric), Condenser (Built-in, None;
None steps the column to 0) and Stages (0, 2 to 8) on the design page (`ConstructorColumn.still`); the draft carries
`BatchStillOptions` (`EquipmentConstructorBlockEntity.Design.still`, saved as a `Still` compound only when not the
default; absent reads the default), and `ConstructorPayload` carries it (`equipment-22`). The constructor corpus has
its image: `compat/constructor/designs-2.snbt` (an electric pot still, a liquid-fired still with four trays); older
images read the default still options.

## 3. Animation (`client/StillFires`)

A watch like the flares' (`Watch.STILLS`, `StillState` on `still-1`, published every bucket to watchers within 48
blocks): heater, status, fire (heat over rating), boiling, dripping (distillate over 5e-5 kg/s). Drawn at the W2 marks:
solid, embers and flames at the door, crackle, cosy smoke at the flue; liquid, the burner's jet and smoke; electric, the
lamp lit (amber dust); boiling, a bubbling sound at the pot; a drip of falling water under the spout.

## 4. Sockets

- Diverter valve: `LineGeometry.diverter` draws a plug body and an actuator case to the free face, whose end carries the
  data gland the cable tool aims at; `LineGeometry.diverterSocketFace` is now the one rule `DataPorts` also uses.
- Electric still FE socket: it did not exist. `EquipmentServices.powered` had no BATCH_STILL, so no power gland, no
  terminal cell (`ShapeRasterizer.terminal`) and no `PowerPorts.eligible`: the electric still could take no FE and stood
  at NO POWER. Fixed in the three places: a power gland on the terminal box's face opposite the data socket, the rule
  every powered unit keeps (`EquipmentServicesTest`). The electric still's layout gains the terminal cell (layout table).

## 5. Owner questions (each built as option A; owner kept G1 to G4 as built, 2026-11-09)

| # | Question | Built (A) | Other options |
|---|---|---|---|
| G1 | Where the still's controls stand | the pot's Overview door, no new tab | B: a Still tab with a schematic on the glass |
| G2 | The fuel slot | Load and Take out keys, the item and count shown | B: a furnace-style slot with the inventory; C: use the firebox with fuel in hand |
| G3 | Off | sends firing 0; the opening is kept in the draft only | B: a saved fire-off flag beside the opening (a new fact field) |
| G4 | Effects | vanilla particles and sounds | B: own particles and a boiling sound event |
| G5 | Constructor draft's options | a `Still` compound with its corpus image `designs-2.snbt` (main thread: the draft has a corpus, as ExchangerType) | |
| G6 | Electric FE gland | opposite the data socket, as every powered unit (main thread: an `EquipmentServicesTest` rule) | the front under the lamp, against that rule |

## 6. Not done here

The constructor's layout table, drawings and in-game check are the main thread's (no dev client); `FlowsheetPaletteCoverageTest`
needs no run: no palette line changed.
