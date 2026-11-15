# Fluid interfaces and pipe accessories

Batch started 2026-11-15 on `claude/fluid-interfaces-a51f3a`, cut from `claude/equipment-visual-design-d4e22c` at `9c777e86e` (plan D12). Status: In progress since 2026-11-15.

Class: PHYSICS (new devices; no existing reference may move). Lanes touched: thermo (data only), fluid laws (`FlowControl`), runtime (`FluidWorldAuthority`, `FluidFacts`, `Converters`), flowsheet and control (`runtime/instrument`, `runtime/control`), equipment (`world/**`, `registry`, new geometry files), client.

Purpose: an orifice plate, an automated shutoff valve, a liquid hopper, a gas intake with the rare gases, a placeholder crude oil Forge fluid and JEI pages for the fluid converter.

## The owner's request (2026-11-15)

1. Add an orifice plate.
2. Add an automated shutoff valve as an accessory.
3. Add a liquid hopper: a simple conversion interface; the player puts a Forge fluid in (right click with a filled bucket, for example) and it converts to IChemE fluid at a small rate ("100mb/s?").
4. Add a gas intake that converts air into IChemE fluid (N2 + O2 + water + rare gases); it must work well with a compressor. Add the rare gases to the compound database.
5. Add a placeholder crude oil Forge fluid "with the same id as most mods" when no other mod adds one.
6. Add JEI support for the fluid converter.

The owner accepted the plan's decisions D1 to D12 on 2026-11-15: "Liquid hopper 4000mb, others go" (D5's Forge tank is 4 000 mB).

## Decisions (plan section 3)

