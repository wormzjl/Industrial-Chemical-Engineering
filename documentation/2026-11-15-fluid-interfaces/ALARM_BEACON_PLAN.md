# Alarm beacon: plan (W9 of batch 2026-11-15-fluid-interfaces)

To be copied by the build thread to `documentation/2026-11-15-fluid-interfaces/ALARM_BEACON_PLAN.md`. Lanes: flowsheet and control (`runtime/control`, `network/ControlNetwork`; hot file `ControlFacts`), equipment (`world/**`, `registry`, one new geometry file), client.

## 1. The owner's request (2026-11-15)

"Add a physical alarm that can be triggered by data system conditions." and, on its effect: "light & sound effect".

## 2. What exists (read at `9c777e86e`)

- The data system: `runtime/control`. A local controller and a DCS are panels (`ControlFacts.Panel(id, dimension, position, dcs, revision, settings)`), each with a data socket (`DataPorts.panel`), joined to sensors and equipment by data cables compiled into a `DataBus`. `ControlService.tick` works every `CADENCE` = 100 online ticks: it reads accepted observations and makes the loops' decisions (`ControlLaw`). Nothing solves there.
- Inputs: every installed sensor (pressure, temperature, flow, level, speed) and every built-in equipment channel is a `ControlInput` with a stable ID (`SensorId`), chosen on a controller's screen from paged choices (`ControlNetwork.Browse`). Equipment alarms are channels already: `alarm_<name>` of kind `Sensor.Kind.ALARM`, 1 while raised, 0 clear (`BuiltInInputs`, `BuiltInInputs.raised`; the names are `EquipmentAlarm`'s: NO POWER, NO FUEL, FLAME OUT, OVERHEAT, FLOODING, UPSET, LEVEL HIGH, LEVEL LOW and the rest).
- `ControlSettings` is a loop's settings and requires an output when enabled, so an alarm cannot reuse it.
- Saves: `ControlFacts.FORMAT` 4, strict key sets; corpus images `src/test/resources/compat/control/control-1.snbt` to `control-4.snbt`.
- Sounds: files in `assets/icheme/sounds/`, entries in `sounds.json`, events in `ModBlocks.SOUNDS` (`loop(name)`); `client/fx/sound/FxSounds` is the single loop budget (at most 12 loops, chosen by level at the listener). The models-and-sfx skill sets the route: physics first, three synthesised candidates for the owner's ear.
- Wire: `NetworkProtocol.VERSION`; the batch's value is `icheme-3`.

## 3. Decisions (each open to the owner's veto)

