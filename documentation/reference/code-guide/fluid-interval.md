# The fluid interval and its booking

Up: [code guide](README.md). Each step of the interval: [fluid-step.md](fluid-step.md). The runtime that asks for an interval: [runtime-engine.md](runtime-engine.md).

<!-- j2a-packages: science/fluid/network/PassiveIntervalSolver.java science/fluid/network/SolidEventIntegrator.java science/fluid/network/ConservativeTransport.java science/fluid/network/InventoryEquilibrium.java science/fluid/network/VesselCrystals.java science/fluid/network/JunctionCrystals.java science/fluid/network/NetworkReduction.java science/fluid/network/PassiveNetwork.java science/fluid/network/PipeTransfer.java science/fluid/network/ScheduledTransfer.java science/fluid/topology/ science/fluid/SimulationTime.java -->

## Read this when

- An island is solved although at rest, or not solved although driven; an interval is refused (`Interval substep limit; no partial interval may commit`) or takes too many substeps.
- Mass, energy, components or solids are not conserved; a junction buffer creates composition; a boundary transfer is booked wrongly.
- A vessel's T, P or phases do not match what it holds; crystals or ice freeze, melt or deposit wrongly; a crystal melts (or does not) in a junction.
- A pipe settles, clogs or deposits wrongly; a closure is kept or lost across an interval start.
- An island's snapshot (`PassiveNetwork`) holds the wrong node, pipe, inventory or level height; a re-mint loses its junction states.
- Which blocks form an island; dead ends; a split at a generator or void.
- You change how process time relates to online ticks (`SimulationTime`).

## What it does

Physically, an island is a set of vessels, fixed boundaries (generators that only supply, voids that only receive) and small mixing junctions, joined by pipe runs that may carry a device. `TopologyCompiler` and `NetworkReduction` turn registered blocks into islands of runs; the runtime builds a `PassiveNetwork` snapshot of each. `PassiveIntervalSolver` advances an island over a requested interval: it refreshes every vessel once at the interval start (`InventoryEquilibrium`; crystal mass moves there, `VesselCrystals`), tests the island for rest, and otherwise takes adaptive backward-Euler steps (`PassiveStepSolver`), or hands an island monitored for solid events to `SolidEventIntegrator`, which locates closures within the interval. After each step `ConservativeTransport` books amounts, energy and solids exactly from the converged flows, a crystal arriving in a warm junction melting there (`JunctionCrystals`), and `InventoryEquilibrium` closes every vessel to what it holds. The interval's simulated seconds come from online ticks through `SimulationTime`. No Minecraft type and no wall-clock time; `checkpoint` is the caller's cancellation hook.

## Class map

Under `src/main/java/com/wormzjl/icheme/science/fluid/`. Lines at `d6f721b94`.

