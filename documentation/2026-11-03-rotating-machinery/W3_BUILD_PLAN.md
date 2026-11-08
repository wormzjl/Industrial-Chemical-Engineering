# W3 shaft trains: build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9` at `581fb58bd`. Binds: plan 1.3, 4.1, 5, 9, 10; D4, D22, D26, D27; A2, A3, A5, A14. Readings A15 to A40 (section 7) wait on review; nothing is built before the go.

## 1. Trains: data, recompile, owner, identity

- **Facts.** Trains must run while their chunks are unloaded, as islands do, so their parts are registered facts and are not walked from blocks (A15). A *shaft unit* is one record per shaft block (cell, axis), per creative motor (cell, facing), and per placed structure with shaft ends: motor, generator, gearbox, steam turbine, pump/compressor casing. Each structure stores its world shaft-end cells (`ShapeRasterizer.shafts`: cell, direction, `DRIVER_OUTPUT`/`DRIVEN_INPUT`), its rated values from `MachineDuty`, and for a casing or turbine its fluid device id. The units live in `WorldTopologyLedger` beside the column units (`columns` precedent), with ids from `nextIdentity`.
- **Compile** (`science/machine/ShaftNetwork`, Minecraft-free). Union-find over the cells. A shaft block joins the shaft blocks on its axis at ±1. A shaft end joins the shaft block standing in its `block()` cell on the same axis. Machines never couple face to face (A16). A gearbox's two ends lie in two trains, joined by its ratio and η 0.98. Results per train: its driver, its driven casings (each with its ratio to the driver shaft and the product of the gear efficiencies), and its generators. Refusals: two drivers, or a gear loop. The train then stands and shows `TWO DRIVERS` (A17).
- **When.** Only on shaft events (section 5). Only the trains of the touched units recompile. Never per slice.
- **Owner.** `runtime/fluid/ShaftTrains`, created by `FluidWorldAuthority` beside `ColumnOperation`. It owns the compiled trains and their state, and is installed in `IslandCoordinator` as a `Shafts` hook (the `Ports` precedent).
- **Identity.** A train is state. Its key is its lowest member unit id: stable across reloads, and it changes only when the train is cut or joined. Settings, trips and bindings sit on units (the driver, the turbine), never on a train.

## 2. Per-slice driver logic

