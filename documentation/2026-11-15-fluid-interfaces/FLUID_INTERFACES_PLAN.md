# Fluid interfaces and pipe accessories: plan

Batch `2026-11-15-fluid-interfaces` (to be created by the build thread as `documentation/2026-11-15-fluid-interfaces/`, this file as `FLUID_INTERFACES_PLAN.md`, with a `README.md` and a row in `documentation/INDEX.md`). Class: PHYSICS (new devices; no existing reference may move). Lanes touched: thermo (data only), fluid laws (`FlowControl`), runtime (`FluidWorldAuthority`, `FluidFacts`, `Converters`), flowsheet and control (`runtime/instrument`, `runtime/control`), equipment (`world/**`, `registry`, new geometry files), client.

## 1. The owner's request (2026-11-15)

1. Add an orifice plate.
2. Add an automated shutoff valve as an accessory.
3. Add a liquid hopper: a simple conversion interface; the player puts a Forge fluid in (right click with a filled bucket, for example) and it converts to IChemE fluid at a small rate ("100mb/s?").
4. Add a gas intake that converts air into IChemE fluid (N2 + O2 + water + rare gases); it must work well with a compressor. Add the rare gases to the compound database.
5. Add a placeholder crude oil Forge fluid "with the same id as most mods" when no other mod adds one.
6. Add JEI support for the fluid converter.

## 2. What exists (read at `9c777e86e`)

- Fluid converter (batch `2026-11-12-fluid-conversion`, README there): `science/fluid/conversion/` (`ConversionRule`, `ConversionRules`, `ConversionLaws`, `ConversionRuntime`), `material/ConversionReloadListener`, `runtime/fluid/Converters` (an `IslandCoordinator.Ports` host: plans at dispatch, books at commit), `ConverterUnit` (facts), `FluidWorldAuthority.placeConverter` / `converterPort`, `world/level/block/entity/ConverterPort` (the Forge capability), 26 bundled rules in `data/icheme/icheme/fluid_conversion/` (forward rules: water, and crude through `#c:crude_oil` to the preset `medium_crude`).
- `Converters.forward` books a forward conversion through a generator device that carries a `FlowControl.ControlValve` of source `CONVERTER`; the plan is a `PortPlan(fill, fillValve, kg/s, T, moles)`. `ConverterUnit` already allows `drain == 0` (no reverse port).
- Pipe fittings: `world/item/PipelineFittingItem` (one class, `Instrument.Type`), block state properties on `FluidDeviceBlock` (`SHUTOFF`, `PRESSURE_CONTROL`, `INLINE_FILTER`, `INLINE_MOVER`, `SIGHT_GLASS`), registry edits in `PhysicalRegistry.installPipe*`, the accessory store `InstrumentService.Store` / `AccessoryFacts` (schema 6). `FluidDeviceBlock.stateFor(Device)` restores a block and its fittings from the registry (registry first); the sight glass comes back from its fitting record (`RegistryRealignment.sightGlass`).
- `PipeResistance.Geometry.minorLoss` exists, is saved in the fluid facts and enters the pipe law as `minorLoss * coefficient * q²`; the pipe sizing tool keeps it on a resize.
- Valve law (`FlowControl.ControlValve`): `bare()` is an open line; a mass target of 0 is `CLOSED` (exact zero flow, no topology change). A flare tip is the precedent for "bare while its mass target is above 0".
- Controllers (`runtime/control`): `ControlSettings(name, enabled, sensor, output, unit, target, action INLET|OUTLET)`, strict keys; `ControlLaw.decide` makes one decision per loop at the 100-tick cadence; `ControlPlans` keeps the valve plans a slice takes.
- Block devices: `ModBlocks.fluid(name, Kind)`; a generator block is a fixed T, p, composition boundary (`FluidDeviceSpec`), placed switched off, forward flow only.
- Ambient on the server: 25 °C, 50 % relative humidity, 101 325 Pa (`CoolingTower.AIR_TEMPERATURE`, `RELATIVE_HUMIDITY`, `PRESSURE`; `TowerTransfer.ambientWaterPressure`).
- Materials: 47 components; the network package `icheme:refinery` (revision `refinery-tier1-r4`) holds nitrogen, oxygen, water, hydrogen (a quantum gas on PR78 already). `tools/compound-import/` turns ChemSep records from the local DWSIM install into records.
- JEI: `compat/jei/IChemEJeiPlugin` (information pages only). The mod registers no Forge fluid. Rules live only on the server (`ConversionRuntime`).
- Formats now: fluid facts schema 25, accessory facts 6, control store 4, wire `icheme-2` (`documentation/reference/FORMATS.md`).

