# The fluid runtime's presentation: buckets, views, displays and the wire

Up: [code guide](README.md). The rule: AGENTS.md, "Engine schedule and player-facing updates". Beside it: [runtime-engine.md](runtime-engine.md) (what publishes), [runtime-registry.md](runtime-registry.md). The screens: [client-screens.md](client-screens.md); the effects and sounds: [effects.md](effects.md).

<!-- j2a-packages: runtime/fluid/FluidPresentation.java runtime/fluid/Display.java runtime/fluid/Displays.java runtime/fluid/ViewBuilder.java runtime/fluid/FluidView.java runtime/fluid/TankOutlets.java runtime/fluid/TankDisplay.java runtime/fluid/PipePresentation.java runtime/fluid/HeatPresentation.java runtime/fluid/MachinePresentation.java runtime/fluid/MachineView.java runtime/fluid/Silhouettes.java runtime/fluid/Noise.java runtime/PresentationMailbox.java -->

## Read this when

- A GUI is late, shows stale or missing data, or a reply (`Queued …`, `Applied`, `Not applied: …`) is wrong.
- A world-space display (an open tank's roof and liquid, a sight glass, a mover's running state, a flare, still, tower, heater or chimney effect, a driver's sound, a rotation) is missing, late or wrong.
- A device's status line reads wrongly (its order, a pump's `ERROR`, `STANDING_OPEN`, a held device beside it).
- A packet is refused, too large or carries the wrong fields.
- The noise watch or the far silhouettes answer wrongly.

## What it does

The engine owns presentation. A round's commit marks the loaded member devices dirty and pushes nothing. `FluidPresentation` keeps one 100-tick bucket per island (the slot `key mod 100`, the key the owning island or the device itself); at the bucket, `flush` builds each device's `FluidView` once from one island read (`ViewBuilder`, over one `FluidWorldAuthority.ViewIndex` per presented island), hands it to the block entity and caches a menu payload per device. Opening a GUI replays the cached payload at once and subscribes the menu to its bucket; a client's watch of a world-space display replays that display's cache (`Display`). Nothing on these paths solves, creates process state, applies pending input or advances time. `Noise` and `Silhouettes` answer their watches from what the islands last accepted and from the structure registry.

## Class map

Under `src/main/java/com/wormzjl/icheme/runtime/`. Lines at `d6f721b94`.

