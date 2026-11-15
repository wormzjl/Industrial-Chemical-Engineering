# B1 profile: fluid step, column, shaft trains (Java baseline)

Batch `2026-11-13-codebase-review`, package B1, 2026-11-13, branch `claude/codebase-review-bench-930e08` at `5f61d3139` (0.30.0 code). A performance task: times are recorded; every timed run ran alone after `check-machine.ps1` (free RAM 20–23 GB, no other busy JVM). JDK 21.0.11, G1, 16 threads (AMD family 26). Column on the Java path (`icheme.native.thermo.batch=false`). Harness: `tools/codebase-review-bench/`. Run folders: `tmp/codebase-review-bench/<run>/` of this worktree (not committed).

## Summary

| Scenario (run folder) | Wall | CPU | Allocated | GC pauses |
|---|---|---|---|---|
| Fluid `mixed100`, 100 islands, 60 + 60 s (`fluid-mixed100`) | island step: 28.4 ms median, 45.9 ms p95 (899 steps, 3 substeps each) | 27.8 ms a step | 60.7 MB a step (54.6 GB in 60 s) | 0.51 % |
| Column `medium_crude`, cold (`column/cold-medium`) | 0.196 s a solve (307) | 0.190 s | 204 MB a solve | 2.1 % |
| Column `medium_crude`, warm +2 % (`column/warm-medium`) | 0.095 s (632) | 0.092 s | 104 MB | 2.5 % |
| Column literature, cold (`column/cold-literature`) | 0.208 s (289) | 0.201 s | 229 MB | 2.4 % |
| Column literature, warm +2 % (`column/warm-literature`) | 1.370 s (44) | 1.333 s | 1,339 MB | 1.7 % |
| 20 shaft trains, islands awake (`machines-cert-false`) | 153 µs an island solve; 3.40 ms a 100-tick cadence, of which 0.33 ms outside the solves | | 157.6 kB a solve | 0.56 % |
| 20 shaft trains, islands certified (`machines-cert-true`) | 11.1 µs a 100-tick cadence (0.6 µs a train) | | 62 B a tick | 0.22 % |

Fluid server thread: 0.025 ms median, 0.93 ms p95 engine time a tick; 917 of 1,200 ticks idle. A first cold column solve in a fresh JVM: 0.87 s (medium), 0.94 s (literature).

Time shares (the table "Time share by kind" below; first mod frame decides, a JDK collection, stream, boxing or string top frame is bookkeeping):
- Fluid step: thermo kernel 41 %, linear algebra 38 %, residual and Jacobian 14 %, flash 1 %, bookkeeping 2 %.
- Column: linear algebra 52–63 %, thermo kernel 13–17 %, the degree-of-freedom ledger 5–17 %, other bookkeeping 6–12 %, residual and Jacobian 5–7 %, flash < 1 %.
- Trains: bookkeeping (maps) 71 % when certified; awake, the island solves take 90 % of the wall.

The five largest costs:
1. **Column banded LU**: `V3BandedPivotedSolver` 34–40 % self (`solve` 22–35 %, `scaleColumns` 7 %). About 0.11 s of a 0.2 s cold solve. The Rust port's stage (b) target.
2. **Fluid PR kernel and its workspace**: `PengRobinsonKernel` 32 % self (`correctExcess` 16 %, `evaluatePrepared` 5 %, `Workspace.<init>` 2.6 %). `PengRobinsonKernel$Workspace.<init>` (lines 1813–1872) is 34 % of the step's bytes: about 21 MB of `double[]` a step, a new workspace per evaluation.
3. **Fluid sparse triangular solves (EJML)**: `TriangularSolver_DSCC` 22 % self, half of it `searchNzRowsInX_DFS`, the symbolic reach recomputed at every solve. `SparseLuSolver$Storage.factor0` allocates 1.8 GB a minute.
4. **Column degree-of-freedom ledger**: `V3DegreeOfFreedomLedger.augment` (line 369) 11–16 % of column CPU, with a `HashMap` of `UnknownId` records (`UnknownId` and `HashMap$Node` together 7–10 % of the column's bytes). A structural audit that repeats per Newton iteration.
5. **Column warm route on a wet seed**: the literature seed carries free water (sum 39.3 mol/s, one wet tray). `retargetWarmSeed` refuses it, the warm pass is skipped and the strict (1e-8) classical chain runs: 183,786 checkpoints and 4 Newton iterations, against 30,194 checkpoints for the learned cold route (`lnn/requested-state/hyd`) and 8,635 for medium's warm pass (`warm/aggressive`, 3 iterations). The same entry with no seed (`OFF_FAST`, closure 1e-3) takes 249,386 checkpoints on the literature input and 258,256 on medium. `ColumnUnit.solve` (the simulator and the bench) keeps a wet seed; `ColumnSolve` (the running column) drops it and solves with no seed. Probe: `warm-probe/warm-probe.txt` (counters; its wall times are 2–3× the bench's and are not claims).

Allocation is about 1 GB/s in every busy scenario; with G1 it costs 0.5–2.5 % in pauses, so it is a RAM-bandwidth and cache cost rather than GC time. 86 % of the fluid step's bytes and 72–76 % of the column's are `double[]`.

Retained heap (histograms after a full GC):
- 20 trains on 20 one-pipe islands (47 components): 28.7 MB over the rig's baseline, 1.4 MB a train with its island; `double[]` 19.8 MB of it (31,184 arrays, about 1 MB an island: the island's retained solver and thermo workspaces). The 100 shaft units and stores are under 0.05 MB.
- Fluid `one` (one island: 1,102 devices, 100 reservoirs): mod objects 1.9 MB (FluidView, FluidNetwork$Controls, FluidDeviceBlockEntity, FluidDeviceSpec, PhysicalFluidTopology$Device: about 0.6 kB a device together); all `double[]`, `long[]`, `int[]` of the server 21.4 MB, an upper bound of 19 kB a device. Histogram taken at 33 min, `fluid-one/histo-one.txt`.
- Fluid `mixed100`: heap after the last collection rose from 736 MB at the fixture's start to 1,654 MB at the end of the warm-up (100 islands, 1,981 reservoirs, 8,722 pipes): an upper bound of 9 MB an island, old-generation garbage included. The histogram planned after this window missed the JVM's exit.