## 3. Decisions taken in the plan (each open to the owner's veto)

| # | Decision | Why |
|---|---|---|
| D1 | The orifice plate is a **restriction orifice**: a fixed pressure loss on a pipe, set by its bore ratio β. It is not a flow meter. | A flow sensor exists already; a fixed restriction is what the network lacks. |
| D2 | β from 0.20 to 0.80 in steps of 0.05, default 0.50, set on the item (as a gauge's settings). | ISO 5167's range; one number. |
| D3 | The automated shutoff valve is an on/off valve: open or closed by hand (empty hand), or by a local controller / DCS loop whose output it is. No FE, no fail position, no redstone. | The control system exists; a second signal path is a second system. |
| D4 | An on/off loop uses the controller's existing settings: `OUTLET` opens above the target and closes below it; `INLET` opens below and closes above; a band of 2 % of the target on each side. No new settings field, no control format change. | The meaning of `INLET` / `OUTLET` is the same as a control valve's. |
| D5 | The liquid hopper is one block: a 4 000 mB Forge tank (owner, 2026-11-15: "Liquid hopper 4000mb") over a boundary that delivers the converted liquid at ambient pressure plus 0.5 m of its own head. It has no IChemE vessel of its own and no settings. It feeds an open tank by gravity, or a pump's suction. | "Simple conversion interface". A pressurised outlet would be a free pump. |
| D6 | Hopper rate: 0.1 L per process second (the converter's unit; 0.4 L per real second at the default time factor 4). Liquid forward rules only. | The owner's "100mb/s?", in the unit the converter already uses. |
| D7 | Intake air: dry air N2 0.781175, O2 0.20946, Ar 0.00934, Ne 18.18 ppm, He 5.24 ppm, Kr 1.14 ppm, Xe 0.087 ppm (N2 takes the balance), wetted to 50 % relative humidity at 25 °C (about 1.56 mol % water), at 25 °C and 101 325 Pa. **No CO2.** | The server's ambient. CO2 would freeze in every cold box and the V3 column refuses CO2 views. |
| D8 | The intake is one block, a boundary: flow enters only, driven by the pressure below it; loss coefficient K = 3 on its pipe's velocity head (filter, louvres, entry: 0.4 kPa at 15 m/s). | A real filter house; no settings. |
| D9 | The crude oil is `icheme:crude_oil` (source), `icheme:flowing_crude_oil`, block `crude_oil`, item `crude_oil_bucket`, in the tag `c:crude_oil`. It is **always registered**; it is hidden from the creative tab and JEI when another mod's fluid is in `c:crude_oil`. No world generation. | A fluid id is namespaced, so the shared name is the path and the tag. Registering by mod list would drop the fluid from a world when a mod is added. |
| D10 | JEI: one category "Fluid conversion", one recipe per rule; the rules reach the client in a new packet at login and after a reload. | JEI runs on the client; the rules are server data. |
| D11 | No new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` or `TopologyCompiler.Kind` constant. | The intake is a generator, the hopper a generator with a converter valve; the simulator's catalog does not change. |
| D12 | Branch base: the tip of `claude/equipment-visual-design-d4e22c` (`9c777e86e`), not `main`. | The new models use that batch's `Mesh.flangeBolts` and relief nuts; `Mesh` and `FluidDeviceModel` are in both. |

## 4. Physics

### 4.1 Orifice plate

Expected: a plate with a hole of β = d/D in a pipe of bore D. The permanent pressure loss, on the pipe's own velocity head, is

K(β) = (1 − β⁴)(1 − β^1.9) / (C² β⁴), C = 0.61 (ISO 5167-2: the differential pressure times the permanent loss fraction 1 − β^1.9).

| β | 0.20 | 0.30 | 0.50 | 0.70 | 0.80 |
|---|---|---|---|---|---|
| K | 1 598 | 296 | 29.5 | 4.19 | 1.34 |

Usual states: water at 2 m/s in a 50 mm pipe (head 2.0 kPa): β 0.5 loses 59 kPa, β 0.7 8.4 kPa, β 0.3 590 kPa. Air at 5 bar and 20 m/s (head 1.16 kPa): β 0.5 loses 34 kPa. Simplifications: local density, no expansion factor, the same K both ways; the 100 m/s cap stays the gas limit (owner rule, no choke law). K does not depend on D, so a resize keeps it.

The plate is the host pipe's `minorLoss` plus K(β). No solver change.

### 4.2 Automated shutoff valve

Open: the line's own law, no added head. Closed: flow exactly zero (`Mode.CLOSED`). It never regulates. A switch is a slice input, like a control valve's plan: no topology edit, no recompilation.

### 4.3 Gas intake

A boundary at 101 325 Pa and 298.15 K holding wet air. With a compressor behind it the suction node stands at ambient less the intake's and the pipe's loss; at the 100 m/s cap the intake alone loses 3 × ½ × 1.17 × 100² = 17.6 kPa (suction 0.83 bar). The air's dew point is 13.9 °C at 1 atm, so no liquid reaches a compressor's suction at ambient; water condenses after compression and cooling, as it does in a real aftercooler. Nothing flows back out of an intake.

### 4.4 Liquid hopper

A litre of Forge fluid becomes the rule's stream (`ConversionLaws`, as the converter's forward direction): V = m / ρ at the flash's liquid density, at the rule's temperature. The outlet boundary stands at 101 325 Pa + ρ g × 0.5 m (water: 106.2 kPa). The valve in front of it holds the mass rate 0.1 L/s × ρ; when the line takes less, the litres not taken stay in the account and return to the tank (the converter's own rule). Mass, components and energy are booked by the same `Converters` code.

### 4.5 Rare gases

Argon, neon, helium, krypton, xenon on PR78 from their ChemSep records. Checks to state in the review: argon's saturation pressure at 87.30 K against 101.3 kPa; krypton's at 119.7 K; xenon's at 165.0 K; each gas's density at 298.15 K and 101 325 Pa against the ideal gas within 0.2 %. Helium and neon are quantum gases with a negative acentric factor (He −0.390, Ne −0.040): treat them exactly as the package treats hydrogen (read how `Hydrogen` gets its alpha and its pairs first); at ppm level their error is invisible in air, and a pure-helium island only needs to be finite and gas-like at 100 to 600 K.

## 5. Work packages

One thread, in this order, one commit per package. Each package ends with its filtered tests green.

### W1 Rare gases (data, thermo lane)

- `tools/compound-import/selection-rare-gases.json` (new): Argon, Neon, Helium (ChemSep "Helium-4" if that is its name), Krypton, Xenon. Run the README's extract and import into `tmp/compound-import/rare-gases/`; copy the records to `src/main/resources/data/icheme/materials/components/`, `properties/`, the freezing sub-records (triple points: Ar 83.81 K, Ne 24.56 K, Kr 115.78 K, Xe 161.41 K; helium has none), transport tables, and a pair library file for pairs with a new compound, as `dwsim_refinery_pr78` did (DWSIM `kij_pr.dat`, else zero; N2–Ar, O2–Ar matter).
- `materials/packages/refinery.json`: append the five ids after `"Ethanol"`; revision `refinery-tier1-r5`; one `advisory_evidence` line `RARE_GASES`.
- `en_us.json`: `material.icheme.argon` … `xenon`.
- Pitfalls: append only (saved compositions are indexed by the package's order; the refinery-compounds batch's handling of a longer list applies); a method file may list compounds by name (grep `Hydrogen` under `materials/methods/` and `interactions/`); tests that pin `refinery-tier1-r4` or a component count; `FormatsTableTest`; the neural JSON is byte-pinned (`-text`), do not touch it; CRLF: no `sed -i` on tracked CRLF files.
- Tests: `./gradlew test --tests "*MaterialCatalog*" --tests "*ConversionRuleTest" --max-workers 4` plus every class a grep for `refinery-tier1-r4` finds; a new `RareGasPropertiesTest` with the four checks of 4.5.
- Done: the catalog loads, the checks pass, no other test's expected value moved (name any that did and stop).

### W2 Gas intake

- Fact: `FluidDeviceSpec` gains `ambient` (boolean, default false: "this boundary is the atmosphere"); `FluidFacts` schema 26 writes and reads it (absent: false); corpus image `src/test/resources/compat/fluid/schema-26/` (it also carries W4's hopper and W7's valve, so add it once, at W7, and extend `FluidFactsCompatTest`). If `FluidDeviceBlock.stateFor` cannot see the spec at every call site, give it the registration; do not derive the block from a hydraulic field.
- `ModBlocks.GAS_INTAKE = fluid("gas_intake", Kind.GENERATOR)` with a block subclass or flag; `ModItems`, `ModCreativeTabs`, block state and loot table, a recipe in `data/icheme/recipe/`.
- `Placement.place` / `FluidWorldAuthority.place`: an intake registers enabled, `ambient` true, spec 298.15 K, `PhaseLayout.AMBIENT_PRESSURE`, composition `Air.wet(catalog, model)` (new, `science/fluid/network/Air.java`: D7's dry fractions by compound id, water from `0.5 × model.saturationPressure(298.15) / 101325`, the rest scaled by 1 − y_water); geometry: 1 m, the joined pipe's bore (else 0.05 m), `minorLoss` 3.
- The device screen: the intake shows its composition, T, p and flow, with no editable field (the generator page with its inputs locked). Read `.claude/skills/gui-creation/SKILL.md` first.
- Model: `science/equipment/IntakeGeometry` (a louvred filter box with a rain hood on a flanged stub; pipe flange by `Mesh.flangeBolts`), drawn by `client/model/FluidDeviceModel`. Read `.claude/skills/models-and-sfx/SKILL.md` first: detail in the normal texture, few polygons, no weld seams.
- Tests: new `GasIntakeTest` (unit, `PassiveNetwork` fixtures): (a) intake → 2 pipes → inline compressor → pipe → closed 10 m³ drum of nitrogen at 1 atm: no hold over 200 intervals, the drum's pressure rises and stops at the compressor's ratio limit, its composition tends to the air's, the ledger closes exactly; (b) the same with a constructed centrifugal compressor's `FlowControl.Machine`; (c) a drum at 3 bar against an intake with no compressor: no flow out. One GameTest `GasIntakeGameTests` (place, connect, run, remove everything it registered; a fresh `fluidGameTestRunId`).
- Pitfalls: a fresh generator is placed off (`generatorEnabled` false): the intake is on; nine components per air island: report the Newton and factorisation counters of test (a) beside the same test on dry nitrogen; a trace at 9e-8 must not trip a remnant or underflow guard (memory: seed 514); if it does, report, do not patch per site.

### W3 Placeholder crude oil

- New `registry/ModFluids.java`: `FluidType` `crude_oil` (density 870, viscosity 5 000, temperature 300), `BaseFlowingFluid.Source` / `.Flowing`, `LiquidBlock` `crude_oil`, `BucketItem` `crude_oil_bucket`; registered from `IChemE`.
- Client: `RegisterClientExtensionsEvent`, `IClientFluidTypeExtensions` on the vanilla water still and flow textures with tint `0xFF1B1611`; no new texture file.
- Tags: `data/c/tags/fluid/crude_oil.json` (both fluids), `data/c/tags/item/buckets/crude_oil.json`.
- Hidden when another mod's fluid is in `c:crude_oil`: the creative tab's generator and `IChemEJeiPlugin.onRuntimeAvailable` (`removeIngredientsAtRuntime` for the bucket and the fluid).
- Tests: a GameTest that the registry holds the fluid, the tag holds it, and `ConversionRules.forward("icheme:crude_oil", tags, false)` answers the bundled crude rule.

### W4 Liquid hopper

- Block `liquid_hopper` (`ModBlocks.fluid`, Kind.GENERATOR, its own block class or flag), block entity with a Forge capability (`IChemE.registerCapabilities`, as `ConverterPort`: fill only, any face but the outlet's; accepts a fluid only when a liquid forward rule names it, `Converters.named`).
- `FluidDeviceBlock.use`: `FluidUtil.interactWithFluidHandler` first, so a filled bucket empties into it.
- Registration (`FluidWorldAuthority.placeHopper`, one ledger event): one device at lane 0, Kind.GENERATOR, enabled, control `FlowControl.ControlValve.converter()`, spec 298.15 K, pressure 101 325 Pa + 4 905 Pa (0.5 m of water; a fixed number, stated), nitrogen until the first plan; then `converters.register(new ConverterUnit(id, 0, 0, id, id, false, Mode.TO_ICHEME, 0.1, DEFAULT_DEADBAND, none, 4000, "", 0, 0, "", 0))`.
- `Converters`: a unit whose `id == fill` has no vessel. `plan` must not look for its reservoir; `forward` takes `want = maxRate × dt` with no free-volume test and never answers `DESTINATION_FULL`; `givenIn` and the account's return are unchanged; `fill`, the level thresholds and the alarms skip it. `stateFor`: a lane-0 generator whose control is a `CONVERTER` valve is the hopper block (no new fact).
- Removal: breaking the block removes the device and the unit and drops nothing for the tank's contents (state the choice in the README); `converterDevices` and the DCS's converter list (`ControlPublication.converters`) must not list a hopper as a converter with a page.
- Screen: the device page shows the Forge tank's bar, the rule, the status word and the rate (`ConverterDoor`'s Tanks, Rule and Converting groups; no Mode, Rate or Alarm inputs).
- Model: `science/equipment/HopperGeometry` (a square funnel on a short body, a flanged outlet low on one side).
- Tests: `ConvertersTest` additions: a hopper with 3 000 mB of water feeds an open tank through one pipe: litres in equal mB out (1 mB = 1 L), the ledger closes, the rate is 0.1 L/s; a blocked line (closed valve) converts nothing and loses nothing; a fluid with no rule is refused by the capability. `FluidFactsCompatTest`: a hopper unit round-trips. One GameTest with a bucket.
- Pitfalls: `LEAST_LITRES` is 1 L and a slice at 0.1 L/s may plan less than 1 L: the test of `SOURCE_EMPTY` reads `stored == 0`, keep it so; the hopper's outlet meets only a pipe (two devices that are not pipes never join); the reverse path must stay unreachable (`drain == 0`).

### W5 Rules on the client and JEI

- New payload `FluidNetwork.ConversionRulesSync` (S→C): each rule's id and its JSON text; sent on `OnDatapackSyncEvent` (login and reload); the client parses with the same `ConversionRule` reader into a client holder (`ConversionRuntime.client()`); `NetworkProtocol.VERSION` `icheme-3`; `FORMATS.md` row.
- `compat/jei/`: `FluidConversionCategory` (`IRecipeCategory<ConversionRule>`): left, the Forge fluid (`NeoForgeTypes.FLUID_STACK`, 1 000 mB; a tag shows its members); an arrow each way the rule has; right, text: forward, the stream's largest six compounds with their mole per cent, T and p; reverse, each range (basis, compound, low to high), the T and p windows. `registerCategories`, `registerRecipes`, `registerRecipeCatalysts` (the fluid and gas converters' reference items, the hopper). If the rules arrive after JEI started, add them through the kept `IJeiRuntime`'s recipe manager.
- Text building in a class without Minecraft types (`client/gui/common/ConversionLines`), so a unit test reads it: `ConversionLinesTest` on two fixture rules.
- Pitfall: the mod must load without JEI (nothing outside `compat/jei` names a JEI class).

### W6 Orifice plate

- `Instrument.Type.ORIFICE` (appended); item `orifice_plate` (`PipelineFittingItem`), data component `orifice_settings` (β; codec, default 0.5; a small screen like the gauge's, or the pipe tool's menu pattern); the fitting record gains `ratio` (default 0: none).
- `PhysicalRegistry.installPipeOrifice(id, axis, beta)`: the pipe's geometry with `minorLoss + Orifice.loss(beta)`; `removePipeAccessory` subtracts it. New `science/fluid/network/Orifice.java` holds K(β) and the table of 4.1.
- Block state `ORIFICE` (boolean) on the pipe, restored from the fitting record as the sight glass is; the axis from `FACING`.
- Model: `science/equipment/OrificeGeometry`: a thin plate between two flanges with its paddle handle standing up; `flangeBolts`.
- The device page of the pipe names the plate, β and K.
- Tests: `OrificeTest`: the five K values of 4.1 to 0.5 %; a pipe with the plate loses K ρ v² / 2 more than without, at water 2 m/s and air 5 bar 20 m/s, within the solver's tolerance; install, resize (K kept), remove (the pipe's `minorLoss` back to its value) on `PhysicalRegistry`.
- Pitfall: a transition pipe and a pipe with another fitting take none (`straight_only`, `one_accessory` keys).

### W7 Automated shutoff valve

- `FlowControl.ValveSource.ON_OFF` (appended, saved by name): `mass()` true, `planned()` false, `sink()` false. `ControlValve.onOff()`: source `ON_OFF`, mass target 1 (open), its own sensor identity. `bare()` also holds for `ON_OFF` with a mass target above 0; a mass target of 0 is closed by the existing rule.
- `Instrument.Type.ACTUATED_VALVE`; item `automated_shutoff_valve`; `PhysicalRegistry.installPipeActuatedValve(id, axis)` as `installPipePressureValve` with `ControlValve.onOff()`; block state `ACTUATED` on the pipe, derived in `FluidDeviceBlock.fittings` from the control's source.
- By hand: an empty hand on the valve toggles the mass target 0 / 1 through a queued edit (`FluidWorldAuthority.toggleActuatedValve`), refused with an action-bar line while an enabled loop holds the valve as its output.
- By a loop: every place the control valve is an eligible output or data host (`ControlHosts`, `DataPorts`, `BuiltInInputs`, the paged choices) takes the actuated valve too (grep `PRESSURE_VALVE`, `hasPressureValve`, `pressureControl` in `runtime/control`, `runtime/instrument`, `world`, `client`). `ControlLaw.decide`: an output whose control is `ON_OFF` gets the rule of D4, its last state kept in the law's memory (state, never saved; after a load it starts from the valve's saved position); the plan sets the mass target 0 or 1 through `ControlPlans`, as a flow loop's plan does. The controller's page shows `OPEN` or `CLOSED` for such a loop.
- Model: `ValveGeometry`'s shutoff body with a pneumatic actuator (yoke and cylinder) in place of the lever; no moving part.
- Facts: accessory facts schema 7 (the two types, `ratio`), corpus `src/test/resources/compat/accessories/schema-7.snbt`; fluid facts schema 26 (`ambient`, the source name `ON_OFF`), corpus `compat/fluid/schema-26/` holding an intake, a hopper unit, an orifice pipe and an actuated valve; `FORMATS.md` rows; if `main` took these numbers meanwhile, they are renumbered at the merge.
- Tests: `ActuatedValveTest`: open equals the bare pipe's flow bit for bit; closed is zero flow and the island rests; a level loop with `OUTLET` at 50 % opens at 51 % and closes at 49 %, with no switch inside the band; a hand toggle is refused under an enabled loop. `AccessoryFactsCompatTest` and `FluidFactsCompatTest` read the new images.

### W8 Close

- `EquipmentInfoCoverageTest` page keys and item names in `en_us.json` only (no other language); recipes for the orifice plate, the valve, the hopper and the intake.
- Code guide rows (`fluid-laws.md`, `instrument-accessories.md`, `runtime-units.md`, `materials.md`, `README.md`), `FORMATS.md`, the batch `README.md` (history, decisions D1 to D12, open items), `documentation/INDEX.md`, `tools/INDEX.md` if a tool changed.
- Software renders of the four models into `tmp/fluid-interfaces/views/` for the owner.

## 6. Ruled out

- A flow-measuring orifice (D1), a regulating automated valve, redstone or FE for the valve (D3).
- A vessel inside the hopper, a pressurised hopper outlet, settings on the hopper (D5).
- CO2, methane and hydrogen in the air (D7); biome or weather ambient on the server.
- Registration of the crude oil by mod list; world generation (D9).
- A new `Kind` constant (D11); migrations for the wire protocol.
- No dev client started by the thread; no whole test task (`test`, `slowGates`, `check`): filtered classes only; the final lane is the merge's.

## 7. Done

Each package's tests pass; the mod compiles in every source set; the two corpus images read; no existing test's expected value moved; the README lists what ran, the decisions and the open items; nothing is merged.
