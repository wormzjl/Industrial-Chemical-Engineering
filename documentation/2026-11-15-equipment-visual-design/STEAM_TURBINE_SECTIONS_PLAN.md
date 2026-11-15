# Steam turbine: combined sections (plan)

Date: 2026-11-15. Batch `2026-11-15-equipment-visual-design`, finding K15 of [VISUAL_REVIEW.md](VISUAL_REVIEW.md).
Status: planned, nothing built.

## 1. Owner decisions (2026-11-15)

- "HP turbine should include MP and LP section, MP should include LP section, make combined model and equipment (yes
  this changes their function)".
- Function: **straight through**. An HP turbine always expands through its MP and LP sections to a low exhaust; an MP
  turbine through its LP section. Back-pressure turbines into the MP or LP header are no longer built new. Saved ones
  keep working.
- **Add an LP class**: an LP turbine is the LP section alone.
- **Every size** gets the combined sections, not only the range above 45 MW.

## 2. Expected behaviour

Steam enters at its header's class and leaves below the LP header. One shaft, one governor valve, one work law.

| Class | Inlet | Sections | Stage drop, kJ/kg (the design's fit, 200 per e-fold) | Stages at 10 kPa |
|---|---|---|---|---|
| VHP | 100 bar, 510 °C | HP, MP, LP | 354 + 266 + 761 = 1,382 | 12 (the cap) |
| HP | 42 bar, 400 °C | HP, MP, LP | 181 + 266 + 761 = 1,208 | 12 (the cap) |
| MP | 17 bar, 300 °C | MP, LP | 266 + 761 = 1,027 | 11 |
| LP (new) | 4.5 bar, T1 | LP | 761 | 8 |

Section boundaries are the header pressures: 17 bar between HP and MP, 4.5 bar between MP and LP.

The work law does not change: steam flow = shaft power / (η × isentropic drop), η from 0.45 to 0.76 by rated power
(`SteamTurbine`). About 14 kg/s for 10 MW from HP steam to 10 kPa on that law (an estimate from ρ ≈ 14.3 kg/m³; the
build measures it). Mass and energy stay exact: one inlet, one exhaust, the exhaust at h_in − W/ṁ.

## 3. What the code does now

- `MachineDuty.Steam` has MP (1,700 kPa, 300 °C), HP (4,200 kPa, 400 °C), VHP (10,000 kPa, 510 °C). No LP.
- The exhaust is any of `MachineDuty.EXHAUSTS` (10 to 4,200 kPa) up to half the inlet (`SteamTurbineDesign.checkDuty`,
  `step`, `MachineDuty.withSteam`).
- `SteamTurbineDesign.sections` returns sections only above `SINGLE_CASING` (45 MW): MP where HP or VHP steam expands
  below 1,700 kPa, LP where the exhaust lies below 450 kPa. Up to 45 MW `SteamTurbineTrain` draws one casing: an HP
  barrel, a cone and an exhaust barrel. The sections are drawing only (owner P12, 2026-11-10).
- Above 45 MW one crossover header runs from the front casing over every section with a drop into each. Steam runs
  through the casings in series, so a header in parallel is wrong.
- The class is saved by name (`EquipmentShellBlockEntity`, `ShapeRepair.STEAM_ALIASES`, `SizingFactsFile`) and sent by
  ordinal (`EquipmentNetwork`, lines 320 to 321).
- `TurbineSizing.auto` proposes the lowest class that holds the inlet, at the exhaust of `EXHAUSTS` nearest the tile's.

## 4. Design

### 4.1 Steam classes

`MachineDuty.Steam` gains `LP(450, T1)`, declared first so that "lowest class first" loops stay right. Saved names do
not move. The wire ordinal moves: bump `NetworkProtocol.VERSION` and its row in `documentation/reference/FORMATS.md`.

### 4.2 Exhaust rule

- New constant `MachineDuty.TURBINE_EXHAUSTS`: the `EXHAUSTS` below the LP header, 10, 15, 20, 150, 250, 350 kPa.
- `SteamTurbineDesign.step("exhaust")`, `MachineDuty.withSteam` and the constructor offer only those, still at most
  half the inlet (LP: 10 to 150 kPa).
- `checkDuty` keeps accepting every exhaust it accepts today. A saved back-pressure turbine is a fact: it loads,
  refreshes and runs as before. Only the offers change.

### 4.3 Sections: one rule for every size

`sections(duty)` loses its `SINGLE_CASING` guard and returns the sections behind the front one:

- MP where the class is above MP and the exhaust is below 1,700 kPa;
- LP where the exhaust is below 450 kPa, double-flow above `DOUBLE_FLOW` (100 MW); none for the LP class, whose
  front section is the LP.

New designs therefore give HP+MP+LP, MP+LP and LP. A saved back-pressure turbine gives fewer sections by the same
rule, with no special case.

### 4.4 The model

- **Up to 45 MW: one casing, stepped.** `SteamTurbineTrain` draws one barrel per section between the end walls it has
  today ((0.5 + 0.15 N) Dc): the HP barrel 0.65 Dc, the MP barrel 0.85 Dc, the exhaust (LP) end Dc, short cones
  between, each barrel's length in proportion to its stages. The steam chest, the trip-and-throttle valve, the
  pedestals, the shaft height, the length and both nozzles stay where they are.
- **Above 45 MW: separate casings on one shaft**, redrawn against the cues:
  - each casing split, with a joint flange along the shaft line;
  - the MP casing widening toward its exhaust; the LP hood as today, double-flow above 100 MW;
  - the crossover in legs: front exhaust to the next inlet, MP exhaust to the LP inlet. The single header goes;
  - pedestals with a base and a bearing cap in place of boxes.
- An LP-class turbine above 45 MW is the LP casing alone behind the front standard.
- Polygons: no new bolt solids. Joint-flange bolting waits for the relief sprite route (review P1); until then the
  flange carries today's studs only where it has them. Count polygons by material before and after for the six
  drawing scenes.

### 4.5 Simulator

- `TurbineSizing.exhaust` picks from `TURBINE_EXHAUSTS`.
- A tile whose exhaust stands at or above the LP header reads red on a new Must limit, `Limit.TURBINE_EXHAUST`
  ("exhaust at or above the LP header: no turbine is built for back pressure"), with its lang line in `en_us.json`.
  The tile's own run (`TurbineUnit`) does not change.
- `FlowsheetCatalog` and `SizingCoverage` need no new kind: the kind is still `STEAM_TURBINE`.

### 4.6 Saves and formats

- No facts schema number moves: `Steam` is saved by name and gains a constant.
- One corpus image under `src/test/resources/compat/` holding an LP-class turbine and a back-pressure HP turbine.
- Saved structures refresh to the current layout (`EquipmentRefresh`). Section 5, T4.

## 5. For the owner

| Id | Question | Options | Recommended |
|---|---|---|---|
| T1 | LP steam temperature | A: 200 °C (52 K superheat). B: saturated, 148 °C | A, if the build's wet-exhaust table (W1) shows no wear at 10 kPa; else the owner chooses again |
| T2 | A new turbine's first design point in the constructor | A: HP to 10 kPa. B: HP to 350 kPa | A |
| T3 | The MP class to vacuum exhausts wet on the game's scale (96 K superheat), so it wears | A: leave it, wear is the cost of MP steam. B: raise MP's temperature | A. W1 gives the number first |
| T4 | Moved cells and nozzle pipe blocks | The build lists every layout row that moves. A moved nozzle pipe block needs the owner's approval before the commit | - |

## 6. Work packages

Run from the batch worktree. No whole task, no final lane: that is the merge's.

**W1 Classes and exhausts** (`science/equipment/MachineDuty.java`, `SteamTurbineDesign.java`,
`network/EquipmentNetwork.java`, `network/NetworkProtocol.java`, `client/gui/common/ConstructorColumn.java`,
`en_us.json`, `documentation/reference/FORMATS.md`)

- `Steam.LP` first; `TURBINE_EXHAUSTS`; `step`, `withSteam` on it; `checkDuty` unchanged.
- Lang: `equipment_constructor.steam.lp`, the exhaust hint ("10 to 20 kPa to a condenser, 150 to 350 kPa below the LP
  header"), the power hint (55 kW to 300 MW).
- A test in `SteamTurbineTest` prints, for each class at 10, 20, 150 and 350 kPa, the steam rate at 10 MW and the
  exhaust's condensed share on `SteamTurbine` and the network's flash. This table answers T1 and T3.
- Tests: `SteamTurbineTest`, `EquipmentNetworkCodecTest`, `ConstructorColumnTest`, `MachineColumnTest`.

**W2 Sections rule and the stepped casing** (`SteamTurbineDesign.java`, `SteamTurbineTrain.java`)

- `sections` as in 4.3; `Section` gains `HP` for the front section's name where the drawing needs it.
- `SteamTurbineTrain.draw` and `radius`: the barrels of 4.4 between the same stations; `stages` split by section in
  proportion to each section's drop (`DROP_PER_LOG` × ln of its pressure ratio).
- Tests: `SteamTurbineSectionsTest` rewritten to the new rule (its first case, "one casing to 45 MW", goes),
  `ProcessUnitTest`, `ShapeRasterizerTest`, `EquipmentLayoutGoldenTest`. Expected: the three single-casing rows of
  `layouts.txt` keep their cells and nozzles; report any that move (T4).

**W3 Tandem casings** (`SteamTurbineDesign.build`, from line 250)

- Casings, legs and pedestals of 4.4. Keep `start[k]`, the casing lengths and the exhaust nozzle's cell so the three
  tandem rows of `layouts.txt` do not move; report any that do.
- Drawing scenes in `tools/equipment-drawings/EngineeringExport.java`: `STEAM_TURBINE_LP`, `STEAM_TURBINE_MP`,
  `STEAM_TURBINE` (HP, 13 MW), `STEAM_TURBINE_TANDEM_80`, `STEAM_TURBINE_TANDEM_300`, `STEAM_TURBINE_LP_TANDEM`.
  Render with `update.ps1 -Only STEAM_TURBINE`; send the iso, +X and -Z views to the owner before the commit.

**W4 Simulator and saves** (`science/flowsheet/sizing/TurbineSizing.java`, `Limit.java`, `en_us.json`, the corpus)

- 4.5 and 4.6. Tests: `TurbineSizingTest`, `FlowsheetUnitsTest`, `SizingCoverageTest`, `EquipmentShapeCompatTest`,
  `FluidFactsCompatTest`.

Each package: `./gradlew <task holding the class> --tests "<class>" --max-workers 4`, only the classes named, once
per change. `LanguageFilesTest` and `UnitTextTest` wait for the translation commit.

**W5 Wet-exhaust wear** (added 2026-11-15; `science/machine/Wear.java`, `runtime/fluid/MachineHealth.java`)

W1 measured (10 MW, network flash, the game's scale): at 10 kPa the exhaust's condensed share is LP 5.45 %, MP 5.39 %,
HP 4.61 %, VHP 4.06 %; at 20 kPa 3.4 to 4.7 %; at 150 to 350 kPa 0.8 % or less. Today's rule (onset 1 %, span 5 %)
gives every condensing turbine a wear intensity of 0.61 to 0.89, about 9 process hours of life for the LP class. So T1's
condition fails and T3 is not an MP question.

Owner, 2026-11-15, after asking what real turbines do: "So you need to change the wear rules instead".

- Expected: a condensing last stage is built for moisture (8 to 12 % by the steam tables) and wears over years on
  design steam. Fast damage comes from inlet steam that lost its superheat.
- The rule: the onset rises as the exhaust pressure falls; the span stays 5 %.
  - `Wear.wetOnset(exhaustPressure)`: 1 % (`WET_EXHAUST_ONSET`) from 150 kPa up; at 20 kPa and at 10 kPa the largest
    reading of the four classes' design steam at that exhaust, up to the next 0.1 %, plus 0.1 % (about 4.8 % and
    5.6 %); linear in ln P between the points; flat below 10 kPa. Two new constants, set from W1's table.
  - `MachineHealth.booked` (line 218) passes `Wear.wetExhaust(share, row.secondPressure())`; the fault's driving
    quantity is the share less the rise of the onset over 1 %, so `Fault.intensity` and the status line keep their form.
- What the build reports: each class's intensity and life with design steam and with a saturated inlet, at 10, 20,
  150 and 350 kPa. Expected: design steam 0 everywhere; back-pressure turbines exactly as today (no reference moves
  at 150 kPa and above); saturated HP steam to 10 kPa about 0.1 (6.2 % against 5.6 %).
- Stop and report if a class's saturated reading at 10 or 20 kPa lies under the onset: a pressure-only onset then
  cannot tell that class's lost superheat from its design steam, and the other form (the turbine's own design
  moisture as a registered fact) is the owner's choice.
- Ruled out: a step at the vacuum limit (a new discontinuity); a different span; a facts or wire change; any change
  to `SteamTurbine`'s work law.
- Tests: `WearTest`, `MachineHealthTest`, `MoverHealthStatusTest`. Guide: the wear rows of `rotating-machinery.md` and
  `shaft-trains.md`.

T1 to T3 after this: LP steam 200 °C, a fresh draft at HP to 10 kPa and MP unchanged all stand.

**W6 The LP casing's look** (added 2026-11-15; `SteamTurbineTrain.java`, `SteamTurbineDesign.java`, one scene in
`EngineeringExport.java`, the layout table)

