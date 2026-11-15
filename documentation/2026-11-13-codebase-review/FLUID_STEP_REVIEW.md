# Fluid step solver review (S3)

Base `259d22a2c`, 2026-11-13. Read-only review; no test or Gradle run; no times measured (counts only).

Read in full: `science/fluid/network/PassiveStepSolver.java` (PSS, 5 752 lines), `PassiveNetwork.java` (PN, 371), `NetworkReduction.java` (NR, 358), `science/fluid/solver/PhaseLayout.java` (PL, 674), `SparseNewton.java` (SN, 357), `science/fluid/linalg/SparseLuSolver.java` (LU, 306), `SparseMatrix.java` (66), `science/fluid/topology/TopologyCompiler.java` (TC, 101).
Signatures only (not read): `FluidThermodynamics`, `ConservativeTransport`, `PassiveIntervalSolver`, `InletPhase`, `PhaseDraw`, `ControlConstraints`, `InventoryEquilibrium`, `HeatCurve`, `FlowControl`, `HeatLaw`, `ShaftPlan`, `FluidCheckpointCodec`, EJML's `LuUpLooking_DSCC`. The code guide chapter was not used.

## 1. Summary

What the area does:
- One island step is an active-set loop of "passes". Each pass freezes the phases, the device modes and the closures, and solves one backward-Euler system (node balances, phase equilibrium, one hydraulic row per connection, one row per actuator) with a finite-difference Newton on a sparse LU.
- A converged pass is booked by `ConservativeTransport.reconstruct`, checked for phases, and gated on the residual of the booked point. A pass that fails or changes a phase, a mode or a closure is solved again.
- A pass without a carried point starts from a second solver (`BalanceNet`): junction pressures that balance every junction's inflow on tabulated densities.

Five largest problems:
1. One class holds 13 stages. `solve` (PSS:366-765) has 19 `continue` branches that restart a pass. The inner classes `Equations` and `BalanceNet` read about 25 mutable solver fields as hidden inputs, three of them one-shot flags (PSS:218, 2795, 3159).
2. Each law is stated two or three times (Newton row, `BalanceNet`, rest test), and the step holds three numerics stacks: `SparseNewton` on EJML's LU; `BalanceNet`'s own Newton on an unpivoted banded elimination with a Gauss-Seidel fallback (PSS:3742, 3950, 3673); four scalar inversions with fixed 50 to 80 iterations.
3. State is object graphs. `PassiveNetwork` is copied and validated again for every plan, flash trial and emptying pass; structure identity is deep record equality; every residual evaluation allocates states, transport records and small arrays. Nothing can cross a native boundary as it stands.
4. Wrong-way imports: `FluidThermodynamics` → `PhaseLayout` + `SparseNewton`; `PassiveNetwork` ↔ `PassiveStepSolver`; `TopologyCompiler` → `NetworkReduction` → `PassiveStepSolver`; the plant's planners (`ports`, `couple`, `pools`, `plan`) are methods of the solver; `PhaseLayout.AMBIENT_PRESSURE` is read by client, equipment and flowsheet code.
5. `IllegalArgumentException` is the solver's signal for "trial outside the property domain" (caught at about 20 sites), and the same type reports layout bugs. The Jacobian exists only as differences of the residual code. Both block a native port; the first masks defects.

Ten changes, ranked by gain over cost:
1. P-01: delete the unused sparsity build in `differentiateEntries` (PSS:5540). One line, bitwise.
2. P-02: build observations for the interval's last accepted step only (PSS:758 against `PassiveIntervalSolver`:352). Bitwise; removes a flash per meter and up to two PH equilibria per valve per accepted step.
3. P-03 + P-04: one edge pre-pass per residual (flow, cap and drawn stream are computed 3 to 5 times per edge today); `saturated` by three square roots in place of the software `pow`.
4. P-05 + P-08: a structure number in place of `List<Identity>` equality; remove the vestigial `junctionDonorFirst` from the workspace key.
5. D-01, D-02: keep the wall branches with the factorization; sample both ends' heat curves before the pass.
6. S-01 to S-07: split PSS into 13 files by stage, pure function groups first; one immutable `SolveConstants` in place of the hidden fields; the planners move to the plant layer.
7. S-08: one statement per law (filter cake, valve restriction, pipe inversion, mode transitions, incidence tables).
8. S-13: a typed domain refusal in place of `IllegalArgumentException`. Needed before any native port.
9. Section 3.5: flat `IslandTables`, `PassTables` and an evaluation workspace in Java first; then the native boundary at one pass per call, after the thermo kernel exists in Rust.
10. X-01, X-02 (owner options): one-way ends and the pump suction limit as laws in the rows; together they delete two latches, one mode and about 530 lines.

## 2. Module map

### 2.1 Stages of the step as the code stands

