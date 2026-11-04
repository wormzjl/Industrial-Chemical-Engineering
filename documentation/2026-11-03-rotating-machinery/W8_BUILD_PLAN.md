# W8 simulator support: build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9`. The owner said "Start W8". The process simulator is already on this branch, through main 0.20.0.

Binds:
- plan section 11 and D9;
- the simulator's rule that each tile refuses what the world refuses;
- the owner's "always have enough excess", which means a 20 % spare (`Motor.SPARE`);
- the simulator batch's open item for this branch: the column door's Standard / In-line pump selector should be "identified with pump duty requirements".

Sheet files have no compatibility work.

## 1. What the sheet does today

- **Pump and compressor tiles** (`MoverUnit`) book the *ideal* power into the discharge, through the world's set-flow closures. There is no efficiency and no driver.
- **The steam turbine tile** is drawn only. Motors, generators and gearboxes stand on its unlit tile (`FlowsheetCatalog`).
- **The column door** asks for each pump's kind (Standard or In-line). The assembly accepts either kind and only puts the chosen one first (`ColumnBlueprint`).

## 2. Form A, simple (recommended)

- **Pump.** Shaft power = ideal / η.
  - η is the design efficiency at the duty: `CentrifugalPump.rated(flow, head)`, Corripio (W1).
  - All shaft power heats the liquid, which is physical: the losses stay in the fluid.
  - Readings: η, shaft power, and a proposed driver: `Motor.ratingFor` (20 % spare) with its IE3 efficiency, electric power and FE/t.
  - Example, 100 m³/h of water at 80 m head:
    - hydraulic power 21.8 kW, η 0.70, shaft power 31.1 kW;
    - proposed motor 45 kW, η 0.94;
    - 33.0 kW electric, 661 FE/t.
- **Compressor.** The same, with `CentrifugalCompressor.DEFAULT_EFFICIENCY` (0.78). The discharge runs hotter by 1/0.78 of the ideal temperature rise, as in the world's machine.
- **Steam turbine tile, modelled.**
  - Steam comes in and leaves as exhaust at a set exhaust pressure.
  - Power out = flow × isentropic drop × η (`SteamTurbine.isentropicDrop`, `typicalEfficiency`: 0.45 at 100 kW, 0.61 at 1 MW, 0.76 at 10 MW). The exhaust is the inlet minus that work.
  - It refuses what the world's turbine refuses, and its readings are shaft power, η and steam rate (kg/kWh).
- **No shaft wires on the sheet.** Each tile states its shaft power, and the player matches drivers to machines. Motors, generators and gearboxes stay without tiles; the proposed driver is a reading on the machine it drives.
- **A72 closed.** FE/t shows beside electric power.

## 3. Form B, fuller

Everything in A, plus:
- a shaft port, so a turbine tile drives a compressor tile and the sheet balances power;
- tiles of their own for the motor, generator and gearbox.

B adds a new kind of wire (power, not a stream) to the compiler, the solver order and the drawing.

## 4. The column door's pump selector (separate choice)

- **(a) Recommended.** The Standard / In-line keys go. In their place, each pump shows its duty: flow (m³/h), head (m), shaft power (kW), NPSH required (m) and the proposed motor (kW).
  - The design no longer names a kind. The assembly takes either pump for the slot, as it does today, with the standard pump first.
  - Column designs and the calculator block need no compatibility work (AGENTS.md).
- **(b)** Keep the keys and add the duty readings beside them.

## 5. Steps

1. The laws in `MoverUnit`; the turbine's unit model and its tile entry (`UnitTypes`, `FlowsheetCatalog` MODELLED); the readings.
2. The sizing rows: the proposed driver with 20 % spare, electric power and FE/t.
3. The column door per section 4, with a prototype sheet that you look at first.
4. Simulator tests: unit tests of the three laws against the worked numbers. Re-record the simulator references the laws move (pump and compressor discharges run warmer), listed by name in the review. Then simulator GameTests.
5. en_us only.