Not measured or limited:
- The default fluid profile `one` measures until 200 intervals on its island; once the island certified at rest the count stopped and the run would have ended only at the GameTest timeout. Stopped at 33 min (its JFR and histogram were dumped first); the fluid numbers are from `mixed100` (60 + 60 s, as the brief's window requires).
- The awake-trains recording has 339 CPU samples where 3,000 were due: JFR rarely sampled the bench thread inside the island solve. Its time shares are low-confidence; its counters (solve wall 90 %, 153 µs a solve) stand.
- The trains rig is the JUnit rig (synchronous solves, electric stores set to need no power, no Create coupling, no pump health), ticked back to back; per-cadence costs are the comparable numbers.
- Native (C++) column path not measured; the Java path is the Rust port's baseline.
- No retained heap per column solve (no column is retained between solves in these scenarios).

Rust toolchain on this machine: `rustup target list --installed` gives `x86_64-pc-windows-msvc` only (rustc 1.99.1). `zig` and `cargo-zigbuild` are not installed. Nothing was installed.

JFR note: the reader leaves out the allocation samples taken inside JFR's own start-up; such a sample carries the weight of every byte the thread allocated during the warm-up (60–73 GB in the column runs). With them left out, the JFR weight agrees with the benches' ThreadMXBean totals to 0.1 % (cold medium: 62.51 against 62.56 GB).

## Time share by kind

Share of JFR CPU samples on the working threads in the measured window (fluid: the two solve workers and the server thread; column and trains: the bench thread). Rules in `JfrSummary.java`: a JDK collection, stream, boxing or string top frame is bookkeeping; otherwise the first mod frame's class and method decide; `DeterministicMath` and `DeterministicPow` frames pass to their caller.

| Kind | Fluid mixed100 | Column cold medium | Column warm medium | Column cold literature | Column warm literature | Trains awake (low samples) | Trains certified |
|---|---|---|---|---|---|---|---|
| Linear algebra (banded LU, EJML sparse LU) | 38.1 % | 57.0 % | 51.8 % | 57.0 % | 63.1 % | 3.2 % | 0.1 % |
| Residual and Jacobian assembly (incl. `PhaseLayout`) | 13.6 % | 6.7 % | 5.0 % | 6.2 % | 6.5 % | 18.0 % | 0.3 % |
| Thermo kernel (PR, mixing, viscosity, water, steam virial) | 40.9 % | 13.2 % | 12.7 % | 14.2 % | 17.3 % | 0 | 0 |
| Flash and phase identification | 1.4 % | 0.2 % | 0.7 % | 0.2 % | 0.1 % | 0 | 0 |
| Bookkeeping: maps, records, streams, boxing | 2.3 % | 9.4 % | 11.7 % | 8.9 % | 6.5 % | 56.6 % | 71.2 % |
| Bookkeeping: column degree-of-freedom ledger | | 12.3 % | 17.1 % | 12.2 % | 5.2 % | | |
| Machine trains (`ShaftTrains`, `ElectricStores`, `Motor`) | 0.1 % | | | | | 7.1 % | 23.2 % |
| Other | 3.6 % | 1.2 % | 1.0 % | 1.5 % | 1.5 % | 15.1 % | 5.2 % |
| CPU samples | 2,193 | 3,153 | 3,053 | 3,089 | 3,153 | 339 | 2,707 |

Trains' bookkeeping by first mod frame (certified): `ShaftTrains.decide` iterating its `TreeMap` every tick (762 of 1,927 samples), `WorldTopologyLedger.shafts()` (`Map.copyOf` of all units at every call, 356; 92 % of the rig's bytes), `WorldTopologyLedger.shaft(long)` (180), `stores(Map)` (158), `ElectricStores.sync` (155). At 20 TPS this is 11 µs a cadence: negligible now, linear in units.

## Warm route probe (`warm-probe/warm-probe.txt`)

| Case, +2 % input | Route | Path | Newton | Closure | Checkpoints |
|---|---|---|---|---|---|
| medium | `ColumnUnit.solve(seed)` = strategy AGGRESSIVE + seed | `warm/aggressive/…/hyd` | 3 | 1e-8 | 8,635 |
| medium | strategy AGGRESSIVE, no seed (`OFF_FAST`) | `cold/stage-continuation/15-30-40/fine-fd/…` | 0 | 1e-3 | 258,256 |
| medium | `ColumnUnit.solve(no seed)` (learned) | `lnn/requested-state/hyd` | 0 | 1e-8 | 26,898 |
| literature | `ColumnUnit.solve(seed)` = strategy AGGRESSIVE + seed | `cold/stage-continuation/…/liquid-only-condenser/…` | 4 | 1e-8 | 183,786 |
| literature | strategy AGGRESSIVE, no seed (`OFF_FAST`) | same chain | 3 | 1e-3 | 249,386 |
| literature | `ColumnUnit.solve(no seed)` (learned) | `lnn/requested-state/hyd` | 4 | 1e-8 | 30,194 |

The cold base's seed: medium dry; literature free water 39.3 mol/s on one tray. Every flash and solver step calls the checkpoint, so the count stands in for residual evaluations (no direct counter is public). On both inputs the learned start is 6–9× cheaper than the classical chain with either closure; for owner decision 8 (D3), the closure is not what makes the literature warm solve slow: the refused wet seed sends it down the classical chain, where the learned route is never tried first.

## Fluid step: `mixed100` (`fluid-mixed100`, report `report.json`, JFR `stress.jfr` window 05:12:34.7–05:13:34.7 UTC)

100 islands of 10–30 reservoirs (50 transient, 25 through-flow, 25 closed and certified at rest), 1,981 reservoirs, 2,854 compiled pipes, 47 components, two workers, cadence 5 s, time factor 4. Completed 15.0 intervals a second (realtime ratio 0.75 awake, 1.20 with the certified). Worker CPU occupancy 0.21. Save of the whole fixture: 191 ms cold (189 ms encode).

Top classes by self samples

| # | Share | Samples | Frame |
|---|---|---|---|
| 1 | 32.42 % | 711 | `science.thermo.PengRobinsonKernel` |
| 2 | 22.39 % | 491 | `ejml.sparse.csc.misc.TriangularSolver_DSCC` |
| 3 | 6.84 % | 150 | `java.lang.FdLibm` |
| 4 | 4.97 % | 109 | `science.fluid.solver.PhaseLayout` |
| 5 | 4.83 % | 106 | `science.fluid.transport.MixtureViscosity` |
| 6 | 3.33 % | 73 | `java.lang.StrictMath` |
| 7 | 3.01 % | 66 | `science.fluid.network.PassiveStepSolver` |
| 8 | 2.01 % | 44 | `net.minecraft.server.MinecraftServer` |
| 9 | 1.96 % | 43 | `science.math.DeterministicPow` |
| 10 | 1.92 % | 42 | `science.fluid.thermo.FluidThermodynamics` |
| 11 | 1.60 % | 35 | `science.fluid.thermo.HydrocarbonModel` |
| 12 | 1.50 % | 33 | `science.fluid.thermo.TranslatedPengRobinson` |
| 13 | 1.37 % | 30 | `ejml.sparse.csc.decomposition.lu.LuUpLooking_DSCC` |
| 14 | 1.23 % | 27 | `java.util.Arrays` |
| 15 | 1.19 % | 26 | `science.fluid.linalg.SparseLuSolver` |
| 16 | 0.96 % | 21 | `science.fluid.solver.SparseNewton` |
| 17 | 0.87 % | 19 | `science.thermo.phase.WaterShare` |
| 18 | 0.87 % | 19 | `java.util.BitSet` |
| 19 | 0.82 % | 18 | `java.util.ImmutableCollections` |
| 20 | 0.78 % | 17 | `science.fluid.network.ConservativeTransport` |

