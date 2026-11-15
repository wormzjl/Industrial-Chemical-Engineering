# Alarm beacon build notes (W9)

Batch `2026-11-15-fluid-interfaces`, plan `ALARM_BEACON_PLAN.md` (decisions A1 to A8). The owner approved it on
2026-11-15 ("Start it"). It was built on branch `claude/pipe-fittings-7f3f4b` after W6, W7 and the W8 share
(`05efa64ea`).

Formats taken:
- Control store format 5 (the `alarms` list), with the corpus image `compat/control/control-5.snbt`.
- `NetworkProtocol.VERSION` `icheme-3`, for two new control payloads.

No fluid or accessory facts change.

## History

- Facts and law, `953214ebf`:
  - `AlarmSettings`: the record, strict save and load.
  - `AlarmLaw`: the decision, the band, any alarm, the acknowledgement.
  - `ControlFacts`: format 5, `Alarm`, `alarms`, `alarm`, `removeAlarm`, `alarmsIn`.
  - `DataPort.Kind.ALARM` (appended).
  - The corpus image and the FORMATS.md control row.
- Block, model, light and sound, `2725c28fe`:
  - `AlarmGeometry`: 17 boxes, 102 faces.
  - `AlarmBeaconBlock` (six facings, `STATE` clear, active or acknowledged, light 12) and its block entity.
  - `ControlAlarms`: the edits, the acknowledgements, the decision after the loops, the block state and the publication.
  - The `ControlService` delegation, the chunk binding, and `RegistryRealignment.alarm`.
  - `DataPort.Owner` and `DataPorts.alarm`.
  - `client/AlarmBeaconSounds` in the `FxSounds` budget.
  - The menu, the two payloads `alarm_edit` and `alarm_view`, and protocol `icheme-3` with its pinned tests.
  - Assets from `tools/pump-sprites/AlarmAssets.java`. Candidate A of the horn from
    `tools/process-sounds/alarm_horn_candidates.py`.
  - Recipe, loot, the Create `non_movable` tag, the equipment-gate resource list, and the GameTest
    `AlarmBeaconGameTests`.
- Screen, `dab362df3`:
  - `AlarmColumns`, a Minecraft-free door.
  - `AlarmBeaconScreen`, with its help tour `alarm_beacon.settings` (7 stops).
  - `GuiPrototypes.alarmBeacons` (two renders).
  - The GUI and tour lang lines.
- Documents, this commit: these notes, the code guide rows (`process-control.md`, `wired-control-power.md`), and the plan.

## Runs

All runs were filtered `--tests` runs, with `--max-workers 4`.

- Compile: `compileJava compileTestJava compileFluidGameTestJava compileMcpCompatJava` BUILD SUCCESSFUL.
- Unit tests: 20 classes, 166 tests, 0 failures. One run held them all; nothing failed:

| Classes | Tests |
|---|---|
| `AlarmLawTest` | 8 |
| `AlarmSettingsTest` | 5 |
| `AlarmGeometryTest` | 3 |
| `AlarmColumnsTest` | 4 |
| `ScreenToursTest` | 8 |
| `HelpCoverageTest` | 6 |
| `FxSoundsTest` | 4 |
| `HeatDiagramTest` | 5 |
| `CardViewTest` | 7 |
| `FormatsTableTest` | 1 |
| `LayeringTest` | 3 |
| `EquipmentNetworkCodecTest` | 14 |
| `FluidPacketCodecTest` | 27 |
| `BuiltInInputsTest` | 5 |
| `CableConnectionsTest` | 3 |
| `ControlSystemTest` | 21 |
| `InventoryControlLoopTest` | 16 |
| `EquipmentInfoCoverageTest` | 6 |
| `GuideCoverageTest` | 12 |
| `LineContractTest` | 8 |

- GameTest `AlarmBeaconGameTests#aBeaconSoundsOnItsInputAndClearsWithItsBand`, focused run
  (`tools/simulator-sizing/focused-gametests.init.gradle`):
  - Run 1 failed. The test read `STATE` from the beacon's cell after its own cleanup had removed the block (a test
    defect). The test now reads it only before cleanup.
  - Run 2: 1 of 1 required test passed.
- `affected.mjs` names 134 of 470 test classes. That set is left to the final lane.
- Renders, in the worktree's `tmp/alarm-beacon/views/`:
  - The beacon, clear and active, standing and on a wall, from front and rear.
  - The screen in light and dark (`alarm-beacon.png`, `alarm-beacon-any.png`).
- Horn candidates, in the worktree's `tmp/alarm-beacon/sounds/`, each at the same loudness (RMS 0.093):
  - A, a two-tone horn. Shipped as `assets/icheme/sounds/alarm_horn.ogg`.
  - B, a sweep siren.
  - C, a piezo pulse.

## Decisions taken in the build

- **Port identity.** The beacon's socket is a data port of kind `ALARM`. Its id is the alarm's id, and its host is 0, so
  no controller lists it as an input or an output.
  - `DataPort.Owner` (one socket of its own) replaces `ControlPanelBlockEntity` in `ConduitLineBackend`, `PowerPorts`
    and `DataControlRenderer`.
- **One device per cell.** A controller and a beacon never share a cell: the fact that stands wins, and the newcomer
  stays unregistered.
- **Missing block.** A beacon whose cell holds another block is `NO_SIGNAL`. It is not held.
- **Unit.** The unit of the threshold is % of span for a level, and the primary unit for any other input. There is no
  secondary-unit choice.
- **Acknowledgement.** It is a queued event, applied at the next cadence before the decision. The player sees an
  action-bar line at once.
- **After a reload**, the saved block state stands until the first cadence restates it. The active and acknowledged state
  is engine state: an alarm that still stands sounds again.
- **The horn** plays on the client from the block state (`AlarmBeaconSounds`). It joins the `FxSounds` budget at 130 dB
  (volume 3, 48 blocks).
- **The flash** is a two-frame texture animation at 2 Hz on a full-bright lens element (NeoForge `neoforge_data`).

## What the plan had wrong

- `DataPort.power()` was decided by ordinal. Appending `ALARM` would have made it a power port. It now names its kinds.
- **The GameTest fixture.** No proven fixture makes a tank's level fall within a test's time.
  - The test uses the control loop's flowmeter line instead: ABOVE half the free flow gives ACTIVE, then a hand's
    acknowledgement gives ACKNOWLEDGED, then a threshold of twice the flow gives CLEAR.
  - The settings are written as facts. A screen's Apply queues the same edit.
- **Packets in commit 2.** The plan put the packets with the screen. The menu and the block's use need them, so they went
  with the block, and each commit compiles.
- **Help tour.** The plan named no help tour. `HelpCoverageTest` asks one of every screen, so the screen has one.
- **Renders.** `tools/heat-gui-renders/GuiPrototypes.java` does not compile on this branch. `OperationPrototypes.java`
  imports `science.column.operation`, now `science.plant.column`. The renders came from a scratch copy with that import
  changed. The tool is not fixed here.

## Open items

- The owner picks the horn from candidates A, B and C (`tmp/alarm-beacon/sounds/`). A is shipped.
- In-game check (owner's choice):
  - The lens flash and the light.
  - The horn's range and loop.
  - The six mountings.
  - The acknowledgement from a hand.
- A wall mounting always puts the socket down. Another socket direction would need a second rotation property.
- The `OperationPrototypes` import in `tools/heat-gui-renders/`: a follow-up.
- The translation commit for the new lang lines, at merge preparation.
- Not built (A7): a redstone output, FE, and an entry on the DCS's pages.