| Stage | Lines (PSS unless named) | Reads | Writes |
|---|---|---|---|
| Slice plans (column ports, exchanger coupling, pools, flue gas) | `ports` 2563-2597, `couple` 2484-2552, `pools` 2619-2644, `flueGas` 2456-2469; called from `PassiveIntervalSolver`:91-92 | graph, `PortPlan` map, `ExchangerRelay.Read` map, carried flows | a new graph (valve targets, generator states, heat-law inputs) |
| Step plan (fired coil, air cooler) | `plan` 2421-2449; `PassiveIntervalSolver`:270 | graph, start flows, `sideCurves` | a new graph |
| Solve entry | 366-381 | graph, `acceptedOpenings` | `inputStates`, `suctionFloors`, `zoneEdges`, `drawnStreams`, `vapourLocked`, suction readings (1940-1966), `heldOpenings`, `standing`, `series` |
| Start modes and closures | 387-474; `initialModes` 3187-3197, `closeDeadHeads` 3008-3038, `closeIllegalStarts` 3053-3086, `relieveSealedPockets` 3135-3147 | carried modes, `keepOpen`, `sliceRefusals` | `modes`, `boundaryClosed` |
| Balance start | `balanceStart` 3366-3401, `BalanceNet` 3404-3901, `supplierSeeds` 3218-3278 | graph, modes, closures, suction readings | junction states, `balanceLawFlows`, modes, closures, `balanceCache` |
| Seeds | `initialPhaseSeeds` 1447-1466, `arrivingPhases` 1540-1611, `flashSeeds` 2779-2792, `startPoint` 1697-1760 | graph, start flows, carried point | seeds, `flashRestated` |
| Pass: equations | `Equations` constructor 4471-4626, `buildSparsity` 5056-5106 | graph, seeds, modes, closures, supports | layout tables, `initialPoint`, `columnEnds`, `segments`, `retainedPressures`, `rowWeights` |
| Pass: Newton | 528-540; SN:174-256; rows 5304-5513, 5631-5694; block Jacobian 5531-5617 | equations, workspace | `x`, factorization, `builtWall` |
| Pass: failure branch | 541-584 | last iterate | modes, seeds, `emptying`, `foldedPhases`, `proposal` |
| Pass: converged checks | 586-714 | `x`, states, flows | modes, closures, seeds, carried point, `proposal` |
| Booking, gate, polish | 699, 715-735; `polishRefusedPoint` 812-850 | states, flows, draws | projection, polished point |
| Ledger and accept | 738-762; `checkConservation` 4194-4232 | projection | carried point, `acceptedPipes`, `lastSolve`, `Result` |
| Observations | `observe` 2023-2066, 2080-2141; records 5707-5751 | accepted point | `PipeObservation` list |
| Heat inside the rows | `sideHeat` 2367-2371 and 5435, `sideCurve` 2389-2405, `poolHeat` 4045-4048 (row 5359), `moverPower` 1901-1906 (row 5419) | start states, heat laws | `sideCurves` |

### 2.2 Files I would draw

| File (package `science/fluid/step` unless named) | From PSS lines | About |
|---|---|---|
| `StepSolver` (the pass loop) | 348-765, 775-850, 1259-1363 | 700 |
| `CarriedPoint`, `SolveConstants` | fields 76-113, 203-337; 294-323; 3165-3179 | 250 |
| `DeviceModes` | 867-932, 1878-1884, 3092-3147, 3187-3197, 3916-3945 | 300 |
| `MoverSuction` | 933-1221, 1246-1258, 1828-1981 | 480 |
| `OneWayClosures` | 119-202, 1797-1827, 2989-3086, 3966-4027 | 350 |
| `PortStreams` (pure functions of model, state, port, volume) | 1982-2014, 2142-2359, 2659-2694, 4233-4276 | 420 |
| `StartSeeds` | 1433-1796, 2762-2883 | 520 |
| `BalanceStart` (`BalanceNet`, supplier seeds, banded solve) | 3148-3965 | 800 |
| `PhaseActiveSet` (folds, corrections, dry and emptying vessels) | 1222-1245, 2724-2759, 2884-2988, 4028-4185 | 400 |
| `StepEquations`: `EquationLayout` | 4286-4626, 5056-5106 | 400 |
| `StepEquations`: `PortDraws` | 4627-5052 | 430 |
| `StepEquations`: `Rows`, `BlockJacobian` | 5107-5705 | 600 |
| `StepObservations`, `StepLedger` | 2023-2141, 4186-4232, 5707-5751 | 220 |
| plant layer: `HeatPlanning`, `PortPlanning` | 2360-2658 | 300 |
| `network/IslandGraph` (records of PN) and `JunctionHoldups` (PN:60-143) | | 371 |
| `topology/GraphReduction` (NR:39-81) and `network/RestTest` (NR:90-357) | | 358 |
| `Ambient`, `WallLaw` (PL:329-416) and `NodeLayout` (rest of PL) | | 674 |
| `topology/DeviceKind` (TC:13-18) | | 6 |

### 2.3 Imports that cross the wrong way