Allocation by site and class

| # | Share | MB | Site |
|---|---|---|---|
| 1 | 12.31 % | 6718.8 | `PengRobinsonKernel$Workspace.<init>:1869  [[D]` |
| 2 | 11.41 % | 6226.8 | `PengRobinsonKernel$Workspace.<init>:1868  [[D]` |
| 3 | 5.55 % | 3029.0 | `PhaseLayout.decode:294  [[D]` |
| 4 | 3.38 % | 1842.1 | `HydrocarbonModel.tangent:206  [[D]` |
| 5 | 2.60 % | 1419.1 | `HydrocarbonModel.phase:170  [[D]` |
| 6 | 2.49 % | 1357.1 | `PengRobinsonKernel$Workspace.<init>:1813  [[J]` |
| 7 | 2.41 % | 1316.5 | `HydrocarbonModel$Tangent.at:296  [[D]` |
| 8 | 2.33 % | 1273.1 | `SparseLuSolver$Storage.factor0:141  [[D]` |
| 9 | 2.16 % | 1180.2 | `PengRobinsonKernel$Workspace.<init>:1827  [[D]` |
| 10 | 1.61 % | 876.1 | `MixtureViscosity$Prepared.<init>:75  [[D]` |
| 11 | 1.56 % | 851.5 | `PassiveNetwork$Inventory.moles:256  [[D]` |
| 12 | 1.52 % | 830.7 | `PengRobinsonKernel$Workspace.<init>:1826  [[D]` |
| 13 | 1.47 % | 799.6 | `PengRobinsonKernel$Workspace.<init>:1839  [[D]` |
| 14 | 1.41 % | 769.0 | `FluidThermodynamics$State.totals:1517  [[D]` |
| 15 | 1.23 % | 669.2 | `PengRobinsonKernel$Workspace.<init>:1820  [[D]` |
| 16 | 1.22 % | 663.4 | `PassiveStepSolver$Equations.buildSparsity:5105  [[I]` |
| 17 | 1.20 % | 652.8 | `FluidThermodynamics.support:790  [[D]` |
| 18 | 1.14 % | 620.5 | `TranslatedPengRobinson$Values.<init>:294  [[D]` |
| 19 | 1.13 % | 615.7 | `PengRobinsonKernel$Workspace.<init>:1872  [[D]` |
| 20 | 1.10 % | 602.5 | `PengRobinsonKernel$Workspace.<init>:1841  [[D]` |

## Column (`column/`, one JVM a phase)

40 stages, 3 side draws, 1 steam feed, 3 pumparounds; medium 19 components, literature 18. Tables for the two medium phases and the warm literature phase; the cold literature tables are in `column/cold-literature.summary.txt` and follow cold medium within 1 point.

### Cold `medium_crude`

Top classes by self samples

| # | Share | Samples | Frame |
|---|---|---|---|
| 1 | 34.19 % | 1078 | `science.column.v3.linalg.V3BandedPivotedSolver` |
| 2 | 11.99 % | 378 | `science.column.v3.V3DegreeOfFreedomLedger` |
| 3 | 11.42 % | 360 | `science.thermo.PengRobinsonKernel` |
| 4 | 6.66 % | 210 | `java.lang.FdLibm` |
| 5 | 6.37 % | 201 | `science.column.v3.V3MeshResidualEvaluator` |
| 6 | 4.85 % | 153 | `java.util.HashMap` |
| 7 | 2.92 % | 92 | `science.column.v3.V3FiniteDifferenceJacobian` |
| 8 | 2.73 % | 86 | `java.lang.StrictMath` |
| 9 | 2.70 % | 85 | `science.column.v3.V3BlockJacobianAssembler` |
| 10 | 1.74 % | 55 | `science.column.v3.thermo.V3PengRobinsonSession` |
| 11 | 1.62 % | 51 | `science.column.v3.V3ColumnProblem` |
| 12 | 1.55 % | 49 | `java.util.TimSort` |
| 13 | 1.40 % | 44 | `science.column.v3.V3BlockJacobian` |
| 14 | 1.27 % | 40 | `science.thermo.phase.SteamVirial` |
| 15 | 1.14 % | 36 | `science.column.v3.thermo.V3PengRobinsonThermo` |
| 16 | 0.82 % | 26 | `java.util.ImmutableCollections` |
| 17 | 0.76 % | 24 | `java.lang.invoke.LambdaForm` |
| 18 | 0.57 % | 18 | `science.column.v3.thermo.V3FugacityResult` |
| 19 | 0.48 % | 15 | `science.column.v3.V3DryMeshState` |
| 20 | 0.44 % | 14 | `java.util.Arrays` |

Allocation by site and class

