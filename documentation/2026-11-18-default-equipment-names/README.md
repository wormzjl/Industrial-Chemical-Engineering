# 2026-11-18-default-equipment-names

Status: in progress since 2026-11-18, on `claude/default-equipment-names-0caa95` (not merged).

Purpose: constructor-built equipment takes a default plant tag (P-101, V-102, ...) as its name.

## The owner's request (2026-11-18)

"Give equipment a default name". Choices: format "Plant tag: P-101"; built equipment only; existing unnamed equipment left unnamed.

## Documents

- [DEFAULT_NAMES_REVIEW.md](DEFAULT_NAMES_REVIEW.md): the rule, the kind-to-prefix table with its questions, where it applies, what was not changed, tests, not verified.

## Code areas

- `science/equipment/PlantTag`; `world/level/block/EquipmentStructure` (`place`, `defaultName`); `runtime/fluid/FluidWorldAuthority.structureNames`; `network/EquipmentNetwork.build` (the message); `en_us.json` (`tutorial.icheme.tour.vessel.datasheet.5`).
- Tests: `PlantTagTest`, `WorldTopologyLedgerTest.everyPlantTagIsAValidStructureName`, GameTest `DefaultNameGameTests`.

## History

- 2026-11-18: build (`cab9affd7`, `a9e49561b` record check moved, documents in the last commit). Runs, classes from `affected.mjs select` through `record.init.gradle`, 4 workers: first `test` 150 classes and `equipmentGates` 2 (PlantTagTest's record check failed: no runtime class in `equipmentGates`; moved to `WorldTopologyLedgerTest`); after the move and the tour line, since `cab9affd7`: `test` 213 classes, 1157 tests, `holdGates` 9 classes, 50 tests, `equipmentGates` 6 classes, 87 tests, 0 failed. `LanguageFilesTest` and `UnitTextTest` left out (translation commit). GameTest `DefaultNameGameTests` not run (final lane).

## Open items

- Owner veto on the prefix table (questions in the review).
- GameTest and an in-game look at a tagged build: not run.