| # | Decision | Why |
|---|---|---|
| A1 | One block, the **alarm beacon** (`alarm_beacon`): a horn and a beacon lamp on a base, placed on a floor, wall or ceiling, with one data socket on its base. | "Physical alarm", "light & sound". |
| A2 | Its condition is one of: **an input on its data bus** (any sensor or built-in channel, chosen by ID as on a controller) compared **above** or **below** a threshold; or **any equipment alarm** raised by a host its bus reaches. An alarm channel as the input needs no threshold: active while raised. | These are the data system's own signals; no new signal kind. |
| A3 | It clears with a band of 2 % of the threshold (the automated valve's band, plan D4). | One rule for on/off decisions. |
| A4 | Decided at the control cadence (100 online ticks) from accepted observations. A missing link or signal is `NO SIGNAL` and not active. | Engine schedule; a broken cable must not sound the plant. |
| A5 | Active: the lamp flashes and lights its surroundings (block light 12), the horn sounds as a loop. An empty hand on an active beacon **acknowledges** it: the horn stops, the lamp keeps flashing until the condition clears; the next rise sounds again. No latch. | The usual plant convention, without a reset step. |
| A6 | Its settings are facts in the control store (a new `alarms` list), format 5. The acknowledged flag and the active state are engine state, never saved. | Registry first, as panels and cables. |
| A7 | No redstone output, no FE, no entry on the DCS's pages. | Not asked; each can follow. |
| A8 | The horn: one sound, chosen by the owner from three synthesised candidates; audible to about 48 blocks. | The skill's route. |

## 4. Logic

For an input with reading x, threshold t and band b = 0.02 |t|:

- ABOVE: becomes active at x ≥ t, clears at x < t − b.
- BELOW: becomes active at x ≤ t, clears at x > t + b.
- An `ALARM` channel: active while its value is 1.
- ANY ALARM: active while any `alarm_*` channel of any host on the beacon's bus is raised (`BuiltInInputs.raised`).
- Not enabled, no socket on a bus, the input not on the bus, or a reading that is unavailable: `NO SIGNAL`, not active; the last active state is forgotten.

Example: a level sensor on a drum, ABOVE 80 %: active from 80 %, clear below 78.4 %. A pressure sensor, ABOVE 1.2 MPa: clear below 1.176 MPa.

The decision is a pure function of (settings, reading, previous active): put it in `runtime/control/AlarmLaw.java` with no Minecraft class, so a unit test reads it.

## 5. Work

### 5.1 Facts and the law

- `runtime/control/AlarmSettings.java` (new record): `name` (cleaned by `DeviceName.clean`), `enabled`, `source` (enum `INPUT`, `ANY_ALARM`, saved by name), `sensor` (UUID, null for `ANY_ALARM`), `unit` (`Sensor.Unit`), `comparison` (enum `ABOVE`, `BELOW`, by name), `threshold` (finite); `save` / `load` with a strict key set, as `ControlSettings`. `EMPTY`: not enabled, `INPUT`, no sensor.
- `ControlFacts`: record `Alarm(id, dimension, position, revision, settings)` beside `Panel`; map `alarms`; `alarm(UUID)`, `alarms()`, `alarm(Alarm)`, `removeAlarm`, `alarmsIn(dimension, chunk)`; saved as the list `alarms`; `FORMAT` 5 (formats 1 to 4 read with no alarms; the key-set check takes `alarms` only at 5); a position holds one panel or one alarm. Corpus image `src/test/resources/compat/control/control-5.snbt` with one beacon; the control compat test reads it and still reads 1 to 4. `FORMATS.md` row.
- `runtime/control/AlarmLaw.java`: `Decision decide(AlarmSettings, Reading or null, boolean wasActive)` and the bus-wide form for `ANY_ALARM`; statuses `CLEAR`, `ACTIVE`, `ACKNOWLEDGED`, `NO_SIGNAL`, `OFF`.
- `ControlService`: at the cadence, after the loops, decide every alarm whose chunk is loaded; keep `active` and `acknowledged` per alarm id in memory; on a change of the drawn state set the block's state (5.2) on the server thread. Edits are queued and revision-checked as a panel's (`Edit`). `acknowledge(UUID)` sets the flag while active; it drops when the alarm clears.
- `DataPorts` / `ControlHosts`: the beacon's socket is a data port of a new kind `DataPort.Kind.ALARM` (appended), on the base's face; the bus reaches it as it reaches a controller. Removal of the block removes its facts and its port.

### 5.2 Block, light and sound

- `world/level/block/AlarmBeaconBlock` (a `BaseEntityBlock`; `FACING` over six directions for the mounting face; `STATE` enum property `CLEAR`, `ACTIVE`, `ACKNOWLEDGED`), `AlarmBeaconBlockEntity` (its control identity, as `ControlPanelBlockEntity`); `ModBlocks.ALARM_BEACON`, `ModItems`, `ModCreativeTabs`, loot table, recipe, `en_us.json` name and `EquipmentInfoCoverageTest` page keys. Light emission 12 unless `CLEAR`. An empty hand: acknowledges when active, else opens the screen; Shift + use always opens the screen.
- Model: `science/equipment/AlarmGeometry.java`: a square base with the socket, a short horn cone on one side, a lamp dome under a wire guard; under 150 polygons; the dome's lens is its own texture. Lit states use a second lens texture that animates (an `.mcmeta` two-frame flash, about 2 Hz) and is drawn full-bright; no block entity renderer, no per-frame code. Voxel shape from the geometry's boxes (`HorizontalShapes` has the pattern). Read `.claude/skills/models-and-sfx/SKILL.md` first.
- Sound: event `alarm_horn` (`ModBlocks.SOUNDS`, `sounds.json`, a subtitle key). Emitters are the loaded beacons in state `ACTIVE`, found on the client from block states (a small client index kept by the block entity's load and unload and its state change; no server packet). They join `FxSounds`' budget as one more kind at a fixed source level that gives about 48 blocks. Three candidates per the skill (for example A: a two-tone electronic horn, 610 and 760 Hz at 1 Hz; B: a rising sweep siren, 500 to 1 200 Hz in 1.5 s; C: a pulsed 2.9 kHz sounder, 4 Hz), files in `<worktree>/tmp/alarm-beacon/sounds/`; ship candidate A in the branch until the owner chooses, and say so in the notes.

### 5.3 Screen and wire

- Menu and screen as the local controller's, on the GUI kit (read `.claude/skills/gui-creation/SKILL.md` first): Name; Enabled; Source (Input, Any alarm); the input by ID with the paged choices; Condition (Above, Below) and Threshold in the player's display unit, both hidden for an alarm channel and for Any alarm; a status line (`CLEAR`, `ACTIVE`, `ACKNOWLEDGED`, `NO SIGNAL`, `OFF`) with the reading. The screen opens as a replay of the cached state and subscribes; closing ends it.
- `ControlNetwork`: `AlarmEdit` (C→S: menu, id, revision, settings), `AlarmView` (S→C), reuse `Browse` for the choices; `NetworkProtocol.VERSION` `icheme-3`.
- A software render of the screen with `tools/heat-gui-renders/` if the tool takes a new prototype without more than a small addition; else say so.

## 6. Tests

- `AlarmLawTest` (quick suite): ABOVE at 80 with readings 79.9, 80, 79, 78.3 gives clear, active, active, clear; BELOW mirrored; an alarm channel 0, 1, 0; ANY ALARM with two hosts; a null reading gives `NO_SIGNAL` and forgets the active state; acknowledge holds until clear and the next rise is `ACTIVE` again.
- `AlarmSettingsTest`: save and load round trip; a damaged tag refuses; names by name.
- Control facts compat: `control-5.snbt` reads; 1 to 4 read with no alarms; a format 5 store round-trips.
- One GameTest (`AlarmBeaconGameTests`): a one-block tank with a level sensor, a data cable, a beacon set ABOVE; fill past the threshold; the beacon's block state is `ACTIVE` at the next cadence, `ACKNOWLEDGED` after an acknowledge, `CLEAR` after the level falls; it removes everything it registered and runs with a fresh `fluidGameTestRunId`.
- Every existing test that lists every block, item, creative tab entry, data port kind or control packet (grep) runs once.

## 7. Pitfalls

- `ControlFacts.load` checks exact key sets per format: add `alarms` for 5 only, and keep 1 to 4 reading.
- The pipe fittings thread (W7) edits `ControlLaw`, `ControlHosts`, `DataPorts`, `BuiltInInputs` and `ControlService` for the automated valve. Keep edits to those files small and local; put the alarm's code in its own files.
- Block light changes relight chunks: change the block state only when the drawn state changes, never every cadence.
- The beacon is not a sensor and not an output: it must not appear among a controller's inputs or outputs.
- A state change is world state, set by the engine at its cadence; no packet handler, block-entity load or menu open decides an alarm.
- `LayeringTest`: `runtime/control` names no client class; the client index of emitters lives under `client/`.
- Other languages are not edited; `LanguageFilesTest` and `UnitTextTest` are not run.

## 8. Ruled out

A latch and a reset button; redstone; FE; several conditions on one beacon (use several beacons, or Any alarm); a DCS page; tones chosen per beacon; a block entity renderer or a moving part; a dev client started by the thread.

## 9. Done

The source sets compile; the tests of section 6 pass; no existing expected value moved other than lists that name every block, item or port kind; three horn candidates are in `tmp/alarm-beacon/sounds/` and a render of the beacon (clear and active) in `tmp/alarm-beacon/views/`; the notes file `ALARM_BEACON_BUILD.md` lists commits, runs, decisions and open items; nothing is merged.