Owner, 2026-11-15, on `STEAM_TURBINE_LP_TANDEM-iso.png`: "really need to improve its visual". Asked where the exhaust
leaves the hood, the owner chose "Top, short neck".

Expected look. A condensing LP turbine is recognised by four things: a casing that flares from a small inlet end to a
large exhaust end, an exhaust hood (a ribbed box under an arched cover) at the exhaust end only, one large low-pressure
inlet with a butterfly valve, and bearing pedestals at both ends. It has no governor valve rack and no
trip-and-throttle valve with a handwheel: those belong to HP steam.

What the build drew instead. The LP class above 45 MW is the hood alone over the whole casing length (`hood > 0` in
`SteamTurbineTrain`), a plain box under a half-round, with the HP steam chest and trip-and-throttle valve hung on it
and the exhaust through a branch, an expansion joint and a reducer that read as a chimney.

Every turbine above 45 MW has d = 3 m (`SINGLE_CASING` is the 3 m casing's power), so this path has one diameter.

Design, in three parts.

1. `SteamTurbineTrain.hood(...)`, shared by the LP class and the tandem LP sections. Its solids (`u.box` with
   `solid = true`, the `Solid.rod` of radius `half`, `u.round`) do not change, so no tandem row moves.
   - The arched cover is set in: `vault` and its two end caps run from `z1 + .06 d` to `z2 - .06 d`. The lower box keeps
     its full length; its top face already exists and shows as the step.
   - Stiffening ribs on both side walls of the lower box: `m.box` 0.06 d proud, `.02 + .01 d` thick along z, from `base`
     to `shaft - th / 2`, without the face on the wall; `max(1, round((z2 - z1) / (.5 d)) - 1)` of them, evenly spaced.
     They are silhouette parts (18 cm proud); do not add more than this count.
   - A gland cone at each end wall: `m.frustum(axis, .35 half, .1 d + .02, ...)` over 0.08 d outward from `z1` and from
     `z2`. Drop the two `m.prism(f, .1 d + .02, ...)` glands of the `hood > 0` branch in `draw` where the cone replaces
     them.
   - A double flow (`doubleFlow`) gets one relief diaphragm: `m.cylinder` on a `Frame.of({a, 0, z1 + .2 (z2 - z1)}, 1, 1)`,
     radius 0.12 d, from `shaft + half - .02` to `shaft + half + .03 + .01 d`, `Material.FLANGE`. The centre ring stays.
2. The LP class above 45 MW (`hood > 0`): `SteamTurbineTrain.draw` and `SteamTurbineDesign.build`.
   - Single flow (up to `DOUBLE_FLOW`), from the HP end wall `zh` to the exhaust end wall `ze`, C = `casing`:
     - inlet belt: `m.annulus` end wall and `m.prism` of radius 0.32 d from `zh` to `zh + Lb`,
       `Lb = min(max(.2 C, oi + .12 d), .35 C)`, `Material.CLADDING`;
     - flare: `m.frustum` from 0.32 d to 0.6 d, from `zh + Lb` to `z0 + coneEnd`, `Material.CLADDING`, with two
       `m.hoop` girth ribs 0.025 d proud at its thirds, `Material.MACHINE`;
     - the joint flange along the belt and the flare with `ProcessUnit.ledge`, as `casing` does per barrel and cone;
     - a foot under the belt: `u.box` `a ± .2 d`, `base` to `shaft - .32 d`, over the belt's middle half, solid;
     - the exhaust hood: `hood(...)` from `z0 + coneEnd` to `ze` only, half width as today (`LP_WIDTH d / 2`);
     - solids: `Solid.rod` radius 0.6 d from `zh` to `z0 + coneEnd` replaces the hood's solid over that length; the
       `u.volumes` rod stays as it is.
   - Double flow: the hood over the whole length as today, with part 1.
   - Inlet, both flows. No `steamChest`, no `tripThrottle`, no valve post or stand plate (`u.post` and the plate in
     `build`). One riser on the shaft line, steam in from above:
     - `oi = min(PipeSizes.MAX_OUTSIDE, .25 d)`, the inlet's outside diameter;
     - a dome `m.cylinder` of radius `oi / 2 + .04 d` on the belt's top (single flow: over the belt's middle; double
       flow: over the hood's middle), 0.1 d high;
     - the riser `m.prism` of radius `oi / 2`, 0.35 d high, with a butterfly valve at its middle: a wafer body
       `m.cylinder` of radius `oi / 2 + .04 + .01 d`, 0.08 d high, `Material.MACHINE`, and an actuator `u.box`
       0.12 d cube on its +x side, `Material.ACTUATOR`, not solid;
     - the inlet flange with `m.bolts`, as the exhaust flange has, and the `Role.STEAM` nozzle up from it
       (`new Nozzle(Role.STEAM, {a, top - .01, zI}, 1, 1, ceil(top + NECK) - top + .01, oi, 0)`), with a `NozzleRoot`
       as the exhaust has;
     - `zI` is on a cell centre, and in this path it fixes `z0` (today the exhaust does):
       `zI = ceil(lead + inletStation - .5 - 1e-9) + .5`, `z0 = zI - inletStation`, where `inletStation` is
       `hpEnd + Lb / 2` (single flow) or `(hpEnd + exhaustEnd) / 2` (double flow).
   - Exhaust, both flows. The branch, the expansion joint and the reducer go. A short neck on the hood's top:
     `m.frustum` from radius `min(.3 d, 1.6 oe / 2)` at `shaft + hood - .05` to `oe / 2` at `shaft + hood + .15 d`, the
     flange and `m.bolts` as today, the `Role.EXHAUST` nozzle up. Its station `zX` is the cell centre nearest the
     hood's 0.7 point (`floor(zHood0 + .7 (ze - zHood0)) + .5`), moved one cell forward if the neck's base would pass
     `ze - .06 d`. On a double flow it stands at least one neck radius plus 0.1 d behind the inlet dome.
   - The rear pedestal as the tandem casings have: move `SteamTurbineDesign.pedestal` to `SteamTurbineTrain` (static,
     package-private) and call it from `draw` in this path in place of the two boxes. Its solid is the same box.
   - The front standard, its governor actuator, the coupling and its guard do not change (`ProcessUnit.coupling` is
     shared by every machine).
3. The tandem turbines' exhaust (`SteamTurbineDesign.build`, the last lines): the stub `m.prism(rise, rc, yx - .1,
   yx + .15, ...)` becomes `m.frustum(rise, 1.6 rc, rc, yx - .05, yx + .15, ...)`. The flange, the solid and the nozzle
   stay, so the row and the nozzle do not move.

Ruled out.
- A side or downward exhaust (owner: top, short neck).
- A flared casing for the tandem turbines' single-flow LP section: it would take cells from the MP 80 MW row. If the
  owner wants it after seeing the views, it is a further step.
- Edits to `Mesh.java` and `ProcessUnit.java`: the relief-sprite branch changes both and is not merged.
- New nut polygons beyond the two flanges' `m.bolts`.
- Any change to the function, the datasheet, lang lines or the simulator.

Pitfalls.
- `Lb` and the flare must both be positive for every LP duty above 45 MW. Print C, the stage count, `Lb` and the
  flare's length at 46, 63 and 100 MW to 10 kPa and to 350 kPa, and assert the flare is at least 0.15 C in
  `SteamTurbineSectionsTest`. If a duty fails, cut the dome's radius to `Lb / 2 - .01` before changing the rule.
- The exhaust neck and the inlet riser each need their own cell column; assert the two nozzles' cells differ and that
  neither riser cuts the other's solid.
- `a` (the shaft's x) comes from `leftReach()` and `lpHalf`, not from the valve, in this path. Check that removing the
  valve does not leave `xv` terms that widen the box.
- The corpus image `shapes-12-turbine-sections.snbt` holds an LP 63 MW turbine. If it stores cells or a stamp, record
  it again: the branch is not merged, so no saved world holds the old layout. Say which it was.
- The LP class is new on this branch: its rows are free. Every row that exists on `main` must stay as the table has it
  now. A row of `main` that moves is a defect of the build.

Tests and proof.
- Add two rows to the layout table's list: LP 63 MW to 10 kPa and LP 150 MW to 10 kPa.
- `./gradlew equipmentGates --tests "*SteamTurbineSectionsTest" --tests "*ProcessUnitTest" --tests
  "*ShapeRasterizerTest" --tests "*EquipmentLayoutGoldenTest" --tests "*EquipmentShapeCompatTest" --max-workers 4`:
  the shape builds, rasters and collides; only the two new rows differ in the table.
- A seventh scene `STEAM_TURBINE_LP_DOUBLE` (LP, 150 MW, 10 kPa). `update.ps1 -Only STEAM_TURBINE`, then look at the iso,
  +X and -Z views of `STEAM_TURBINE_LP_TANDEM`, `STEAM_TURBINE_LP_DOUBLE`, `STEAM_TURBINE_TANDEM_80` and
  `STEAM_TURBINE_TANDEM_300`.
- Polygons by material, before and after, for the seven scenes. `STEAM_TURBINE_LP_TANDEM` must not exceed today's 1,508.

Done: the tests pass, the four scenes' views are in `tmp/turbine-sections/views/`, the README has the history line with
the polygon counts and the rows, and the code guide's turbine paragraph in `equipment-designs.md` describes the LP
class's casing.

## 7. Pitfalls

- `EquipmentNetwork` reads the class by ordinal with 0 for none. `LP` first shifts every ordinal by one: an old client
  against a new server must be refused by the version, not mis-read.
- `ConstructorDefaults` and `EquipmentConstructorScreen` line 796 call `design.duty(size, length)` for a fresh draft.
  `SteamTurbineDesign.duty` is also the design point of a turbine saved before design points existed (HP to 450 kPa).
  Keep that method for old saves and give the fresh draft its own design point (T2).
- `UnitDesign` line 329 scales a turbine's sound by `SINGLE_CASING`. Leave it.
- `ShapeRepair` line 376 gives an old condensing set HP to 10 kPa: with the new rule it draws HP+MP+LP. That is the
  intended look; its row in the layout table shows whether its cells move.
- A casing profile that grows outside today's solids adds cells. Keep `u.solids` inside today's envelope up to 45 MW.
- Do not add reheat lines or extraction nozzles: the owner chose straight through.

## 8. Done

- The four work packages pass their named tests.
- The wet-exhaust table and the list of moved layout rows are in the batch README, with the owner's answers to T1 to T4.
- The six drawings are approved by the owner.
- `documentation/reference/code-guide/equipment-designs.md` and `rotating-machinery.md` say what a class's sections
  are. `CHANGELOG.md` waits for the merge.
