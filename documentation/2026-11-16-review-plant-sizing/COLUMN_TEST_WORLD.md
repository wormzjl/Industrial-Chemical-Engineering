# Column test world: a world preset that builds and starts the crude unit

Batch `2026-11-16-review-plant-sizing`, 2026-11-17. Owner: "Add a world generator to generate the column test case from
code (creative, superflat, no day/weather cycle)"; chosen form: a world preset in the Create World screen, with the
plant started.

## What it is

A dev-only world preset, **IChemE column test**, in the test mod `icheme_fluid_test` (`src/fluidGameTest`). It is never
in `src/main` or the mod's jar. A world made with it is:

- superflat: bedrock, two dirt, grass (the GameTest world's layers), plains, no structures, no features, no lakes; the
  nether and the end as vanilla gives them;
- creative (the world's default game type and every joining player), daylight, weather and mob spawning cycles off,
  spawn radius 0, clear weather at noon (time 6000);
- with the review rig's crude unit (`ColumnOperationReviewWorld`: the sized column, condenser, receiver, pumps,
  pumparound coolers on the crude, charge pump on its flow-regulating drive, residue exchanger, two fired heaters) built
  at spawn on the world's first load, then started.

## How to use it

```bash
./gradlew runReviewClient
```

`runReviewClient` is the `client` run with the test mod loaded, in the same `run/` folder (your options and saves).
The plain `runClient` does not load the test mod and does not list the preset.

1. Singleplayer, Create New World, World tab, World Type: **IChemE column test**. Game mode does not matter (the world
   sets creative). Create.
2. The loading screen stays up while the plant is sized and built: about 10 s of sizing and about a minute of pipe
   routing on the review machine (the GameTest's build took 64 s).
3. You spawn north of the plant's box, at the column's x, facing south at the column.
4. The start runs by itself, one step a tick at most, in the rig's order. In play time (from the GameTest's log at
   fa33d0241): valves installed and nozzles checked in about 1 s, the charge drive's train and the heaters' fuel in a
   few seconds, the hot-charge wait at most 200 ticks (10 s), the column started about 25 s after you spawn and past its first solve about 35 s after
   (the preset GameTest: 479 and 707 online ticks after the build). The log (`run/logs/latest.log`) shows each step as a `column_test_world` line.
5. A reload builds nothing (the flag is set before the build).

The log lines:

| Line | When |
|---|---|
| `column_test_world built origin=… devices=… structures=… box=…` | after the build |
| `column_test_world spawn=… facing=south` | spawn moved |
| `column_test_world step=<name> reached t=<online tick> ticks=<waited>` | each step: `valves`, `settle`, `drive`, `fuel`, `warm` |
| `column_test_world done t=… column=…` | the column past its first solve |
| `column_test_world step=<name> not reached` | a step waited 6000 ticks (5 min); the start stops |
| `column_test_world step=<name> failed: …` | a step's own check failed (an unjoined nozzle, the column refused); the start stops |
| `column_test_world step=start not finished before the world closed …` | the world closed during the start: it does not resume; finish by hand |

The rig's own `review_operation …` lines (sizing, charge pump hold and start, warming, status=STARTED) appear too.

## How it works

- **Preset**: `data/icheme_fluid_test/worldgen/world_preset/column_test.json` and the entry in
  `data/minecraft/tags/worldgen/world_preset/normal.json`, the tag the Create World screen lists. A mod's data is
  part of the default data packs, so NeoForge 1.21.1 loads the preset with no code (no `RegisterEvent`). Its button name
  is `generator.icheme_fluid_test.column_test` in `assets/icheme_fluid_test/lang/en_us.json` (not in the mod's
  `en_us.json`).
- **Recognising the world** (`ColumnTestWorld.isPreset`, on `ServerStartedEvent`): the overworld's generator is flat
  and its settings encode, through `FlatLevelGeneratorSettings.CODEC`, exactly as the preset's own overworld read from
  the server's `worldgen/world_preset` registry (one source: the JSON). The GameTest world's flat preset and a player's
  Classic Flat carry the village structures, so they never match. Only a flat world rebuilt by hand from an
  empty-structure, no-feature preset (Redstone Ready) to these very layers and biome could match, and the build also
  requires game time 0 (a world that has never run) and the test mod loaded (`runReviewClient`).
- **Flag**: the test mod's own `SavedData` `icheme_fluid_test_column_test_world` on the overworld (new; no saved
  format of the mod changes): `built`, `started` and the chunks the build holds loaded.
- **Build** (`ColumnTestWorld.build`): the rig's sizing (`ColumnOperationReviewWorld.review`), then
  `ColumnOperationReviewWorld.rig(level, base, options)` and `Rig.build()` with the column's origin on the ground at the
  world spawn's x and z. `rig(level, base, options)` is the one entry the GameTest's `rig(helper, options)` also builds
  through; `AssemblyGameTests.Plant` takes a `ServerLevel` (it never read the helper for anything else). The build holds
  the plant's chunks forced (the rig's `hold`, `ServerLevel.setChunkForced`), and they are released when the start ends.
- **Start** (`ColumnTestWorld.Start`, on `ServerTickEvent.Post`): the GameTest `aCrudeColumnRuns`'s sequence with the
  same rig methods and conditions: `valvesInstalled`; 20 ticks, `unjoined`; `chargeDriven`, then `holdCharge` and
  `light`; `fueled`, then `openCrude`; `warmed`, then `start`; `past`. The tick only reads registrations, views and the
  column's phase and queues the rig's engine events (device edits, the charge drive's `Configure`, the column's start):
  no solve on the tick. The GameTest's `paced()` (holding the tick for the solves) is used only by the preset's GameTest: a game runs at 20 ticks
  a second. The GameTest waits without limit; the world's start waits 6000 ticks a step.

## Verified

- `compileJava compileTestJava compileFluidGameTestJava`: pass.
- `runReviewWorldServer -PreviewBatch=review-operation` on a fresh world: "All 5 required tests passed" (the 4 of fa33d0241 and the new one, which skips when not named); the same plant: 1340 devices, 21 slots, the same sizing and layout lines; the charge check made at report 3; column on a solved table from report 4; three round-deadline holds of island 1412 or 1402 (reports 7 to 10, the known one); no `column_test_world` line (the GameTest world is not taken for the preset).
- `runReviewWorldServer -PreviewBatch=review-column-test-world` (`ColumnTestWorldReviewWorld`, runs only when named):
  "All 5 required tests passed" on its second run. The first run stopped at `step=first_solve not reached`: the GameTest server ticked its 6000 ticks in 2.5 s, ahead of the solves; the test now paces the start as the rig does (`Start(..., paced=true)`, the rig's `paced()`; a client is not paced). Second run, online ticks from the build at t=121: valves 0, settle 20, drive 21, fuel 279, warm 479 (column started), first solve 707. It checks the preset is loaded from the test mod's data (the registry's codec: a bad file fails the
  server's data load) and is in `minecraft:normal`, that its overworld has no structures and is recognised while the
  GameTest world is not, then builds through `ColumnTestWorld.build` on clear ground and runs `ColumnTestWorld.Start` on
  the server tick to its end.

## Not verified

- The Create World screen itself (the button and its name), the first load in a client, the creative switch on join
  and the spawn's place and facing: no dev client was started. The owner looks in game.
- `ServerStartedEvent`'s path as a whole (world set-up, build at the world spawn, flag) runs only in a client world of
  the preset; its parts are the ones the GameTest runs.
- The start's timing in a client at 20 ticks a second (the GameTest server is paced by the solves instead).
