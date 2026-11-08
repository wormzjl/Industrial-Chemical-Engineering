# W5 electricity (FE): build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9` at `42ff3e588`. Binds: plan 1.4, 7, 10; D3, D6, D7; A1, A2, A4, A14, D27 to D29. The owner's request of 2026-11-04 ("add power usage" to the forced-draft cooler) is today's air cooler: its fans sit under the bundle, so it is the forced-draft cooler (section 3). Readings A53 to A73 (section 8; A73 is an owner decision) wait on review; nothing is built before the go.

## 1. Expected behaviour (numbers at 2.5 J per FE)

- **Motor.** It draws `P_e = P_shaft/η` (IE3 by rating and poles, −3 % with a variable drive; `Motor.electricPower`). W3's case 1: a pump demanding 24.9 kW on a 30 kW 2-pole motor draws 26.7 kW, which is 534 FE/t.
  - Its buffer holds 40 s at rated draw (two slices at the longest 20 s cadence): 32.2 kW × 40 s = 1.29 MJ, which is 515,000 FE.
  - It takes up to twice its rated draw per tick: 1,288 FE/t.
- **Forced-draft air cooler.** Its fans draw `HeatSides.fanPower(surface, s)/η_fan` (1 kW per m² of bundle face at full speed, `s³` below). A 3 × 9 m bay at full fans draws 27 kW of shaft power and 29.3 kW electric (η 0.92, IE3 4-pole at 15 kW per fan), which is 587 FE/t.
  - With no power the fans stop. The louvers give natural draught (0.25 of the full-fan conductance, `HeatSides.NATURAL_DRAUGHT`), and a regulating cooler reports `LIMITED`.
- **Induced-draft cooling tower.** Its fans draw `CoolingTower.fanPower(speed)/η_fan`. With no power, speed 0: the tower runs on its stack draught alone, which `TowerTransfer` already solves.
- **Generator.** It gives `P_e = η_gen · P_shaft` (η_gen from the motor's IE3 table) into its own buffer. Its shaft load is what its buffer can take: a full buffer takes no load, and a governed turbine then closes its valve (D27).
- **Loss of power.** A motor whose buffer cannot cover a slice books what is left, then trips `TRIPPED: NO POWER` (speed 0) at that commit. It restarts by itself once its buffer holds one slice of its rated draw (D6, A2).

## 2. The store: one electric record per powered unit

- **Facts** (A53, A54). Each powered unit keeps an *electric store*: a motor, generator, air cooler, induced tower or a future Create coupling. The store saves its content in joules, a double, with no FE rounding, so a change of `joulesPerFE` keeps the energy (A55).
  - Key: the unit's id. For a motor or generator that is its `ShaftUnit`; for an air cooler, its heat unit's registration; for a tower, its tower registration.
  - Saved in fluid facts schema 11, with corpus image `compat/fluid/schema-11/`. Schema 10 (W3) stays as its image.
  - The store also saves whether it is metered. A store missing from an older save reads as unmetered: it runs free, as before the field. It becomes metered the first time its port receives FE (A73). A store built from W5 on starts metered and empty.
  - Air coolers and induced towers exist in released worlds, so this is the case that matters. Motors and generators exist only on this branch.
- **Capacity and rate.** The capacity is 40 s × rated electric draw, and the receive rate is 2 × rated draw per tick (plan 7). For an air cooler or tower, the rated draw is full-speed fan power over η_fan.
- **Port.** The structure's terminal cell exposes `Capabilities.EnergyStorage.BLOCK`, registered for the shell block entity type, on every face (A56):
  - receive-only for a consumer;
  - extract-only for a generator (A57).
  
  Other mods' pushes add to the store on the server thread. That is their protocol, not process work, so nothing wakes and nothing solves.
- **Terminal cells** (A58). W4 drew the motor's box. W5 adds one to the air cooler and to the induced tower. Each goes on an outside face at grade, in a cell the structure already owns: no new cell, no nozzle pipe block moved, one free block from any nozzle pipe block. The layout table records the terminal; no cell moves (`EquipmentLayoutGoldenTest`).

## 3. Booking on the engine schedule

- **At a commit** (A59). The draw is booked at the commit of the slice that ran it: a train's commit (W3's step) for a motor, the heat or tower island's commit for fans. `E = P_e · Δt` uses the committed P and the slice's online Δt. A generator books `+E` likewise.
- **While at rest** (A60). An island or train at rest has no commits, so its draw is booked on the presentation cadence (about 5 s) with the elapsed online ticks, at the last committed power. A resting air cooler with running fans still draws.
- **Running short.** When `E > content`, the store books the content and goes to 0, and the unit is marked unpowered. Its next dispatch sees it unpowered:
  - a motor's speed is 0 (`NO POWER`), and the train wakes its islands (`shaftsMoved`);
  - an air cooler's fan effort is capped at 0 through its heat law (`HeatSides.heatSide`'s share);
  - a tower's speed is 0.
- **The unpowered flag** (A61). It is state, not a fact: it rebuilds from the store at load. It reaches the solver as a dispatch input, as W3's `ShaftPlan` does, never as an edit of the player's settings. So the fans' set share and the motor's settings survive a power cut.
- **Restart.** At each presentation cadence, a store holding one slice of rated draw clears its flag and wakes the unit's islands (A62). An `OVERLOAD` or `OVERSPEED` trip still waits for the player's reset (A2).
- **A generator's load** (A63). At its train's step, its shaft demand is `min(P_rated, room/Δt_next)/η_gen`, where room is capacity minus content. A full buffer demands nothing.
- **Generator speed** (A64). A generator at 0.95 to 1.05 of its rated speed delivers its output. Outside that window it takes no load and shows `OFF SPEED`.
- **Pushing out** (A65). A generator's port also pushes FE to receivers on the six faces of its terminal cell each server tick, up to its buffer. That is the FE protocol, as receiving is.

