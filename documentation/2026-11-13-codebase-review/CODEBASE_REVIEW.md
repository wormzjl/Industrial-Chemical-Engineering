# Code base review: synthesis

Base `259d22a2c` (main 0.30.0), 2026-11-13. Written by the main thread from the twelve phase 1 reviews in this folder. Finding ids (C4, F1, P-01 ...) are the reviews' own; the review that owns an id is named once in section 2. "Checked" means the main thread read the cited lines; everything else is the reviewer's reading. The profile (B1) is not in yet: where an order depends on it, the text says so.

## 1. Summary

The code base is 861 production files and 208 131 lines. Its science is sound and its rules (facts and state, the engine schedule, deterministic math) are held almost everywhere. Its problems are of four kinds:

1. **Layering.** The intended order (math, thermo, solvers, plant, equipment and flowsheet, runtime, client) exists in the package names but not in the imports. There are six import cycles in `science/` and the two solvers meet in at least fifteen places instead of one.
2. **Monoliths with hidden state.** Eight classes hold 28 000 lines. The three solver cores pass state between their stages through mutable fields (about 110 in the equilibrium engine, about 25 in the fluid step), which is what blocks both a split and a native port.
3. **One law, several statements.** Water is written four times, the cubic's roots three times, Rachford-Rice four times, the ejector's capacity twice with different answers, the still's fill limit twice with different numbers. The simulator's sizing and the world disagree in at least four places.
4. **Cost that buys nothing.** Work repeated per residual, per step, per frame or per bucket whose result cannot have changed; objects where arrays would do; exceptions and strings as control flow.

Twenty-three defects were found (section 4). Seven break a stated rule today: three break determinism, three break the saves rule, one runs a flash on the server thread.

## 2. Modules as they stand, and the reviews that cover them

| Area | Files | Lines | Review | Largest problems |
|---|---|---|---|---|
| `science/thermo/**` (kernel, stability, equilibrium engine) | 29 | 12 883 | [THERMO_CORE_REVIEW.md](THERMO_CORE_REVIEW.md) (S01-S16, P20-P27, X30-X39, D09, D14) | `FluidTpEquilibrium` 6 290 lines on shared scratch; engine imports the network's package; failures typed by text |
| `science/fluid/thermo`, `column/v3/thermo`, `material`, `math` | 58 | 11 898 | [THERMO_ADAPTERS_REVIEW.md](THERMO_ADAPTERS_REVIEW.md) (F1-F30) | water four times; the column's identity misses its pairs; `FluidThermodynamics` is seven modules |
| Fluid step (`PassiveStepSolver`, Newton, LU, reduction) | 8 | 7 985 | [FLUID_STEP_REVIEW.md](FLUID_STEP_REVIEW.md) (P-01-P-16, D-01, D-02, S-01-S-14, X-01-X-03) | one class, 13 stages, 19 pass restarts; three numerics stacks; laws stated up to four times |
| Fluid laws, booking, interval, transport, state | 49 | 8 553 | [FLUID_LAWS_REVIEW.md](FLUID_LAWS_REVIEW.md) (F1-F25, cited here as L-F1 ...) | tower root searches inside the residual; `ConservativeTransport` on tree maps; design data in the law package |
| Column V3 and the native lane | 81 + C++ | 20 001 + 742 | [COLUMN_REVIEW.md](COLUMN_REVIEW.md) (D1-D6, P1-P6, S1-S7, L1-L4, T1, T2, A2, SC1), [RUST_COLUMN_PORT_PLAN.md](RUST_COLUMN_PORT_PLAN.md) | results depend on the machine; two Jacobians; a 3 637-line ladder that reads strings |
| Where column and network meet | 20 | 5 500 | [PLANT_COUPLING_REVIEW.md](PLANT_COUPLING_REVIEW.md) (C1-C25) | the column's energy books run on network flashes; a column solver in the fluid package; port code twice |
| Fluid runtime, scheduling, saves | 40 | 16 000 | [RUNTIME_REVIEW.md](RUNTIME_REVIEW.md) (R1-R26) | presentation quadratic; a read commits state; plant jobs can stop every island; `FluidWorldAuthority` has 14 roles |
| Rotating machinery | 26 | 4 848 | [MACHINES_REVIEW.md](MACHINES_REVIEW.md) (M-01-M-20, K1, K2, N1-N7) | driver and gas disagree on shaft power; both trips unreachable; wear for pumps only |
| Equipment and world | 174 | 47 974 | [EQUIPMENT_REVIEW.md](EQUIPMENT_REVIEW.md) (E1-E23) | `ProcessUnit` is kit, drawings and ratings; four parallel kind switches; shape fields read without aliases |
| Client and tutorial | 236 | 48 275 | [CLIENT_REVIEW.md](CLIENT_REVIEW.md) (C1-C23, cited here as G-C1 ...) | `FluidDeviceScreen` off the kit; eight screens unbatched; six hand-written watches |
| Flowsheet, assembly, control, power, instruments | 127 | 20 000 | [FLOWSHEET_CONTROL_REVIEW.md](FLOWSHEET_CONTROL_REVIEW.md) (P3-01-P3-29) | sizing restates world laws and disagrees; identities and dispatch by strings |
| Tests, build, tools, documents | | | [INVENTORY.md](INVENTORY.md) | `tools/` 516 MB tracked, 113 folders unused; ten Gradle tasks with no source |