| # | Share | MB | Site |
|---|---|---|---|
| 1 | 7.74 % | 4841.1 | `PengRobinsonKernel$Evaluation.logFugacityCoefficients:2011  [[D]` |
| 2 | 7.40 % | 4625.9 | `DeterministicMath.cos:55  [[D]` |
| 3 | 7.35 % | 4595.9 | `V3MeshResidualEvaluator.normalizedPublicPhaseComposition:607  [[D]` |
| 4 | 6.09 % | 3808.5 | `V3BandedMatrix.<init>:19  [[D]` |
| 5 | 5.30 % | 3309.8 | `V3FugacityResult.<init>:19  [[D]` |
| 6 | 5.23 % | 3267.7 | `V3BandedPivotedSolver$BandRows.<init>:306  [[D]` |
| 7 | 4.16 % | 2598.4 | `V3MeshResidualEvaluator.evaluate:181  [[D]` |
| 8 | 4.07 % | 2544.3 | `V3DegreeOfFreedomLedger.addIfActive:335  [V3DegreeOfFreedomLedger$UnknownId]` |
| 9 | 3.91 % | 2441.2 | `V3DryMeshCoordinateMap.decode:66  [[D]` |
| 10 | 3.81 % | 2384.3 | `V3DegreeOfFreedomLedger.addIfActive:336  [HashMap$Node]` |
| 11 | 3.78 % | 2363.1 | `PengRobinsonKernel$Derivatives.dLogPhiDn:2062  [[D]` |
| 12 | 2.95 % | 1842.7 | `PengRobinsonKernel.selectRoot:1578  [PengRobinsonKernel$RootSelection]` |
| 13 | 2.43 % | 1518.8 | `V3MeshResidualEvaluator.evaluate:180  [[D]` |
| 14 | 2.42 % | 1511.1 | `V3FugacityDerivatives.copy:108  [[D]` |
| 15 | 2.40 % | 1499.1 | `V3FiniteDifferenceJacobian.stageColoredJacobian:123  [[D]` |
| 16 | 2.14 % | 1337.9 | `V3FiniteDifferenceJacobian$MatrixValues.<init>:349  [[D]` |
| 17 | 1.92 % | 1202.4 | `V3DryMeshCoordinateMap.decode:67  [[D]` |
| 18 | 1.68 % | 1047.9 | `V3DegreeOfFreedomLedger.addIfActive:336  [HashMap$Node;]` |
| 19 | 1.64 % | 1028.0 | `V3MeshResidualEvaluator.nodeProperties:574  [V3MeshResidualEvaluator$NodeProperties;]` |
| 20 | 1.37 % | 854.3 | `V3MeshResidualEvaluator.localDerivatives:315  [[D]` |

### Warm `medium_crude` (+2 %)

Top classes by self samples

| # | Share | Samples | Frame |
|---|---|---|---|
| 1 | 28.46 % | 869 | `science.column.v3.linalg.V3BandedPivotedSolver` |
| 2 | 16.84 % | 514 | `science.column.v3.V3DegreeOfFreedomLedger` |
| 3 | 11.37 % | 347 | `science.thermo.PengRobinsonKernel` |
| 4 | 7.40 % | 226 | `science.column.v3.V3MeshResidualEvaluator` |
| 5 | 6.55 % | 200 | `java.lang.FdLibm` |
| 6 | 5.40 % | 165 | `java.util.HashMap` |
| 7 | 2.95 % | 90 | `science.column.v3.V3FiniteDifferenceJacobian` |
| 8 | 2.29 % | 70 | `science.column.v3.thermo.V3PengRobinsonSession` |
| 9 | 2.19 % | 67 | `java.util.TimSort` |
| 10 | 2.00 % | 61 | `java.lang.StrictMath` |
| 11 | 1.77 % | 54 | `science.column.v3.V3ColumnProblem` |
| 12 | 1.41 % | 43 | `science.column.v3.V3BlockJacobianAssembler` |
| 13 | 1.18 % | 36 | `science.thermo.phase.SteamVirial` |
| 14 | 1.15 % | 35 | `java.lang.invoke.LambdaForm` |
| 15 | 1.08 % | 33 | `science.column.v3.V3BlockJacobian` |
| 16 | 0.92 % | 28 | `java.util.ImmutableCollections` |
| 17 | 0.85 % | 26 | `science.column.v3.thermo.V3PengRobinsonThermo` |
| 18 | 0.69 % | 21 | `java.util.stream.AbstractPipeline` |
| 19 | 0.49 % | 15 | `science.column.v3.thermo.V3FeedFlash` |
| 20 | 0.46 % | 14 | `science.column.v3.V3DryMeshState` |

Allocation by site and class

| # | Share | MB | Site |
|---|---|---|---|
| 1 | 6.96 % | 4587.0 | `V3BandedMatrix.<init>:19  [[D]` |
| 2 | 6.71 % | 4418.6 | `V3BandedPivotedSolver$BandRows.<init>:306  [[D]` |
| 3 | 6.47 % | 4262.7 | `V3FugacityResult.<init>:19  [[D]` |
| 4 | 5.92 % | 3903.7 | `V3DegreeOfFreedomLedger.addIfActive:336  [HashMap$Node]` |
| 5 | 5.38 % | 3547.4 | `DeterministicMath.cos:55  [[D]` |
| 6 | 4.43 % | 2918.0 | `V3DegreeOfFreedomLedger.addIfActive:335  [V3DegreeOfFreedomLedger$UnknownId]` |
| 7 | 4.42 % | 2909.5 | `PengRobinsonKernel$Evaluation.logFugacityCoefficients:2011  [[D]` |
| 8 | 4.01 % | 2639.6 | `V3PengRobinsonThermo.fugacity:125  [V3FugacityResult]` |
| 9 | 3.90 % | 2571.2 | `V3FiniteDifferenceJacobian.stageColoredJacobian:123  [[D]` |
| 10 | 3.90 % | 2569.6 | `V3MeshResidualEvaluator.normalizedPublicPhaseComposition:607  [[D]` |
| 11 | 3.17 % | 2088.5 | `V3DryMeshCoordinateMap.decode:66  [[D]` |
| 12 | 3.13 % | 2063.8 | `V3MeshResidualEvaluator.evaluate:180  [[D]` |
| 13 | 3.01 % | 1981.3 | `V3FiniteDifferenceJacobian$MatrixValues.<init>:349  [[D]` |
| 14 | 2.75 % | 1811.4 | `V3MeshResidualEvaluator.evaluate:181  [[D]` |
| 15 | 2.51 % | 1654.9 | `V3MeshResidualEvaluator.nodeProperties:599  [[D]` |
| 16 | 2.47 % | 1629.1 | `V3SideDrawSpec.withdrawal:25  [V3SideDrawSpec$Withdrawal]` |
| 17 | 2.40 % | 1582.5 | `V3DryMeshCoordinateMap.decode:67  [[D]` |
| 18 | 2.32 % | 1529.5 | `V3DegreeOfFreedomLedger.addIfActive:336  [HashMap$Node;]` |
| 19 | 2.28 % | 1502.9 | `PengRobinsonKernel.selectRoot:1578  [PengRobinsonKernel$RootSelection]` |
| 20 | 2.27 % | 1493.3 | `V3DegreeOfFreedomLedger.ordered:340  [Object;]` |

### Warm literature (+2 %; runs the classical chain, see the probe)

Top classes by self samples

