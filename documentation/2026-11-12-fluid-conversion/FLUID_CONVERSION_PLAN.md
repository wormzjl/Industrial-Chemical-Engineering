# Fluid conversion plan

Date: 2026-11-12. Base: `main` at `ccde84295` (0.27.1). Source: the owner's plan ([README.md](README.md)), its review ([FLUID_CONVERSION_PLAN_REVIEW.md](FLUID_CONVERSION_PLAN_REVIEW.md)) and the owner's decisions of 2026-11-12 below. Class names are checked against the source at the base unless marked "to locate".

## 1. Owner decisions (2026-11-12)

| # | Decision |
|---|---|
| D1 (P2) | The IChemE tank is open to the atmosphere. |
| D2 (P4) | Forge to IChemE rules stay few and limited, so the bundled rules cannot break a plant. Most use is IChemE to Forge. No power charge, no loader tolerance check. |
| D3 (P5) | Liquids convert; a rule may also map a gaseous Forge fluid. |
| D4 | Gas rules run only on a closed variant with a blanket and vent. The open variant refuses a gas rule. |
| D5 (E2) | The max conversion rate is in process time, L/s. |
| D6 (B2) | Balance mode equalises fill fraction, with a deadband. |
| D7 (Q1) | A new equipment constructor kind with size tiers. No FE. The Forge side is passive. |
| D9 | The gas variant takes a pressure safety valve (owner, 2026-11-12: "for gas variant, a PSV needs to be installable"). W3: the gas converter's process vessel accepts the valve as a drum does (`FluidDeviceSpec.relief`, `reliefFace`); the model carries a flanged stub on its apex for it. |
| D10 | The planning session builds the model (owner: "Do the model by yourself here"): the two kinds, `ConverterDesign`, constructor and catalog wiring are done there; W3 keeps registration, the Forge capability and the valve. Sizes: tank diameter 1 to 20 m, shell 2 to 24 m (7,540 m3 at most, the owner's "bigger" maximum); rated flow one tank in 1000 s. |
| D11 | The liquid variant takes the liquid level gauge as an optional accessory (owner, 2026-11-12), on its process tank. The model has no fixed gauge and reserves the place (`ConverterDesign.gauge`: the front, 0.7 of the radius outboard, 0.5 m off the shell). W3: the gauge reads a registered open vessel's level, so it lands with registration. Move the gauge's drawing from `TankDesign.gauge` into `TankGaugeGeometry` on a kind-neutral place (designs may not name each other, `ProcessUnitTest.designsShareOnlyTheKit`); let `TankGaugeGeometry.bounds`, `ShapeRasterizer.tankGauge`, `InstrumentHosts.LevelGauge.of` and `TankRenderer` (today bound to `ProcessUnit.Floating`) take `FLUID_CONVERTER`; extend `LiquidLevelGaugeTest`. The tank's own gauge must render and place unchanged. |
| D8 (Q4) | Rules are datapack JSON only. KubeJS scripts generate that JSON; the mod names no KubeJS type. |

## 2. Physics

- **The converter is a boundary of the network.** Its IChemE tank is an ordinary vessel. Between that vessel and the Forge tank sits one hidden port: a void when the converter takes fluid out (reverse), a generator when it puts fluid in (forward). Mass and energy cross only there, and the ledger books them as it books every void and generator.
- **Reverse (IChemE to Forge).** The port draws the phase that stands at its nozzle. Everything it draws leaves the network. The Forge tank gains `V = m / rho` litres, with `rho` the drawn stream's density at the vessel's state, and 1 L = 1 mB. Example: 1 L/s of a 850 kg/m3 oil is 0.85 kg/s; at time factor 4 the Forge tank gains 4 mB per second of play, 0.2 mB per tick.
- **Forward (Forge to IChemE).** The port gives the rule's composition at the rule's T. The moles for `V` litres are `V * rho_rule / M`, with `rho_rule` the package's liquid density of that composition at the rule's T and P (for a gas rule, the rule's own `moles_per_litre`). The vessel takes the stream's enthalpy; its pressure follows from its contents (the atmosphere, or the blanket band).
- **Whole millibuckets.** The Forge tank holds whole mB. The converter keeps the fraction below 1 mB as a saved remainder in litres, so no volume is lost between slices.
- **Steady state.** Conversion runs at `min(set rate, what the source holds, what the destination has room for)` per slice. A full destination or an empty source gives zero flow and an alarm, not a hold.

## 3. Design

### 3.1 Equipment (D1, D4, D7)

- `EquipmentShape.Kind`: append `FLUID_CONVERTER` (open, liquids) and `GAS_CONVERTER` (closed). Appended constants only; `ShapeRepair` reads kinds by name.
- Both are drawn by `science/equipment/ConverterDesign` (built, D10): two tanks of one size on a slab, a transfer line with a flow meter across the front, the controller's cabinet behind it, a catwalk between the roofs from 4 m. Sizes: the shape's `diameter` is each tank's, 1 to 20 m; `tangentLength` each shell's height, 2 to 24 m; a tank holds `ConverterDesign.volume` (1.6 to 7,540 m3) and the rated flow is `ConverterDesign.rate`, one tank in 1000 s of process time (1.6 to 7,540 L/s). A Forge tank holds an `int` of mB, so the largest (7.5e9 mB) needs a `long` store behind the handler, which reports at most `Integer.MAX_VALUE` per call.
- IChemE side: process nozzles as on a tank. Forge side: one or more shell cells expose `Capabilities.FluidHandler.BLOCK` (registered in the mod's capability event; the handler lives on the structure's controller cell).
- Registration: `EquipmentStructure` calls `FluidWorldAuthority.placeVessel(node, ports, volume, levelHeight, open, geometry)` with `open = true` for `FLUID_CONVERTER`; for `GAS_CONVERTER` `open = false` and `FluidDeviceSpec.blanket` set (the existing `GasBlanket`). The hidden port is one extra `PhysicalFluidTopology.Port` at the tank's bottom (liquid) or top (gas), compiled as a `GENERATOR` or `VOID` node behind a boundary valve, as a column's port is.
- Duties of a new kind (AGENTS.md "New equipment"): the simulator catalog case as DRAWING_ONLY (the class AGENTS.md names `FlowsheetCatalog`: to locate), `FlowsheetPaletteCoverageTest`, `EquipmentLayoutGoldenTest`, `tools/equipment-drawings/update.ps1`, the `models-and-sfx` skill.

### 3.2 Engine side: `Converters` (E1 to E4)

- New `runtime/fluid/Converters`, an `IslandCoordinator.Ports` host joined to the column's with `Ports.both`. It follows `ColumnOperation`'s port pattern and `Flares`' `plans`/`booked` pair.
- `plans(island, graph, slice)`, on the server at dispatch, per converter in the island:
  1. Read the settings and the published vessel state. Pick the direction: the set one, or in balance mode the one that reduces `fillIChemE - fillForge` when its magnitude exceeds the deadband (default 5 %, a setting).
  2. Reverse: find the first rule (by `priority`, then id) whose ranges hold for the drawn phase. Target `m = min(rate * rho, room * rho / dt)` where `room` is the Forge tank's free litres minus reservations. Reserve that room. Emit `new PortPlan(node, valve, m, 0, null)` (a void).
  3. Forward: find the rule for the Forge tank's fluid. Take `V = min(rate * dt, stored)` out of the Forge tank into the converter's account (reserved, not yet converted). Emit `new PortPlan(node, valve, m, T_rule, moles)` (a generator bounded by the account).
  4. No rule, off specification, empty or full: no flow (`massTarget` 0) and the matching alarm.
- `booked(island, graph, start, end, result)`, on the server when the slice is accepted: read the port's booked mass from `result`. Reverse: add litres to the remainder, move whole mB into the Forge tank, release the reservation. Forward: return the unconverted part of the account to the Forge tank.
- A held or fenced island dispatches no slice, so nothing moves; the page shows the island's status.
- Nothing computes on the tick. The Forge handler's `fill` and `drain` only move whole mB in the Forge tank, inside `capacity - reserved` and `stored - reserved`.
- Rate clock (D5): `rate` is L per process second; `dt` is the slice's process seconds (`slice.seconds(settings.time)`).

### 3.3 Rules (R1 to R6, D2, D3, D8)

- Folder `data/<namespace>/icheme/fluid_conversion/<name>.json`, read by a new `SimplePreparableReloadListener` beside `MaterialReloadListener`, applied after the material catalog.
- Record `ConversionRule` (`science/fluid/conversion/`, Minecraft-free; the fluid is a string id or tag):
  - `revision` (declared token), `priority` (int, default 0), `fluid` (`"mod:id"` or `"#c:tag"`), `phase` (`liquid` or `gas`).
  - `to_icheme` (optional): `preset` (an existing mixture preset, for example `medium_crude`) or `composition` (compound id to mole fraction), `temperature` (K, or `"ambient"`), `pressure` (Pa, or `"ambient"`; used only for the density and enthalpy), and for `gas` `moles_per_litre`.
  - `to_forge` (optional): `basis` (`mole` or `mass`, default `mole`), `components` (compound id to `{min, max}`), `others_max` (the sum of unnamed compounds, default 0.01), `temperature` `{min, max}`, `pressure` `{min, max}`.
- Validation at load, each a refusal of that rule with a logged message: an unknown compound or preset in `icheme:refinery`, fractions that do not sum to 1, an empty range, `gas` without `moles_per_litre`. A fluid id whose mod is absent skips the rule silently.
- Matching: rules sorted by `priority` descending, then id. A rule that matched in the last slice keeps matching while the state is inside its ranges widened by 2 % of each bound (hysteresis).
- Forge side: match on the fluid only; `FluidStack` data components are ignored and never produced.
- Bundled rules (D2): reverse rules for products; forward rules only for crude oil to `medium_crude` and water to Water, both at ambient T and P. The fluid survey (review Q5) decides the list; it is step W6.
- Reload: a converter whose rule left or changed revision stops, keeps both tanks, and shows NO RULE until a rule matches again.
- Clients receive the rule list on login and reload (one packet) for the page and a JEI/EMI category (optional, last).
- KubeJS (D8): a documented example script writes the same JSON through KubeJS's own data generation. No mod code.

### 3.4 Control and alarms (Q3)

- `BuiltInInputs.channels`: `level_icheme`, `level_forge` (fraction), `rate` (L/s, booked), `direction` (state), and `alarm_<name>` channels.
- `EquipmentAlarm`: append `NO_RULE`, `OFF_SPEC`, `SOURCE_EMPTY`, `DESTINATION_FULL`, `LEVEL_HIGH`, `LEVEL_LOW`. The level alarms compare each tank's fill with four saved thresholds (defaults: none).
- DCS commands follow `ColumnCommands` (`Command`, `Door`, `run`): set direction (TO_ICHEME, TO_FORGE, BALANCE, OFF), max rate, deadband, thresholds. They are queued engine events, acknowledged on the schedule, and reach the converter only over its data link.

### 3.5 Saves (S1)

- `FluidFacts.SCHEMA` 23 to 24. New facts per converter: mode, max rate, deadband, four thresholds, Forge tank fluid id and mB, remainder litres, the account (fluid id, litres). Every field has a default equal to "no converter"; enums by name.
- A Forge fluid id that no longer resolves is kept as its string with its amount, and the converter shows NO RULE.
- State (rebuilt): the matched rule, reservations, the last booked rate.
- A corpus image under `src/test/resources/compat/` at schema 24. Shape fields: none new beyond the two kinds.

### 3.6 GUI

- One page on the fluid device screen, built by the `gui-creation` skill: two level meters, direction and mode, rate field, thresholds, the matched rule and its ranges with the current values, status and alarms. Opening replays the last snapshot; edits are queued events.

## 4. Ruled out

- A converter that sets the vessel's pressure (the vessel's state decides it).
- Conversion work in a Forge handler call or on the tick (engine schedule rule).
- A KubeJS plugin, FE use, a loader tolerance check on reverse rules (D8, D7, D2).
- Solids: a port with solids arriving closes, as a flare's does; the alarm is OFF_SPEC.

## 5. Pitfalls

- A void's draw takes the phase at the port's elevation. With two liquids in the tank the bottom port draws the aqueous phase; the rule is tested on the drawn phase, not on the whole tank.
- A reservation must be released on a rejected or replayed slice, or the Forge tank locks. Release in `booked` and when the island restates.
- Balance mode with wide reverse ranges swings heat out of the plant on each change of direction; the deadband and D2's short forward list bound it.
- Water in a Forge tank is an infinite source in most packs. The forward water rule is therefore a free water supply; this is accepted with D2.
- A new GameTest must remove what it registers, and use a fresh `fluidGameTestRunId`.

## 6. Work packages

| WP | Content | Proof (run only these) |
|---|---|---|
| W1 | `ConversionRule`, the loader, validation, matching with hysteresis | `ConversionRuleTest` (new): refusals, priority, hysteresis, the litre-to-mole numbers of section 2 |
| W2 | `Converters` with accounts, reservations, remainder; facts schema 24 and the corpus image | `ConvertersTest` (new): mass and energy across 100 slices each way equal the ledger's totals to rounding; a rejected slice releases its reservation; the corpus gates |
| W3 | Registration, the Forge capability, the gas variant's safety valve (the kinds and designs are built, D10) | `EquipmentLayoutGoldenTest`, `FlowsheetPaletteCoverageTest`, `equipmentGates` for the changed files; one GameTest: a Forge handler drains what a reverse rule produced |
| W4 | Channels, alarms, DCS commands | `BuiltInInputsTest`, `ControlSystemTest` additions |
| W5 | The page | compile, the GUI gates, software renders (`tools/heat-gui-renders/`) |
| W6 | The fluid survey and the bundled rules; the KubeJS example | `ConversionRuleTest` loads every bundled rule |
| W7 | Merge preparation on the owner's word (`merge-prep`) | the final lane, once |

Order: W1, W2, W3, W4, W5; W6 can run beside W2 to W5 (data only). Done means: each WP's proof passes, `en_us.json` holds the new keys, the batch README has one history line per WP.

## 7. Open

- The survey's result (W6) and the final bundled rule list: an owner check before merge.
- An in-game check with a second mod's pipes: the owner starts it.
