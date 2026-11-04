# Varying components on the fluid network: review

Batch [2026-11-04-refinery-compounds](README.md), 2026-11-04, on `claude/refinery-compounds-thermos-252973` at 9596aac11 (main 84f739baa, 0.20.0). Owner request: "The fluid network needs to handle varying components, need to research about how." It builds on [PACKAGE_REWORK_PLAN.md](PACKAGE_REWORK_PLAN.md) (a package is a method plus a compound list). Code guide: no change (no code changed). No test run (document only). Code paths are under `src/main/java/com/wormzjl/icheme/`, with line numbers at 9596aac11. The costs in section 3 are counted from the loops, not timed.

## 1. Expected behaviour

**Physics.**
- "Varying components" means: the world lists many compounds; each island carries only the compounds that are physically on it; an island's set changes when material with a new compound arrives; a catalog addition does not disturb an island that never holds that compound.
- No reaction runs on the network. In an island, dN_i/dt = in_i − out_i for every compound i, so a compound that no source delivers stays at exactly 0 mol. An island's set is what its vessels hold plus what its sources deliver: generators, column ports, and crystals that melt into their own compound. A merge joins two sets. Each part of a split keeps a subset.
- Islands share no material. Exchangers pass heat only. A column unit moves material between islands through its accounts. Each island's set is therefore independent of the others.
- PR78 with one-fluid mixing: a_m = ΣΣ x_i x_j (1 − k_ij)(a_i a_j)^½, b_m = Σ x_i b_i. A compound with x_i = 0 adds exact zeros to a_m, b_m, Z, h and the ln φ of every other compound, so the state does not depend on which absent compounds a list names. In floating point, the same sums in the same order without the zeros give the same bits. Wilke viscosity, the ideal-gas terms and the liquid-viscosity correction also sum only what is present. The energy datum is per compound with zero offsets (`science/fluid/state/EnergyReference.java:28-29`), so an island's energy does not depend on its list either.
- An absent compound has only an infinite-dilution ln φ, which counts when it is about to arrive. The solver already gives an arriving compound a 1e-12 entry trace in its trial seed, never in the stock (`science/fluid/network/PassiveStepSolver.java:1098`).
- In numbers: a refinery world lists about 70 compounds (20 on today's network axis, up to 49 candidates). A crude island holds 20, a hydrotreater about 23 (+ H2, H2S, NH3), a Claus or sour-gas island 7 to 8 (H2S, SO2, COS, CO2, N2, O2, water), a reformer or FCC gas plant 20 to 25. One island holds 8 to 25 of the 70.

**Practice** (sources at the end; paraphrased):

| Tool | Compound list | Local reduction |
|---|---|---|
| DWSIM [S1] | compounds are added to the simulation; a property package may differ per unit operation and per stream | none |
| Aspen HYSYS [S2] | each component list has its own fluid package; several lists help when streams have very different compositions | per flowsheet section, with maps between lists |
| CAPE-OPEN Thermo [S3] | every property call names its compounds by id; a material object's list is fixed when it is configured; a package holds a limited set chosen for an application | the caller chooses |
| OLGA compositional tracking [S4] | one list per case; a mass equation per component per phase; merged fluids of different compositions are tracked component by component | none |
| OpenFOAM TDAC [S5] | the full mechanism with an active flag per species; a species with no initial field starts inactive; a run only ever sets flags | per cell: a reduced mechanism with complete/simplified index maps; only its species are updated |
| PhreeqcRM [S6] | the transported components are the elements of every definition; repeated calls accumulate the list; arrays are components × cells | speciation per cell |
| Factorio, NeoForge [S7] | one fluid per pipe segment or tank; mixing in Factorio deletes all but one fluid | none: no mixtures |

Every tool keeps one bookkeeping list by id per flowsheet, case or mechanism, and that list only grows. The tools that scale to many species (TDAC, PhreeqcRM) compute each cell on its own active set and map it to the full list by index. HYSYS does the same per flowsheet section, with maps the user draws.

## 2. What the code does now

| Area | Today | Where |
|---|---|---|
| Network package | one package, 20 compounds + water; a save of another id is refused ("Use a fresh development world") | `science/fluid/thermo/FluidMaterialCatalog.java:9-20` |
| Caps | 64 + water per axis, 64 per package, 64 in the V3 column | `science/material/MaterialAxis.java:7`; `MaterialCatalog.java:492, 783`; `science/column/v3/V3ComponentBasis.java:10` |
| World basis | ids, Water last, Nitrogen required; any difference restates every island | `runtime/fluid/WorldTopologyLedger.java:370-383`; `FluidCheckpointStore.java:362` |
| Node arrays | world width: State liquid/vapor, Inventory moles, supports nodes × n, targets and incoming per node | `science/fluid/thermo/FluidThermodynamics.java:1491-1513`; `PassiveStepSolver.java:961-997, 3878` |
| Newton unknowns | already per node: present ∪ reachable compounds only, 2·m + 5 for a two-phase node | `PassiveStepSolver.java:1012-1061`; `science/fluid/solver/PhaseLayout.java:65-89` |
| EOS value call | world width: about 10 loops over n (≈ 60 operations, 8 divisions per compound) and one pass over the pairs | `science/thermo/PengRobinsonKernel.java:294-386`; `science/fluid/thermo/TranslatedPengRobinson.java:306-358` |
| EOS derivatives | an n × n `dLogPhiDn` per call, although the TP flash and the stability test work on their present list | `PengRobinsonKernel.java:402-468`; `science/thermo/phase/FluidTpEquilibrium.java:1228-1231, 5447-5458`; `science/thermo/TangentPlaneStability.java:21-22` |
| E-PPR78 (spine package) | a rule pair for every pair without a constant k_ij; one pow per group term per pair at each new temperature | `science/material/GroupContributionInteractions.java:188-213`; `science/thermo/PairInteractions.java:100-116` |
| Viscosity | (n+1)² Wilke tables; the mixing loops skip zero amounts | `science/fluid/transport/MixtureViscosity.java:37-38, 195-212` |
| Facts (schema 9) | by id: the world list once; each island its own list, then positional vectors; a restate maps by id | `runtime/fluid/FluidFacts.java:186, 484, 521, 618, 720-735` |
| State (format 30) | positional; sparse doubles (a bit mask and the nonzero entries) | `runtime/fluid/FluidCheckpointCodec.java:88, 861-866` |
| Per-island models, latent | an island holds its model; a checkpoint has a package table; the authority caches models by key; each island unit is written on its own model's list | `IslandCoordinator.java:125, 296`; `FluidCheckpointCodec.java:111-127, 463-474, 616-621`; `FluidWorldAuthority.java:35, 168`; `FluidCheckpointStore.java:174-183` |
| One model in practice | the registry compiles with one model; a topology change refuses originals of another revision and compares stock positionally | `runtime/fluid/PhysicalRegistry.java:79-80, 440`; `IslandCoordinator.java:780, 802` |
| Wire fluid-17 | positional 3 × (n+1) per stream on the world list; Gson writes a zero as `0.0` | `network/FluidNetwork.java:55-57, 115-134, 156` |
| GUI pickers | a search over the world list | `client/gui/screens/inventory/FluidDeviceScreen.java:384-389`; `client/gui/flowsheet/StreamDoors.java:236-249` |
| Simulator | the network package only; feeds stored by id | `science/flowsheet/FlowsheetEngine.java:70-73`; `Flowsheet.java:113`; `ProcessSimulatorScreen.java:181-185` |
| Nitrogen, water | N2 is the ambient and the junction placeholder; water is a separate phase, last | `science/fluid/network/NetworkReduction.java:346`; `runtime/fluid/FluidDeviceSpec.java:74` |

Measured on 2026-09-15 with 22 compounds, before later optimizations ([FLUID_NETWORK_IMPLEMENTATION_REVIEW.md](../2026-09-15-fluid-network/FLUID_NETWORK_IMPLEMENTATION_REVIEW.md), lines 85-101 and 225-253): 47 unknowns per node; 191 Jacobian colours, set by the node block and its degree, not by the island size; 316 kB allocated per residual, about 42 % of it in categories that scale with the axis width (record clones, residual arrays, layouts); 822 temperature preparations per quiet 30-vessel interval.

So the Newton system already follows each node's compounds. The width is fixed in the thermo evaluation, the node arrays and the bookkeeping.

## 3. Options

### 3.1 Cost against width

A value call costs a fixed part (the cubic, three logarithms) plus 60·n + c·P operations: c ≈ 4 for constant pairs, c ≈ 20 for E-PPR78 pairs. A derivative call costs ≈ 25·n². A new temperature with E-PPR78 costs ≈ 6·P pow (about 6 group terms per pair).

| Case | n | P | Value call (operations) | Derivative call (operations) | E-PPR78 pow per temperature |
|---|---:|---:|---:|---:|---:|
| Network today | 20 | 11 | 1,244 | 10,000 | none |
| World list, constant pairs (11 + 155 DECHEMA) | 64 | 166 | 4,504 (3.6×) | 102,400 (10×) | none |
| World list, E-PPR78 | 64 | 2,016 | 44,160 (35×) | 102,400 (10×) | 12,100 |
| Crude island, E-PPR78 | 20 | 190 | 5,000 (4×) | 10,000 | 1,140 |
| Claus island, E-PPR78 | 8 | 28 | 1,040 (0.8×) | 1,600 | 170 |

- E-PPR78 at the measured 822 temperature preparations per quiet interval: ≈ 10 M pow per interval on the full list (0.5 to 1 s at an assumed 50 to 100 ns per pow), ≈ 0.9 M on a 20-compound island (50 to 100 ms). Constant pairs need no pow.
- Memory (estimate): about 12 vectors of width n+1 per node: 6.2 kB at 65 against 2.0 kB at 21. Allocation per residual ≈ 316 kB × (0.58 + 0.42 × 65/22) ≈ 575 kB at 65 (×1.8).
- Wire: a view holds at most 34 three-phase vectors (state, pipe contents, 8 heat ports, 12 pipe-history pairs). With every entry nonzero at 22 B: 47 kB at 21, 146 kB at 65, 269 kB at 120 (over MAX_JSON, 262,144). Typical, 20 nonzero in 65: 63 kB.
- Checkpoint: ⌈(n+1)/8⌉ mask bytes per vector, so 6 B more per vector at 65. Negligible.

### 3.2 The options

**A. Fixed world list (≤ 64 + water), each island evaluated on an active plan.**
- Changes: the network package lists every network compound. `PengRobinsonKernel` (values, derivatives, pair terms), `TranslatedPengRobinson.fill`, `MixtureViscosity` and the flash's derivative calls loop over an index plan: the island's `componentMask` ∪ reachable set, carried on the workspace, pairs filtered and cached per plan. Every caller outside a solve (spec initialisation, restates, presentation) needs its own plan.
- Numbers: EOS as the island rows of 3.1; arrays, allocation (×1.8) and wire at full width.
- Saves: facts unchanged. Each change of the list restates every world (a TP flash per vessel, the energy booked), unless the restate learns an exact remap for a pure widening.
- Solver needs: none. Newton, PhaseLayout supports, junction seeding and certificates keep one model.
- Risks: a mode in the hot path (ln φ outside the plan is not filled and must never be read). The cap stays 64, so the 49 candidates still need tiers. Every catalog addition restates every world.
- Deletes: nothing. The plan is code that B never needs.

**B. Island compound sets: an island's list is the compounds on it.**
- Changes:
  - An island's model is the network method on the island's own list, in world-list order: a restricted package view with the same records, pairs only among its compounds, Nitrogen and water always, and the network's ranges, numerical box and crystal rules, so no state is judged differently.
  - `PackageKey` gains the set (`FluidCheckpointCodec.java:127`); the authority's cache serves it (`FluidWorldAuthority.java:168`).
  - The registry compile gives each island the union of its stock, generator specs, column-port products and crystal compounds. A generator respecified with a new compound already takes this path: a menu edit is a topology event (`FluidWorldAuthority.java:347-356`, `PhysicalRegistry.java:368-385`).
  - `IslandCoordinator.topology` takes a model per replacement, remaps stock by id (zero padding, exact) and checks stock by id (`:780, :802`). The warm carry and junction states are remapped the same way (`:811`).
  - A column plan that names a compound outside its target island first queues that island's growth; the column account holds the moles until then.
  - Presentation and ledger totals project to the world list by id, so the wire format does not change while the world list has at most 119 entries (3.1).
- Numbers: EOS, arrays and allocation follow m = 8 to 25 per island, never the world list. Wire as today (typical 63 kB at a 65-entry list).
- Saves: facts schema 9 unchanged. Island facts already carry their own list (`FluidFacts.java:484, 521`), and the rebuild maps any saved list by id (`:618`). World facts stay on the world list. State format 31: the package table is keyed by package and set, and the first load rebuilds through FACTS. A catalog change restates only the islands whose own compounds' records moved; a new compound moves none.
- Solver needs: none in the Newton system or the PhaseLayout supports (they size on the model's list). Junction seeding: mint on the island model; remap carried states at a growth. Certificates: the signature carries the island model's revision (`IslandCertificate.java:315-327`), so only an island whose set changed loses its certificate.
- Risks: the largest change in `runtime/fluid` (registry, coordinator, column operation, presentation). A remap bug would lose stock; the by-id stock check and the ledger catch it. Sets only grow during an island's life and spread traces through headers and columns (physically right). Per-island cap 64 + water: a larger merge is a capability limit. A new set builds a model at a topology event (E-PPR78 rule pairs precomputed once per catalog, selected by index).
- Deletes: the refusals of another package and basis (`FluidMaterialCatalog.java:16-20`, `WorldTopologyLedger.java:381-383`); the world-wide restate when the catalog gains a compound; the tiers to fit 64 (the world list is bookkeeping and may exceed 64); the re-capture of chain-100 at a new width; the simulator's package equality (`FlowsheetEngine.java:73`), which becomes physics equality on the sheet's compounds; any plan mode in a kernel.

**C. Append-only world list, grown when a compound first enters the world.**
- Changes: the first generator, preset or column product with a new id appends it. Water is last, so every island's arrays are remapped live and the model is replaced.
- Numbers: the width tends to the world list during a play-through, so C needs A's plan for cost; one world-wide remap per new compound (up to 49).
- Saves: facts unchanged (the world list grows); each growth is an exact remap without a flash.
- Solver needs: each growth drops every certificate, retained solver and workspace (the signature carries the model revision): a world-wide cold start.
- Risks: a world-wide barrier at runtime and its cost spike; the cap stays 64. Deletes: nothing beyond A.

**D. Id-keyed sparse vectors everywhere.**
- Changes: every positional vector (inventory, state, stream, transfer, drift, codec, wire) becomes sorted id/value pairs; every component loop in `science/fluid` and `runtime/fluid` is rewritten (41 `componentCount()` reads in 9 files and the positional loops behind them).
- Numbers: EOS on present entries; every transport step merges id sets; maps would box.
- Saves: facts unchanged (an id list plus positions is already an id map); state and wire reformatted. Solver needs: Newton none (local layouts); every other consumer rewritten.
- Risks: the largest refactor and regression surface, and no physics gain over B, which keeps dense arrays inside an island. Deletes: positional axes and every cap.

**Other forms considered.** Per-node reduction (TDAC reduces per cell) on top of B would cut a node from its island's set to its own; the nodes of one island exchange material and converge to similar sets, so the gain is small. HYSYS-style sections drawn by the player would add a device and maps that the physics does not ask for: the islands are the natural sections. Neither is proposed.

## 4. Recommendation and plan

Recommend **B**. It puts the compound set where the physics puts it, on the mixing domain (the island), and it follows the TDAC and PhreeqcRM pattern: one id list for bookkeeping, the work on the local set. It uses the per-island model plumbing that the saves and the coordinator already have and changes no kernel. By the inertness of section 1 it keeps every result bit for bit for an island whose set covers what it holds; V1 proves this on the thermo and V5 on the references. It needs the package rework first (W1, a package is a method plus a list; W4, the world basis by ids), and it replaces the rework's W7 (widening the network axis).

| WP | Content | Tests (only these, with `--tests`) |
|---|---|---|
| V1, with rework W1 | `Package.restrict(ids)`: the restricted view and its identity on its compounds' records; `FluidThermodynamics` on it | new `RestrictedPackagePhysicsTest` (state, TP and PH flash, ln φ, viscosity, crystals and data-range continuation on a subset equal the full model bit for bit); `FluidThermodynamicsTest`, `HydrocarbonModelTest` |
| V2 | island sets: key and cache, registry compile, coordinator merge, split and growth by id, column plans, presentation and ledger projection | `IslandCoordinatorTest`, `PhysicalRegistryTest`, `PhysicalFluidTopologyTest`, `WorldTopologyLedgerTest`, `ColumnRunTest`, `PipePresentationTest`, `HeatPresentationTest`, `FluidPacketCodecTest`; new `MixedSetMergeTest` (exact moles and energy, junction carry, an untouched island keeps its certificate) |
| V3, with rework W4 | state format 31; a per-island model in the FACTS rebuild; restate per island identity; one corpus image with two sets | `FluidFactsCompatTest`, `FluidCheckpointCodecTest`, `FluidCheckpointStoreTest`, `FluidCheckpointFormatTest`, `IslandCertificateTest` |
| V4 | the simulator on the sheet's list; the column's public basis is its feed island's set; caps: world list above 64, island 65 (wire fluid-18) | the flowsheet tests that load `FlowsheetEngine`, `ColumnRunTest`, `V3NeuralRegistryTest` |
| V5 | final lane, once: `test slowGates`; the 33 junction lines and chain-100 exact must not move (the proof of inertness); campaign counters; then one timing run alone on a mixed-set world after the new compounds land (rework W6) | the lane |

Owner questions:
1. B as the target, after the package rework, or A as a smaller first step?
2. Per-island cap: 64 + water, with a larger merge refused as a capability limit, or a higher cap?
3. An island set never shrinks, except at a split or a facts rebuild, and then only by compounds exactly zero everywhere in it?
4. Does the simulator run each sheet on its own compound list (as DWSIM does), with the network's method and records?
5. The k_ij route (rework Q3) also sets the cost: E-PPR78 needs about 6 pow per pair at each new temperature, even on an island set (1,140 at 20 compounds); constant pairs need none. Which route?

## Owner decisions (2026-11-04)

1. Route: option B, island compound sets, after the package rework. B replaces the rework plan's W7 (widening the network axis). Its work packages are V1 to V5 of section 4.
2. Per-island cap: 64 compounds + water. A merge past the cap is refused as a capability limit. The world list (the network package) may hold more than 64 compounds.
3. An island's list only grows while the island lives. It shrinks only at a split or a facts rebuild, and then only by compounds that are exactly zero everywhere on the island.
4. The process simulator runs each sheet on its own compound list, with the network's method and records.
5. The k_ij route is already settled by the rework plan's D3: the crude method keeps constant DWSIM pairs, else zero. E-PPR78 stays only in the pilot method.

## Sources

- [S1] DWSIM help, running a simulation: https://dwsim.org/docs/crossplatform/help/running.htm
- [S2] HYSYS v8.0 training slides: https://www.slideshare.net/slideshow/baijan-savalan-hysys-v80/72355976. The component maps between lists (a chapter of the HYSYS simulation basis manual, and an OLI guide to two fluid packages) were seen only as search extracts.
- [S3] CAPE-OPEN `ICapeThermoCompounds.GetCompoundList`, DWSIM API reference: https://dwsim.org/api_help/html/M_CapeOpen_ICapeThermoCompounds_GetCompoundList.htm
- [S4] SLB, Olga compositional tracking: https://www.slb.com/products-and-services/delivering-digital-at-scale/software/olga/olga-pipeline-management/olga-compositional-tracking
- [S5] OpenFOAM v9 `TDACChemistryModel.C`, lines 44-45, 82-86, 186-187, 467-471, 606-617: https://cpp.openfoam.org/v9/TDACChemistryModel_8C_source.html; active flags in `basicSpecieMixture`: https://cpp.openfoam.org/v8/classFoam_1_1basicSpecieMixture.html
- [S6] PhreeqcRM class reference, `FindComponents`: https://water.usgs.gov/water-resources/software/PHREEQC/documentation/phreeqcrm-html/classPhreeqcRM.html
- [S7] Factorio wiki, fluid system: https://wiki.factorio.com/Fluid_system; NeoForge 1.21.1 `IFluidHandler` (javadoc mirror): https://aldak.netlify.app/javadoc/1.21.1-21.1.x/net/neoforged/neoforge/fluids/capability/ifluidhandler
- In this repository, the same choice stood open in [CRUDE_REGROUPING_PLAN_REVIEW.md](../2026-09-17-crude-regrouping/CRUDE_REGROUPING_PLAN_REVIEW.md), E2 (lines 411-413): one growing global package, or per-island sub-bases.