| # | Share | Samples | Frame |
|---|---|---|---|
| 1 | 40.47 % | 1276 | `science.column.v3.linalg.V3BandedPivotedSolver` |
| 2 | 12.37 % | 390 | `science.thermo.PengRobinsonKernel` |
| 3 | 7.01 % | 221 | `java.lang.FdLibm` |
| 4 | 6.12 % | 193 | `science.column.v3.V3MeshResidualEvaluator` |
| 5 | 5.17 % | 163 | `science.column.v3.V3DegreeOfFreedomLedger` |
| 6 | 4.03 % | 127 | `science.column.v3.thermo.V3PengRobinsonSession` |
| 7 | 2.79 % | 88 | `science.column.v3.V3FiniteDifferenceJacobian` |
| 8 | 2.32 % | 73 | `java.lang.StrictMath` |
| 9 | 2.09 % | 66 | `java.util.HashMap` |
| 10 | 1.97 % | 62 | `science.thermo.phase.SteamVirial` |
| 11 | 1.65 % | 52 | `science.column.v3.V3ColumnProblem` |
| 12 | 1.65 % | 52 | `science.column.v3.V3BlockJacobianAssembler` |
| 13 | 1.33 % | 42 | `java.util.ImmutableCollections` |
| 14 | 1.30 % | 41 | `science.column.v3.V3BlockJacobian` |
| 15 | 1.05 % | 33 | `science.column.v3.thermo.V3PengRobinsonThermo` |
| 16 | 0.92 % | 29 | `science.column.v3.V3MeshResidual` |
| 17 | 0.92 % | 29 | `java.util.TimSort` |
| 18 | 0.76 % | 24 | `science.column.v3.V3ActiveComponentBasis` |
| 19 | 0.73 % | 23 | `java.lang.invoke.LambdaForm` |
| 20 | 0.73 % | 23 | `java.lang.Math` |

Allocation by site and class

| # | Share | MB | Site |
|---|---|---|---|
| 1 | 9.50 % | 5595.1 | `PengRobinsonKernel$Evaluation.logFugacityCoefficients:2011  [[D]` |
| 2 | 9.34 % | 5503.4 | `V3MeshResidualEvaluator.normalizedPublicPhaseComposition:607  [[D]` |
| 3 | 7.48 % | 4403.4 | `V3DryMeshCoordinateMap.decode:67  [[D]` |
| 4 | 6.71 % | 3949.9 | `V3MeshResidualEvaluator.evaluate:180  [[D]` |
| 5 | 6.18 % | 3640.1 | `V3FugacityResult.<init>:19  [[D]` |
| 6 | 5.49 % | 3235.2 | `V3MeshResidualEvaluator.evaluate:181  [[D]` |
| 7 | 4.55 % | 2677.2 | `DeterministicMath.cos:55  [[D]` |
| 8 | 3.49 % | 2057.2 | `V3DryMeshCoordinateMap.decode:66  [[D]` |
| 9 | 3.49 % | 2056.1 | `V3BandedPivotedSolver$BandRows.<init>:306  [[D]` |
| 10 | 2.91 % | 1714.9 | `V3DegreeOfFreedomLedger.addIfActive:335  [V3DegreeOfFreedomLedger$UnknownId]` |
| 11 | 2.67 % | 1570.3 | `V3FiniteDifferenceJacobian.stageColoredJacobian:123  [[D]` |
| 12 | 2.66 % | 1567.6 | `V3BandedMatrix.<init>:19  [[D]` |
| 13 | 2.30 % | 1356.9 | `PengRobinsonKernel.selectRoot:1578  [PengRobinsonKernel$RootSelection]` |
| 14 | 2.24 % | 1318.5 | `V3DegreeOfFreedomLedger.addIfActive:336  [HashMap$Node]` |
| 15 | 2.24 % | 1316.7 | `V3FugacityDerivatives.copy:108  [[D]` |
| 16 | 1.98 % | 1167.6 | `V3MeshResidualEvaluator.nodeProperties:598  [V3MeshResidualEvaluator$NodeProperties]` |
| 17 | 1.96 % | 1153.5 | `PengRobinsonKernel$Derivatives.dLogPhiDn:2062  [[D]` |
| 18 | 1.77 % | 1042.0 | `V3FiniteDifferenceJacobian$MatrixValues.<init>:349  [[D]` |
| 19 | 1.72 % | 1013.1 | `V3SideDrawSpec.withdrawal:25  [V3SideDrawSpec$Withdrawal]` |
| 20 | 1.71 % | 1007.0 | `V3MeshResidualEvaluator.localDerivatives:315  [[D]` |

The top allocation site of the cold and warm medium phases before the JFR start-up samples were left out was `StringLatin1.replace` under `jdk.jfr.Recording.start` with 60.5 GB: the warm-up's bytes (JFR note in the summary).

## Shaft trains (`machines-cert-true`, `machines-cert-false`)

Each train: motor (11 kW, 2 poles) – shaft – gearbox (Auto) – shaft – pump casing (CentrifugalPump 0.01 m³/s, 50 m, 0.7), on its own island (supply at 1 atm, the casing's machine pipe, a void at the 0.8 speed line's rise). All 20 motors and casings ran at 2,950 rpm through both windows. Certified: 20/20 islands certified, 6,260 renewal solves in the window (129 µs each). Awake: 353,340 island solves (153 µs each, 90 % of the wall).

### Certified

Top classes by self samples

| # | Share | Samples | Frame |
|---|---|---|---|
| 1 | 36.68 % | 993 | `java.util.TreeMap` |
| 2 | 13.71 % | 371 | `java.util.HashMap` |
| 3 | 13.04 % | 353 | `java.util.ImmutableCollections` |
| 4 | 12.49 % | 338 | `runtime.fluid.ShaftTrains` |
| 5 | 5.17 % | 140 | `java.util.AbstractCollection` |
| 6 | 4.77 % | 129 | `runtime.fluid.ElectricStores` |
| 7 | 3.58 % | 97 | `MachineBench` |
| 8 | 2.73 % | 74 | `java.util.Map` |
| 9 | 2.62 % | 71 | `java.lang.FdLibm` |
| 10 | 1.66 % | 45 | `java.lang.Long` |
| 11 | 1.33 % | 36 | `runtime.fluid.IslandCoordinator` |
| 12 | 1.11 % | 30 | `science.machine.Motor` |
| 13 | 0.30 % | 8 | `java.lang.StrictMath` |
| 14 | 0.18 % | 5 | `runtime.fluid.WorldTopologyLedger` |
| 15 | 0.15 % | 4 | `science.fluid.network.ConservativeTransport` |
| 16 | 0.11 % | 3 | `science.fluid.network.PassiveStepSolver` |
| 17 | 0.11 % | 3 | `java.util.Collections` |
| 18 | 0.07 % | 2 | `science.fluid.thermo.FluidThermodynamics` |
| 19 | 0.04 % | 1 | `science.math.DeterministicPow` |
| 20 | 0.04 % | 1 | `jdk.internal.math.DoubleToDecimal` |

