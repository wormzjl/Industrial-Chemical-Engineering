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