| From | To | Where |
|---|---|---|
| `fluid/thermo/FluidThermodynamics` | `fluid/solver/PhaseLayout`, `SparseNewton` | `FluidThermodynamics`:5-6, 1106-1110 (the junction split runs the network's Newton) |
| `fluid/thermo/NetworkCrystals` | `PhaseLayout.AMBIENT_PRESSURE` | `NetworkCrystals`:3, 94, 113 |
| `PassiveNetwork` | `PassiveStepSolver` | PN:101 (builds a solver to mint junctions), 105-107, 150, 219 |
| `topology/TopologyCompiler` | `network/NetworkReduction`, `PipeResistance` | TC:3-4, 54, 73; NR in turn calls PSS statics at NR:94-100, 144, 149, 234-235 |
| client, equipment, flowsheet, runtime | `PhaseLayout` | 20 uses of `AMBIENT_PRESSURE`, 4 of `atmosphere`, 3 of `totalAmounts` |
| `fluid/solver/SparseNewton` | `fluid/SolverOwnership`, `diagnostics`, `thermo/ThermoHold` | SN:3-7, 94-119 (blocks a move to `math`) |
| 36 files | `TopologyCompiler.Kind` | 350 references to an enum nested in the compiler |

No file of this scope imports column code. The column's only names here are `PortPlan.COLUMN_SENSOR` (PN:298; PSS:2590-2594) and `operation.column()` (PSS:2524, 2538).

## 3. Answers to the brief

### 3.1 Unknowns, rows, Jacobian

Variable vector (PSS:4489-4515): node blocks, then one law flow per connection (`edgeOffset`), then one head per non-passive connection (`controlOffsets`), one loading per filter (`filterOffsets`), one water flow per `BOOT_HC` end (`weirOffsets`).

Node block (PL:103-112): per present component, in the slot order liquid, aqueous, vapour: the total amount over `amountScale` for its first supported slot, `ln(n_ik/n_i,ref)` for each further slot; then `ln(T/350)`, `ln(P/1e5)`; then three solid moments where the island carries solids. A two-phase node of C components with full support has 2C + 2 (+3) unknowns. A fixed node and a dry vessel have none.

Rows:
- Vessel (PL:441-451, 508-529): C component balances, energy with the wall's work, the wall row `max(taut, slack)`, three solid rows, one equilibrium row per further slot.
- Junction (PL:472-487): C − 1 mixing rows weighted by `(dt/m)(m/dt + Q)`, continuity (or the retained pressure of a sealed pocket), enthalpy, amount normalization, solids, equilibrium.
- Connection (PSS:5458-5513): `(driving − loss(q_law)) / pressureScale`, or `flow` for a closed passive run; the actuator row by mode (seven forms, 5492-5512); the filter loading row (5474); the weir complementarity row (5311-5321).

Jacobian (PSS:5056-5106): a node column touches its own block, every row of each incident connection, and the whole block of each neighbour; exact structural zeros are removed for non-amount columns of nodes with bulk ends only (5094-5104); a suction limit adds its supplier's columns (5081-5082). Entries are one-sided differences at 1e-6 of a scale: the block sweep (5531-5617) on phase tangents, or the coloured whole-island sweep (SN:280-295) when a perturbed node leaves the domain or the island has a weir (5539).

Flat form: `x`, `f` as `double[]`; `nodeOffset[N+1]`; per node `slotIndex[C×3]`, `amountIndex[C]`, `support[C]`, `componentScale[C]`, `amountScale`, `energyScale`; `edgeFirst[E]`, `edgeSecond[E]`, `portFirst[E]`, `portSecond[E]`, `mode[E]`, `closed[E]`, `columnEnd[E]`, `pressureScale[E]`, `controlOffset[E]`, `filterOffset[E]`; incidence in CSR (`nodeEdgeStart[N+1]`, `nodeEdge[2E]`); sections in CSR; `oldAmounts[N×C]`, `oldEnergy[N]`; transport as struct-of-arrays `moles[N×C]`, `mass`, `volume`, `density`, `viscosity`, `specificEnthalpy`, `velocityLimit`, `solidMoments[N×3]`, with a second block of 3N rows for the phase streams.

### 3.2 What a step allocates

Per residual evaluation (`Equations.residual`, PSS:5263):
- the result `new double[size]` (5264); SN adds the right-hand side (SN:202) and a clone of `x` per line-search trial (SN:211);
- per decoded node: `new double[3][n]` (PL:294), a `State` with its arrays and phases, a `Transport` record with `state.totals()` (5252), three more at a phase-port node (5255-5256);
- per node's rows: four small arrays (PL:510, 518); a junction adds `state.totals()` (PL:474), `solidReference.clone()` (PL:485), `solidIncoming.clone()` (5665);
- per connection: a `Transport` and its mole array for a priority mixture on each `drawn` call (4942-4951), a `boolean[3]` per `held()` (5244), two lambdas in `columnEndPressure` (2161);
- a tower node: a flow array over all connections and a list view (5361, 5368).

Per pass: an `Equations` with about 40 arrays; a `PhaseLayout` with nine arrays per node; `List.copyOf` of modes and seeds (4473-4474); two `WorkspaceKey` and one `PassKey`; a sparsity build (finding P-01); a new validated graph while a vessel empties (510).

Per solve: `identities(graph)` at about ten sites (finding P-05); suction arrays (1942-1943); a new graph per flash trial (2869-2871).

Census of the file: 146 `new double[`, 49 `new ArrayList`, 58 `.clone()`, 19 streams, 11 `new PassiveNetwork(`.

A workspace would keep: six vectors of `size`; the decode buffers; the transport arrays; the accumulators the equations already keep (4466-4467); the Jacobian entries and LU (kept today in SN:127-131).

### 3.3 Special cases

| Item | Where | Physical statement | Absorbed by a row or a law? |
|---|---|---|---|
| `boundaryClosed` latch, five ways to set it, never cleared in a solve | 441-456, 650-667, 3008-3086, 3390-3398, 724-730; reopened only by the interval (162-202, 106-109) | A one-way end passes nothing backwards | Yes: finding X-01 |
| `allowedRestart` | 775-783, 664-665 | The same one-way end has two roots; start on the allowed one | Goes with X-01 |
| Modes `MOVER_LAW`, `CLOSED`, `PUMP_SUCTION_LIMIT` | 889-908, 5499-5505 | A check valve; a pump delivers what its suction feeds | Suction limit: finding X-02. The check valve stays a mode |
| `vapourLocked`, one fact per solve | 1038-1050 | NPSHA ≤ 0 at no flow | Goes with X-02 |
| `cavitates` retry | 1246-1258 | As above, found on a failed iterate | Goes with X-02 |
| Modes `VALVE_OPEN`, `VALVE_REGULATING`, `CLOSED` | 909-930, 5506-5507 | A regulator holds its target or stands open | Candidate: `min(target − q, head)` as one row. Not proposed: no numbers yet |
| `VALVE_CONFLICT`, `heldOpenings`, `standing`, `series` | 378-381, 616-627, 867-875, 5508 | Two regulators on one flow: the lower target wins | No: a property of the control set |
| `INLET_WRONG_PHASE`, `sliceRefusals` | 80-104, 450-451 | A mover refuses a supply of the wrong phase for a slice | No: an owner rule (D6) |
| `columnEnds`, frozen per pass | 4460, 4591-4624, 1821-1827 | Which fluid stands in a vertical line | No. The line's content is a state the model does not carry. An averaged column errs by up to 19.5 kPa on a 4 m water and gas riser |
| `junctionDonorFirst`, frozen per pass | 4437, 4582-4590 | None any more: the junction rows read the live donor (5386-5395) | Delete: finding P-08 |
| `segments`, frozen per pass | 4351, 4634-4646, 4896-4903 | A port draws its phases by priority up to what the vessel holds | Already a law with a frozen kink; kept |
| Wall row | PL:372-377, PSS:5157-5163 | A bag with a stiff wall | Already one semismooth row |
| Weir row | 5311-5321 | Water over a weir | Already one complementarity row |
| `retainedPressures`, pockets | 4569-4580, 3110-3122 | A sealed fitting keeps its pressure | Already a row replacement |
| `dry` vessel, `HOLDS_NOTHING` | 4095, 4176 | A vessel with no fluid has no pressure of its own | No: an owner rule (D54) |
| `emptying` vessel as a junction | 4106-4118, 4863-4884, 4156-4166 | A vessel cannot give more than its stock | Candidate: the bulk draw capped at stock over `dt`, as phase ports are (4688-4701). Open question 6 |
| Trace support, `promoted`, `foldedPhases`, `proposal` | 491-499, 1328-1353, 2746-2759 | The phase set of each node | No: belongs to `PhaseActiveSet` |
| `rateOnly` | 327 and about 20 branches (103, 372, 384, 455, 694, 1405, 1542, 2034, 3176-3177, 4485, 4511, 4694, 4720, 5446, 5473, 5666, 5683) | The island's flows with its vessels held | Overlaps `BalanceNet`: finding X-03 |
| `lawFlowsFromBalance`, `flashRestated` | 3159, 2795 | Where the next pass starts | Explicit pass arguments: finding S-03 |
| `HOLDUP_TAU` buffer | 64-70 | A junction's mixing lag (numerical) | Already a row state |

### 3.4 The thermo calls (the network's thermo port)

Inside the Newton, per residual evaluation:
- `model.prepare(T)` once per node whose temperature moved (PSS:5222);
- `model.adoptingState(T, P, nV, nL, nA, prepared)` once per node (PL:313): one equation-of-state evaluation per held phase, giving `ln φ`, volume, enthalpy, internal energy;
- `viscosity.liquid` per liquid slot and `viscosity.vapor` (4267-4275), `velocityLimit` (5253); `phaseStream` three times at a phase-port node (5256);
- per connection: `columnDensity` (5484), `portHead` twice (2189-2203, reading `liquidMass`, `phaseMass`, `heavierLiquid`, `phaseVolume`), `molecularWeight`. No flash, except the lazy heat curve of finding D-02.

A chord iteration costs one evaluation per line-search trial (SN:209-215). A fresh Jacobian by blocks costs, per node: one `model.tangents` (5556), one `prepare` for the temperature column (5563), and per local column one `adoptingState` on the tangents (PL:313) and one `transport` (5568).

Outside the Newton, per pass or per solve: `flashTP` (14 sites), `equilibriumPH` (6), `fluidPH`, `equilibriumAtVolume` (1238), `junctionSplit` (1601, 2859), `phaseCheck` (2752), `folded` (11 sites), `heatCapacityRatio` (1979), `isothermalAcousticBound` (5284), `initialNitrogenCharge` (NR:347), `traceTruncation`, `standIn` (PL:612); through other classes `HeatCurve.sample`, `InventoryEquilibrium.split`, `ConservativeTransport.reconstruct` and `repartition`, `InletPhase`, `ReleasePressure.lowest` (about 24 PH equilibria per new supplier state, PSS:1160-1163).

So the port has two halves. The kernel half (prepare, state at T, P and amounts, tangents, transport properties) is called N times per evaluation and must be native for any native residual. The engine half (flashes and closures) is called a few times per pass and can stay in Java behind the pass boundary.

### 3.5 The boundary for a Rust port

"One call per island step" is not reachable first: a step is up to `min(512, 16 + 2N + 2E)` passes (PSS:500), and between passes the code runs the equilibrium engine, the booking and the phase checks.

| Stop at | Crosses per call | Java callbacks that remain | Assessment |
|---|---|---|---|
| Linear algebra | CSC values and a right-hand side, several times per Newton iteration (SN:205, 225, 245) | none | Small gain unless B1 shows the LU share is large (`luFactorNanos`, LU:106). The LU is EJML 0.44, not owned code: a bitwise port must copy its pivot rule; LU:180 and 194 use `Math.fma` |
| Newton | `x`, pattern | `residual`, `differentiateEntries`, `maximumStep`, `project`, `linearisationHolds`, `linearising`, `linearised`, `residualNorm`, `differenceScale`, the checkpoint (SN:14-72) | Ten upcalls per iteration for 150 lines of logic. Not worth a stage |
| One pass (residual, Jacobian, Newton, LU for one frozen active set) | island tables (kinds, elevations, incidence, sections, vessel geometry, old amounts and energies); pass tables (supports, modes, closures, `columnEnds`, segments with capacities, `retainedPressures`, `pressureScales`, `rowWeights`, start phase streams, `x0`); solve constants (suction floors, zone edges, rise densities, `kappa`, wet shares, NPSH designs, heat curves as tables, pool heat, releases, emptyings) | none, if these are native or tabulated: the thermo kernel half of 3.4; the mover laws (`FlowControl.Mover` and `MachineLaw` curves, `Npsh`); `HeatLaw.heat` on `HeatCurve`; `VesselGeometry.head`; `TowerExchange.at` (PSS:5368); `JunctionCrystals` splits (5400) | The recommended first fluid stage. Returns `x`, a status code, the last iterate, counters. The pass loop, the engine and the booking stay in Java |
| Whole step | as above plus the equilibrium engine and `ConservativeTransport` | none | Last; needs S1's and S4's code native |

Before any of it, in Java: flat tables shared by the Java path and the native path (3.1); a status code or typed refusal in place of exceptions (S-13); the checkpoint as a counter the native loop polls; `DeterministicMath.exp`, `log`, `pow` reproduced (PL:262-311, PSS:2007, 3471, 3554).

### 3.6 Entry points of the column, the still, heat units and shaft trains

- Running column: `ports(graph, plans, checkpoint)` PSS:2563 (from `PassiveIntervalSolver`:91; plans built in `ColumnOperation`:851-878 and `Converters`:314-379); `Pipe.Identity.sameLine` PN:291-298; `couple` branches on `operation.column()`, `read.gives()`, `read.ceiling()` PSS:2512, 2524-2538.
- Still: `PortPlan` from `Stills`:279-285 through `ports`; the fired pot as `Pool.fired()`, skipped by `pools` at PSS:2623 and read by `poolHeat` 4045 and `boilUp` 4055.
- Heat unit: `couple` 2484, `pools` 2619, `flueGas` 2456, `plan` 2421; in the rows `sideHeat` 5424 and 5435, `poolHeat` 5359; `reading(partner)` 2647 builds a second solver on the partner island's model.
- Shaft train: `ShaftPlan.apply` in `IslandCoordinator`:612 rewrites the connection's `FlowControl` before dispatch; in the step the law is read through `mover()` 1914, the `MOVER_LAW` row 5499, `moverPower` 1901 and 5419 (a turbine's shaft power at 1903), `readSuctions` 1940, `kappa` 1976.
- Also: `GasBlanket.plan` and `blanketInflows` (`PassiveIntervalSolver`:270-271; PSS:112, 4667); `refuseInlets` PSS:82; `reopenNext` 109; `replayStart` 294 (from `RetainedSolver`:62); `markBackwardEulerAccepted` 280.