## 3. Structural issues, ranked

### 3.1 Import cycles and inversions (all checked from the import lines)

| Cycle or inversion | Where | Fix (class STRUCTURE unless stated) |
|---|---|---|
| column ↔ fluid through water | `V3WaterProperties` → `WaterRegion1`; `FluidThermodynamics` → `V3WaterProperties` (5 sites); `HenryConstant` → `V3WaterProperties` | one `science/thermo/water` module on plain constants (F2, F6, X39); Wagner-Pruss moves about 1e-16 relative for the solver whose association is dropped |
| thermo ↔ material | `phase/` reads `MaterialCatalog` in 5 files; `DerivedPairs`, `VolumeTranslation`, `ReferenceSpine` read the kernel | the core is two layers: kernel and flash numerics below material, the engine above it behind one binding class (S05) |
| engine → network package | `CubicPhaseEvaluator`, `CrystalPhaseEvaluator`, `PhaseContract`, `PhaseDomain` → `fluid/thermo` | move the residual cubic, the ideal-gas fit and the spine data down (S04, F9) |
| thermo → solver | `FluidThermodynamics` → `PhaseLayout`, `SparseNewton` (junction split) | the junction closure moves to `fluid/solver` (F8, S-10) |
| material → column, material ↔ fluid state | `MaterialPresets`, `MaterialCatalog` 778; `SolidMaterialCatalog` ↔ `SolidInventory` | presets as plain records, preset check in the plant layer, inventory check in `fluid/state` (F4, F5, F7) |
| column ↔ equipment | `equipment` → `column/v3` (`ColumnOperatingPoint`, `ColumnDesign`); `column/operation` → `equipment` | the operating point becomes a plain fact; its conversion to a V3 input moves to the plant layer (E18, C13) |
| machine → flowsheet, machine ↔ fluid | `SteamEjector` → `EjectorLaw`, `Limit`, `EjectorUnit`; `InlineMachines` ↔ `FlowControl`; `runtime/fluid/Ejectors` → `flowsheet.EjectorLaw` | the ejector law and its constants move into `science/machine` (M-14, P3-09) |
| step solver ↔ its graph, laws → step solver | `PassiveNetwork` ↔ `PassiveStepSolver`; nine laws read solver statics; `transport` → `network` | node predicates and constants move below the laws (S-10, L-F8) |
| control ↔ instrument ↔ power | all three through `FluidWorldAuthority` | one host interface (P3-26) |
| world → client | about 20 sites in three block entities | one client-hook interface (E17) |

