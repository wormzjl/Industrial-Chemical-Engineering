# Default equipment names: review

Branch `claude/default-equipment-names-0caa95` (cut from `claude/column-testing-sizing-26b5b9`). Not merged, not pushed.

## Request and owner choices (2026-11-18)

"Give equipment a default name". Choices: format **"Plant tag: P-101"**; scope **built equipment only** (what the constructor builds; valves, sensors and pipe devices stay unnamed); existing unnamed equipment in saved worlds: **leave unnamed**.

## The rule

- `science/equipment/PlantTag` (pure, no Minecraft type):
  - `prefix(kind)`: one letter code per `EquipmentShape.Kind`, an exhaustive `switch` (a new kind does not compile until it has a prefix).
  - `next(prefix, taken)`: the lowest n ≥ 101 such that no taken name is exactly `prefix + "-" + n` (as stored, case-sensitive; `P-0101`, `P-1010`, `PX-101`, `p-101`, `P-101 feed` do not count). Deterministic. Numbers above `101 + taken.size()` cannot close a gap and are not stored, so a name such as `P-2147483647` costs no memory.
  - `tag(kind, taken) = prefix + "-" + next`. Every tag passes `DeviceName.clean` unchanged and the ledger's `named` (checked for every kind); the longest possible, `TK-2147483647`, is 13 of the 32 characters allowed.
- Applied in **one place**: `EquipmentStructure.place(level, origin, shape, paint, name, rows)`. When `name` is empty and the structure is placed whole (`rows >= sizeY`), after the cell checks pass and before any cell is set, the name becomes `defaultName(...)` = `PlantTag.tag(kind, FluidWorldAuthority.structureNames(dimension))`. The shells and the registry's structure record then hold it as any name.
  - `FluidWorldAuthority.structureNames(dimension)`: the non-empty names of the registry's structure records of that dimension, read from the structure chunk index (every record, loaded or not), on the server thread.
- It is an ordinary name in the existing fact field: no new saved field, no format change, no migration, no protocol change.
- A rename, a load, a `StructureRefresh`, a restate and a re-alignment never give a tag: they do not call `place`. A cleared name stays empty.
- The constructor's design keeps its own name empty, so the next build takes the next number. **Three pumps built in a row** from one constructor: the chat says "Built P-101 (Pump): … cells from …", then P-102, then P-103; each has a nameplate with its tag. Clear P-102's name and build again: the new pump is P-102 (the lowest free number).
- The build message: `EquipmentNetwork.build` reads the name the cells hold after `place` (`EquipmentStructure.name(level, vessel)`), so it sends `built_named` with the tag. `built`, `built_column` and `built_unit` remain for a build with no fluid world (no tag); in play they no longer show, so the column's diameter and tray count leave the build message (the datasheet still shows them).
- Paths through the same call, which therefore also tag: the constructor's Build, the `/icheme` debug placement command, and the column test world's `PlantBuilder.place` (no special case).
- There is no multi-equipment build: one Build places one structure. A column's condenser, drums and pumps are built one by one, each with its own kind's tag.
- Engine schedule: a build-time world edit. Nothing on the tick loop, no forced solve, no engine event.

## Kind-to-prefix table (for the owner's veto)

| Prefix | Kinds | Question |
|---|---|---|
| `C` | `COLUMN`, `BATCH_STILL` | Batch still as `C`? Alternative: `V` (the pot) |
| `V` | `DRUM`, `ADSORBER`, `CYCLONE`, `GAS_CONVERTER` | Adsorber (often `V` or `A`), cyclone (often `S` or `CY`), gas converter (its pressure vessels) |
| `TK` | `TANK`, `SPHERE`, `FLUID_CONVERTER` | Sphere (often `TK` or `V`); fluid converter (its atmospheric tanks) |
| `E` | `EXCHANGER`, `KETTLE`, `AIR_COOLER` | Air cooler sometimes `EA` / `AC` |
| `P` | `PUMP`, `PLUNGER_PUMP` | |
| `K` | `COMPRESSOR`, `CENTRIFUGAL_COMPRESSOR`, `LIQUID_RING_PUMP`, `DRY_SCREW_PUMP`, `EJECTOR` | Vacuum pumps as `K` (gas movers) or `P`; ejector usually `J` / `EJ` |
| `H` | `VERTICAL_HEATER`, `CABIN_HEATER`, `FLUIDIZED_BED_BOILER`, `GRATE_BOILER` | Boilers often `B` |
| `R` | `STIRRED_REACTOR`, `TRICKLE_BED_REACTOR`, `EBULLATED_BED_REACTOR`, `FCC_CONVERTER`, `DELAYED_COKER`, `FLUIDIZED_BED_GASIFIER`, `FIXED_BED_GASIFIER` | Delayed coker (coke drums, often `V` / `D`) |
| `CT` | `INDUCED_DRAFT_COOLING_TOWER`, `NATURAL_DRAFT_COOLING_TOWER` | |
| `FL` | `FLARE_STACK`, `STEEL_STACK`, `BRICK_CHIMNEY` | Stacks and chimneys often `S` / `STK` |
| `M` | `MOTOR`, `GENERATOR`, `GEARBOX`, `STEAM_TURBINE`, `COMBUSTION_ENGINE`, `GAS_TURBINE` | Generator `G`, gearbox `GB`, turbines `KT` / `T` are common |

`F` (filter) from the base table has no kind: the in-line filter is a pipe device, out of scope.

A changed letter is one line in `PlantTag.prefix`; it changes only new tags (saved names are facts and stay).

## What was not changed

- Pipe devices (`FluidDeviceBlockEntity.nameable()`: valves, sensors, controllers, intakes, outfalls): no default.
- Equipment already in saved worlds: unnamed stays unnamed (no load-time tagging).
- A player-typed or imported name is never replaced.
- A partial placement (`rows < sizeY`, the debug command's row argument) takes no tag: it is not recorded, so its tag could not be counted as taken.
- No saved format, wire format, protocol or `FORMATS.md` row.

## Tests

- New `science/equipment/PlantTagTest` (`equipmentGates`, 4 tests): every kind has a prefix of 1 or 2 capital letters; 101 first, P-101 + P-103 give P-102, three pumps in a row; near misses; every tag passes `DeviceName.clean` and `valid`.
- `runtime/fluid/WorldTopologyLedgerTest.everyPlantTagIsAValidStructureName` (`test`): every kind's tag passes `named` and a `Structure` record takes it. (`equipmentGates` has no runtime class on its classpath, so this check sits here; its first place, in `PlantTagTest`, failed with `NoClassDefFoundError`.)
- New GameTest `fluid/gametest/DefaultNameGameTests.anUnnamedBuildTakesTheNextFreeTagOfItsKind` (batch `equipment-default-names`): two unnamed drums take the next two free `V-` tags on every shell cell and in the record; a named drum keeps "Feed drum"; a tag cleared by `rename(…, "")` stays empty after a restate; all three drums are dismantled. **Not run** (GameTests are the final lane's).
- No existing test asserted an empty name after a build; none changed.
- Runs: in the batch README's history.

## Not verified

- The GameTest (final lane only), and the build in a client: the nameplate and screens showing the tag, and the chat line.
- GameTests and review worlds that place structures now get tags. None asserts a name, and no pinned digest of structure records was found, but the final lane is the check.