| Class | Role | Lines |
|---|---|---|
| [`network/PassiveIntervalSolver`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveIntervalSolver.java) | The adaptive BE controller over one interval: `solve` (with the coupled reads and port plans), `run`, `replayStart`, `coldRateSeed`, `carriedModes`, `carriedFlows`, `settlesWithin`, `flushTraces`; `Settings` (`defaults(time)`), `StageGuard`, `Result` | 553 |
| [`network/SolidEventIntegrator`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/SolidEventIntegrator.java) | The interval driver of an island monitored for solid events: settled beds and clogged filters as located transitions (`Transition`), still pipes (`still`), the closure carry (`carried`, `carriedWithoutRate`, `drawnThrough`), `mobility`, `failed` | 398 |
| [`network/ConservativeTransport`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ConservativeTransport.java) | Exact booking from solved flows (`reconstruct`, `reconstruct0`): `BoundaryTransfer` with its `Kind`, `EdgeHeat`, the junction pin (`pinnedFlows`), emptying vessels, the melt (`Melted`, `Projection.melted`, `reach`, `melt`, `meltingSolve`), the rigid flag, `Workspace` | 881 |
| [`network/InventoryEquilibrium`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/InventoryEquilibrium.java) | A vessel's T, P, phases and crystals restated from its inventory: `refresh`, `conversions`, `completed`, `passes`, `close`, `split`; `Refreshed`; `CLOSURE` | 304 |
| [`network/VesselCrystals`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/VesselCrystals.java) | The crystal part of a vessel's refresh, the one place a vessel's crystal mass moves: `settle`, `competes`, `precipitateDiameter`; nested `Settled`, `Joint`, `JointClosure` | 443 |
| [`network/JunctionCrystals`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/JunctionCrystals.java) | Package-private: which junctions sit above a crystal's melting point (`of`, once per step on the start state), `melts`, `split` (per kilogram of a stock's solids), `kept`; nested `Split` | 111 |
| [`network/NetworkReduction`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/NetworkReduction.java) | Dead-end pruning, split at fixed boundaries, static drives (`drives`), the rest test (`atRest`, `REST_RESOLUTION`, `resolutionFlow`, `WALL_RESOLUTION`), `reopened`, `viscosity`, `ambient` | 358 |
| [`network/PassiveNetwork`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveNetwork.java) | The immutable island snapshot: `Reservoir` (any node), `NodeKind`, `Pipe` (with `Identity`), `Inventory`, `PhasePort`; `mintJunctions`, `sizeJunctionHoldups`, `withJunctionStates`, `sameStructure`, `sameLines`, `emptyVessel` | 373 |
| [`network/PipeTransfer`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/PipeTransfer.java) | Gross per-pipe transport history, forward and reverse, by phase; not owned stock | 118 |
| [`network/ScheduledTransfer`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ScheduledTransfer.java) | Boundary withdrawal or injection rates at a vessel, booked with the pipe flows; only tests create one | 25 |
| [`topology/TopologyCompiler`](../../../src/main/java/com/wormzjl/icheme/science/fluid/topology/TopologyCompiler.java) | Block ids to runs and islands, the pump-cycle check, `Kind`; `compile(nodes, links, retainedPipes)` keeps a check-valve pipe as a node of its run | 101 |
| [`SimulationTime`](../../../src/main/java/com/wormzjl/icheme/science/fluid/SimulationTime.java) | The time factor: one online tick is `factor / 20` s of process time (`seconds`, `ticks`, `tickSeconds`), `playSeconds`, `ONLINE`, `DEFAULT_FACTOR`, `MINIMUM_FACTOR`, `MAXIMUM_FACTOR` | 60 |

## Main flow

`PassiveIntervalSolver.solve(initial, duration, settings, checkpoint, startingStep, reads, ports)`, normally through the runtime's `RetainedSolver`, which first calls `replayStart` (a job depends only on the committed interval) and starts at `min(initialStep, duration)`:

1. `PassiveNetwork.sizeJunctionHoldups` sizes any unsized junction buffer. `PassiveStepSolver.ports` states a running column's or still's outlets and sets their valves' targets for the slice; `couple` plans the heat sides once ([fluid-laws.md](fluid-laws.md)).
2. `NetworkReduction.reopened` reads every transport closure open for the interval, except a filter stopped at capacity; every later path reads that graph. `decideInlets` runs `InletPhase.decide` once on it and hands the refused movers to the step solver (`refuseInlets`).
3. `InventoryEquilibrium.conversions` refreshes the stocks that may convert (a crystal that may freeze or melt, sizes to merge). Only when nothing converted does `NetworkReduction.atRest` test the start; at rest, `restInterval` returns the identity of the committed graph and no Newton runs.
4. Otherwise `InventoryEquilibrium.completed` finishes the interval's one refresh (`Refreshed`). An island goes to `SolidEventIntegrator.solve` (with the committed graph, whose closures the carry reads) when `SolidMobility.monitored` holds, or when the immobile-viscosity setting is below 0.002 Pa s and a node holds an aqueous phase; else to `integrate`.
5. `run` loops: step `h` truncated to the remaining time; `StageGuard.checkRate` at t0; `PassiveStepSolver.solve(start, h, ...)` with the accepted endpoint modes (`carriedModes`; a rejected attempt never replaces them). At each step's start `implicit.plan` plans the local heat actuators from the last accepted step's flows and `GasBlanket.plan` the pads. While no step of the structure was accepted (`backwardEulerCold`), `start` is `coldRateSeed`: the junction states of one rate solve of the port graph, each junction's own solids scaled with its fluid mass; if it fails, the step starts from the balance.
6. Acceptance: no vessel's pressure (over max(100 Pa, P0)) or mass (over the larger of its mass and `STATE_MASS_REFERENCE_DENSITY` × its volume) changed by more than `STATE_CAP`. The step grows by `clamp(0.9 sqrt(cap/change), 0.5, 2)`. A refused change that halving did not reduce (at least 0.9 of the change at twice the step) is an algebraic jump and is accepted; a step at or under `transitionFloor` passes the cap too.
7. After a step: a reopenable run (`reopenable`) forces a re-solve; `flushTraces` removes components below `TRACE_FLUSH_FRACTION` of a node; `atRest` at the step end ends the interval early. A solid transition `checkFilters` finds at a step's end rejects the step and is searched onto by halving down to `transitionFloor` = max(1e-6 s, 1e-9 of the interval), except a deposition `settlesWithin` accepts.
8. `Result`: end states, `endpointFlows` (transported flows), modes, heads, boundary transfers, `observations`, `PipeTransfer` samples.

