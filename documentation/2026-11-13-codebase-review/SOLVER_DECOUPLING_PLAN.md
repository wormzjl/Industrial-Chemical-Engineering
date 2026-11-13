# Solver decoupling and Rust plan

Base `259d22a2c`, 2026-11-13. Design by the main thread from the phase 1 reviews. It replaces section 2 of [REVIEW_PLAN.md](REVIEW_PLAN.md). Finding ids are the reviews' ([CODEBASE_REVIEW.md](CODEBASE_REVIEW.md), section 2, names the review of each id). The build plan of the column's Rust port is [RUST_COLUMN_PORT_PLAN.md](RUST_COLUMN_PORT_PLAN.md); this document sets the decisions it left open and the order around it. Steps that depend on the profile (B1) are marked.

## 1. How the two solvers are correlated today

In the order of how much each one ties them:

1. **The column's energy books run on the network's thermo.** After a V3 solve, `ColumnSolve` prices every product with network flashes (m + 1 flashes per product), closes the condenser duty on the network's basis, finds the feed temperature by a secant on network flashes and sizes the air condenser on a network heat curve (C1, C2). A change to the network's kernel, water or pairs moves the column's duties, its closure band and its trips.
2. **One water, written in both solvers and called across them** (F2; the import cycle of REVIEW_PLAN finding 2). The column also has a second water mode, water on the component axis, that uses the network's cubic water (F29).
3. **A second column solver inside the fluid package.** `StillColumn` is a Wang-Henke column on the network's `HydrocarbonModel` (C17).
4. **The column's identity does not name the pairs it evaluates** (F1, defect): the network restates on a derivation change and the column does not.
5. **Shared scheduling.** Column and still jobs queue; islands take only idle workers and none while a job waits, so two running columns stop every island on a 4-core host (R6).
6. **Shared policy constants and markers.** `TraceTruncationPolicy.REINSERTION_FACTOR` is kept equal "so the two engines agree"; the network's control code tests `PortPlan.COLUMN_SENSOR` (C24); V3 reads its catalog through a thread-local the caller pins (C16).
7. **The sheet and the world solve one design by two routes and two energy closures** (C5).
8. **Duplicated port machinery** between `ColumnOperation` and `Stills` (C6).

What is not a correlation to remove: both solvers evaluate one equation of state (`PengRobinsonKernel` with the derived pairs). That is an owner rule (one EOS for the still, the column and the network). A kernel change will always move both; the aim is that nothing else does.

## 2. Target layers

One-way imports, lower layers first. A package may import its own layer's lower rows only where the table says so.