Allocation by site and class

| # | Share | MB | Site |
|---|---|---|---|
| 1 | 92.21 % | 31002.2 | `WorldTopologyLedger.shafts:485  [Object;]` |
| 2 | 0.83 % | 280.7 | `WorldTopologyLedger.shafts:485  [ImmutableCollections$MapN]` |
| 3 | 0.73 % | 246.2 | `WorldTopologyLedger.shafts:485  [Map$Entry;]` |
| 4 | 0.52 % | 174.9 | `WorldTopologyLedger.stores:503  [Object;]` |
| 5 | 0.51 % | 172.8 | `ElectricStores.lambda$facts$3:577  [HashMap$Node]` |
| 6 | 0.45 % | 149.8 | `ElectricStores.lambda$facts$3:577  [ElectricStore$Facts]` |
| 7 | 0.42 % | 141.0 | `ConservativeTransport$BoundaryTransfer.moles:77  [[D]` |
| 8 | 0.32 % | 108.9 | `MixtureViscosity$Prepared.<init>:75  [[D]` |
| 9 | 0.22 % | 73.3 | `PipeTransfer$Accumulator.lambda$add$0:97  [PipeTransfer$Mutable]` |
| 10 | 0.21 % | 72.3 | `InletPhase.walk:77  [[Z]` |
| 11 | 0.21 % | 71.2 | `IslandScheduler.schedule:45  [IslandScheduler$Deadline]` |
| 12 | 0.17 % | 55.5 | `WorldTopologyLedger.stores:503  [Map$Entry;]` |
| 13 | 0.14 % | 46.1 | `ElectricStores.sync:255  [HashMap$Node]` |
| 14 | 0.12 % | 41.9 | `PassiveIntervalSolver.run:279  [LinkedHashMap]` |
| 15 | 0.12 % | 38.8 | `PipeTransfer$Stream.lambda$copy$0:27  [[D]` |
| 16 | 0.10 % | 34.6 | `PassiveNetwork$Inventory.moles:256  [[D]` |
| 17 | 0.10 % | 33.5 | `FluidThermodynamics$State.totals:1517  [[D]` |
| 18 | 0.09 % | 31.4 | `ElectricStores.lambda$facts$3:577  [HashMap$Node;]` |
| 19 | 0.08 % | 27.2 | `PassiveStepSolver$Equations.<init>:4528  [[D]` |
| 20 | 0.07 % | 22.0 | `PipeTransfer.sample:58  [[D]` |

### Awake (339 CPU samples: low confidence for the class table; the allocation table is from 55.8 GB of samples)

Top classes by self samples

| # | Share | Samples | Frame |
|---|---|---|---|
| 1 | 23.01 % | 78 | `java.util.HashMap` |
| 2 | 9.44 % | 32 | `java.util.ImmutableCollections` |
| 3 | 7.67 % | 26 | `java.util.stream.AbstractPipeline` |
| 4 | 7.08 % | 24 | `science.fluid.network.PassiveStepSolver` |
| 5 | 6.78 % | 23 | `java.util.TreeMap` |
| 6 | 5.60 % | 19 | `science.fluid.network.ConservativeTransport` |
| 7 | 5.01 % | 17 | `runtime.fluid.IslandCoordinator` |
| 8 | 4.13 % | 14 | `java.util.LinkedHashMap` |
| 9 | 2.65 % | 9 | `java.util.Arrays` |
| 10 | 2.65 % | 9 | `runtime.fluid.ShaftTrains` |
| 11 | 2.65 % | 9 | `science.fluid.thermo.FluidThermodynamics` |
| 12 | 1.77 % | 6 | `java.util.Objects` |
| 13 | 1.47 % | 5 | `java.lang.Long` |
| 14 | 1.47 % | 5 | `java.util.Map` |
| 15 | 1.47 % | 5 | `runtime.fluid.TankOutlets` |
| 16 | 1.18 % | 4 | `runtime.fluid.WorldTopologyLedger` |
| 17 | 1.18 % | 4 | `science.fluid.network.InletPhase` |
| 18 | 1.18 % | 4 | `java.util.Comparator` |
| 19 | 0.88 % | 3 | `runtime.fluid.IslandScheduler` |
| 20 | 0.88 % | 3 | `java.util.ArrayList` |

Allocation by site and class

| # | Share | MB | Site |
|---|---|---|---|
| 1 | 5.39 % | 3009.4 | `MixtureViscosity$Prepared.<init>:75  [[D]` |
| 2 | 5.19 % | 2897.3 | `FluidThermodynamics$State.totals:1517  [[D]` |
| 3 | 4.99 % | 2787.3 | `PassiveStepSolver$Equations.<init>:4528  [[D]` |
| 4 | 3.48 % | 1941.7 | `PipeTransfer$Stream.lambda$copy$0:27  [[D]` |
| 5 | 3.32 % | 1855.8 | `SparseLuSolver$Storage.solveMultiple:161  [Spliterators$ArraySpliterator]` |
| 6 | 3.13 % | 1746.8 | `ConservativeTransport.reconstruct0:265  [[D]` |
| 7 | 2.87 % | 1601.2 | `PassiveStepSolver$Result.<init>:343  [[D]` |
| 8 | 2.64 % | 1476.4 | `PipeTransfer$Mutable.<init>:108  [[D]` |
| 9 | 2.52 % | 1404.1 | `ConservativeTransport$Projection.externalMoles:107  [[D]` |
| 10 | 2.42 % | 1352.9 | `PassiveStepSolver$Equations.<init>:4529  [[D]` |
| 11 | 2.41 % | 1347.3 | `ConservativeTransport$BoundaryTransfer.<init>:76  [[D]` |
| 12 | 2.35 % | 1310.8 | `MixtureViscosity$Prepared.<init>:74  [[D]` |
| 13 | 2.24 % | 1250.1 | `PassiveNetwork$Inventory.moles:256  [[D]` |
| 14 | 1.75 % | 976.7 | `InletPhase.walk:90  [ReferencePipeline$4]` |
| 15 | 1.65 % | 920.1 | `MixtureViscosity$Prepared.<init>:74  [[B]` |
| 16 | 1.65 % | 920.0 | `ConservativeTransport.reconstruct0:624  [[D]` |
| 17 | 1.54 % | 858.3 | `ConservativeTransport.reconstruct0:356  [[D]` |
| 18 | 1.52 % | 850.9 | `InletPhase.walk:90  [ArrayList$ArrayListSpliterator]` |
| 19 | 1.50 % | 836.2 | `PassiveStepSolver.solve:522  [PassiveStepSolver$Equations]` |
| 20 | 1.46 % | 815.1 | `PassiveStepSolver.checkConservation:4195  [[D]` |