## Contracts

- **Conservation by construction.** Owned amounts and energy come only from `ConservativeTransport`. `StepObservations.checkConservation` audits components, total energy (internal energy plus filter cake energy; no `m g z`), solids per population, junction buffers (their own term) and boundary transfers. A crystal a junction melts is an internal conversion: its mass leaves the solid balance and enters the component balance, its offsets the energy balance (`Projection.melted`). No stream or ledger carries potential energy.
- **No partial interval commits** (`Interval substep limit; no partial interval may commit`); `StateChangeRejection` (private) is the controller's refusal of a converged step.
- **Junction buffers are not inventory.** Nothing is booked when a buffer is minted, sized or destroyed. One rule (`sizeJunctionHoldups`) sizes a junction wherever it gets a state: at a mint (`mintJunctions`, which starts from `PassiveStepSolver.balanceStarts`), at a carry across a same-structure replacement (`withJunctionStates`) and at a solve's entry for a graph built directly; a heat side's passage is not counted. Between sizings the mass pin keeps the buffer at exactly `m_J`. Total mass is exact; only composition goes unbooked (each buffer life puts `m_J (w_install − w_end)` into the vessels).
- **Crystal mass moves only in a vessel's refresh, and in a junction above the crystal's melting point**, decided once per step on the junction's start state. Elsewhere within a step a crystal is an inert population.
- **Still pipes.** A pipe at rest carries no solids, and the sign of a roundoff flow names no donor. `SolidEventIntegrator.still` reads a flow of the interval-start rate solve within `PassiveStepSolver.NEWTON_TOLERANCE` of its largest flow (at least 1 kg/s) as still; where that rate solve fails, the flows the last accepted step left (`carriedFlows`) by the same bound; a structure with nothing carried keeps the sign reading. Decided once, where the closures are decided: a still pipe raises no solid transition for the whole interval. No threshold on a real flow fires inside a step.
- **A closure stands while nothing moves its pipe.** Every interval start reads closures open; the solid events decide them again at t0 from one rate solve of the port graph (`PassiveStepSolver.portRate`). A pipe the drive moves is decided by its flow (`failed`). A pipe the committed graph left closed both ways that nothing moves keeps its closure while an end still holds what closed it (`carried`); `mobility` is read both ways at a vanishing flow. Without a rate solve, `carriedWithoutRate` reads still pipes from the carried flows, and a closure on a line a mover draws through (`drawnThrough`) reads open. A carried closure is declared at t0 like any other.
- **Empty vessels.** `PassiveNetwork.emptyVessel` holds no moles and stands at the ambient state (`NetworkReduction.ambient`: `initialNitrogenCharge(1, 298.15, AMBIENT_PRESSURE)`). A vessel that runs dry within a step is solved as a junction holding its stock (`emptyingGraph`) and ends empty; it keeps its cooling tower, whose sprays' loss the pin reads on the booked flows. A vessel whose releases take all it holds and that nothing reaches ends the step empty.
- **Rest.** `atRest` needs no drive above `REST_RESOLUTION` (D42's `resolutionFlow`, a LIQUID end holding both liquids read as its mixed stream) and every vessel holding fluid on its wall row within `WALL_RESOLUTION`; a vessel whose kettle pool heats, a releasing open vessel and a venting blanket are never at rest. A configured valve drives forward only: a pressure valve by the smaller of its static drive and its excess over the set point, a flow valve by its static drive, nothing at a zero target; a pad valve passes only while its vessel lacks gas.
- **Rigid vessels.** A closed vessel becomes rigid when a booked step leaves it taut while it holds fluid (`ConservativeTransport`, `Inventory.withRigid`); the flag is never cleared; an open vessel is never rigid.
- **Every rebuild of an inventory keeps its geometry.** `Inventory.with(moles, internalEnergy, solids)` keeps `volume`, `levelHeight`, `open`, geometry, pool and blanket: `InventoryEquilibrium.refresh`, `VesselCrystals`, the step's emptying and splits, `ConservativeTransport.reconstruct0`, `flushTraces` and the runtime's `IslandCertificate.graphAt` all go through it. A new rebuild site that calls the constructor instead resets a drum to `LEVEL_HEAD_HEIGHT`. `Inventory.sameContents` compares contents alone (what a topology event conserves); `equals` includes the geometry.
- **Validation.** `Inventory`, `Pipe` and `ScheduledTransfer` refuse non-finite or negative values.

## Laws and constants

**Island snapshot** (`PassiveNetwork`). All SI; mass flows are signed, positive from `Pipe.first` to `Pipe.second`. `NodeKind`: `RESERVOIR` (a vessel with inventory), `JUNCTION` (a buffer), `GENERATOR` (fixed state, supply only), `VOID` (fixed state, receive only), `PORT` (a vessel held fixed for one rate solve; never a world object). `Inventory(volume, moles, internalEnergy, solids, levelHeight, open, geometry, pool, …)`: the conserved truth of a vessel. `volume` is the capacity; for an open vessel the volume up to its fill height. Every reading of the wall goes through `wallCapacity()` (infinity for an open vessel). `levelHeight` is the height a level head stands over (`LEVEL_HEAD_HEIGHT` for a one-block tank, a drum its own height). `moles` excludes crystal mass. For a junction, `internalEnergy` holds `m h`. `Pipe(id, first, second, sections, control, blockedDirections, filter, firstPort, secondPort, forbiddenDirections, …)`: `blockedDirections` is a 2-bit mask (1 forbids first to second, 2 the reverse), the solid closures an interval start reopens; `forbiddenDirections` the same bits for a check valve; `effectiveBlockedDirections` the two together; both are part of the pipe's identity, `flowKey` and the certificate signature. `Pipe.heat` is a `HeatLaw` (null for a plain run); `Pipe.withControl` a real valve at its planned target. `Pipe.Identity.line()` is the identity without a flow valve's set point: `PassiveStepSolver.sameLines` compares graphs by it, so a new target keeps the committed interval as a warm start; certificates and workspace keys read the full identity. `sameStructure(a, b)`: the same pipe identities and node ids, in order. `withJunctionStates(graph, from, model)` takes every junction's state from the node of the same id in `from` and sizes its buffer on that state.

**Junction buffer**: `m_J` = `holdupTau(time)` × the largest over its connections of rho × the smallest area × the velocity limit, on the junction's state ([fluid-step.md](fluid-step.md) for the rows).

**Crystals melting in a junction** (`JunctionCrystals`; read by `StepEquations` and `ConservativeTransport`, so the Newton point states what the booking books). A junction is a fitting full of fluid with no refresh of its own. Above a pure crystal's melting temperature (`NetworkCrystals.Crystal.meltingTemperature(P)`) none of it is stable beside any fluid, so what the junction receives and holds of it melts at once. It arrives as its component with the crystal's own enthalpy (its stored `c (T − 298.15) + P v` plus its energy offset), so the fusion heat is charged to the junction. In the rows (`junctionInflow`) the melting share of each arriving solid stream enters the component and enthalpy sums and leaves the solid moments; the stock melts the same way (`heldMelt`, `heldMeltEnergy`, `heldKept`). A rate solve melts nothing. In the booking, `reach` limits the melt to the junctions the step's flows can bring the population to; `meltingSolve` solves each population first, on a matrix whose melting junctions take none of it from a solved donor, books what those donors deliver as the component, and solves the fluid components last; every quantity stays linear in its own right-hand side, so the booking is exact.

**Crystals and ice at a vessel's refresh** (`VesselCrystals`). At each interval and solid-event segment start a vessel's refresh equilibrates its crystals with its fluid: fugacity equality, the crystal amounts extra unknowns of the vessel's closure (`Joint`), at fixed volume while the wall is taut and at the ambient pressure while it is slack. The latent offset and the ambient's work on a moving bag are booked as an energy-only boundary transfer. Frozen mass enters at `precipitateDiameter` and merges to one size per solid. Ice Ih is one of the crystals on every wet model; ice and another crystal coexist.

**Booking** (`ConservativeTransport.reconstruct0`): with the flows fixed, one sparse matrix per step (upwind, implicit donor) solved for every component with one factorization. Junctions are pinned at `m_J`. Phase-port draws are booked as fixed donors; a component that would go negative is re-booked implicitly. Every vessel is then closed to its booked inventory (`InventoryEquilibrium.close`, Newton to `CLOSURE`) in its own pass loop (`passes`). A heat-exchanging connection's heat goes into the receiver and out of the island as a `HEAT` boundary at the connection's id (energy only), a kettle's pool's at its key; a mover's or turbine's work as `SHAFT`; an open vessel's releases as fixed amounts and `VENT` and `SPILL` boundaries; a tower's exchange as `ATMOSPHERE`; a trace flush as `FLUSH`.

**Deposition at a step's end** (`PassiveIntervalSolver.settlesWithin`). A DEPOSITION transition seen at the end of a step of `h` s closes at that end, without the halving search, when `h v_s ≤ D` (D the bore from the pipe's largest area, `v_s` the deposition velocity over `SlurryTransport.Settings.suspensionMultiplier`): a flow below the deposition velocity keeps its solids that long anyway. Filter and viscosity events keep their search.