| Class | Role | Lines |
|---|---|---|
| [`fluid/FluidPresentation`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidPresentation.java) | The buckets by island, dirty marks (`markDirty`, `markIfLoaded`, `rekey`), menu subscriptions, input replies, `observe`/`unobserve` | 253 |
| [`fluid/ViewBuilder`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/ViewBuilder.java) | Package-private: the read-only builder of a device's `FluidView` (`view(id, islands)`), its status clauses and the groups only a view reads (drive, pump, still page, a pipe's line reading) | 336 |
| [`fluid/FluidView`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/FluidView.java) | The read-only view of a committed interval; `Tank`, `Heat`, `PipeInfo` with `Reading`, `Drive`, `Power`, `Pump`, `Relief`, `Converter`, `Chimney`, `Still`, `Port`, `Lines`, `Part`, `Range`; `Receiver` | 428 |
| [`fluid/Display`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Display.java) | Package-private: one requested world-space display (its `Watchers`, the state it last published per device, a target `Check`, a `Range`, the state read, a `Sink`); `bucket` or `cadence` publication; `watch`, `replay`, `publish`, `publishDue` | 168 |
| [`fluid/Displays`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Displays.java) | Package-private: the authority's displays (the eight `Display`s in two lists, the rotations' and drivers' own maps), the watch bodies, the bucket publications (`publishTank`, `publishSightGlass`, `publishMover`), the cadence passes (`publishDue`, `publishDrivers`, `publishRotations`), `refreshObserved`, and the authority's changes (`forget`, `unloaded`, `removed`, `recompiled`, `clearPublished`, `clearWatches`) | 416 |
| [`fluid/TankOutlets`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/TankOutlets.java) | A tank's port lines and `tank(...)` from committed data (no flash); `bottomHead`; `released` (the vent and spill rates) | 80 |
| [`fluid/TankDisplay`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/TankDisplay.java) | An open tank's drawn liquid level, free-water level, fill height and two liquid tints; `Glide` | 90 |
| [`fluid/PipePresentation`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/PipePresentation.java) | Pipe contents and speeds from the committed graph and history; `SpeedWindow` (display only, and the level loop's totaliser); `SightGlass`; `pumpStatus`; `actuatorMappings` | 232 |
| [`fluid/HeatPresentation`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/HeatPresentation.java) | Completes a heat side's `FluidView.Heat` at a bucket: the actuator and its effort, the limit, every port with its stream, an exchanger's lines part by part | 199 |
| [`fluid/MachinePresentation`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/MachinePresentation.java) | A machine part's page and its watchers ([shaft-trains.md](shaft-trains.md)) | 101 |
| [`fluid/MachineView`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/MachineView.java) | A machine part's view on the `machine` registrar | 129 |
| [`fluid/Noise`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Noise.java) | Each island's sound sources by chunk (sound power levels of valves, vents, fans, falling water, burners, gears, kettles, knocks, ejectors) from what it last accepted; the noise watch (`MAXIMUM_SOURCES` loudest within `RANGE`); `Kind` | 204 |
| [`fluid/Silhouettes`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Silhouettes.java) | The far silhouettes' subscriptions: the placed structures within a client's far radius, then placements, repaints and removals; `MAXIMUM_RADIUS`, `MAXIMUM_STRUCTURES`, `REQUEST_TICKS` | 224 |
| [`PresentationMailbox`](../../../src/main/java/com/wormzjl/icheme/runtime/PresentationMailbox.java) | A 100-tick session mailbox used by the assembly screens (`AssemblyPresentation`, `AssemblyWatch`), not by fluids; a command accepted from a session that closed before its bucket still runs | 76 |

Outside the package: `network/FluidNetwork` (the `fluid` registrar and the display registrars), `world/inventory/FluidDeviceMenu`, `world/level/block/entity/FluidDeviceBlockEntity` (receives views, opens the menu, then replays the cache).

## Main flow

**Publication to a bucket.** `FluidWorldAuthority.published` marks the saved data dirty, logs holds (a phase hold through `phaseHeld`, rate-limited per island) and calls `FluidPresentation.markIfLoaded` for each member device. At the island's next bucket (online tick ≡ island id mod 100) `flush`:
1. builds one `ViewIndex` per presented island (nodes and pipes by identity, each node's pipes in order, the interval's pipe histories by pipe, HEAT bookings by node in booking order, the control constraint sets); `IslandSets.presented` builds one axis map per island;
2. `ViewBuilder.view` per device (map lookups and degree-sized loops); `HeatPresentation` completes a heat side's view; an instrument's pipe reading reads the island's stored last result (`IslandCoordinator.lastResult`), only where the host has a sensor;
3. the presentation host's `present`: for an open tank first the next `TankDisplay.Glide` (before the block entity takes the view), given to `FloatingRoofs` and sent to the tank's watchers; then the block entity and the gauges (`InstrumentService.publish`); a sight glass's `PipePresentation.sightGlass`; a constructed mover's `MoverState`;
4. caches a `MenuData` per device in `publishedMenuData` and sends the live payload to each subscribed menu (the static payload only when the registration revision changed).

The cadence displays (flares, stills, towers, heaters, chimneys) publish at their device id's slot mod 100, each `Display.publishDue` replacing only its due devices' states, in that order; drivers, rotations, silhouettes and noise stay per watcher on `% 100`.

**GUI open.** Right-click → `FluidDeviceBlockEntity.open` → `player.openMenu`; the menu constructor calls `FluidWorldAuthority.subscribe(menu)` (its island's bucket slot, nothing sent). After the opening packet, `replayOnOpen(menu)` sends the cached entry as static plus live payloads with an empty reply: a read of cached data, no view built, no solve, no event applied. With no entry the menu waits for its bucket; the client shows `FluidNetwork.lastDelivered(identity)` if this session has one. An edit or recovery packet is validated and queued as a ledger event; its reply arrives with the menu's first bucket after that tick: `Queued for simulation event at tick N`, then `Applied` or `Not applied: <reason>`. `FluidDeviceMenu.removed` → `unsubscribe`; a menu abandoned without it is dropped at its next bucket.

**A requested display.** A client renderer asks by packet: `TankWatch` (registrar `fluid`), `Watch` `sight_glass_watch` (`sight-glass`), `Watch` `mover_watch` and `DriverWatch` (`mover`), `InstrumentWatch` (`instruments`, [instrument-accessories.md](instrument-accessories.md)). A request names at most `FluidNetwork.MAXIMUM_WATCHED` targets of one kind; a tank or sight glass within `WATCH_RANGE`, a mover within `MOVER_RANGE`. `watchTanks`, `watchSightGlasses`, `watchMovers` or `InstrumentService.watch` checks each target (loaded, identity, kind, dimension, range) and replays the last published display (`Display.watch`; a tank's by cell through `Display.replay`). A sight glass, mover or gauge watch also calls `FluidPresentation.observe`, so its device's buckets present it while its island is idle. An unwatch, a logout (`forgetWatcher`), an unloaded or removed device, or a topology commit that removed it ends the watch.

## Contracts

- **Do not present from a handler or a load.** A block-entity load, a binding, a chunk load or a publication may only mark dirty; only `FluidPresentation.tick` delivers, except `replayOnOpen`, which replays the cache.
- **`FluidWorldAuthority.view(id)` is a cached read**: it applies no queued event. Diagnostics that must see queued events (`capture`, `diagnosticSnapshots`, `materialiseAll`, `presentationKey`) flush; GUI and packet paths never call them.
- **A certified island is previewed, not materialised**, by every presentation read (views, menus, instruments; [runtime-engine.md](runtime-engine.md)). `FluidNetwork.opening` and the still's stop step read the view directly.
- **The control channels read accepted presentations.** `instrumentMoverPort`, `instrumentVesselPorts`, `instrumentAt`, `columnInstrument`, `converterFill` and `converterAlarms` reduce what was last accepted for the factory channels ([wired-control-power.md](wired-control-power.md)); a mover's discharge pressure is its accepted outlet observation; reads never solve or advance time.
- **A watch never solves.** It replays the cached display; the next display arrives with the device's bucket or slot.
- **The wire.** One protocol for every registrar (`NetworkProtocol.VERSION`; NeoForge refuses a client and a server whose version differs). Every list is a VarInt count read through `Wire.count`: a count outside its bound, or longer than the bytes left at its elements' least size, is refused as a `DecoderException` before anything is allocated; `Wire.positions` and `Wire.doubles` read and write the position and double lists. JSON is Gson; records validate in their compact constructors on both sides; `MenuData` refuses a crystal of a component off the axis (`requireCrystalAxis`). Bounds: a status of 2048 characters, a reply of 1024, 12 pipe history and route entries, a phase axis of 3 × components; only a tank lists outlets.
- **Admission** drops a packet silently unless the menu id, identity and position match, `stillValid` holds, the menu is not a debug menu, the player may build and is not a spectator, `mayInteract` holds and `admitEdit` allows it (5 ticks since the last edit). A rejected value becomes `Not applied: <innermost message>`. `Controls` requires `power` (`Power` ON or OFF); `Controls.from(registration)` reads OFF for a generator switched off or a constructed mover that is off.
- **Kind-specific edits**, by `hydraulicKind`: pipe and filter → diameter; pump → requested flow, maximum added pressure, power; compressor → requested flow, pressure ratio, power; pressure valve → pressure; generator → T, p, composition, solids, power; void → pressure; heat side → a fired heater coil's burners and set point (inside the package's envelope, `requireInputTemperature`; both coils in one event), a controllable side's `Controls.heat`, an air cooler's fans. A reservoir refuses edits. Power OFF on an in-line mover is refused. Generator, void and valve values are checked against the package envelope before anything is queued: an input box, never a hold.

## Laws and constants

**Status line** (`ViewBuilder.view`): the island's status, the held devices beside the device (`HELD_DEVICE`), a device in no island, a mover's inlet reason, `STANDING_OPEN: a lower flow target on this line regulates` for a valve standing open behind a lower target. **Pump status** (`PipePresentation.pumpStatus`): `pump running`, `pump off`, or `ERROR: …` for a set-flow mover delivering under 99.5 % of its set flow (`shortfall`), the suction limit, a dry or wrong-phase inlet, or no forward flow; a failed mover that wears starts `FAILED: use a repair kit / `, otherwise its `CAVITATION`, `GAS` or `LIQUID` word (`MachineHealth.status`) comes last; the island's own status follows after ` / `.

**Tank** (`TankOutlets`, `FluidView.Tank`): level height, open, vent and spill kg/s, capacity, top and bottom pressure (null while empty); a LIQUID or OVERFLOW port's head g·m_liquid·H/V with the vessel's own `levelHeight`. **Glide** (`TankDisplay.Glide`): from the last display to the new one over one bucket, the same on client and server; the first glide after a load starts at the level the roof stands at (`Glide.next(old, standing, display, tick)`, `standing` from `FloatingRoofs.standing`, NaN when no tank is indexed). Tints mix components by mass fraction (Beer-Lambert). **Sight glass** (`PipePresentation.SightGlass(argb, bubbles, flowDirection)`): from the accepted transport, no optical model. **Mover state**: running = enabled and requested flow > 0.

**Noise** (`Noise`): a sound power level per source from what the island last accepted, computed once per publication and kept by chunk; a valve by its pressure drop, a liquid valve's loop by its regime (`StepObservations.cavitation`); a vent as a free jet; fans by the fan law; nothing is saved, solved or fed back.

**Payloads** (registrar `fluid` unless named):

| Payload id | Direction | Fields | Handler |
|---|---|---|---|
| `icheme:fluid_edit` | C→S | menu id, BlockPos, identity, revision, `Controls` JSON (≤ 4096) | `FluidNetwork.edit` |
| `icheme:fluid_recover_solids` | C→S | menu id, BlockPos, identity, revision | `FluidNetwork.recover` |
| `icheme:fluid_open_partner` | C→S | menu id, BlockPos, identity | `FluidNetwork.openPartner`: the partner side's own cell opens its menu (a replay) |
| `icheme:fluid_check_lines` | C→S | menu id, BlockPos, identity | `FluidNetwork.checkLines`: an exchanger's lines checked at the next server tick |
| `icheme:fluid_static`, `icheme:fluid_live` | S→C | menu id, identity, `StaticData` or `LiveData` JSON (≤ 262144) | `menu.acceptStatic`, `acceptLive` |
| `icheme:tank_watch`, `icheme:tank_display` | C→S, S→C | two BlockPos lists; a node and its `TankDisplay.Glide` | `watchTanks`; `TankRenderer.accept` |
| `icheme:sight_glass_watch`, `icheme:sight_glass_display` (`sight-glass`) | C→S, S→C | `Target` lists; a `Target` and its `SightGlass` | `watchSightGlasses`; `SightGlassRenderer.accept` |
| `icheme:mover_watch`, `icheme:mover_state` (`mover`) | C→S, S→C | `Target` lists; `MoverState` | `watchMovers`; `MoverSounds.accept` |
| `icheme:driver_watch`, `icheme:driver_state` (`mover`) | C→S, S→C | origin lists; `DriverState` | `watchDrivers`; `DriverSounds.accept` |
| `icheme:machine_*` (`machine`) | both | a part's `MachineView`, an edit of `ShaftUnit.Settings`, open a driver page | `MachineNetwork` |
| `icheme:instrument_*`, `icheme:gauge_save` (`instruments`) | both | [instrument-accessories.md](instrument-accessories.md) | |
| `icheme:pipeline_tool_save` (`pipeline-tool`) | C→S | tool settings, bounded by `PipelineToolMenu` | `PipelineToolMenu.save` |
| `icheme:line_command`, `icheme:line_answer` (`lines`) | C→S, S→C | [pipe-laying.md](pipe-laying.md) | `LineLaying.execute`, `LineToolSession.answered` |

The flare, still, tower, heater and chimney watches (`FluidNetwork.Watch` kinds), the noise watch (`NoiseWatch`, `NoiseSources`) and the converter's edit (`FluidNetwork.ConverterEditPayload`) ride the same registrars; [effects.md](effects.md).

## Formats

Every registrar opens on `NetworkProtocol.VERSION` ([FORMATS.md](../FORMATS.md)). The wire gets no compatibility work: a breaking change is tested on a fresh world. Nothing here is saved; `TankDisplay`'s first glide reads the level the node cell saved.

## Tests and gates

| Rule | Test |
|---|---|
| GUI timing, replies, the cached open | `PresentationBucketTest`, GameTest `FluidPresentationGameTests`, `CrystalIslandRuntimeTest.theCrystalReachesTheViewsAndMenusOnTheEnginesBucketOnly` |
| The view index; the presented axis | `ViewIndexTest`, `IslandSetsPresentedTest` |
| Cadence displays | `DisplayCadenceTest` |
| Packet fields, bounds, admission | `FluidPacketCodecTest` (incl. `aPipesLineReadingRoundTrips`, `aHeatSidesPortsLinesAndActuatorRoundTrip`, `tankCapacityAndBothPressuresRoundTripAndMalformedReadingsAreRejected`, `theNoiseAnswerRoundTripsAndHoldsAtMostSixteenSources`), GameTest `FluidPacketGameTests` |
| Status text, pump status, tank ports, sight glass | `TankOutletsTest`, `PipePresentationTest`, GameTest `FluidPhasePortGameTests` |
| A valve's standing-open status | `FlowControlTest` |
| An open tank and its roof | `OpenTankRuntimeTest`, `FloatingRoofsTest.aReloadedRoofGlidesFromTheLevelItsNodeCellSaved` |
| A heat side's view | `HeatPresentationTest` |
| The mailbox | `PresentationMailboxTest` (`test` only) |

No automated test sends every watch request: the watch paths of the generic `Display` are read, not run; the GameTests exercise the tank, sight-glass and mover displays.

## Traps

- **A chunk load marks its devices one tick late** ([runtime-registry.md](runtime-registry.md)), so a GameTest that reloads a chunk at online tick v gets the view at the first bucket after v+1 (`identityBindingOnAChunkLoadCarriesNoViewData`).
- **A tank needs no `observe`**: every presentation of a loaded open tank renews its glide.
- **`FluidPacketCodecTest`'s protocol test keeps an old name** (`theProtocolIsFluidNineteenWithDistinctStaticAndLivePayloads`); the protocol is `NetworkProtocol.VERSION`.
- **The sound gain of a mover** is `science/equipment/UnitDesign.soundGain`, not this package's.

**Snapshot:** `d6f721b94`, 2026-11-15.
