# 2026-11-16 fouling, corrosion and erosion

Status: In progress since 2026-11-16. Plan revision 6; WP1 and WP7 started. Branch `claude/fouling-corrosion-system-ba0275`, cut from `main` at `f305ac6b0` (0.32.0), rebased on `main` at `66a87f9d5` (0.33.0) on 2026-11-16.

Purpose: walls that corrode by their material and their fluid (a refinery corrosion matrix, four materials of construction chosen in the constructor and in a simulator tab), erosion by particles (control valve trims, pumps, tubes), failures through the existing breach and machine failure, final blast damage, repair only when idle and empty, corrosive air on equipment, fouling of heat sides (lower films, higher drop, cleaning), and columns that pass inert particles to their bottoms and stop only for freezing.

Lane: new package `science/integrity`; hot files touched, each in one work package: `MaterialCatalog` (WP1), `FluidFacts`, `FluidWorldAuthority`, `ShaftTrains` (WP2), `EquipmentShape`, `EquipmentStructure` (WP3), `SheetSizing` (WP5), `ColumnOperation` (WP7). Class: new feature; no reference moves expected.

## History

- 2026-11-16, plan: survey of the existing pieces (`Wear` and `MachineHealth`, `Breaches` and `FluidDeviceSpec.breached`, the corrosive channel that nothing reads for equipment, the constant films, the assays' sulfur as metadata, free construction); the corrosion matrix from API 571, 581, 939-C, 932-B, de Waard–Milliams and TEMA (game-level fits; three web searches gave no table values beyond these references); [FOULING_CORROSION_PLAN.md](FOULING_CORROSION_PLAN.md). Decisions D1 to D4 taken by the agent; O1 to O4 put to the owner. No code, no test run.

- 2026-11-16, owner: O1 the Kern–Seaton placeholder grows the deposit until the reaction module; O2 the cost index only, construction stays free; O3 sounds set A (one new loop, `wall_weep`); the start of WP1: "Not yet", the owner reviews the plan first. Nothing started.

- 2026-11-16, owner, after the plan: "Need a smaller list of materials, keep in mind this is not for full realism"; "Allow distillation column to handle inert particles and pass particulates to bottom output, only stop when something freezes"; "Add particulate erosion to equipment, especially control valves". Plan revision 2: four materials (carbon steel, chrome steel, stainless steel, nickel alloy), one per structure (D2 changed); erosion by `φ v^2.6` on heat-side walls, a `SOLIDS` wear fault for pumps and a trim health for control valves that fails open (D5, section 8); columns (section 13, WP7). Survey for it: the running column already moves inert solids to the bottoms and melts arriving crystals (`ColumnOperation`); the simulator column refuses every solid (`ColumnBlockSolve` line 119); nothing stops for freezing. Reading taken (D6): freezing becomes a stop in the simulator and a trip in the running column. No code, no test run.

- 2026-11-16, owner, on D6: "only when solidification happens inside column". Plan revision 3: an arriving crystal melts into the feed in both columns and stops nothing; a product flash of the solve that holds a crystal phase refuses the simulator's run (`column.frozen`) and trips the running column (`Condition.FROZEN`). `ColumnOperation.credit` and `melted` stay as they are.

- 2026-11-16, owner: "how about hot hydrogen service and hp oxygen service?". Plan revision 4 (D7): hydrogen attack (M10: above 7 bar of hydrogen, carbon steel to 230 °C, chrome steel to 450 °C, stainless and nickel alloy free; API RP 941 reduced to one temperature per material), the H2/H2S row of sulfidation (chrome steel 0.6), and oxygen fire (M11: carbon and chrome steel to 2 bar of oxygen, stainless to 14 bar, nickel alloy to 69 bar; EIGA Doc 13 exemption pressures). Both booked as equivalent wall loss on the one wall record; an oxygen fire's failure is also a flame for the next pollution pass.

- 2026-11-16, owner: "how you deal with the combined P, T effect?". Answer: M1 to M7 were already a pressure term times a temperature term; M10 and M11 were steps. Plan revision 5: the hydrogen limit is a line in log pressure (carbon steel 260 °C at 7 bar, 45 K per decade, floor 205 °C; chrome steel 600 °C, 120 K per decade, floor 450 °C); the oxygen limit halves per 100 K above 150 °C, floor 0.3 bar.

- 2026-11-16, owner: "repairs needs to be performed with the constructor". Plan revision 6 (D8): a Repair key in the constructor's group of the structure in front, beside Deconstruct, queues the structure's repair (idle and empty, no item); the repair kit is refused on constructed equipment and stays for in-line pumps and compressors and for control valve trims.

- 2026-11-16, owner: "Keep the kit": the repair kit stays for in-line pumps and compressors and for control valve trims.

- 2026-11-16, owner: "Start both" (WP1 and WP7 as two Opus medium sub-threads), then "rebase on latest main first". The branch rebased on `main` `66a87f9d5` (0.33.0, 67 commits ahead of the old base); one conflict, `documentation/INDEX.md`, every row kept. `main` now holds facts schema 27 and `icheme-3` (fluid interfaces) and reserves 28, state format 51 and `icheme-4` (supercritical fluids), so this batch takes facts schema 29 and `icheme-5`; shape fields 13 and sheet file 3 stand. The plan's code facts checked on the new base: `ColumnBlockSolve` line 119 unchanged; `Wear` gained the turbine's wet-exhaust onset by pressure (no clash with `SOLIDS`).

- 2026-11-16, WP7 and WP1 started on the owner's "Start both", each an Opus medium sub-thread with a fresh brief in its own worktree cut from this branch at `5330f457e`: `fc-wp7-columns` (branch slug `fouling-corrosion-wp7-columns`: the simulator column passes inert particles and melts a frozen feed; `Condition.FROZEN` from the solve's product flash) and `fc-wp1-laws` (branch slug `fouling-corrosion-wp1-laws`: `science/integrity`, `Wear.Fault.SOLIDS`, `MaterialCatalog.Corrosion`, the twelve cuts' data). One Gradle lane each, `--max-workers 4`. Neither edits this README or the index; the main thread records their reports.

## Documents

- [FOULING_CORROSION_PLAN.md](FOULING_CORROSION_PLAN.md): the request, the physics in numbers, scope and CPU, materials, the matrix, the wall and its failures, repair, fouling, GUI, sounds, formats, work packages.

## Code areas

None yet.

## Open items

- WP1 and WP7 running; their reports and diffs to be checked by the main thread.
- O4: the format reservation on `main` (facts schema 29, shape fields 13, `icheme-5`, sheet file 3), needed before WP2; the owner's word.
- `wall_weep` candidates (three, synthesised) once the build starts.
- Whether another live batch holds the hot files named above.