## Retained heap

### 20 trains and their islands (`machines-cert-false/histo-after.txt` minus `histo-before.txt`)
total 28.74 MB (after minus before)
mod classes 1.12 MB

| Package | MB |
|---|---|
| science.material | 0.693 |
| science.thermo | 0.151 |
| science.thermo.phase | 0.097 |
| science.fluid.network | 0.065 |
| science.fluid.thermo | 0.060 |
| runtime.fluid | 0.036 |
| science.fluid.linalg | 0.005 |
| science.machine | 0.004 |
| science.fluid.solver | 0.003 |
| runtime | 0.002 |
| science.fluid.transport | 0.002 |
| science.fluid.state | 0.001 |

| # | Class | Instances | MB |
|---|---|---|---|
| 1 | `[D` | 31184 | 19.820 |
| 2 | `[B` | 17099 | 3.920 |
| 3 | `[I` | 4017 | 0.907 |
| 4 | `science.material.DerivedPairs$Pair` | 21620 | 0.692 |
| 5 | `java.lang.String` | 17501 | 0.420 |
| 6 | `[J` | 538 | 0.316 |
| 7 | `[Ljava.util.concurrent.ConcurrentHashMap$Node;` | 10 | 0.264 |
| 8 | `[Ljava.lang.Object;` | 3167 | 0.245 |
| 9 | `[Ljdk.internal.vm.FillerElement;` | 507 | 0.221 |
| 10 | `java.lang.Class` | 1019 | 0.122 |
| 11 | `[Ljava.util.HashMap$Node;` | 1403 | 0.091 |
| 12 | `java.util.LinkedHashMap$Entry` | 2220 | 0.089 |
| 13 | `[Z` | 780 | 0.079 |
| 14 | `[[D` | 602 | 0.075 |
| 15 | `java.util.ImmutableCollections$List12` | 2871 | 0.069 |

### Fluid `one`, whole server (`fluid-one/histo-one.txt`), mod classes
total 703.97 MB (live after a full GC)
mod classes 1.89 MB

| Package | MB |
|---|---|
| runtime.fluid | 0.631 |
| science.fluid.network | 0.290 |
| fluid.gametest | 0.222 |
| network | 0.206 |
| science.material | 0.173 |
| world.level.block.entity | 0.115 |
| science.fluid.thermo | 0.093 |
| runtime.instrument | 0.062 |
| science.fluid.state | 0.031 |
| science.thermo | 0.017 |
| world.level.block | 0.017 |
| science.thermo.phase | 0.010 |

| # | Class | Instances | MB |
|---|---|---|---|
| 1 | `fluid.gametest.FluidServerBenchmark$MemorySample` | 1943 | 0.218 |
| 2 | `runtime.fluid.FluidView` | 1102 | 0.150 |
| 3 | `network.FluidNetwork$Controls` | 1102 | 0.141 |
| 4 | `world.level.block.entity.FluidDeviceBlockEntity` | 1102 | 0.115 |
| 5 | `runtime.fluid.FluidDeviceSpec` | 1102 | 0.097 |
| 6 | `runtime.fluid.PhysicalFluidTopology$Device` | 1102 | 0.088 |
| 7 | `science.fluid.network.VesselGeometry` | 1204 | 0.077 |
| 8 | `science.material.DerivedPairs$Pair` | 2162 | 0.069 |
| 9 | `network.FluidNetwork$MenuData` | 1102 | 0.062 |
| 10 | `runtime.instrument.Instrument$Metadata` | 1102 | 0.062 |
| 11 | `science.fluid.network.PipeResistance$Geometry` | 1204 | 0.058 |
| 12 | `science.fluid.thermo.HydrocarbonModel$Phase` | 904 | 0.051 |
| 13 | `science.material.ModifiedUnifac$Parameters` | 1244 | 0.050 |
| 14 | `runtime.fluid.FluidView$PipeConnection` | 1000 | 0.048 |
| 15 | `runtime.fluid.PhysicalFluidTopology$View` | 1000 | 0.040 |
| 16 | `science.fluid.network.PipeTransfer$Stream` | 1204 | 0.039 |
| 17 | `science.fluid.thermo.FluidThermodynamics$State` | 302 | 0.036 |
| 18 | `runtime.fluid.PhysicalFluidTopology$Position` | 1102 | 0.035 |
| 19 | `runtime.fluid.WorldTopologyLedger$Registration` | 1102 | 0.035 |
| 20 | `science.fluid.network.PassiveNetwork$Inventory` | 402 | 0.032 |

## Run folders

| Folder | What |
|---|---|
| `fluid-one/` | stopped `one` run (b1one1): `gradle.log`, `stress-partial.jfr` (JFR.dump at 33 min), `histo-one.txt`, `summary-partial.txt` |
| `fluid-mixed100/` | b1mixed1: `report.json`, `runtime-audit.json` (PASS), `stress.jfr`, `summary-window-{all,solve,server}.txt` |
| `column/` | `<phase>.txt` (bench numbers), `<phase>.jfr`, `<phase>.summary.txt`, `machine-<phase>.txt` |
| `warm-probe/` | `warm-probe.txt` |
| `machines-cert-true/`, `machines-cert-false/` | `machines.txt`, `machines.jfr`, `summary.txt`, `histo-before.txt`, `histo-after.txt`, `machine.txt` |

## After wave B (main thread, 2026-11-13, review branch at 32350ac1d)

The same harness and inputs, one 60 + 60 s pair a scenario, run from the review branch with B-T1, B-S1, B-R1, B-M1, B-E1, B-C1, B-S2, C1a and G1 part 1 merged. Output: `tmp/codebase-review-bench/after-b/` of the review worktree. The Gradle lanes were idle; the Rust thread ran `cargo test` at some time between 15:20 and 15:30 (its commits are at 15:30), which covers the column phases, so the column times are an upper bound. Bytes and counters do not depend on the load.

| Scenario | Before (B1) | After wave B |
|---|---|---|
| Fluid `mixed100`, island step | 28.4 ms median, 45.9 ms p95; 60.7 MB a step | 29.1 ms median, 48.3 ms p95; 39.5 MB a step (35.5 GB in 60 s, 899 steps) |
| Column `medium_crude`, cold | 0.196 s; 204 MB a solve | 0.174 s; 169 MB |
| Column `medium_crude`, warm +2 % | 0.095 s; 104 MB | 0.081 s; 81 MB |
| Column literature, cold | 0.208 s; 229 MB | 0.180 s; 191 MB |
| Column literature, warm +2 % (classical chain) | 1.370 s; 1,339 MB | 1.208 s; 1,189 MB |
| 20 shaft trains, certified | 11.1 µs a 100-tick cadence; 62 B a tick | 8.1 µs; 33 B a tick |

