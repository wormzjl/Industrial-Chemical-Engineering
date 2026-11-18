# Machine assemblies: the check of a column's or an exchanger's plant against its design

Up: [code guide](README.md). When a check runs, what keeps and reverts its report, the screens, the laying tool's watch
and Start: [assembly-service.md](assembly-service.md). The column's design (`ColumnComponents` with its member counts,
`ColumnDesign`) and its nozzles: [equipment-designs.md](equipment-designs.md); the pipes and fittings the walk reads are
registry devices ([runtime-registry.md](runtime-registry.md)); what a started column does: [column-run.md](column-run.md).

## Read this when

- Check parts gives an answer you did not expect: a part in place, misplaced or missing where it should not be, a state
  on the wrong slot, a tee's branches assigned wrongly.
- Units the design wants in parallel (a condenser's banks or shells, kettles, a pumparound's coolers, an
  air-then-water condenser's trim) check wrong: `UNIT_COUNT`, `UNIT_SHAPES`, `CONFIGURATION` or a branch at their split
  or merge.
- An exchanger's control lines (a bypass round a side, a ratio station, a column condenser's water feed) check wrong.
- The walk reads a fitting as the wrong kind, stops early (`assembly status=WALK_LIMIT`) or reads a chunk as not loaded.
- You add a slot, a link or a part form to a column's blueprint, or a new kind of assembly.

## What it does