**Interval controller**: first-order backward Euler (owner decision D4). `Settings.defaults()` and `defaults(time)` (first step and step cap scaled by the time factor; the attempt cap stays); tests that want a small first step use `FluidTestSupport.COLD_START_SECONDS`.

**Process time** (`SimulationTime`): only process durations pass through it; schedule quantities stay in ticks. `ONLINE` (factor 1) is the engine's default in tests and bit-identical to `ticks / 20.0`; `DEFAULT_FACTOR` is the `fluid.timeScale` default and the hold-fuzz harnesses'.

| Constant | Meaning | File |
|---|---|---|
| `STATE_CAP`, `STATE_MASS_REFERENCE_DENSITY` | the step's state cap | `PassiveIntervalSolver` |
| `TRACE_FLUSH_FRACTION` | the trace flush | `PassiveIntervalSolver` |
| `MAXIMUM_TRANSITION_REJECTIONS` | transitions searched per interval | `PassiveIntervalSolver` |
| `REST_RESOLUTION`, `WALL_RESOLUTION` | the rest test's drive; the rest gauge on the wall row's slack branch | `NetworkReduction` |
| `CLOSURE` | a vessel's closure tolerance | `InventoryEquilibrium` |
| `MINIMUM_FACTOR`, `MAXIMUM_FACTOR`, `DEFAULT_FACTOR` | the time factor's range and default | `SimulationTime` |