| Layer | Packages | Holds | Imports |
|---|---|---|---|
| 0 math | `science/math`, `science/math/linalg` | deterministic functions, constants (R, g, atmosphere), one root finder, banded and sparse LU with shared guards, small dense solves, the stage kernel (Rachford-Rice, tridiagonal sweep) | nothing |
| 1 kernel | `science/thermo` (kernel, mixing rule, pairs, residual cubic, phase identification, stability, flash numerics, `NumericalBox`), `science/thermo/water` | everything that is arithmetic on arrays | 0 |
| 2 material | `science/material` | records, catalog, derived pairs, ideal-gas fits, spines, solids | 0, 1 |
| 3 engine | `science/thermo/phase` | requests, results, evaluators, the equilibrium engine, one binding class for the catalog factories | 0 to 2 |
| 4 solvers (side by side, no import between them) | `science/column` (V3; `column/still`) | the column's port on the kernel; never the engine | 0, 1, 2 (catalog passed in) |
| | `science/fluid` (step, laws, booking, interval, transport, state; the network's port) | the network's port on the engine | 0 to 3, `science/machine` laws |
| | `science/machine` | laws, shaft trains, health | 0 |
| | `science/equipment/design` | shape facts, operating point, ratings | 0 |
| 5 plant | `science/plant` (`port`, `heat`, `column`, `still`, `machine`) | port accounts, utility debt, the column's cycle and pricing, the still's laws on its stage solver, shape-to-law ratings | 0 to 4 |
| 6 above plant | `science/flowsheet`, `science/equipment` geometry (`kit`, `units`, `lines`, `instruments`), `science/assembly`, `science/tutorial` | sizing, drawings, blueprints, the guide | 0 to 5 |
| runtime | `runtime/solve` and `runtime/fluid/engine` (Minecraft-free), `registry`, `save`, `present`, `runtime/plant`, control and power | | science |
| edge | `network`, `world`, `client` | | runtime, science |

`science/plant` is a new package under `science/`, not a new top-level folder.

## 3. The two thermo ports

Signatures are in THERMO_ADAPTERS_REVIEW section 2.5 and are adopted as written, with three decisions:

1. **The column port carries water inside it** (steam, free water, the datum) and reads the water module of layer 1. The column job takes its catalog as an argument (C16).
2. **A phase is requested by slot, not by root name**: slot 0 the smallest root, slot 1 the largest, and for callers that want it, "lower Gibbs" (P22). The answer carries the root count and separation. This is the room for supercritical fluids (section 7).
3. **Each port has its own identity**, naming only what its values read: the column's is the package's `scientificRevision`, the derivation identity (F1) and a column water token; the network's is today's. Each port owns its trace policy.

The network port has two halves (FLUID_STEP_REVIEW 3.4): a kernel half called once per node per residual (prepare at T; state at T, P and amounts; tangents; transport properties) and an engine half called a few times per pass (flashes, closures). Only the kernel half has to be native for a native residual.

## 4. De-correlation steps

Each row is one change; "Results" says what moves. Rows 1 to 9 are STRUCTURE or defect fixes and are the main thread's call. Rows 10 to 13 move references and are owner options ([SIMPLIFICATIONS_REVIEW.md](SIMPLIFICATIONS_REVIEW.md) has their numbers).

| # | Change | Findings | Results |
|---|---|---|---|
| 1 | Column identity names the derivation | F1 | every cached column state rebuilds once |
| 2 | One water module in layer 1; the column and the network call it; continued forms for both | F2, F3, F6, X39 | Region 1 identical; Wagner-Pruss about 1e-16 for one solver; the column's steam only where it threw |
| 3 | Material free of solver types | F4, F5, F7 | identical |
| 4 | Engine free of the network's package; catalog factories in one binding class | S04, S05, F9 | identical |
| 5 | `ColumnPricing` in `science/plant/column`: pricing, closure, trim and air area leave the column job | C1 | identical |
| 6 | `StillColumn` to `science/column/still` behind a `StageThermo` interface the network adapter implements | C17 option (a) | identical |
| 7 | `PortAccounts` and `UtilityDebt` in `science/plant`, used by columns and stills; one port hook | C6, C23, C24 | identical |
| 8 | The operating point a plain fact; the catalog an argument; equipment imports reversed | E18, C13, C16 | identical |
| 9 | Scheduling: islands keep one worker while one is ready; the plant deadline starts at the worker | R6 option A | none on values; latency of plant jobs under island load |
| 10 | Price products at their specific enthalpy: one flash per product instead of m + 1 | C2 | 0 at the reference state; at most about 0.3 % of the condenser duty at the drift-band edge, returned exactly at the outlet booking |
| 11 | One `ColumnJob` for the sheet and the world | C5 | the sheet's duties move by the basis gap |
| 12 | Price on V3's own enthalpies; the column job then reads no network thermo but the feed temperature | C3 | a few tenths of a percent of the throughput's enthalpy move from the cycle to the outlet booking |
| 13 | One water model for both solvers (water on the axis only) | F29 | large; needs three-phase stages in V3; not before the Rust port |

Not taken: moving the column's feed flash onto the equilibrium engine (F14). It would add the one dependency the column does not have today. If the feed flash needs a stability test, it takes the layer 1 stability test, which is below material.

After rows 1 to 9 the two solvers share the kernel, the water module, the catalog and the plant layer, and nothing else.

## 5. Determinism and results

The owner's rule (2026-11-13): an optimization need not keep results byte-identical; the shift must be small and justified. The rules that follow from it and from the reviews:

1. **Each path is deterministic.** For the Java path this means, beyond `DeterministicMath`: no platform math in a native pass (D1), no wall clock inside a solve (D2), no booking by a read (R5), no result that depends on where a poll stands unless the count is pinned (D6). The wall-budget hold of an island (R25, owner D37) stays as designed and is named as the one place machine speed can change a result.
2. **A function-level port is exact.** The Rust math layer returns the Java bits (fdlibm from the C sources, `DeterministicPow`, a correctly rounded fused multiply-add where Java calls `Math.fma`, Java's `max`, `min`, `round` and compensated stream sums). A difference in a kernel, a residual or a factorization is then a defect, not noise. Decision on the fused multiply-add: the hardware instruction where the processor has it (arm64 always; x86-64 by run-time detection), a software form otherwise; both are correctly rounded, and the load-time self-test compares them with Java's.
3. **A whole solve has a tolerance, from the solver's own gates**: twice the certified Newton step (RUST_COLUMN_PORT_PLAN section 7). A deliberate deviation (for example the analytic Jacobian in Rust while Java still differences) is named, and its observed shift printed.
4. **A change that moves a reference** states the old and new values and the bound it was expected to keep. Conservation stays exact on the ledger. The exact gates of the lane (chain-100, the 33 junction lines) get a re-record step; [DOCS_AND_COLLABORATION_PLAN.md](DOCS_AND_COLLABORATION_PLAN.md) has the wording for AGENTS.md.
5. **The Java audit publishes on both paths.** A Rust defect can cost a fallback solve; it cannot publish a column the Java audit refuses.

## 6. Rust

### 6.1 Decisions

- **Order.** Column first, in the stages of RUST_COLUMN_PORT_PLAN: stage 0 (Java), (a) toolchain and math, (b) linear algebra and thermo, (c) one attempt, (d) the whole solve. The thermo kernel is therefore ported with the column, at stage (b). The fluid step is gated (owner, 2026-11-13; PHASE2_PLAN wave F): the Java groundwork F0 always (typed refusals, flat tables, the kernel as one call, the two splits); then an island step is measured again, after F0 and the wave B fluid and thermo batches; the port F1 (the kernel half of the network port native, then one pass per call) goes ahead only if kernel calls, residual and Jacobian assembly and the LU are still the large majority of step time and step cost still limits plant size. The equilibrium engine stays in Java unless the profile says otherwise.
- **Stage 0 is the largest gain and comes before any Rust.** The column review's count: the finite-difference Jacobian costs 12 C + 12 whole-column property passes (252 at 20 components) where the analytic one needs one. Making the analytic Jacobian authoritative (P1) removes about 700 lines from the port and was expected to save more time than the language change. **B1 refutes the time claim** (section 6.3): residual and Jacobian assembly with the whole kernel are 19 to 24 % of a solve, and the band solver is 52 to 63 %. Stage 0 still comes first, for the ledger (5 to 17 %), the bookkeeping, the route (PHASE2_PLAN decision 16) and the lines it deletes, but its largest items are those, not P1.
- **No native state, no callbacks, flat arrays, JNI.** As planned. The cancel and deadline signal is a 32-byte direct buffer.
- **The C++ lane goes at stage (a).** Until then, owner option: switch it off by default now (D1), which restores determinism at once and costs nothing measured.
- **Packaging, recommended:** Windows x64 from this machine; Linux x64 and arm64 from a container on this machine (Docker and WSL are installed; B1 reports the Rust side); macOS on the Java path until a macOS build exists. A target ships as verified only after the parity gate has run on it once; until then it is guarded by the load-time self-test and the Java audit, or stays on Java (owner decision 3 and 4 of the port plan).
- **Two implementations.** Java first, same batch or a lower ceiling, fixtures move with the physics, anchors from Java methods to Rust functions, one layout document. Every rule deleted before the port is paid for once instead of twice.

### 6.2 The fluid side: groundwork, gate, port

**F0, in Java, always.** It is needed for any port and is expected to give most of the gain by itself.

1. Typed refusals in the kernel, the engine and the step (S07, S-13, S06): a status code and a `FailureKind`, one refusal type without a stack trace.
2. Flat `IslandTables`, `PassTables` and an evaluation workspace (FLUID_STEP_REVIEW 3.1, 3.5); the kernel boundary as one call with a `want` mask (X37).
3. The splits of `PassiveStepSolver` and `FluidTpEquilibrium` with explicit inputs (S-01, S-02, S01, S02).

**The gate.** After F0 and the wave B fluid and thermo batches, an island step is measured again on the bench scenarios. The port goes ahead only if (1) kernel calls, residual and Jacobian assembly and the LU are still the large majority of step time, and (2) step cost still limits plant size. PHASE2_PLAN states how each is read.

**F1, only on a passed gate.** The kernel half of the network port native, then one pass per call. For a native pass the laws it calls must cross as numbers: mover curves, heat curves as tables, vessel head, the tower with its controller planned per step (L-F1). The sparse LU is EJML's: a native pass either copies its pivot rule bit for bit or takes a stated shift, decided from the gate's LU share.

**What never moves.** The pass loop, the booking, the interval solver and the device laws stay in Java. The Java booking and the residual gate of the booked point check every Rust pass, so the fluid side has the same safety as the column, where the Java audit publishes. The equilibrium engine is not ported; it is reconsidered only if a profile shows flashes as the top cost after P20, P21, P24 and P25.

### 6.3 What is expected, and what would change the plan

Expected from the reviews' counts, before B1: most of the CPU and RAM gain comes from the free optimizations and the flat layouts in Java; the Rust port then adds the gain of arrays without bounds checks, no allocation and no JIT warm-up, and makes the cost of a solve predictable. The column's port goes ahead in any case (owner decision). The fluid step's port does not: it waits for the gate of 6.2, which is measured on the code as it stands after F0, not on today's.

What B1 measured (`PROFILE_REVIEW.md` on B1's branch, 2026-11-13; the tables are in PHASE2_PLAN, waves B, E and F), and what it changes:

1. **Column: the band solver, not the Jacobian.** Band solver 52 to 63 % of a solve, kernel 13 to 17 %, the degree-of-freedom ledger 5 to 17 %, residual and Jacobian assembly 5 to 7 %. The expectation above about the kernel was wrong in the useful direction: the kernel and the solver are both in stage (b) and are about four fifths of a solve once stage 0 has removed the ledger and the bookkeeping, so stage (b) is where the port's gain is measured. P1 keeps its place for the lines it deletes, not for time.
2. **Column: the route.** The learned start costs 27,000 to 30,000 checkpoints where the classical chain costs 184,000 to 258,000, and the running column always takes the classical chain on a first solve. This is larger than any gain above, needs no port, and is an owner decision because the published closure changes (PHASE2_PLAN decision 16).
3. **Fluid: the share is there, the need is not.** Kernel, linear algebra and assembly are 93 % of a step, so a port would reach nearly all of it. But 100 islands keep pace with the workers 21 % busy, and two free items (the kernel workspace kept, the triangular solves' reach cached) aim at a third of the step's bytes and about a seventh of its CPU. The gate stands as written; on today's readings it would not pass on need, and the bench needs a large-island scenario before it is run.
4. **Allocation is bandwidth, not pauses.** About 1 GB a second in every busy scenario, 0.5 to 2.5 % in G1 pauses, 72 to 86 % of it `double[]`. The flat layouts and the kept workspaces (P5, P-07, P20) are therefore a memory-traffic gain, to be judged by bytes per step and per solve.
5. **Machines cost nothing at rest.** 20 certified trains take 11 µs per 100 ticks; awake, 90 % of the time is the island solves. The machines lane needs correctness and coherence work (MACHINE_FAILURE_MODES_REVIEW), not speed.

## 7. Room for supercritical fluids

The kernel assumes nothing subcritical (THERMO_CORE_REVIEW 2.4). The assumptions are in what callers ask and in how answers are labelled. The room is made by four changes, none of which moves a subcritical result:

1. **Requests by slot, answers with traits.** `Root` becomes a selector (smallest, largest, lower Gibbs). A phase state carries `PhaseTraits` (root count, compressibility, volume ratio, identification parameter); the engine lists fluid phases lightest first; a label is one function of the traits a caller may ignore (X36).
2. **Consumers map phases to their slots in one place, by a continuous trait.** The network's three slots (PL 26-29), the client's phase tabs (G-C23), the facts' two liquid slots and gas (R, question 8) stay; one dense phase arrives as one fluid phase with one root.
3. **No infinity for "no saturation".** Water's saturation pressure jumps to infinity at the critical temperature (F21); the descriptor says "no saturation" instead. C4 is the first case of this defect and is fixed now.
4. **Films, heads and priorities follow density, not a label** (L-F24): a dense phase labelled vapour gets a film 20 times lower today.

The readiness list, by review, for the batch that adds supercritical support: THERMO_CORE_REVIEW 2.4 (19 sites), THERMO_ADAPTERS_REVIEW 2.9, FLUID_STEP_REVIEW 3.7 (16 sites), PLANT_COUPLING_REVIEW 3.6, COLUMN_REVIEW SC1, L-F24, P3-29, G-C23, RUNTIME_REVIEW question 8. For the column, a tray above the mixture critical point stays a capability limit with a typed refusal.

Owner questions for that batch, not for now: which fluids and states first; the alpha function's curvature step at the critical temperature (X34); the research-only grade of the two supercritical boxes (X35).
