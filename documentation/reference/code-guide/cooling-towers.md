# Water cooling towers

**Branch snapshot:** `dd71de17` (`kimi/water-cooler-fixes`), based on `codex/water-cooling-tower` at `2433b1355` and main `79b853e0d`, mod 0.17.1, 2026-10-30. Unmerged. Batch: [2026-10-30-water-cooling-tower](../../2026-10-30-water-cooling-tower/README.md). Up: [code guide](README.md).

## Geometry and placement

`EquipmentShape.Kind` appends `INDUCED_DRAFT_COOLING_TOWER` and `NATURAL_DRAFT_COOLING_TOWER`. Both are open reservoirs. The shape's separate `TowerBank` defaults to `ONE` and validates 1–6 columns and 1–4 rows. `ProcessUnit.Bank` keeps its dry air-cooler series/parallel meaning. The constructor and server share validated shape/specification paths.

`CoolingTowerDesign` uses width 4, 6 or 8 m and fill 2–6 m for each fan cell. It repeats each fan, fill, distributor and eliminator at cell pitch; it builds one shared metal basin, steel frame and bank access. Concrete is not used in this structure. Each tower has one water inlet at distributor height and one basin outlet centred 0.5 m above the base, without an internal riser. Makeup shares the inlet. Natural towers have diameter 40–80 m in 10 m increments; height follows automatically at twice diameter, default 60 × 120 m. `CoolingTower` derives nominal contact area and the 2 m basin volume from these dimensions.

Both designs use the same bottom-outlet builder. Nozzle welds end within their wall instead of projecting into the interior; the natural inlet follows the shell band's radius at distributor height while keeping its external flange position. Each fan cell uses `ProcessUnit.axialFan`, an open metal cowl with a rotor and grille. The shared rotor builder places the animation pivot inside the hub so its owning shell cell exists below the top grille.

The natural shell is made of 4 m hyperbolic bands with about 2 m circumferential chords. Matching `Solid.hollowCone` bands leave the chimney air empty. `ShapeRasterizer` adds only cells touched by facets, nozzles or climbs and sorts the result; it does not scan/fill the entire bounding volume. `NaturalTowerRenderer` uploads one static shell VBO and draws it once; `chunkSurface` omits the same facets from chunk quads. `EquipmentGhost` uses whole tower facets for its preview. Ordinary placement checks every occupied cell against world height and blocks. Existing refresh logic restores cells and ports when chunks load.

## Contact physics

`TowerTransfer` fixes ambient at 298.15 K, RH 0.5 and 101325 Pa. Humidity ratio is `0.621945 pv / (P − pv)`. Each of eight fill segments solves liquid mass/enthalpy balance implicitly against its local air temperature and humidity. Sensible and mass coefficients follow a unity Lewis relation. Relaxed air sweeps enforce counterflow; reverse draft runs in the opposite direction. Saturation and liquid/vapor water enthalpies come from `FluidThermodynamics`, preserving the network reference. No water is retained in fill.

The coefficient fits a declared game calibration: 2 m fill, 2 kg/s/m² each water and dry air, 308.15 K return to 298.15 K outlet. A volatile immutable `(model, coefficient)` pair keeps a model from borrowing another package's calibration. This is a game rating, not a packing-vendor performance claim.

Airflow balances signed quadratic resistance with `20 speed²` Pa fan pressure and humid-air buoyancy over effective chimney height. The root bracket expands when necessary. Fan-off operation can retain passive airflow; cold water can reverse it. Fan power uses the declared 2 kg/(s m²) full-speed air loading and fixed ambient density: per representative cell, `20 * (2 * cellArea * speed) / rho / .6 * speed²`, scaled once by cell count. For a fixed tower it is therefore proportional to speed³ and independent of the solved air or water state. Bank contact is calculated at `returnFlow / cells` and scaled once by cell count. Droplet drift is omitted. Sensible surface contact remains while the basin is wet, even with zero spray; the basin itself has no evaporation or condensation.

## Conservation and hydraulics

`TowerExchange.at` is the canonical exchange for Newton, conservative reconstruction and accepted presentation. It mixes return streams by specific enthalpy and keeps makeup separate. `PassiveStepSolver` includes net water and energy exchange in node residuals; `ConservativeTransport` applies the same terms to reconstructed inventory and emits an `ATMOSPHERE` boundary. Positive fill evaporation removes water and carried vapor enthalpy; sensible exchange adds its own energy term. Latent duty is diagnostic and is never booked again. The existing accepted conservation audit sees the resulting boundary totals.

`PhasePort.SPRAY` admit only pure liquid water still liquid at atmospheric discharge, with no freezing-point cutoff; this includes 80 °C natural-draft feed. Unsupported material closes the inlet using the hydraulic active set, preserving upstream inventory. The inlet row discharges at atmospheric pressure at nozzle elevation, using the incoming stream's density. `LIQUID` basin outlets retain level head. The common inlet accepts return and makeup supplied by external piping; there is no built-in float valve. Existing open-vessel spill handles overfill without an overflow nozzle. Basin operating depth is 2 m, freeboard is 1 m and construction creates no water.

## Controls and presentation

Fan banks default to manual speed 1. Target-temperature control solves fan share for the water leaving the fill before basin mixing, independently of basin holdup, and saturates at its endpoint when unreachable. With no spray it stops the fans. Natural towers stay passive. Capability and target-limit statuses do not hold supported operation.

`PassiveStepSolver.Result.towers` and `PassiveIntervalSolver.Result.towers` hold accepted rates. `FluidWorldAuthority` copies these into scheduled `FluidView.Tower` snapshots. The compact device screen starts with inlet temperature, basin temperature and the tank snapshot's bottom pressure, and combines sensible/evaporative duty as cooling duty. It retains inlet flow, fill evaporation and fan power/speed, omitting ambient pressure, mass, net flow, airflow, separate makeup/supply rates, drift and phase composition. `CoolingTowerBasinRenderer` draws cached level snapshots with two triangles for a bank or 64 for a natural basin; the same display carries accepted fan speed for `MachineAnimation`. A lower `InstrumentGeometry.levelSockets` mount accepts the drum-style level sensor; valve linking is deferred. Menu opening does not solve contact. Server control edits retain existing geometry and inventory.

`CoolingTowerBasinRenderer` and the floating tank gauge use the same `LiquidRenderer` for their texture, translucent layer and vertices. Basin surfaces preserve the water material's opacity (currently about 25%), removing the earlier minimum of 160/255. The tank gauge keeps its existing readability minimum. Both still use scheduled cached displays after block entities; no new process calculation or payload is introduced.

## Persistence and verification

State format 30 and facts schema 6 include optional `CoolingTower` geometry/controls and basin contents; equipment shape fields 4 append `TowerBank`. Protocols are `fluid-17` and `equipment-15`. Certificate graph identity 9 includes the tower specification. Replay scales the accepted atmospheric boundary with inventory deltas and keeps instantaneous diagnostic rates as rates. Historical images remain readable through the existing exact/facts routes; new images are in `compat/equipment/shapes-4.snbt` and `compat/fluid/schema-6/`.

The focused tests are `CoolingTowerTest`, `CoolingTowerGeometryTest`, `CoolingTowerRuntimeTest` and `CoolingTowerGameTests`; historical compatibility and equipment gates remain active. [Verification tools](../../../tools/cooling-towers/README.md) run the prescribed lane with every output under the owning worktree's indexed `tmp/`. Native single-client MCP GUI/render qualification uses the local bridge launcher; see the batch review for the current evidence.