## Formats

The snapshot (`PassiveNetwork`) is written by the runtime's codec as an island unit's state and its vessels' inventories as facts ([runtime-saves.md](runtime-saves.md); numbers in [FORMATS.md](../FORMATS.md)). `Inventory.levelHeight`, `open`, the geometry, `blanket` and `rigid` are facts of each vessel.

## Tests and gates

| Rule | Test |
|---|---|
| Islands, dead ends, splits | `TopologyCompilerTest`, runtime `DeadEndPruningTest`, `FixedBoundarySplitTest` |
| Rest | `RestResolutionTest`, runtime `RestIslandTest`, `RestGaugeTest`, `VesselRebuildTest`, `FlowControlTest.aFlowValveRestsWhenNothingDrivesThroughIt` |
| Conservation | `ConservativeTransportTest`, `SolidConservationTest`, runtime `TraceFlushLedgerTest` |
| A vessel's closure and refresh | `BackwardEulerStepTest`, `TraceTruncationStepTest`, `LiquidFullBubblePointTest`, runtime `VesselPhaseSeedTest` |
| Crystals and ice; the junction melt | `PrecipitatedSolidsTest`, `CrystalDepositionIslandTest`, `CrystalFlushBudgetTest`, `WaterIntoCryogenicMethaneTest`, runtime `CrystalIslandRuntimeTest`, `LiquidCo2LetDownTest`, `HoldClassWorldsTest.aCrystalArrivingInAWarmJunctionMeltsThere` (prints `MELT_GATE`) |
| Still pipes and the closure carry | runtime `HoldClassWorldsTest` (`aStillPipeBesideATankHoldingCrystalsDepositsNothing`, prints `STILL_PIPE_GATE`; `aTankDrainingDownAWideLineBelowItsSuspensionVelocityClosesItAtTheIntervalStart`; `aClosureNothingMovesCarriesAcrossTheIntervalStartInATankDrainingToAVoid`; `aClosureNothingMovesCarriesAcrossTheIntervalStart`), `PumpCavitationTest` |
| Where a deposition closes | `SolidClosureFeasibilityTest`, `SolidTransportAcceptanceTest` |
| Junction buffers; unbooked composition | runtime `VirtualJunctionHoldupTest`, `MixedGasJunctionTransientTest`, `JunctionSeedingGateTest.aBufferMintedOnWaterAndWashedByNitrogenCreatesNoMoreWaterThanItWasMintedWith` (prints `D46_GATE`) |
| A carried buffer's size | runtime `JunctionSeedingGateTest.aJunctionCarriedAcrossAReplacementHasItsBufferSizedOnWhatItHolds` |
| Empty tanks | `EmptyNetworkTest`, runtime `EmptyTankFillsTest` |
| Level height kept through a solve | `LevelHeadTest.aTallerVesselHasItsOwnLevelHeadAndKeepsItThroughASolve` |
| Substeps and transients | `PassiveTimeRefinementTest`, `TransientQualificationTest`, `SharedSourceDepletionQualificationTest`; the chain-100 replay `fluidSolverRegression` |
| Process time | `SimulationTimeTest`, runtime `TimeScaledSolverSettingsTest` |
| Scheduled transfers | `ScheduledTransferTest` |