`science` imports nothing from `runtime`, `world`, `network` or `client`. That rule holds today and is the first one to pin with a test ([DOCS_AND_COLLABORATION_PLAN.md](DOCS_AND_COLLABORATION_PLAN.md), section 3).

### 3.2 Monoliths

| Class | Lines | What it holds | Split |
|---|---|---|---|
| `FluidTpEquilibrium` | 6 290 | ten stages on about 110 workspace fields | ten files; the dry core a function of an explicit feed (S01, S02); first delete 170 dead lines of per-branch pressure (S03, checked) |
| `PassiveStepSolver` | 5 752 | 13 stages; `Equations` and `BalanceNet` read about 25 mutable fields | 13 files, pure function groups first; one immutable `SolveConstants` (S-01 to S-07) |
| `ProcessUnit` | 4 856 | drawing kit, 11 kinds' drawings, rating laws, 13 forwarders | drawings into their design files, ratings into `UnitRatings` (E1 to E4) |
| `V3ColumnCalculator` | 3 637 | ten responsibilities, about fourteen nested recovery layers | nine files, one rung runner, enums for the codes (S1 to S3 of the column review) |
| `FluidWorldAuthority` | 2 898 | 14 responsibilities, reached from 60 files | a facade plus registry, presentation, chunk binding and plant parts (R8, R9) |
| `EquipmentStructure` | 1 760 | four parallel kind switches, machine-law construction | one registration family per design, five classes (E7 to E9) |
| `FluidDeviceScreen` | 1 581 | frame, eight pages as ints, meters with physics, status parsing | page enum, `DeviceMeters`, onto the kit (G-C1 to G-C3) |
| `FluidThermodynamics` | 1 556 | seven roles | port, phase rules, streams, water, facts reader (F8) |
| `IslandCoordinator`, `FluidFacts` | 1 458, 1 128 | scheduling, coupling, certificates; every subsystem's facts | four classes; one facts section per subsystem, bytes identical (R12, R13) |

A split is safe only after the hidden state is explicit. The order inside each split is therefore: make inputs explicit (bitwise identical), move pure groups, move the rest.

### 3.3 One law, several statements

| Physics | Sites | One home |
|---|---|---|
| Water saturation, IF97 Region 1, steam ideal gas | `V3WaterProperties`, `WaterShare`, `WaterRegion1`, `TrainedPhysics`, `WaterSaturation` | `science/thermo/water` |
| Cubic roots and PR constants | kernel, `HydrocarbonModel.pureLiquidCompressibility`, kernel again at 786 | the kernel (D09, F19) |
| Pressure derivatives of the cubic | 4 sites | one kernel function (S08) |
| Rachford-Rice, tridiagonal sweep, bubble update | engine twice, `StillColumn`, `V3FeedFlash`, `V3TruncatedFlash`, auditor, initializer | a stage kernel in the math layer, with the Rust crate (C18) |
| Illinois root, series cell march | 3 and 4 copies in the heat laws | `science/math` (L-F9) |
| Port accounts and utility debt | `ColumnOperation`, `Stills` | `plant/port`, `plant/heat` (C6) |
| Ejector capacity and load | `EjectorSizing` against `SteamEjector`; `EjectorUnit` against `Ejectors` | `science/machine` (P3-01, P3-03) |
| Compressor discharge temperature | `CompressorSizing` against `FlowControl.Compressor` | the machine law (P3-02) |
| Counterflow exchanger | zone LMTD on the sheet, cell march in the world | measure, then one (P3-13, L-F12) |
| Still fill limit, 20 % spare, R, g, 101 325 Pa, air density | 2, 11, 3, 4, 3 and 3 statements | one constant each (P3-04, P3-10, P3-11, L-F13) |
| Pump shaft power for the driver | recomputed in `ShaftTrains`, `ElectricStores`, `InlineMachines` | the step result carries it (M-01) |

