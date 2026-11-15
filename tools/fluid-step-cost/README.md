# Fluid step cost probes

Batch [`2026-11-15-fluid-step-cost`](../../documentation/2026-11-15-fluid-step-cost/README.md), step S0 of [FLUID_STEP_COST_PLAN.md](../../documentation/2026-11-15-fluid-step-cost/FLUID_STEP_COST_PLAN.md): measure what decides the Jacobian pattern form (S1). Nothing here is a gate or part of `src/main`.

## Purpose

The step's Newton (`SparseNewton`) keeps an entry of the declared Jacobian only where its difference is not exactly 0 (`Pattern.numericMatrix`), so the pattern the LU factors follows rounding. The census says, for every class of declared entry, whether it is never, sometimes or always kept, and how large a sometimes-kept entry is against the largest entry of its row.

- **Class**: row kind (vessel component balance, energy, wall, equilibrium, junction mixing, continuity, junction enthalpy, normalization, solids, connection law, actuator, filter loading, weir), column kind (amount, phase split, temperature, pressure, solid moment, law flow, head, filter loading, weir flow) and relation (own block; the other node of a connection, by whether the column's node is upstream, downstream or at zero law flow in the iterate; a connection row and its end's column, the same three ways; the connection's own column; a remote suction's supplier). Read off `StepEquations`' layout and `PhaseLayout`'s order of a node block.
- **Per pattern** (the identity of the declared `offsets` array; the first 256, the rest counted untracked), per entry and direction: seen zero, seen nonzero, the largest `|value| / max |value| of its row`.
- **Report**: a table per class and one per relation (declared, never kept, always kept, flipping, the flipping ones binned by largest ratio below 1e-12, 1e-12 to 1e-9, 1e-9 to 1e-6, above; sightings and kept sightings over every factorization), the totals, and the factorizations of other equations (vessel closures, junction splits, crystal closures) by class name.

The counters that go with it are in `SolverDiagnostics` (S0, same batch): `jacobianDeclaredEntries` beside `jacobianNonzeros`, `luFactorEntries` and `transportFactorEntries` (L plus U per completed factorization). The census reads the Jacobians through `SolverDiagnostics.patternObserver`, which `SparseNewton.factor` calls before it drops the zeros.

## How to run

One Gradle lane (AGENTS.md); the harness's worlds run in the census's JVM:

```
./gradlew --console=plain --no-configuration-cache --init-script tools/fluid-step-cost/census.init.gradle test \
    --tests '*PatternCensusTest' -PholdFuzzSeeds=1-200 -PholdFuzzThreads=4 -PcensusOut=<absolute file>
```

`-PholdFuzzOut=<file>` names the harness's own report (default: `hold-fuzz-report.txt` beside the census); the other `holdFuzz*` switches are the bundled harness's (`../unified-thermo-port-forms/hold-fuzz-bundled/hold-fuzz.init.gradle`). Without `-PcensusOut` the census goes to `logs/census.txt` here (git-ignored). Put run output under the worktree's `tmp/<name>/`.

## Entry points

| File | Role |
|---|---|
| `census.init.gradle` | Attaches the bundled hold-fuzz harness's `src/` and this `src/` to the test source set; forwards `holdFuzz*` and `censusOut` |
| `JfrAncestors.java` | A JFR recording's samples by what is on the stack, not by the top frame: `java -Dfrom=<epoch ms> tools/fluid-step-cost/JfrAncestors.java <recording.jfr> 'name=regex&regex' ...` counts, per rule, the samples whose stack holds a frame for every pattern (`Class.method`), among those with a frame under `-Dbase` (default the fluid science packages). `-Devent=jdk.ObjectAllocationSample` sums allocation samples by their weight (bytes); `-Dtop=N -Ddepth=D` prints the first rule's N heaviest stacks. Run with the JDK's `java` (single-file source). The bench's `report.json` gives `measuredStartEpochMillis` for `-Dfrom` |
| `src/com/wormzjl/icheme/science/fluid/network/PatternCensusTest.java` | Installs the observer, runs `HoldFuzzTest.placementSequenceFuzz` (by reflection: it is package-private in `runtime.fluid`), removes the observer, writes the census. In `science.fluid.network` to read `StepEquations`' package-private layout |

## History

- 2026-11-15: made (S0). Counters, observer hook and census; not yet run.
- 2026-11-15: the census run on seeds 1 to 200 and extended with the transitions between consecutive factorizations; `JfrAncestors.java` added (CPU by stack, then allocation by weight and the heaviest stacks) for the bench pair of S2.