### 3.7 Sites that assume a subcritical state

| Where | Assumption |
|---|---|
| PL:26-29, 127-133 | Three fixed slots named liquid, vapour, aqueous; reference order liquid, aqueous, vapour; a slot is active by its volume |
| PL:118; PSS:5294 | "Nearly liquid-full" read as vapour volume under 1 % |
| PSS:1143-1158 | `release`: bubble pressure of the liquids; compounds above their critical temperature are left out (`ReleasePressure.leaveOutGases`) |
| PSS:1077-1102, 988-996 | A liquid pump's floor needs a liquid label (`drawnLiquid`); none at a `VAPOR` port |
| PSS:1190-1200, 1208-1213 | The floor is a bisection for the first vapour in a PH equilibrium |
| PSS:1229-1245 | A rigid vessel "flashes" when its seed holds no gas |
| PSS:1977 | `kappa` is 1.4 for a liquid mover; gas elsewhere |
| PSS:2080-2103 | Valve cavitation and flashing by a vapour pressure |
| PSS:2148-2150, 2189-2203 | Spray riser and level head weigh a "liquid" (`liquidDensity`, `liquidMass`) |
| PSS:2761, 2815-2820, 2985 | `VAPOUR_BITS`, `laidOutFor` test phase bits 1, 2, 4 |
| PSS:3442, 3574 | A gas junction's density scales with pressure (ideal gas) in the balance |
| PSS:3461-3487 | Balance tables: a junction is "liquid", "vapour" or both; a liquid flashes below its bubble pressure |
| PSS:3887-3900 | `mixed`: water alone has no two-phase TP state at its vapour pressure |
| PSS:4019-4026 | A `VAPOR` port is dry when a slack vessel holds no gas |
| PSS:4055-4063 | `boilUp` divides by a latent heat (gas enthalpy less liquid enthalpy) |
| PN:29; `PhaseDraw` (not read) | Ports `VAPOR`, `LIQUID`, `BOOT_WATER`, `BOOT_HC` select phases by kind |