### 3.4 Control flow on strings and exceptions

- The column reads failure codes, audit families and rung labels as strings (S2 of the column review); the device screen parses the engine's English status with regular expressions (G-C18, checked); the network adapter decides what to throw from the prefix of the engine's `detail()` (S06, checked); tile types are switched as strings in 13 places (P3-07); driver status words are compared as strings (M-20).
- `IllegalArgumentException` means both "trial outside the property domain" and "bug" in the kernel, the engine and the fluid step (S07, S-13). A bug inside a residual is backtracked 24 times and reported as a stalled line search.

Both must become typed codes before any native boundary, because a native call returns a number.

## 4. Defects

| Id | What | Rule broken | Checked |
|---|---|---|---|
| D1 (column) | The C++ pass is on by default and uses the platform's `cbrt`, `acos`, `cos`, `log`: a jar's results depend on the machine that built it | determinism | yes (default `true`) |
| D2 (column) | The hydraulic correction runs under `System.nanoTime`: a slow machine publishes another pressure profile | determinism | yes |
| R5 | A GUI or instrument read materialises a certified island and books the span: accounts and the heat in transit depend in their last bits on when players look | determinism | mechanism yes |
| E10 | Shape fields 9 to 11 and the column's exchanger types are read with a bare `valueOf`: one unknown name throws through `readShape` ("Never throws") | saves: enum by name with an alias | yes |
| P3-06 | A built-in control channel's saved id is an MD5 of host and channel name; the name comes from an enum name and the nozzle order | saves: a fact that can move; no content hashes | hash yes |
| R20 | A world from before the envelope is reset, which discards facts (owner D4 at the time) | saves: never discard a fact | no |
| R26 | A generator edit runs a TP flash on the server thread inside the packet path, and again at apply | engine schedule | no |
| F1 | The column's identity omits the pair derivation it evaluates: a derivation change keeps cached column results and seeds current | revisions | yes |
| F3 | The column's steam enthalpy and saturation throw outside their data; the network continues them | thermo validity | range yes |
| C4 | Water of a feed above 647 K reads "below steam" and goes to the distillate | physics | yes |
| D3, D4 (column) | A first solve certifies at 1e-3 and a seeded one at 1e-8; the audit's material and energy limits are 1.0 against a Newton stop of 1e-8 | coherence | D4 yes |
| M-01 | The driver is charged a shaft power recomputed with k = 1.4, no wet factor, and for an in-line compressor a second efficiency: −11 % to +54 % against the work the gas gets | energy consistency | k = 1.4 yes |
| M-02 | A failed dry screw cannot be repaired and never reads FAILED | coherence | kit yes |
| M-03 | The overload and overspeed trips cannot fire; the guide says they do | dead code | motor yes |
| M-04 | An unpowered in-line pump runs every other slice (inferred) | physics | no |
| P3-01 | The sheet credits an ejector 2.4 times the world's capacity on 5 bar steam | sheet against world | structure yes |
| P3-04 | Still fill limit 0.70 in sizing, 0.75 in the run | sheet against world | yes |
| C5 | Sheet and world solve one design by two routes and two energy closures | sheet against world | no |
| D-01, D-02 (step) | The wall-branch chord rule is skipped on an inherited factorization; a heat curve can be flashed inside a residual | solver rule | no |
| L-F2 | One static calibration slot: towers on islands with different compound lists refit in turn | cost | yes |
| C10, C19 (coupling) | A still's readiness test materialises an island; the sheet's still throws when the pot loses its bubble point | schedule; hold by exception | no |
| G-C12 | Three client watches never ask again for a lost first state; the tank watch keys by position only | display | no |
| D14 (thermo) | The by-anchor crystal path builds another crystal than the engine's; production does not use it | latent | no |

Three more content hashes stand against the owner's rule and are state, not defects: the layout stamp (E20, checked), the certificate's graph digest (R11, checked) and the benchmark's provenance digests.