What it says:

1. **Column: 11 to 15 % less time and 11 to 22 % fewer bytes a solve**, from the band elimination's extent (P8), the two loops (P9), the ledger on int tables (P3) and the kernel's batch (B-T1). The band solver still carries the solve; the next gains are E0-1 (no finite-difference certificate) and the Rust band solver.
2. **Fluid step: a third fewer bytes, no less time.** The step's CPU is where it was: thermo kernel 35.7 % of samples (40.9 % before), linear algebra 44.4 % counting `UpLookingLu` (38.1 %), residual and Jacobian 12.7 % (13.6 %), flash 1.4 %. `PengRobinsonKernel.correctExcess` is 12.3 % (16 %).
3. **The sparse LU's kept reach does not reach the server path.** `UpLookingLu.depthFirst` and `search` are still 10.7 % of the step's CPU, and `UpLookingLu.prepare` allocates 1.1 GB a minute (lines 198 and 199: the L and U arrays of a new instance). The LU storage is owned by `ConservativeTransport.Workspace` and `SparseNewton.Shared`, and both are built again for each step, so every step's first factorization searches every column and allocates its arrays. B-S2's gate proved the reuse on a retained solver; the engine's step does not retain one across steps. Follow-up P-17b: the storage (with its kept reach) lives with the island's retained state across steps, keyed by the pattern; then the reach is searched once per island and topology.
4. **Kernel workspaces are still the largest allocation**: `PengRobinsonKernel$Workspace.<init>` 7.5 GB a minute (21 % of the bytes), then `PhaseLayout.decode` 3.1 GB and `HydrocarbonModel`'s tangents 5.9 GB. These are F0's items (one workspace per worker, flat pass tables).
5. **The fluid gate's reading does not change**: the same step time, the same share in kernel, residual and LU (about 93 %), and the worker occupancy 0.21 on two workers for 75 awake islands. The need condition is still not met on this scenario.

## The Rust band solve, measured (main thread, 2026-11-13, review branch at d529685af)

Stage (a) of the port puts the column's band solve in the Rust library behind `-Dicheme.native.column=band`. The results are the Java bits (the gate: 1,570 band cases, ten column classes with the ceiling off and on). One 60 + 60 s pair a form, the same build, the machine alone, `ColumnBench` on `medium_crude`:

| Form | Cold: time, bytes a solve | Warm +2 %: time, bytes a solve |
|---|---|---|
| Java band solve (after P8, P9) | 0.172 s, 169 MB | 0.080 s, 81 MB |
| Rust band solve, the shipped build (x86-64 baseline, SSE2) | 0.200 s, 161 MB | 0.092 s, 76 MB |
| Rust band solve built with `-C target-cpu=x86-64-v3` (AVX2; bit tables pass) | 0.189 s, 161 MB | not run |

What it says:

1. **One kernel behind a call per solve does not pay.** The Rust band solve is 15 to 16 % slower a column solve than Java's at the baseline target and about 10 % slower with AVX2. The bytes fall by 5 %. The earlier C++ outer pass gave no gain either.
2. **Why (reasoned, not separated by a measurement):** HotSpot compiles the elimination's update loop to the machine's vector width (P9 wrote it for that), so the Rust loop has nothing to add there; each call copies the band in and the solution out through JNI (the band is several MB at 40 trays and 19 components, 21 factorizations a cold solve) and reserves its work rows again.
3. **So the library's default stays `off`**, and the ceilings `band` and `kernel` are correctness stages, not performance stages. A gain can only come where a whole attempt or a whole solve runs in one call (no copies per linear solve, one arena, no garbage) and from what Rust would shed of the Java bookkeeping (maps, records, the ledger: 20 to 29 % of a solve before wave B, less after P3). The band solve and the kernel are the larger part of a solve and would run at about Java's speed.
4. **Consequence for the plan (PHASE2_PLAN, wave E):** stage 0 in Java goes on (it serves the Java path and shrinks any port). Stages (b) to (d) of the Rust port, about 12,000 Rust lines with a Java twin to keep, wait for the owner's reading of these numbers: the measurements so far give no performance case for them. If the port goes on, its next measurement is the band solve alone on both sides without JNI (to separate the copy from the loop), then stage (c) as a prototype of one rung before the rest.

## After the kept LU storages (main thread, 2026-11-13, review branch at 81101690e)

Item 3 of "After wave B" named the wrong cause. `ConservativeTransport.Workspace` and the step Newton's `SparseNewton.Shared` were already kept by the island's `RetainedSolver`; the storages built at every step were those of the solves that make a workspace of their own (a vessel's closure, a junction's split, a crystal's closure). P-17b keeps those with the island, and P-17c keeps the reaches of a storage's last four patterns ([wave-b/B-S2B_REPORT.md](wave-b/B-S2B_REPORT.md)). One 60 + 60 s pair on `mixed100`, the machine alone; output `tmp/codebase-review-bench/after-p17b/` and `after-p17c/`.

| | After wave B | After P-17b | After P-17c |
|---|---|---|---|
| Island step, median / p95 | 29.1 / 48.3 ms | 29.6 / 49.3 ms | 29.7 / 48.7 ms |
| Bytes in 60 s (899 steps) | 35.5 GB | 33.3 GB | 33.5 GB |
| `UpLookingLu.prepare` | 1.1 GB | off the list | off the list |
| `UpLookingLu.depthFirst` and `search`, CPU | 10.7 % | 9.7 % | 10.9 % |
| New LU storages an accepted interval | not counted | 0.11 | 0.11 |
| Reach searches of columns factored | not counted | 96 % | 91 % |

What it says:

1. **The storages are kept and the bytes fell by 6 %; the time did not move.** The step is where it was, inside the run-to-run spread.
2. **The reach search stays, and no cache removes it.** Every search is a pattern miss, none a changed pivot. 95 % of them are the step's own Newton: its Jacobian is a coloured finite difference whose exact zeros are dropped before the LU, and an entry that is zero or 1e-20 by rounding changes the pattern from one factorization to the next (16 kept patterns still miss 81 %).
3. **What would remove it** is a Jacobian whose pattern does not depend on rounding: analytic entries, which F0's flat tables prepare. It is about 10 % of the step's CPU. Factoring the declared pattern instead would fill on thousands of structural zeros and move every result in the last digits; not pursued.
4. **The fluid gate's reading is unchanged** (share about 93 %, need not met).
