# P5 part 2: building and starting the unit (chapters `building` "Building" and `start` "Start-up")

Part 1: P5_PART1_UNIT_CHAPTER.md. Stage A read the code (no change); stage B built on it.
## Supplies (decisions 1 and 2)

| Need | Form chosen | In-mod path |
|---|---|---|
| Steam, 259 kg/h at 533 K | **A**: H-102, a fired heater on water | Water converter (`minecraft:water`, 5 bar) into H-102 (outlet 533.15 K) into T-101's steam. Sheet: unit H-102, streams BFW and STEAM; products bit-identical (18.2 / 34.9 / 46.9 %); H-102 207.2 kW, the catalogue's smallest vertical heater, all lamps green |
| Heater fuel | Crude, on both FUEL nozzles | Crude burns at about 42.8 MJ/kg (pseudo-components' combustion records); fired = absorbed × 1.775 (`FIRED_PER_RADIANT`): H-101 about 1.80 MW, 150 kg/h; H-102 209.6 kW, 17.6 kg/h (proof 2). Off-gas to a flare |
| Power (default `machinesNeedPower`) | In the mod | Pumps on a combustion engine (crude) or a gas turbine (off-gas); burner and air-cooler fans on FE from a generator on a shaft train |
| Cooling water | Converter in, drain out | `minecraft:water` converter to the condenser, back to a drain converter |
| Crude | Unchanged (O2) | A converter on another mod's oil |

## Start-up order (from the code)

1. Lines, valves and Check parts COMPLETE. 2. Crude in its tank to the feed pump; fuel to both burners; fans and pumps
powered; cooling water through; water to H-102. 3. On (Operation tab). Before Start the column's inlets dead-end at their
nozzles: the boundary valves become ports only while the column runs, so no crude or water flows through H-101 or H-102
before Start. A heater with no flow asks no fuel and stays unlit; both light as flow comes. 4. The first solve is a START
solve of the design (no seed); a feed colder than the design re-solves on the ENTHALPY band (1 K). 5. The products
leave; the line names any limit.

## What was built

- **Chapters** (8 keys fit the 320 by 240 window; one thing to build a step): `building` site (film `unit_build`,
  watched to its end: code review finding 3, `design_sent` was the simulator's Send step's too), tower (COLUMN built), top (the condenser and drum; DRUM built), pumps (driver's page), loop (exchanger's
  page), lines (read; the Structure tour reports it too), parts (`assembly_state` COMPLETE). `start` crude, fuel, power,
  cooling, steam, tanks (each opens its kind's page; the server sends nothing checkable before On), go (`column_state`
  started), limit (card, RUNNING), products (a product leaving), end (names templates and the controller box, not vacuum).
- **Criteria** `assembly_state`, `column_state` (`AssemblyCondition`, `ColumnCondition`): fired where the assembly
  screen's view is sent (`AssemblyNetwork` host send, a read). No converter, heat-side or tank criteria were needed.
- **Film** `unit_build` (5 stops: tower; drum and overhead line; the crude's way with the loop cooler; H-102 and its
  water; the products), one camera key a stop, the tower whole. **Card** `limit`: feed short, steam short, tank full,
  each line from `ColumnStatus.line`; steam short reads 100 % (rule B takes the whole feed), so its advice says so.
- **Tours** `assembly.structure` (6 stops) and `assembly.operation` (4), the `?` key left of the theme key.
- Chapter 4's heat step names H-101 (`SheetCondition.heater`): H-102 at 533 K had met it. "Build the unit" was cut at 312 px; the id is `building` as `build/` is git-ignored.

## Proofs and gates (each class once, `--tests`, 4 workers)

- Proof 2 `SteamHeaterCoilTest` (1): water at 259 kg/h through H-102's coils reaches 533.1 K, vapour 1.00, no alarm.
  Proof 3 (holdGates): `SmallCrudeUnitTest` (4), `SmallUnitRecordingTest` (2), all pass.
- Proof 1 `TutorialUnitGameTests` (small_crude at its design feed: COMPLETE, On, RUNNING, the guide's conditions hold,
  plant removed): compiled; the fluid GameTest task has no per-test filter, so its first run is the final lane's.
- Quick suite: 18 classes, 85 tests pass, after six fixes from the first run (chapter list, chain end, triggers, one
  criterion for `top`, condenser marked, H-102 at the unit film's stop 3) and one rename; new gate: a client step with no
  media must be one a screen reports. Not run: LanguageFilesTest, UnitTextTest.
- Renders `tmp/tutorial-renders/p5/` (`StageBRender`): `unit_build-*`, `card-limit`, `tour-assembly-*`, `guide-building`, `guide-start`.

**Not proved by any test:** the survival supplies together (converters, crude-fired burners, engine-driven pumps, FE fans, H-102 feeding the tower):
the owner's in-game check.