## 5. Optimization, in classes

**Free (results bitwise identical).** These need no owner decision and no reference move. The order is set by B1.

- Fluid step: delete the unused sparsity build (P-01); observations once per interval, not per step (P-02); one edge pre-pass per residual (P-03); a structure number in place of deep record equality (P-05); no graph copy per trial (P-06); the junction basis cached per structure (L-F3); one adjacency table (L-F5).
- Thermo: one set of temperature terms for the engine's four kernel workspaces (P20); a table of the distinct E-PPR78 powers (P21); no second evaluation in the derivative call (P23); no record per root selection (P27); PH and UV searches on an internal outcome with no text and no result graph (P24).
- Column: audit only a converged attempt (P2); the layout as int tables instead of ledger records and a matching per attempt (P3); array indices instead of hash maps in the assembler (P4); one workspace per solve (P5); the feed flash once per solve (P6); phase calls into caller-owned arrays (F10, F11).
- Runtime: the presented island cached per bucket (R1); one index per island per bucket (R2); constant menu parts cached (R4); sides indexed by pair (R23); motor binding on load, not every 100 ticks (R24).
- Machines: one output map and one pipe walk per dispatch (M-08); publish only the stepped train (M-09); arrays in `Run` (M-10); speed bisections only for a regulating driver (M-11).
- Equipment and client: each unit drawn once per geometry (E11); the unit cache without the heading (E12); one bounded cache type (E13, G-C22); the frame in one batch on the seven all-kit screens (G-C4); a column reused while its rows are equal (G-C5); ports gathered every 5 ticks (G-C13); the body region map event-driven (G-C14).
- Control and instruments: channel lists cached by bus revision (P3-18); the accessory store indexed by host and not dirtied every 5 s (P3-19).

**Small shifts (last digits, stated and re-recorded).** `saturated` by three square roots (P-04, about 4 ulp); a flat booking matrix with a topological sweep (L-F4, below 1e-14); a Newton for a vessel's height (L-F6, below 1e-15 m); one cubic solver (D09, F19); the lower-Gibbs root in closed form (P22, ties only); a symmetric fill of the fugacity Hessian (P26); one R (P3-11, L-F13).

**Owner options (references move by more).** In [SIMPLIFICATIONS_REVIEW.md](SIMPLIFICATIONS_REVIEW.md).

## 6. What the reviews corrected in the plan

REVIEW_PLAN sections 1 and 2 were wrong or incomplete in these points; [SOLVER_DECOUPLING_PLAN.md](SOLVER_DECOUPLING_PLAN.md) carries the corrected design.

1. The thermo core is two layers with material between them (S1, S2).
2. The equilibrium engine is the network's alone; the column needs only the kernel, the mixing rule, the pairs and water (S1). The column's port does not wait on the engine.
3. `StillColumn` runs on the network's adapter, not on the kernel directly (S8, checked).
4. The deepest column and network coupling is the column's energy books, which REVIEW_PLAN did not list (S8).
5. Islands never enter the shared queue; column and still jobs do, and stills are a third job family (S6, checked).
6. "No FMA" is the wrong rule: the Java kernel, `EnergyReference` and `SparseLuSolver` call `Math.fma` explicitly (S1, S3, S4, S5; checked). The rule is "no contraction, and a correctly rounded fused multiply-add where Java has one".
7. Java-path determinism is not only a math question: D1, D2, R5 and the wall-budget holds (R25) break it (S5, S6).
8. "One call per island step" is not the first fluid boundary; one pass per call is (S3). Since the owner's plan update of 2026-11-13 that boundary is built only if the gate of PHASE2_PLAN wave F passes, and the equilibrium engine stays in Java.
9. Equipment cannot sit above the plant layer as one module: design facts sit below it, geometry above (P1).
10. Four rows of the draft failure-mode matrix have nothing in the solve to drive them (S7).
