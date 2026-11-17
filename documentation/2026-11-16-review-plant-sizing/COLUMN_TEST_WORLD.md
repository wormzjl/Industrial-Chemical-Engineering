# Column test world: the world type that builds and starts the crude unit

Batch `2026-11-16-review-plant-sizing`. Owner, 2026-11-17: "Add a world generator to generate the column test case from
code (creative, superflat, no day/weather cycle)"; chosen form: a world preset in the Create World screen, with the plant
started. Then, 2026-11-18: "it should be a ingame world type", listed "In the mod, for players". The preset first lived in
the test mod (built 2026-11-17, `runReviewClient`); it is now part of the mod `icheme`.

AGENTS.md's rule that probes and rigs leave `src/main` before a merge does not apply to this plant: the owner made it a
player feature. The review GameTests now build through the mod's code.

## What it is

The world type **IChemE column test** (`icheme:column_test`). A world made with it is:

- superflat: bedrock, two dirt, grass (the GameTest world's layers), plains, no structures, no features, no lakes; the
  nether and the end as vanilla gives them;
- creative (the world's default game type and every joining player), daylight, weather and mob spawning cycles off,
  spawn radius 0, clear weather at noon (time 6000);
- with the crude unit test plant built at spawn on the world's first load, then started. The plant is the review rig's
  (`CrudeUnitPlant`): the sized 7.5 m column with 40 trays, its condenser, receiver and pumps, the pumparound coolers on
  the crude, the charge pump on its flow-regulating drive, the residue exchanger and two fired heaters.

## How to use it

```bash
./gradlew runClient
```

Or a released jar of the mod. Singleplayer, Create New World, World tab, World Type: **IChemE column test**. The game
mode you pick does not matter: the world sets creative. Create.

1. The loading screen stays up while the plant is sized and built: about 10 s of sizing and about a minute of pipe
   routing on the review machine (the GameTest's build took 64 s).
2. You spawn north of the plant's box, at the column's x, facing south at the column (about 40 blocks away).
3. The start runs by itself, one step a tick at most, in the rig's order. In play time, from the preset GameTest: the
   valves in about 1 s, the charge drive and the heaters' fuel within about 14 s, the column started about 25 s after
   you spawn and past its first solve about 35 s after (479 and 707 online ticks after the build).
4. A chat message says "Column test plant: started." when the column is past its first solve, or "the start stopped at
   <step>" when a step fails or waits 5 minutes. The log shows every step as a `column_test_world` line.
5. A reload builds nothing (the flag is set before the build). If the world closed during the start, the start does not
   resume: each player who joins is told, and the plant is finished by hand.

A dedicated server makes the world with `level-type=icheme\:column_test` in `server.properties` before its first start
(read from the code: `DedicatedServerProperties.WorldDimensionData.create` looks the level type up in the world-preset
registry; not run).

The log lines:

| Line | When |
|---|---|
| `column_test_world built origin=… devices=… structures=… box=…` | after the build |
| `column_test_world spawn=… facing=south` | spawn moved |
| `column_test_world step=<name> reached t=<online tick> ticks=<waited>` | each step: `valves`, `settle`, `drive`, `fuel`, `warm` |
| `column_test_world done t=… column=…` | the column past its first solve (chat: started) |
| `column_test_world step=<name> not reached` | a step waited 6000 ticks (5 min); the start stops (chat) |
| `column_test_world step=<name> failed: …` | a step's own check failed (an unjoined nozzle, the column refused); the start stops (chat) |
| `column_test_world step=start not finished before the world closed …` | at the next start of a world closed during the start (chat to each player who joins) |

The rig's own `review_operation …` lines (sizing, charge pump hold and start, warming, status=STARTED) appear too.

## How it works

Package `src/main/java/com/wormzjl/icheme/testworld/`:

- `ColumnTestWorld`: the preset's server side. Its listeners are registered in `IChemE` with the mod's other game-bus
  listeners.
- `CrudeUnitPlant`: the plant (sizing by the process simulator's Size all, layout, options) and its rig (`Rig`: build,
  start steps, reports, the charge pump's check). Moved whole from the review GameTest `ColumnOperationReviewWorld`,
  which keeps its `@GameTest`, the clear-ground search `rig(GameTestHelper, Options)` and its report counts.
- `PlantBuilder`: places structures, lays lines, installs valves and drives. Moved from the GameTests'
  `AssemblyGameTests.Plant`, with the cable helpers (`port`, `lay`, `place`, `cableRoute`, from
  `ColumnFailureModesReviewWorld`), the generator edit (`charge`, from `TankCharge.run`) and the creative removal
  (`removeStructure`, from `EquipmentTestCleanup.remove`). The GameTests reach it through `AssemblyGameTests.plant(helper, …)`.
  The old names delegate.

Details:

- **Preset**: `src/main/resources/data/icheme/worldgen/world_preset/column_test.json` and the entry in
  `data/minecraft/tags/worldgen/world_preset/normal.json`, the tag the Create World screen lists. NeoForge 1.21.1 loads
  a mod's world preset from its data, no code (no `RegisterEvent`). Button name `generator.icheme.column_test`.
- **Recognising the world** (`ColumnTestWorld.isPreset`, on `ServerStartedEvent`): the overworld's generator is flat and
  its settings encode, through `FlatLevelGeneratorSettings.CODEC`, exactly as the preset's own overworld read from the
  server's `worldgen/world_preset` registry (one source: the JSON). The GameTest world's flat preset and a player's
  Classic Flat carry the village structures, so they never match. Only a flat world rebuilt by hand from an
  empty-structure, no-feature preset (Redstone Ready) to these very layers and biome could match, and the build also
  requires game time 0 (a world that has never run).
- **Flag**: `SavedData` `icheme_column_test_world` on the overworld, format 1 (`ColumnTestWorld#FORMAT`, FORMATS.md):
  `built`, `started` and the chunks the build holds loaded. A newer format reads as built. No migration from the test
  mod's name of one day.
- **Build** (`ColumnTestWorld.build`): `CrudeUnitPlant.review` (the sizing), then `CrudeUnitPlant.rig(level, base,
  options)` and `Rig.build()` with the column's origin on the ground at the world spawn's x and z: the one entry the
  review GameTest's `rig(helper, options)` also builds through. The build holds the plant's chunks forced (the builder's
  `hold`, `ServerLevel.setChunkForced`); they are released when the start ends.
- **Start** (`ColumnTestWorld.Start`, on `ServerTickEvent.Post`): the GameTest `aCrudeColumnRuns`'s sequence with the
  same rig methods and conditions: `valvesInstalled`; 20 ticks, `unjoined`; `chargeDriven`, then `holdCharge` and
  `light`; `fueled`, then `openCrude`; `warmed`, then `start`; `past`. Engine schedule: the tick reads only
  registrations, views, burner views and the column's phase, and queues what a player's inputs queue (device edits, the
  charge drive's `Configure`, the assembly's validation and the column's start). It never solves or computes process
  state. The rig's `paced()`, which holds the tick for the solves, is used only by the preset's GameTest (a GameTest
  server ticks far ahead of the solves; a game runs at 20 ticks a second). The GameTest waits without limit; the
  world's start waits 6000 ticks a step.
- **Messages**: `message.icheme.column_test.started`, `.stopped` (the step), `.closed`, sent as the mod's other system
  messages (`ServerPlayer.sendSystemMessage`) to every player online.

## Verified

On the move (2026-11-18, after the control fix f31db3a4b was merged in):

- `compileJava`, `compileTestJava`, `compileFluidGameTestJava`: pass. No `net.minecraft.gametest` or NeoForge
  gametest import in `src/main`.
- The moved code is the old text: compared line by line with `ColumnOperationReviewWorld` and `AssemblyGameTests.Plant`
  at 87c95f0a2, the only differences are the names, `public` and the helpers moved in from the other test classes.
- `test`, `holdGates`, `columnGates`, `equipmentGates`: 3311, 138, 559 and 293 tests. The filter file was given without
  the lane's init script, so each task ran whole (not the 409 selected picks). 3 failures, none from the move:
  `LanguageFilesTest` (the new `en_us.json` keys wait for the translation commit), `SizingPageTest` in Chinese and
  `ColumnRunTest.aColumnsOutletFeedsAnother` (both named before in the README, the second left out of the lane).
  `FormatsTableTest` passes with the flag's row.
- `runReviewWorldServer -PreviewBatch=review-operation` on a fresh world: "All 5 required tests passed"; 1340 devices,
  21 slots; the sizing lines are f31db3a4b's (the same values; the readings print in another order). Charge pump at the
  reports, this run against f31db3a4b's: share 0.800, 0.583, 0.588, 1.000 at reports 1 to 4 in both; flow 0.24411,
  0.19206 (0.19213), 0.19200 (0.19199), 0 m3/s. Reports 5 and 6 differ (0.609 and 0.573 against 1.000 and 0.582) after
  a round-deadline hold of island 1412 at tick 2495 that the earlier run did not have there; the column on a solved
  table from report 4 in both.
- `runReviewWorldServer -PreviewBatch=review-column-test-world` on a fresh world: passes under `icheme:column_test`;
  online ticks from the build at t=118: valves 0, settle 20, drive 21, fuel 282, warm 482 (column started), first solve
  710.

Before the move (2026-11-17, in the test mod): review-operation "All 5 required tests passed" (1340 devices, as
fa33d0241); review-column-test-world passed on its second run (the first was unpaced: 6000 ticks in 2.5 s on the GameTest
server, `step=first_solve not reached`; the start now paces in its GameTest as the rig does).

## Not verified

- The Create World screen itself (the button and its name), the first load in a client, the creative switch on join,
  the chat messages and the spawn's place and facing: no dev client was started. The owner looks in game.
- `ServerStartedEvent`'s path as a whole (world set-up, build at the world spawn, flag) runs only in a world of the
  preset; its parts are the ones the GameTest runs. The dedicated server's `level-type` was not run.
- The start's timing in a client at 20 ticks a second (the GameTest server is paced by the solves instead).