- D1: the orifice plate is a restriction orifice (a fixed pressure loss set by its bore ratio), not a flow meter.
- D2: bore ratio 0.20 to 0.80 in steps of 0.05, default 0.50, set on the item.
- D3: the automated shutoff valve is on/off, by hand or by a local controller or DCS loop; no FE, fail position or redstone.
- D4: an on/off loop uses the controller's existing settings: `OUTLET` opens above the target and closes below it, `INLET` the reverse, a band of 2 % of the target each side; no format change.
- D5: the liquid hopper is one block, a 4 000 mB Forge tank over a boundary at ambient pressure plus 0.5 m of head; no vessel, no settings.
- D6: hopper rate 0.1 L per process second; liquid forward rules only.
- D7: intake air: dry air N2, O2, Ar, Ne, He, Kr, Xe, wetted to 50 % relative humidity at 25 °C, at 25 °C and 101 325 Pa; no CO2.
- D8: the intake is one boundary block: flow enters only; loss coefficient K = 3.
- D9: the crude oil Forge fluid `icheme:crude_oil`, always registered, in `c:crude_oil`, hidden when another mod's fluid is in the tag; no world generation.
- D10: JEI: one category "Fluid conversion", one recipe per rule; the rules reach the client in a new packet.
- D11: no new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` or `TopologyCompiler.Kind` constant.
- D12: branch base `claude/equipment-visual-design-d4e22c` at `9c777e86e`.

## History

- 2026-11-15: batch opened; the plan copied in ([FLUID_INTERFACES_PLAN.md](FLUID_INTERFACES_PLAN.md)). Documents only, no test run.
- 2026-11-15: W1, the rare gases. Argon, neon, helium, krypton and xenon from ChemSep v8.3 through `tools/compound-import` (`selection-rare-gases.json`; new options `no_freezing` and `no_group_counts`), appended to `icheme:refinery` after ethanol (`refinery-tier1-r5`, 52-entry world list, water last); their pairs in `icheme:dwsim_refinery_pr78_rare_gases` (five DECHEMA pairs: Methane/Argon 0.0078, Nitrogen/Argon -0.0052, Oxygen/Argon 0.0089, Oxygen/Krypton 0.0185, Ammonia/Argon -0.18; every other pair from `DerivedPairs`), named by `icheme:crude_pr78` (`crude-pr78-r4`, in no identity). `RareGasPropertiesTest`: PR78 Psat at the normal boiling points Ar +1.34 %, Kr -1.52 %, Xe +0.47 % of 1 atm; gas density at 25 °C and 1 atm within 0.06 % of the real gas (second virials) for all five. Tests (filtered, 4 workers): the material classes (142); the classes that load the data (82 classes, 513 tests: 7 failed, fixed below, re-run with the material classes and the example readers, 192, and StillHeadTest, 6); holdGates (five classes, 70); columnGates CrystalPackageConsumersTest (4). All pass.
  - Decisions: helium and neon are treated as hydrogen (zero pairs through the import's pair exceptions; helium without group counts), so `DerivedPairs` and its revision stay as they are; all five keep their IAPWS G7-04 Henry constants; argon keeps its E-PPR78 group; the rare gases carry no thermochemistry (burn as inerts); helium has no freezing sub-record (no triple point at any pressure the network reaches); ChemSep's triple points stand (Ar 83.8 K, Xe 161.25 K; the plan said 83.81 and 161.41). The plan's "within 0.2 % of ideal" density check is a real-gas check: xenon is Z = 0.9947 at 25 °C and 1 atm.
  - No package evidence line: a 16th `advisory_evidence` line made the V3 column's acceptance audit throw (the out-of-range notice in `V3AcceptanceAuditor` is added without the 16-entry cap the other notices keep; `ColumnWaterTest`, `FlowsheetAqueousColumnTest` and `SheetCompoundsTest` failed with ACCEPTANCE_AUDIT_FAILURE). The rare gases' sources are in the import README and each record, as ethanol's are. The latent defect is an open item.
  - Pins that follow from the list: component and record counts in the material tests; `DerivedPairs` fits 291 to 326; `StillHeadTest`'s positional pot gains five zeros; `ColumnSolveTest`'s wide package uses three clone sets (four pass the 100-entry axis); `SheetCompoundsTest` parks radon in place of helium; `FluidCheckpointCodecTest`'s per-device budget 2 020 to 2 045 bytes (2 017 at 47 entries, 2 040 at 52: each id's text-table length and index, and a seventh mask byte on each of eight sparse compositions); the three simulator examples list the five ids (their data identity did not move); the engine digest re-recorded: 24 of 429 lines change, each only by water's index label (46 to 51), every value and counter bit-identical (`tmp/fluid-interfaces/digest-lines.js`).
- 2026-11-15: W2, the gas intake (`39790a116`). `ModBlocks.GAS_INTAKE`, a generator block (`FluidDeviceBlock.intake`) that registers the atmosphere (`Placement.intake`): switched on, 1 m at the joined pipe's bore, entry loss K = 3, its spec `ambient` (new fact, fluid facts schema 26, absent false; corpus image `compat/fluid/schema-26/`, which W4 and W7 write again while the batch is unmerged) at 298.15 K and 101 325 Pa holding `Air.wet` (D7: y_water = 0.5 p_sat(25 °C)/p = 0.01565; nitrogen the balance). The registry stands it again as the intake block (`FluidDeviceBlock.stateFor` takes the registration); the edit handler refuses every setting on it; the screen tells it by its block and shows no switch, field or composition page. Model `IntakeGeometry`: a louvred filter house (the louvres are `MOTOR_LOUVRE` relief, one facet a side) under a rain hood, flanged stubs to the joined faces; 11 facets and the stubs.
  - Plan correction: a boundary's own geometry never reached its link (the link is half the joined pipe's), so the planned "minorLoss 3" on the intake did nothing. `PhysicalFluidTopology.boundaryHalf` adds a generator's own minor loss to its link; every existing generator has 0, so every other link is bit-identical.
  - `GasIntakeTest` (4): the air's fractions; intake, two pipes, a set-flow compressor (0.05 m³/s, ratio 3), a pipe and a 2 m³ drum: the drum rests at 303 975 Pa (3.000 × the suction) and 422 K after 37 intervals, holding the air to 1e-15 in each fraction, the intake's exchanges equal to the island's gain within 1e-9; the same behind a centrifugal machine (rated 0.02 m³/s, 24.4 kJ/kg): rest at 137.7 kPa after 30 intervals; a drum at 3 bar sends nothing out through an intake. Counters of the compressor run, air against dry nitrogen: 518 against 503 Newton iterations, 48 against 46 LU factorisations. `GasIntakeGameTests` (one: the block registers the atmosphere, stands again as itself, leaves with its block): compiled, not run (final lane).
  - Pins: `FluidCheckpointCodecTest`'s per-device budget 2 045 to 2 051 bytes (2 046 at schema 26: the ambient flag on five specs and one field count). Tests: GasIntakeTest, FluidFactsCompatTest (22), FluidCheckpointCodecTest, FluidCheckpointFormatTest, FluidDeviceSpecDomainTest, PhysicalFluidTopologyTest, EquipmentInfoCoverageTest, GuideCoverageTest, TutorialTextTest: all pass.
- 2026-11-15: W3, the placeholder crude oil. `registry/ModFluids`: the fluid type `crude_oil` (870 kg/m³, viscosity 5 000, 300 K), its source and flowing fluids, liquid block and bucket (`neoforge:fluid_container` model on the vanilla bucket), tinted water sprites (`0xFF1B1611`, no texture of its own), the tags `c:crude_oil` (both fluids) and `c:buckets/crude_oil`. Hidden from the creative tab and from JEI (`IChemEJeiPlugin.onRuntimeAvailable`) when another mod's fluid is in `c:crude_oil`. `CrudeOilGameTests` (registered, tagged, the bucket holds it, the bundled `#c:crude_oil` rule answers it): compiled, not run (final lane). Tests: EquipmentInfoCoverageTest, TutorialTextTest, GuideCoverageTest pass.

## Documents

- [FLUID_INTERFACES_PLAN.md](FLUID_INTERFACES_PLAN.md): decisions D1 to D12, the physics with numbers, work packages W1 to W8, what is ruled out.

## Code areas

(filled in per package)

## Open items

- W4 to W8 to build.
- Not checked in game: the crude oil's colour, its bucket model and its hiding beside another mod's crude.
- Not checked in game: the intake's look and its screen (no switch, no fields).
- `V3AcceptanceAuditor` adds its out-of-range notice without the 16-entry cap: a package with 16 evidence lines fails every column solve that reaches the notice. Proposal: cap it like the other notices, or keep the package's evidence out of the notice budget (owner choice).
- Not run for W1: the fluid GameTests and chain-100 (final lane).