## 4. Config (A66)

- `energy.joulesPerFE`, default 2.5. It replaces W3's constant `ShaftTrains.JOULE_PER_FE`, which every reader then uses.
- `energy.machinesNeedPower`, default true. When false, nothing is booked, no store trips and the ports still accept FE: a pack without energy mods runs motors and fans free.
- Both live in the common config `icheme-common.toml`, in a new `energy` section.

## 5. What stays without FE

- In-line movers (D3: utility movers with an integral motor), the creative motor, steam turbines, ejectors and natural-draft towers draw nothing.
- A fired heater's burners burn fuel, not FE.

## 6. Presentation (no screens: W7)

- **Driver view.** W3's `ShaftTrains.View` gains the store's content, its capacity, FE/t in and out, and `NO POWER` and `OFF SPEED` (A67).
- **Heat and tower views.** They gain `NO POWER`, the electric draw and the store. The air cooler's device page keeps its `fan_power` meter, and its status line names `NO POWER` (A68).
- An unmetered unit's status line shows the notice `UNMETERED` (non-blocking).
- Data reaches clients only through the existing requests: W3's driver watchers and the device page's subscription. Status words stay in English. New lang keys go into all five files.

## 7. Build order and tests

1. `science/machine/ElectricStore` (Minecraft-free): capacity, rate, booking, unpowered, restart, generator load. Test: `ElectricStoreTest`, with section 1's numbers.
2. Facts schema 11 and the corpus image (`FluidFactsCompatTest` writes it when missing). Test: the old images still read; a schema-10 save reads with empty stores.
3. Terminal cells (air cooler, induced tower). Tests: `ShapeRasterizerTest` and `EquipmentLayoutGoldenTest` (terminal rows only).
4. Booking in `ShaftTrains` and the heat and tower commits; the unpowered dispatch input; restart on the cadence. Tests:
   - `ShaftCouplingTest` (a motor runs short, trips, refills, restarts);
   - `HeatUnitPowerTest` (an unpowered cooler at natural draught, its set share kept);
   - a tower at speed 0.
5. The capability registration and the generator push. GameTest `ElectricGameTests`, which fills a port through the capability:
   - a motor-driven pump runs, drains, trips `NO POWER` and restarts on refill;
   - an air cooler without power holds natural draught;
   - a generator on a creative motor fills a neighbour.
6. Config, views, lang (five files), `check.js`.

- **Affected tests.** By `--tests`. The campaign runs once, alone, on the final code: no campaign world has a powered unit, so it guards against side effects only (A69).

## 8. Readings (recommended answer: reason)

- **A53.** One electric store per powered unit, keyed by the unit's id. A powered structure always has one registration, and trains, heat and towers already key their facts that way.
- **A54.** Fluid facts schema 11 with its own corpus image, not an edit of W3's schema 10. Schema 10 has an image and a reader; the rule is append-only.
- **A55.** The content is saved in joules, as a double. FE rounding happens only at the port. A ratio change neither creates nor destroys energy.
- **A56.** The port works on every face of the terminal cell. Cables from any side connect, as on other mods' machines.
- **A57.** A generator's port is extract-only, and a consumer's receive-only. No machine is both: a motor-generator set is two machines.
- **A58.** The terminal cell sits on an outside face at grade, in an existing cell, one block clear of nozzle pipe blocks. A cable can reach it, and the layout table records it without moving a cell.
- **A59.** Draw is booked at the commit that ran it, at its own P and Δt. This is plan 7, and it puts no rounding on small loads.
- **A60.** A resting unit is booked on the presentation cadence at its last power. A resting cooler or motor still turns.
- **A61.** Unpowered is state and a dispatch input. A power cut must not overwrite the player's settings (the facts rule).
- **A62.** The restart threshold is one slice of *rated* draw, checked on the presentation cadence. A2's "does not chatter" holds even at a light load.
- **A63.** A generator's load is the room left in its buffer. A full buffer takes no load, and the governor closes the valve: a grid that takes nothing is a load rejection.
- **A64.** A generator delivers only within ±5 % of its rated speed, else `OFF SPEED`. A synchronous machine needs its grid speed; ±5 % covers the governor's dead band.
- **A65.** A generator pushes to its six neighbours each tick. FE generators push by convention; pull-only cables still extract.
- **A66.** The config has the plan's two keys, in the common config. They join the existing sections, with no new file.
- **A67.** W3's driver view carries the store. W7's screens read one view.
- **A68.** The air cooler's status line names `NO POWER`. A reason the player must see before W7.
- **A69.** One campaign on the final code. No campaign world is affected (lean measurement).
- **A70.** η_fan uses the IE3 4-pole efficiency at the power of one fan. Fan motors are 4- or 6-pole, sized one per fan.
- **A71.** Cooling-tower fans and air cooler fans share one store per structure, at one terminal box. That is real plant practice: one MCC feeder per bay.
- **A73 (owner decision: the facts rule).** Air coolers and induced towers already in a world, which ran their fans free:
  - **A (recommended, follows the rule):** they stay unmetered, with the notice, until their port first receives FE. An old world behaves as before until the player wires it. The cost is one boolean and a free ride for old structures left unwired.
  - **B (an exception to the rule):** every cooler and tower needs FE from the update on. Old coolers fall to natural draught (0.25) and old towers to stack draught until they are wired. `machinesNeedPower = false` restores the old behaviour for a whole world.
- **A72.** No FE price on the process simulator's sheet until W8. The simulator already shows fan and shaft power in kW.