- **Speed reaches the islands.** The driver shaft speed n is in rpm. A casing's share is `s = n·ratio/n_rated`; a 4-pole pump on a 2-pole motor runs at s = 2 (A18). At dispatch, `Shafts.plans(island, slice)` gives a `ShaftPlan` per connection: s for a `FlowControl.Machine`; Q_d·s for a reciprocating frame (`Compressor`, A32); the mass target for a governor valve (`ValveSource.SHAFT_SPEED`). `PassiveStepSolver.shafts(graph, plans)` applies the plans before the rest test, as `ports()` applies a column's plans, with `VALVE_DEADBAND`. Speed 0 stands a machine `CLOSED`. A casing with no train shows `NO DRIVER` (D4). A casing behind a tripped driver shows `DRIVER TRIPPED` (A39).
- **Power comes back.** `ConservativeTransport` books a `SHAFT` boundary transfer at every worked connection's id: +W for a mover (the fluid work, already counted in `pumpWork`), −W for a turbine (as now). No conservation term changes (A27). At each commit and replay, `Shafts.booked` records P_i = W_i/Δt. A pump's demand is P_i/η(Q_end, s): S1, the losses are not booked into the liquid. A compressor's demand is P_i. Train demand: `P_d = Σ P_i/η_gears`.
- **Step.** After member commits, at the coordinator's next decision point, once per train per tick (A28). The step is pure and lives in `science/machine/ShaftTrain.step`:
  - Fixed motor: s = 1. If P_d > P_rated: latched `TRIPPED: OVERLOAD`, n = 0, waits for a reset (A2, A21).
  - Variable-speed motor: the set speed, or `SpeedControl.regulate` of the first casing's flow or discharge pressure (A30, A31). Above P_rated, `powerLimited` gives `CURRENT LIMITED`.
  - Turbine (D27): needs a bound speed sensor (section 3). The governor target is `ṁ = P_d(set speed)/(η_is Δh_is)`, with Δh_is from the inlet's committed P, ρ, k and the exhaust node's P. It is set at the turbine island's dispatch. If the turbine books P_t < P_d (valve wide open), `STEAM LIMITED` and `s' = s (P_t/P_d)^(1/6)`. If P_t > P_d with no valve to cut it, the same step rises without a cap; above 1.10 it gives a latched `TRIPPED: OVERSPEED` (A22, A23).
  - Creative motor: holds its set rpm up to its power limit, then `powerLimited` (A19).
  - Generator: a load of 0 until W5 (A34).
  - Motor draw (A14): `Motor.electricPower` is shown, nothing is booked (A33).
- **A change wakes.** A speed change above 1e-3 of rated, a trip, a start or a reset calls `IslandCoordinator.shaftsMoved(islands)` and ends the certificates (`toWake`). A train whose islands rest gets no commits and takes no steps: its speed holds.
- **Case 1: pump on a 30 kW 2-pole motor.** The pump: 100 m³/h at 80 m, ρ 800, η 0.70 (A5). Hydraulic power 17.43 kW, so a 5 s slice books 87.2 kJ of SHAFT work and demands 24.9 kW. s = 1, the motor RUNNING; electric 24.9/0.933 = 26.7 kW (534 FE/t shown). At 150 % flow, demand 0.35 + 0.65·1.5 = 1.325 × 24.9 = 33.0 kW > 30 kW: a fixed motor trips at the next step, and the pump island wakes at s = 0. A variable-speed motor on a friction line (P ∝ s³) steps 0.984, 0.976, 0.973 toward 0.969; the error halves each slice.
- **Case 2: turbine-driven pump, 40 bar/400 °C to 4 bar.** The smallest turbine of at least 1.2 × 24.9 kW is 55 kW: η_is 0.45, Δh_is 524 kJ/kg. Governor target 24.9/(0.45·524) = 0.106 kg/s (15.3 kg/kWh). The exhaust leaves 236 kJ/kg lower, about 257 °C at 4 bar. With 0.080 kg/s at most: P_t 18.9 kW, s goes 0.955, 0.933, toward 0.912. If the discharge closes on an ungoverned turbine: demand 8.7 kW (shutoff, 35 %), s' = (24.9/8.7)^(1/6) = 1.19, an OVERSPEED trip. A governed one shuts its valve and holds 1.0.

## 3. Speed sensor

- An accessory item, `speed_sensor` (`Sensor.Kind.SPEED`, saved by name). It mounts on a steam turbine's coupling guard; the host is the turbine's fluid registration. One per turbine (A24).
- **Binding (A25).** On install, the engine walks upstream from the turbine inlet through unbranched pipes in its island to the nearest `ControlValve`. One registry edit sets that valve's source to `SHAFT_SPEED` and its sensor to the sensor's UUID, through the `InstrumentService.editSensorValve` path. A valve that holds its own `valve_sensor` refuses the binding (`NO GOVERNOR VALVE`). Removal, a cut line or a changed binding reverts the source to `NONE`; the last target is kept.
- `FlowControl.ValveSource` appends `SHAFT_SPEED`. Its target is the mass target, set each slice by the plan.

## 4. Saves

- **Fluid facts schema 10.** Every field is appended; its default is the behaviour before it.
  - New control `machine` (centrifugal pump or compressor; law by name, rated flow m³/s, head in m or J/kg, η, rated rpm, enabled). It also takes the set flow and the set discharge pressure (default 0 = none, A30). Speed is not saved; it reads 0.
  - New control `turbine` (rated W, η_is). `TopologyCompiler.Kind.TURBINE` (A37). `ValveSource.SHAFT_SPEED`.
  - The world record appends `shafts`, the shaft units. Each has: id; kind by name; position; ends (cell, direction, role); fluid id (default 0); rated W, rpm and poles; drive by name; settings (running, default true; mode SPEED; set point, default 0 = design speed; creative power limit, default 1 MW; gearbox ratio, default 0 = auto, A20); trip by name (default `NONE`).
  - Owner: `WorldTopologyLedger`. The FE buffer is W5; health is W10b (schema 11).
  - Old casings with a `pump` or `compressor` control change to `machine` when they are restored on chunk load, from the shape's design point (A3), with their set flow kept (A29).
- **Accessory facts schema 3.** Adds the SPEED kind. Corpus image `compat/accessories/schema-3.snbt`.
- **Corpus `compat/fluid/schema-10/`.** It holds a motor, 3 shafts and a pump casing; motor, gearbox (4.27) and centrifugal compressor; a turbine, a `SHAFT_SPEED` valve and a pump; a creative motor; a motor tripped on OVERLOAD. `FluidFactsCompatTest` counts machines 2, turbines 1, speed valves 1, trips 1, and the shaft units. Images 1 to 9 must still read.
- **State.** Checkpoint format 35 holds the train states (rpm, booked powers, secant history, governor target, status), the machine codec's new fields and machine SHAFT boundaries. On a mismatch they are dropped and the trains start at their set speeds. Wire protocol `fluid-18` (A36).

## 5. Events and presentation

- **Queued `ShaftEvent`s.** Shaft or creative-motor placed or broken (block hooks). A structure whole or removed (`EquipmentStructure` register, restore, remove). Settings, reset and ratio events (the debug command `/icheme shaft`; W7 packets later). They apply at the tick drain, then recompile and wake. No packet handler or block-entity load acts directly.
- **Snapshot.** Published on the engine's ~5 s schedule and replayed on request. `FluidView.Machine` serves the casing and turbine pages: driver kind, status word, rpm, s, shaft W, and for a turbine its steam kg/s and governor bound. `ShaftTrains.view(unit)` serves drivers, gearboxes and the creative motor: status, rpm on each side, demand, shaft W, electric W and FE/t (A14), set points. These are for W7. Sounds: a `DriverState` watch at the presentation cadence calls `DriverSounds.accept`, and `MoverState` gains the speed share (A36).

## 6. Build order and tests

1. `science/machine/ShaftNetwork`, `ShaftTrain` (step), `SpeedControl.balance` (the uncapped step). *ShaftTrainTest*: cases 1 and 2 to 1e-3; gear 4.27 and η .98, a 796 kW compressor demands 812 kW of a 1 MW motor (848 kW electric); creative limit; generator 0. *ShaftNetworkTest*: 1 train; a broken shaft gives 2 and NO DRIVER; a gearbox gives 2 trains; TWO DRIVERS; an off-axis shaft does not join.
2. `FlowControl` (`SHAFT_SPEED`, the machine fields), `ConservativeTransport` (SHAFT per mover), `PassiveStepSolver.shafts`. *Tests*: s 0 gives CLOSED; s 0.81 on plan 1.1's line gives 70 % flow; Σ machine SHAFT = `pumpWorkJoule`.
3. `ProcessSolveServices.FluidIslandCommand` (plans), `IslandCoordinator.Shafts` and `shaftsMoved`. *ShaftCouplingTest*: a pump island lags its turbine island by one slice; a certified island stays certified while s holds, and a change of s wakes it (`couplingWakes` +1).
4. `ShaftUnit`, `WorldTopologyLedger.shafts`, `FluidFacts` 10, `FluidCheckpointCodec` 35, `AccessoryFacts` 3. *FluidFactsCompatTest* and the accessory compat test (images 10 and 3).
5. `ShaftTrains`, `FluidWorldAuthority` wiring, `EquipmentStructure` (casings as machines, turbines registered, `fluidDevice()` takes STEAM_TURBINE), `ShaftBlock` and `CreativeMotorBlock` hooks, `ShaftBlock.SIZE` from the rated torque (A35), `Sensor.Kind.SPEED` with its item and binding, `/icheme shaft`, `FluidNetwork` (`DriverState`, `MoverState` speed), `DriverSounds.request`, one lang key in five languages (A40).
6. *GameTest `ShaftTrainGameTests`*: creative motor, shafts and a pump between two tanks: the pump delivers; break a shaft and it is CLOSED within one slice; a motor at 150 % trips, and a reset restarts it. The GameTests that place constructed pumps (`AssemblyGameTests`, the column review worlds) get a creative motor where a pump must run.
7. Only affected tests run, with `--tests`, plus `equipmentGates` (`EquipmentStructure`) and the fluid GameTests. One quick check (200 seeds) is needed only if the reviewer counts the SHAFT boundaries as solver-reaching.

## 7. Readings (recommended answer: reason)

- **A15.** Shaft parts are facts in the topology unit: trains then run unloaded, as islands do.
- **A16.** A coupling needs at least one shaft block between the ends: the plan's "through shaft blocks".
- **A17.** One driver per train. Two drivers, or a gear loop, stands the train with `TWO DRIVERS`: load sharing would need a droop model.
- **A18.** Train speed is in rpm, and each casing's share is n·ratio/n_rated, with no casing cap: a wrong pole count shows up as an overload, which is real.
- **A19.** Defaults. A motor runs at its poles' speed. A variable drive's set point is 100 %. A turbine's set point is the design speed of the first casing, referred through the gears (otherwise its own speed). The creative motor turns at 2950 rpm with a 1 MW limit: most casings are 2-pole.
- **A20.** Gearbox ratio 0 = auto: n_casing/n_driver at rated, within 1:20 to 20:1. This is plant design practice, and 1.2's compressor gets its 4.27 without a setting.
- **A21.** OVERLOAD trips when P_d > 1.0 × rated, with no service factor (the plan's wording). The 20 % spare is the margin.
- **A22.** One damped step both ways, uncapped when the driver cannot cut power. Overspeed trips above 1.10 × set and waits for a reset: D27's trip needs a path to overspeed.
- **A23.** A turbine without a bound sensor runs ungoverned on its valve's setting, with an `UNGOVERNED` notice; it overspeeds on load loss, which is real.
- **A24.** The speed sensor is an accessory on the turbine's coupling guard. Motors and shaft blocks have no fluid host or identity.
- **A25.** The sensor binds to the nearest unbranched upstream control valve, the cable-free form of the "existing sensor UUID path". A valve's own sensor takes precedence.
- **A26.** The governor target is fed forward from the demand at the set speed, with the ports' dead band so steady islands certify. At zero demand the valve shuts.
- **A27.** Machine work is booked as SHAFT boundaries, not as a new result field: it reuses the codec and the replay scaling. To check in the build: every boundary reader filters by kind.
- **A28.** A train steps at its members' commits, once per tick, never in a presentation read: the heat-coupling precedent.
- **A29.** Old casings convert when restored, not in the facts reader, which has no shape. Until its chunk loads, a casing pumps as before.
- **A30.** The machine control carries the set flow and the set discharge pressure (the plan 9 targets on the machine's page). The regulating mode belongs to the driver.
- **A31.** W3 builds the regulation logic and its tests; its settings come from the debug command until W7. On a train with several casings it reads the first one.
- **A32.** A reciprocating frame stays a set-flow `Compressor`: per slice it has displacement Q_d·s, and its variable drive sets s = Q_set/Q_d.
- **A33.** Electric draw is shown at 2.5 J/FE until W5's config exists; nothing is booked (A14).
- **A34.** A generator is a member with zero load until W5.
- **A35.** Shaft size comes from the rated torque at compile, not per slice: no block updates every 5 s.
- **A36.** `MoverState` gains the speed share and drivers get a `DriverState` watch: D26's law needs s, and the open item names W3.
- **A37.** A new `TopologyCompiler.Kind.TURBINE` (by name), placed like a mover (inlet = suction): STEAM_TURBINE structures have no registration today.
- **A38.** The creative motor's unit is keyed by its block position (it has no block entity). Breaking it removes the unit.
- **A39.** A casing whose driver tripped stands closed with `DRIVER TRIPPED`, apart from `NO DRIVER`.
- **A40.** W3 adds the speed sensor's item name in five languages; the status words stay English. No screens.