A dense single phase fits the layout as a one-slot node (PL:591: one active slot gets every component). The sites above are where the code asks which slot it is.

## 4. Findings

Proof names: "lane" = the final lane's references (chain-100 exact, the 33 junction lines, `MIXED_GAS_COST`); "quick check" = the 200-seed check; tests by class name.

| Id | File:line and symbol | What it is | Proposed change | Class | Lines removed or moved | Risk | Proof |
|---|---|---|---|---|---|---|---|
| P-01 | PSS:5540 `differentiateEntries` | Builds `sparsity` (one `BitSet` of `size` bits per column, 5058, then 5105) and never reads it: 5541-5617 use the workspace's `entryOffsets` and `entryRows`. `Equations` is new each pass (522), so every pass that refreshes a Jacobian pays it | Delete the line | PERF, bitwise | 1 removed | None: `columnRows()` (5191) still builds it for a new pattern (SN:178) | `BlockJacobianEquivalenceTest`; lane |
| P-02 | PSS:758 `observe`, `TowerExchange.readings` | Run for every accepted step; `PassiveIntervalSolver`:352 keeps the last step's only. Per step: per connection two lists and a record (2031-2033), `e.drawn` three times on the same arguments (2027, 2028, 2033), a `flashTP` per observation point (2043), up to two `equilibriumPH` per flowing valve (2060, 2089-2093) | Observe on request, once per interval, from the retained last solve (745), before `releaseLastSolve` (`PassiveIntervalSolver`:301) | PERF, bitwise | 20 moved | The decode cache is released at 761; the read must come first | `FlowmeterObservationTest`; lane |
| P-03 | PSS:5373, 5376, 5419, 5462-5464, 5361 | `flow(edge)` (4991 → `cap` 4967 → `capLimit` 4993 → `saturated` 2004) runs three times per connection per evaluation; `drawn` five times; a tower node recomputes every flow | One edge pre-pass into arrays (flow, donor stream, suction stream), then the node accumulation in the same edge order (4366-4368) | PERF, bitwise | 40 changed | The block sweep (5572-5579) must refresh the edge cache per column | lane; `BlockJacobianEquivalenceTest` |
| P-04 | PSS:2004-2014 `saturated`, `saturationSlope` | `(1 + r^8)^(-1/8)` through the software `pow` | `s = 1/sqrt(sqrt(sqrt(1 + r^8)))`; slope `s^9`. `Math.sqrt` is correctly rounded on every runtime | PERF, shift at most about 4 ulp (1e-15 relative) on transported flows; exactly 1 where `1 + r^8` rounds to 1, as today | 4 changed | Exact references move in the last digits and need a re-record (owner rule 2 of 2026-11-13) | lane re-record with old and new values |
| P-05 | PSS:1365-1369 `identities`; PN:288-312; PSS:1264-1282 `WorkspaceKey` | `identities(graph)` allocates E records at 283, 315-317, 388, 455, 484, 1451, 1544, 3064, 3067, 3166; `sameLines` allocates `line()` copies (PN:292-304) and compares records deeply; the key hashes the list at 529-534 every pass | A structure number set where the graph's identities change; compare and hash a `long` | PERF, bitwise if the number changes exactly when the lists differ | 60 removed | The "same line" rule for a flow valve's target, a turbine and a column-worked valve (PN:291-304) must define the number | `RetainedSolverTest`; counters `workspaceBuilds`, `workspaceReuses` |
| P-06 | PN:30-47; PSS:363, 510 (4106-4118), 2448-2643, 2805, 2871, 3337, 3861 | Each `new PassiveNetwork` copies three lists and fills three hash sets; `withPorts` and the other `with…` run the `Pipe` constructor and `coalesce` again (PN:341-351). `netInflow` builds a graph per trial, up to 13 per flashed junction (2842-2850); `emptyingGraph` every pass | A trusted internal constructor; state overrides as a `states[]` argument in place of a new graph | PERF, bitwise | 50 changed | None found | lane |
| P-07 | section 3.2 | Per-evaluation allocation | Evaluation workspace with the buffers of 3.2 | PERF, bitwise | 150 changed | Buffers must not escape an evaluation (the rule at 4461-4465) | lane; allocation counts from B1 |
| P-08 | PSS:4437, 4582-4590, 528, 5702 `junctionDonorFirst` | Read only by the workspace key and by `carries`. The junction mixture reads the live donor (5386-5395; `int mixed=donor` 5388). A start-flow sign change at a junction therefore changes the key and loses the retained Jacobian. The comment at 4453 records the same loss for `columnEnds` | Remove the field, its key component and the second clause of `carries` | PERF. Entries bitwise. The Newton's path moves where a workspace is now reused: results within the tolerance 1e-9 of the scaled rows | 30 removed | The cycle key loses a component: two passes that differ only in donor signs now count as a repeat (529) | quick check; campaign counters |
| P-09 | PSS:1105, 1135, 3424 | Three static synchronized `WeakHashMap`s keyed by `State` objects, shared by every worker | Fields of the per-island solver | PERF, bitwise (pure functions of the key) | 10 changed | The mint's throwaway solver (PN:101) no longer shares tables with the first step (comment 3422) | `BalanceTablesTest`; lane |
| P-10 | PSS:533-534; SN:127-131; LU:84-96 | Each new structure gets its own `Shared` (difference arrays and an EJML storage of `4·nnz + n`); up to four dt-keyed and four structure-keyed workspaces stay per island | Option: one `Shared` per solver | PERF (RAM against one refactorization when a structure returns) | 15 changed | More Jacobian builds on alternating modes | B1 heap per island; `luFactorizations` |
| P-11 | PSS:1781-1783, 3326-3328 | Pipe law inverted by 50 fixed bisections; reached per connection from 1689, 2810, 2877 (inside the flash march), 3080 | Use `runFlow`'s bracketed Newton (3606-3614) | PERF; start flows move in the last digits | 10 removed | Path shift only; starts decide nothing | quick check |
| P-12 | PN:256; PSS:4070-4072 | `holdsNoFluid` clones the mole array to test for all zeros; reached from `boundaryAllowed` (3991-3995, 4009, 4019-4021), which most pass-level loops call per connection | `Inventory.holdsNoAmounts()` on the field | PERF, bitwise | 3 changed | None | lane |
| P-13 | NR:347, 352; PN:158; PSS:755 | `ambient(model)` builds a nitrogen state per call | Keep one per model | PERF, bitwise | 5 changed | None | lane |
| P-14 | PSS:5335, 4871 | Scheduled transfers scanned per node per evaluation | A per-node index, order kept | PERF, bitwise | 10 changed | None | lane |
| P-15 | PSS:531-534 | `workspaces` evicts the oldest inserted key; a `get` does not reorder, so the step size in use can be evicted | Reinsert on use | PERF | 2 changed | None | `RetainedSolverTest` |
| P-16 | TC:42-74; NR:44 | Boxed `Long` maps and streams per node | `int` tables, only if S6 shows the compile count matters | PERF, bitwise | 60 changed | Order of islands must stay (NR:54-55) | `TopologyCompilerTest` |
| D-01 | PSS:4311, 5152-5153, 5161; SN:146, 154, 195 | Decision A423 (PL:384-388) refuses a chord step once a vessel's wall branch differs from the Jacobian's. The branches at the build live in the `Equations` object; the factorization lives in the workspace and is forked to later passes and steps. A pass that opens on an inherited factorization has `builtWall == null` and skips the test. Cost only: the stall is caught at SN:233 or SN:250 | Keep the wall branches beside `jacobianPoint` in the workspace, or decode `built` once | DEFECT (rule not applied; no wrong root) | 10 changed | None | `SparseNewtonTest`; a `PhaseLayoutTest` case of a full tank at the kink on a carried Jacobian; `newtonRefreshesFailed` |
| D-02 | PSS:5435, 2367-2371, 2389-2405 | `sideHeat` inside a residual can sample a heat curve (a flash march, 2401) when a flow reverses and the other end's curve is not kept yet, with a no-op checkpoint. The class states "no nested TP/UV/PH flash" (16) | Sample both ends' curves when the pass is built | DEFECT (cancellation; a refusal there is read as a trial outside the domain) | 10 changed | `HeatCurve.sample` not read | add a `PassiveStepSolverTest` case: a heat side whose flow reverses within a step |
| S-13 | SN:16, 237, 283, 290; PSS:364, 1044, 1153, 1198, 1210, 1231, 1251, 1497, 1642, 2045, 2115, 3307, 3483, 3849, 4766, 4867, 5548, 5610 against PL:83-84, 93-95, 117, 514; PSS:376, 396, 401; PN:32-45 | One exception type means both "trial outside the property domain" and "argument or layout bug". A bug raised inside a residual is backtracked 24 times and reported as a stalled line search | A `DomainRefusal` type (as `ThermoHold` is), thrown by the thermo and layout code for the first meaning only | DEFECT (masking) and the precondition of 3.5 | 40 changed | Thermo throw sites are S1's and S2's | `SparseNewtonTest`; lane |
| S-01 | PSS:17-5752 | One class, 13 stages | The files of 2.2, pure groups first (`PortStreams`, `StepObservations`, `BalanceStart`, planners), `StepEquations` last | STRUCTURE | about 5 000 moved | Inner classes read outer fields (S-02) | lane; `PassiveStepSolverTest`; `BlockJacobianEquivalenceTest`; `RetainedSolverTest` |
| S-02 | PSS:224-263, 327; read at 4421, 4485, 4667, 5419, 5427, 5435, 5499, 5505, 5683 and 3508-3533 | Per-solve constants are mutable fields that `Equations` and `BalanceNet` read when a row is evaluated | One immutable `SolveConstants` built at 366-381 and passed in | STRUCTURE | 120 moved | `balanceStarts` (3339-3340) overwrites the same fields for the mint | lane |
| S-03 | PSS:218 (set 665, read 5198, cleared 522); 3159 (set 677, read and cleared inside `startPoint` 1703, which 1451, 1544, 3067 and 5197 call); 2795 (set 2788, read 1730); 106 (read 455) | One-shot flags consumed by whichever call comes first | A `PassStart` argument | STRUCTURE | 30 changed | Today's order is implicit | quick check |
| S-04 | PSS:679, 684, 697, 729, 739; 302-303, 469 | Nine carried fields assigned together, five times verbatim | A `CarriedPoint` record | STRUCTURE | 10 long lines removed | None | lane |
| S-05 | PSS:812-850 against 587-611, 684-719 | The polish restates the pass loop's acceptance checks | One `examine(point)` returning a verdict | STRUCTURE | 35 removed | The order of checks is the cycle guard's order | lane; `equationGatePolishesAccepted` |
| S-06 | PSS:2421-2658 | The plant's planners are solver methods; `reading` (2647-2653) builds a solver to read a stream | Move to the plant layer on `PortStreams` | STRUCTURE | 230 moved | S8 owns the target | lane |
| S-07 | PSS:2189-2359, 2659-2694; static twins at 2191, 2215, 2240, 2276 for NR | Pure stream functions as instance methods, with static copies for the rest test | `PortStreams` statics | STRUCTURE | 330 moved | None | lane |
| S-08 | Filter cake law at PSS:3498, 5472, NR:238. Fixed-opening restriction at 3608, 5508. Pipe inversion at 1781, 3326, 3606, 3629. Mixing-enthalpy bisection at 3298-3309, 3887-3900. Mode transitions at 880-932 and 3695-3739. Incidence tables at 120-134, 1732-1740, 3903-3909, 4539-4546 | Laws and tables stated two to four times | One function each | STRUCTURE where the arithmetic is kept; PERF for the inversion | 120 removed | The balance's transitions differ on purpose in places (3690-3694) | `BalanceTablesTest`; quick check |
| S-09 | PSS:385, 1390, 1449, 3219, 3817, 4195 against 824, 1343, 1356, 1379 | The component count as `hydrocarbon.componentCount() + 1` and as `componentCount()` | One accessor | STRUCTURE | 10 changed | Their equality is not checked here | lane |
| S-10 | section 2.3 | Cycles | The junction closure moves beside `InventoryEquilibrium`; `Ambient` leaves `PhaseLayout`; NR splits into graph reduction and rest test; PN:60-143 moves to `JunctionHoldups` | STRUCTURE | 450 moved | 27 files import `PhaseLayout` constants | lane; `TopologyCompilerTest`; `PhaseLayoutTest` |
| S-11 | TC:13-18 `Kind` | An enum saved by name, nested in the compiler | Own file; constant names unchanged | STRUCTURE | 6 moved, 350 references | None for saves: constants keep their names | compile; corpus images |
| S-12 | PN:202-220, 270-279, 313-341 | `Inventory`: 12 components, 9 constructors. `Pipe`: 13 components, 9 constructors, 8 `with…` copies | A builder; with P-06 | STRUCTURE | 40 removed | Test fixtures use the short constructors | compile |
| S-14 | SN:3-7, 94-119 | The Newton names fluid types (`SolverOwnership`, `SolverDiagnostics`, `ThermoHold`) | A generic refusal cause and a diagnostics interface, if it moves to `math` | STRUCTURE | 30 changed | Only worth it if the column will share it (question 2) | `SparseNewtonTest` |
| X-01 | PSS:3008-3086, 3390-3398, 650-667, 775-783, 724-730, 162-202, 106-109; `PassiveIntervalSolver`:279-281 | Expected: a one-way end (void, generator, blocked way, dry end) passes nothing backwards and opens as soon as the drive turns. Code: a latch set at five places before and during the passes, never cleared inside a solve; the interval refuses the step and repeats it to reopen a run | The edge row states it, as the wall and weir rows state their kinks: `q` where the allowed-direction drive is not positive, else `driving − loss(q)`, the branch frozen inside a Jacobian build | PHYSICS, owner option | about 230 removed | Effect at usual states (flow the allowed way): none, the same row. At a one-way end near zero drive: the run opens inside the step in place of one step later; the flows concerned are those under the reopen band of 1 Pa (PSS:115): up to about 0.17 kg/s of water or 0.01 kg/s of nitrogen at 1 atm in a 1 m run of 0.05 m (from NR:228). Risk: the slope kink at zero flow, three orders between a liquid and a gas (PSS:2995-2997), is why the closures are taken before the pass | quick check, at most three forms; then one campaign |
| X-02 | PSS:5505, 1038-1050, 1246-1258, 903-908, 3508, 3723-3735, 4373-4382, 5081-5082 | Expected: a pump whose NPSHA falls to its NPSH3 loses head and delivers what its suction feeds. Code: the mode `PUMP_SUCTION_LIMIT` with its own row, a vapour-lock fact per solve, a retry on a failed iterate, anchors in the balance, extra sparsity | The rise is the law's times a factor of NPSHA over NPSH3 (1 above the knee, 0 at the floor), as a centrifugal pump's head already takes a gas factor (1956) | PHYSICS, owner option | about 300 removed, one `Mode` constant | Effect at usual states (NPSHA above NPSH3): none. Between NPSH3 and the breakdown line (0.85 of it, PSS:956): the flow moves by the knee's width, a few per cent. At the floor: the same limit. Risks: a steep factor is a stiff row where seeds 181, 575 and 896 held (1025-1032); `Mode` is written by `FluidCheckpointCodec`:358, so a removed constant needs an alias if that section is saved by name | quick check; `PumpCavitationTest` |
| X-03 | PSS:351-365 `solveRate`, `portRate`; 3404-3901 `BalanceNet` | Two quasi-steady solvers of the island on its start states: the full equations with vessels held as ports, and the pressure-only balance | The balance serves the interval's guard and the solid closure check; `rateOnly` and its 20 branches go | PHYSICS, owner option after S4 | about 150 removed | Effect: guard and seed decisions only; no state. Risk: the cold rate seed (`PassiveIntervalSolver.coldRateSeed`, not read) starts a step from the rate point | quick check |