Shared helpers: `FluidTestSupport`, `ConservationAssertions`, `PortFixture`. Gradle: `fluidScienceTest`; `fluidSolverRegression` replays the `chain-100` fixture against its captured states (properties `fluidRegressionReferences`, `-Capture`, `-Mode`, `-TraceCutoff`); the plain `test` task excludes `**/fluid/benchmark/**`.

## Traps

- **`PassiveNetwork` is not passive.** It holds pumps, compressors and valves; read the name as "island snapshot".
- **`Reservoir` means any node**: use `kind()`, `junction()` and `fixed()`; `fixed()` is true for `PORT` as well.
- **`Inventory.internalEnergy` of a junction is `m h`**, not U (`PassiveNetwork.mintedJunction`).
- **An unsized junction is an all-zero inventory** (`unsized` tests exactly that), so a sized junction always holds positive mass.
- **A vessel off its wall is not at rest.** A closed bag charged below the ambient misses its row too (its slack branch stands it at the ambient).
- **A dry end holds no fluid whatever solids it holds**: a vessel whose fluid is at most `HOLDS_NOTHING` of its capacity gives nothing, its column is the other end's.
- **`ScheduledTransfer` has no runtime producer**; only tests create one.
- **`initialNitrogenCharge` takes a volume (m³) first**, not an amount; it is a placement initializer only.

**Snapshot:** `d6f721b94`, 2026-11-15.