A real distillation column is one item of a plant: an overhead system (an ejector or a vacuum pump under vacuum, a
condenser, a receiver drum with a water boot, a reflux pump, a tee that splits reflux from distillate), a valve on every
line that leaves the plant, a pump and a cooler per pumparound, and a kettle reboiler where the design has reboiler duty.
The design saves which of these it needs and how many identical units stand in parallel on each heat-transfer step.
`ColumnBlueprint.of(trays)` turns the design into slots (the parts) and links (the pipe paths between them).
`AssemblyWalker` reads the pipes round the column from the fluid registry and the shells in loaded chunks as a
`PortGraph`. `AssemblyMatcher` follows the links from the column, meets a step of several units where its line splits
and goes on where their outlets join again, and gives an `AssemblyReport`: each slot in place (teal), misplaced
(yellow) or missing (red), each named port needed, connected, a utility or unused, and the structural code of every
block the walk read. A shell-and-tube exchanger is the second kind of master: when a side's control asks for lines,
`ExchangerBlueprint` gives its slots. The check is a pure function of the graph: the same pipes give the same report.

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role | Lines |
|---|---|---|
| [`science/assembly/PortGraph`](../../../src/main/java/com/wormzjl/icheme/science/assembly/PortGraph.java) | The pipes as the check sees them: `run(Port)` from a unit's port to its end, `branches(Junction, Run arrived)`; `Unit(origin, kind, lying, boot, nozzles, size)` with `Size` (diameter, length, bank, exchanger type: what makes units in parallel identical; null where not known), `Port(unit, role, ordinal)`; `Element(kind, direction, position, pipesBefore)` with `Fitting` CONTROL_VALVE, INLINE_PUMP, OTHER, FLOWMETER (codes by ordinal: a new kind goes last); ends `AtPort`, `Junction`, `Terminal`, `OpenEnd`, `NotLoaded`, `Unconnected`; `Run(elements, end, pipes, positions)` | 114 |
| [`science/assembly/Blueprint`](../../../src/main/java/com/wormzjl/icheme/science/assembly/Blueprint.java) | Slots and links: `Slot(part, index)` (lang key `gui.icheme.assembly.slot.<part>`); forms `UnitForm(kind, lyingWithBoot, in, out, utilities, exchangerType)`, `FittingForm`, `TeeForm(branches)`; `Step(slot, form, family, anyOrder, count)` (the first form is designed, the others fill the slot as the wrong kind; `withCount`: the identical units in parallel); `Start` at a slot's port or after a slot; finishes `AtUnit`, `ToPort`, `Outside`; `Link(name, start, steps, finish, upstream, owner)` | 146 |
| [`science/assembly/ColumnBlueprint`](../../../src/main/java/com/wormzjl/icheme/science/assembly/ColumnBlueprint.java) | `of(trays)`: the column's slots and links from its `ColumnComponents`, each heat-transfer step with its member count and its exchangers' `ExchangerType`; refuses trays without components; `waterCooled(condenser)`: the slot whose units' water sides cool the condenser | 164 |
| [`science/assembly/ExchangerBlueprint`](../../../src/main/java/com/wormzjl/icheme/science/assembly/ExchangerBlueprint.java) | An exchanger's control lines: `Lines` NONE, BYPASS, RATIO, FEED; `Design(tube, shell)`, `any()`; the slots per side (inlet tee, bypass valve, outlet tee, feed valve, meter, feed meter); `of(design)` (refuses a design without lines); `operated(side, lines)`: the slot whose valve the side's control works | 148 |
| [`science/assembly/AssemblyMatcher`](../../../src/main/java/com/wormzjl/icheme/science/assembly/AssemblyMatcher.java) | `match(blueprint, graph, master, taken)`: binds slots along the links in order, the worst fault per slot; `EXTERNAL_REACH`; units in parallel (`parallel`, `merge`, at most `MAX_MANIFOLD_RUNS` runs a manifold); tee branches by score | 661 |
| [`science/assembly/AssemblyReport`](../../../src/main/java/com/wormzjl/icheme/science/assembly/AssemblyReport.java) | `SlotReport(slot, state, member, at, detail, parallel)` (`units()`: the member, then the units beside it), `State` with its `Colour`, `PortReport(unit, role, ordinal, PortState)`, `footprint`, `members` (master first); `connected`, `misplaced`, `missing`, `complete`; `NONE` | 117 |
| [`science/assembly/AssemblyView`](../../../src/main/java/com/wormzjl/icheme/science/assembly/AssemblyView.java) | What a screen shows: `Status` NO_DESIGN, NOT_VALIDATED, CHECKED, COMPLETE, the report (only when checked), `changedAt`, `operation` (`withOperation`; a watch carries none) | 40 |
| [`runtime/assembly/AssemblyWalker`](../../../src/main/java/com/wormzjl/icheme/runtime/assembly/AssemblyWalker.java) | `PortGraph` over the world: a unit's `Size` from its shape; `Ground` (`loaded`, `piece`, `shell`), `Piece(pipe, terminal, fitting, facing, faces)` with `code()`, `Shell(origin, shape)`; `MAX_WALK`, `exhausted()`; `codes()` (every block and nozzle read); `nozzleCode(kind, nozzle)` | 251 |
| `runtime/assembly/LevelGround` (pp) | The `Ground` of a level: the registry's latest projected registrations (`FluidWorldAuthority.at`) and the shell block entities, loaded chunks only; `piece(device, metered)` (`FluidWorldAuthority.flowmeterOn`) | 81 |

## Main flow

1. `AssemblyService.validate` ([assembly-service.md](assembly-service.md)) builds an `AssemblyWalker` over `LevelGround`.
2. `AssemblyMatcher.match(ColumnBlueprint.of(trays), walker, master unit, store.taken(master))`; an exchanger:
   `ExchangerBlueprint.of(ExchangerLines.design(...))`.
3. For each link in order: `PortGraph.run` from its start port (an upstream link against the stream), each run's
   elements checked against the link's steps; at a `Junction` a tee's branches (`branches`) or a counted step's units
   (`parallel`, then `merge`).
4. Each slot gets its worst state, each named port its state; the walker's `codes()` and the report's footprint go to the
   store's entry. The entry is COMPLETE when every slot is teal, else CHECKED.

## Contracts