## 5. Open questions for the main thread

1. LU for the native path: copy EJML 0.44's up-looking LU bit for bit (pivot rule included), or accept a stated shift under the owner's result rule? LU:180 and 194 use an explicit `Math.fma`; Rust's `f64::mul_add` gives the same double, so "no FMA" in REVIEW_PLAN section 2 must mean "no contraction by the compiler".
2. Will the column share `SparseNewton`? It is built for finite differences and a general sparse pattern; the column has a banded analytic Jacobian. If not, only the LU kernels belong in `math`, and S-14 is not needed.
3. `BalanceNet` solves an unpivoted banded system (PSS:3950-3965). Should it use the column's `V3BandedPivotedSolver` once that is in `math`, or stay as it is because its matrix is a weighted Laplacian?
4. Is `FlowControl.Mode` saved by name in a facts section or only in state (`FluidCheckpointCodec`:358, 593)? X-02 removes a constant.
5. Analytic Jacobian: the block sweep already differences on phase tangents (PSS:5523-5529). Is an analytic assembly of the node blocks from those tangents wanted before the port, so the native code does not carry the difference machinery?
6. An emptying vessel (PSS:4106-4118): may a bulk end be capped at the vessel's stock over the step, as a phase port is (4688-4701), in place of the junction form? It needs the owner's view of D54.
7. `invalidPumps` examines `PUMP` and `COMPRESSOR` only (TC:84). Are `EJECTOR` and constructed machines meant to be exempt from the closed-loop rule?
8. The three static caches (P-09) are shared across islands and worlds. Is sharing between the mint's solver and the first step's (PSS:3422) a measured need?
9. B1: please report, per island step, the counters `residualEvaluations`, `jacobianBlockBuilds`, `luFactorNanos` against the property time, and the heap held by `workspaces` and `structures` (P-10). They decide the order of 3.5 and P-10.
10. REVIEW_PLAN section 1, item 8, counts 100 `new double[]` in PSS; the file has 146 (two-dimensional forms included).