**The column's blueprint** (`ColumnBlueprint`). Slots in report order: `column`, [`ejector`], `condenser`,
[`trim_condenser`], `receiver`, [`vacuum_pump`], `offgas_valve`, [`water_pump`], `water_valve`, `reflux_pump`,
`reflux_tee`, `reflux_valve`, `distillate_valve`, `feed_valve`, `steam_valve` i, `draw_valve` i, `pa_pump` i and
`pa_cooler` i, [`reboiler`], `bottoms_valve`. Bracketed slots follow the design's flags: a vacuum top takes the ejector,
or with `ColumnComponents.Vacuum` `LIQUID_RING` or `DRY_SCREW` the vacuum pump and the water pump instead.

| Link | From | Steps | Finish |
|---|---|---|---|
| overhead | column VAPOUR_OUTLET | [ejector: SUCTION → DISCHARGE, MOTIVE a utility]; condenser: an air cooler's PROCESS_INLET or an exchanger's SHELL_INLET (the other kind is the wrong kind); air then water: the air cooler PROCESS_INLET → PROCESS_OUTLET, then the trim, an exchanger's SHELL_INLET | at the unit (the trim's, with air then water) |
| condensate | after the condenser (the trim) | receiver: a lying drum with a boot at its SIDE connection (any other drum is the wrong kind) | at the unit |
| offgas | receiver VAPOUR_OUTLET | [the vacuum pump of the design's type: SUCTION → DISCHARGE]; a control valve | outside |
| water | receiver BOOT_DRAIN | [the water pump, of the reflux pump's kind]; a control valve | outside |
| oil | receiver LIQUID_OUTLET | the reflux pump (standard or in-line as designed; the other is the wrong kind); the reflux tee: one branch through the reflux valve to the column's REFLUX, one through the distillate valve outside | at the tee |
| feed (upstream) | column FEED | a control valve | outside |
| steam i (upstream) | column STEAM i | a control valve | outside |
| draw i | column DRAW i | a control valve | outside |
| pumparound i | column PUMPAROUND_DRAW i | the pump and the cooler in either order (`anyOrder`; the cooler on its shell side, tubes a utility) | the column's RETURN i |
| reboiler, vapour_return, bottoms (with a kettle) | column BOTTOMS → kettle FEED; kettle VAPOUR_OUTLET → column VAPOUR_RETURN; kettle LIQUID_OUTLET → a control valve | | outside |
| bottoms (no kettle) | column BOTTOMS | a control valve | outside (no vapour return) |

- **Member counts.** The condenser step takes `units().condenser()` units (air cooler banks or exchanger shells), the
  trim step `units().trim()` shells, the kettle step `units().kettle()` kettles and each pumparound's cooler step
  `units().cooler(i)`; a count of 1 is one unit in line. The pumps and the receiver take none. Several units stand
  between a split and a merge on their link; where the condenser's banks and the trim's shells are both several, the
  banks' outlet header may split straight into the trim's shells. A link that starts after such a slot starts where its
  units' ports join.
- The reflux line is in every design, also at a zero reflux ratio. An upstream link (feed, steam) is walked from the
  column against the stream.

**The exchanger's lines** (`ExchangerBlueprint`). An EXCHANGER or a KETTLE is a master only while a side's control asks
for lines (`HeatUnit.lines()`: BYPASS for an outlet temperature or a duty, RATIO for a ratio, FEED where a running
column sets the side, mode COLUMN: a condenser's water side, a kettle's tubes). The design is read from the registered controls each time; it is never saved on
the shell.

| Link | From | Steps | Finish |
|---|---|---|---|
| `<side>_bypass` (upstream) | the side's inlet nozzle | the inlet tee: one branch outside (the feed), the other through the bypass valve to the outlet tee, whose branches go to the side's outlet nozzle and outside (the product) | at the unit |
| `<side>_feed` (upstream) | the side's inlet nozzle | a control valve (the feed valve) | outside |
| `<other side>_reference` (upstream, with RATIO) | the other side's inlet nozzle | a flowmeter | outside |
| `<side>_feed` (upstream, with FEED) | the side's inlet nozzle | the feed valve, then a flowmeter (`feedMeter`) | outside |

**The walk** (`AssemblyWalker`, `LevelGround`).

- A run starts at a nozzle's pipe block, the cell in front of its flange face, and continues only from a pipe with a
  joint towards the nozzle; it meets a structure at a nozzle facing it (`NozzlePorts.at`, the rule a pipe's drawn joint
  keeps too). It follows joined blocks. Only pipes join. A fitting passes along its axis and
  is recorded as an `Element` with its direction and the pipes before it.
- A run ends at a structure's nozzle (`AtPort`), a block of three or more joints (`Junction`), a one-block tank,
  generator or void (`Terminal`, outside), an open end, a chunk that is not loaded (`NotLoaded`), or no pipe in front of
  a nozzle (`Unconnected`).
- The walk reads loaded chunks only (`getChunkNow`), never passes through a vessel or a constructed mover, and stops
  after `MAX_WALK` blocks; the rest reads as not loaded.
- `LevelGround.piece(device)`: a device with ports (a vessel, a constructed mover, a heat side, a steam turbine) is null:
  the walk meets it at its shell's nozzles. A pipe's fitting comes from the law it compiles through (`hydraulicKind`):
  VALVE is a CONTROL_VALVE whatever its module or setting; PUMP or COMPRESSOR an INLINE_PUMP; a shutoff, check valve or
  strainer is OTHER and passes the line on; a plain pipe carrying a flowmeter is a FLOWMETER. A one-block filter is
  OTHER, a one-block pump or compressor an INLINE_PUMP, a one-block valve a CONTROL_VALVE; a reservoir, generator or void
  is a terminal.
- Every block and nozzle read gets a structural code (`Piece.code()`, or `nozzleCode`); a setting edit does not change a
  code; installing or removing a fitting, a flowmeter included, does.

**Slot states** (`AssemblyReport.State`, in rank order: a later one outranks an earlier one on the same slot):

| State | Colour | Meaning |
|---|---|---|
| OK | teal | in place |
| SHARED | yellow | the unit is already a member of another column's report (`taken`); it is not bound, and the line's later steps are MISSING |
| WRONG_ORDER | yellow | met on another line than its step expected |
| BRANCHED | yellow | a junction where the design has none: before a line's last step (unless the step is a tee), after it on a line that must reach a port, or a tee with more runs than branches |
| EXTRA_VALVE | yellow | a control valve the design does not ask for |
| VALVE_REVERSED | yellow | a control valve pointing against the stream |
| WRONG_PORT | yellow | a unit entered at the wrong nozzle, or a line ending at the wrong port |
| UNIT_SHAPES | yellow | units in parallel on one step are not all of one kind and size (`PortGraph.Size`) |
| UNIT_COUNT | yellow | another number of units in parallel than the step asks for (detail `found:wanted`; one unit in line where several are asked reads `1:n`) |
| WRONG_KIND | yellow | a unit or fitting of the slot's family but not the designed form |
| CONFIGURATION | yellow | an exchanger of the designed kind whose `ExchangerType` (Standard or Crossflow) is not the designed one; a missing saved type reads Standard |
| NOT_LOADED | red | the line runs into a chunk that is not loaded (or past the walk budget) |
| OPEN_END | red | the line stops |
| MISSING | red | nothing fills the slot |

- A unit is bound as soon as any of its ports is reached; a misplaced part stays bound and the walk goes on through it. A
  stopped line leaves its later steps pending; met later on another line they are WRONG_ORDER, never met they are
  MISSING.
- An outside line may end in a terminal, a branch, or an open end at least `EXTERNAL_REACH` pipe blocks beyond its last
  valve; fittings after that boundary valve are outside the assembly and not checked.
- A fitting's arrow must point with the stream: along the walk downstream, back along it on an upstream link, so the
  feed and steam valves point into the column.
- **Units in parallel** (`parallel`, `merge`). At a junction whose line's next step (or either of the next two in any
  order) counts several units, every unit of the step's form at the ends of the split's branches fills the slot: the
  first is the `member`, the others `parallel`. Each entry is checked as one unit's is. The wrong number is UNIT_COUNT at
  the split, units of another kind or size UNIT_SHAPES, a branch to anything else BRANCHED, a fitting between the split
  and the units EXTRA_VALVE (one a line passes over excepted); a split that loops or reads past `MAX_MANIFOLD_RUNS` is
  BRANCHED. Their outlets must join again at one merge with one way on; no way on leaves the later steps MISSING. A step
  of one unit stops at a branch.
- A tee's branches are assigned by the best score over their permutations: a branch scores by where it goes (its port 4,
  outside 2, a valve on it 1). A branch that splits again (an exchanger's bypass) adds 2 for a run ending at a junction
  and 1 for a valve where its steps ask for one.
- A flowmeter no step asks for is passed over and reads either way.
- **Port states**: NEEDED, CONNECTED, UTILITY (advice: cooling water, motive steam), UNUSED. Every port the blueprint
  names on a filled slot is NEEDED until a run reaches it (`requireNamed`).
- The matcher checks the number of units in parallel against `ColumnComponents.Units`; the units' sizes are compared
  only with each other, never with a sizing.

## Laws and constants

- `AssemblyMatcher.EXTERNAL_REACH`: the pipe blocks an outside line runs past its boundary valve.
- `AssemblyMatcher.MAX_MANIFOLD_RUNS`: the runs a manifold of units in parallel may read.
- `AssemblyWalker.MAX_WALK`: the blocks one check walks.
- `AssemblyWalker.nozzleCode(kind, nozzle)` = `-1 - (kind.ordinal() << 16 | nozzle)`.
- `AssemblyReport.NONE` = `Long.MIN_VALUE` (no member).

## Formats

None of its own: the report is kept in the store `icheme_assemblies` and sent in the `VIEW` codec
([assembly-service.md](assembly-service.md); numbers in [FORMATS.md](../FORMATS.md#saves)). `PortGraph.Fitting` codes
are by ordinal: a new kind goes last.

## Tests and gates

All under `test`.

| Rule | Owner | Test |
|---|---|---|
| What a column asks for (slots, links, families, member counts); the blueprint names every nozzle of the built column and no other | `ColumnBlueprint` | `ColumnBlueprintTest` |
| Units in parallel: the split, the merge, their faults | `AssemblyMatcher` (`parallel`, `merge`), `Blueprint.Step.count`, `PortGraph.Size` | `AssemblyParallelTest` (on `TestGraph`'s manifold `Hub`s) |
| An exchanger's lines; a bypass tee's branches; the flowmeter fitting | `ExchangerBlueprint`, `AssemblyMatcher`, `PortGraph.Fitting`, `LevelGround.piece` | `ExchangerBlueprintTest` (on `TestGraph`) |
| How a slot is filled or faulted; tee branches; outside lines; the same graph gives the same report | `AssemblyMatcher` | `AssemblyMatcherTest` (`theSamePipesGiveTheSameReport`, on the hand-built `TestGraph`) |
| How pipes and fittings are read from the world | `AssemblyWalker`, `LevelGround` | `AssemblyWalkerTest`, `LevelGroundTest` |
| A nozzle's flow direction, a unit's pass-through partner | `science/equipment/PortMarkings` ([equipment-geometry.md](equipment-geometry.md)) | `PortMarkingsTest` |

Filters: `--tests 'com.wormzjl.icheme.science.assembly.*'`, `--tests 'com.wormzjl.icheme.runtime.assembly.AssemblyWalkerTest'`.

## Traps

- **Columns without components have no assembly** (`AssemblyService.designed` is false): their screen shows NO_DESIGN.
- **The first report keeps a unit.** A unit another column's report already holds is SHARED for the next column checked
  and not bound there.
- **A setting edit changes no code.** A valve's module or a pump's flow is not structural; installing or removing a
  fitting or a flowmeter is.
- **A step's count is the design's**, not a sizing's: units in parallel of the right count and one size pass whatever
  their size.
- **The exchanger's lines are not saved on the shell**: a change of control that asks for other lines makes a new
  design, and the old entry is forgotten ([assembly-service.md](assembly-service.md)).

**Snapshot:** `9d6d0d82e`, 2026-11-15.
