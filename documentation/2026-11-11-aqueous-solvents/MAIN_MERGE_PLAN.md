# Bringing main into the branch before the final lane

Batch 2026-11-11-aqueous-solvents. Owner, 2026-11-12, after "Run final lane": "Main got a lot of changes, adapt".
The final lane compares against main's last lane (0.27.1), so the branch must hold main first. A plan; not built.

## 1. Where the two sides stand (measured, `git merge-tree`, nothing touched)

| | Branch point | main | Branch |
|---|---|---|---|
| Commit | `ff6f6892f` (0.26.0) | `ccde84295` (0.27.1), 79 commits | `b06d025b1`, 189 commits |
| Changed | | 189 files, +9956 / -528 | 368 files, +30600 / -6795 |
| Fluid facts schema | 21 | 23 | 22 |
| State format | 46 | 47 | 48 |

main brought two merges: 0.27.0 (the simulated time factor, default 4; the drum operation fixes; the gas
blanketing valve; the pressure safety valve; the flare tip as a resistance) and 0.27.1 (the valve follow-ups).
It changed 54 main Java files and added 4 (`GasBlanket`, `BlanketValveGeometry`, `ReliefValveGeometry`,
`SimulationTime`). 37 files changed on both sides, 20 of them main code.

11 files conflict: `FluidFacts`, `FluidCheckpointCodec`, `FluidThermodynamics`; the tests `FluidFactsCompatTest`,
`FluidCheckpointStoreTest`, `DeviceColumnTest`; the corpus image `compat/fluid/schema-22` (three files, added on
both sides); `documentation/INDEX.md`; the code guide's `fluid-runtime.md`.

## 2. The two things that are not mechanical

**The facts schema number 22 means two things.**
- main 22: a device spec's gas blanket set pressure, the port role `BLANKET`. main 23: a device spec's relief
  set pressure, a pipe's relief outlet face, the port role `RELIEF`. Both are released on main.
- Branch 22: a vessel's oil-liquid and aqueous-liquid amounts, after its `rigid` (W3b step 6).
- Rule (AGENTS.md, saves): fact fields are append-only and a field never changes meaning. main's 22 and 23
  stand. The branch's vessel fields become schema 24: read only where the saved schema is 24 or more.
- The corpus: main's `schema-22` and `schema-23` images replace the branch's `schema-22`; a new `schema-24`
  image is written by the merged code.
- Consequence: a world saved by this branch before the merge (its own 22) does not load after it. No released
  version wrote that format; only test worlds of this worktree hold it.

**main's new code was written on the engine this batch replaced.** Its lines read a node's state as the
compounds' liquid and vapour, liquid water, steam and the compounds' gas pressure
(`held.state(t, p, v.liquid, v.vapor, v.waterLiquid, v.waterVapor, v.hydrocarbonPressure)`). The branch's state
is three slots on the island's list with water last (`model.state(t, p, vapor, liquid, aqueous)`). Lines that
merge without a textual conflict can still fail to compile or read the wrong quantity.

## 3. Resolution, file by file

- `FluidFacts`: `SCHEMA = 24`; the history comment keeps main's 22 and 23 and renumbers the branch's entry to
  24; the vessel row's two amounts are read where the schema is 24 or more; the kept reservoir is the branch's
  state (`v.state(held)`) with main's `.withBlanket(r.spec().blanket())`.
- `FluidCheckpointCodec`: `VERSION = 49`; the history keeps main's 47 (the blanket set pressure) and lists the
  branch's two entries (the tower fill's result, the three slots) under 48 and 49 by content; a node reads the
  branch's three sparse slots and builds main's inventory `.withBlanket(t.blankets[n])`. State: a mismatch
  drops it and the engine rebuilds; no migration.
- `FluidThermodynamics` (3 hunks): the branch's model is the structure; main's additions are carried onto it.
- `GasBlanket`, the relief valve, the flare tip, the drum fixes, the valve follow-ups: every read of the gas
  pressure, the vapour amounts or the liquid level goes to the three-slot state's own accessors. The gas a
  blanket admits and a relief valve releases is the gas slot's.
- Time: every time step or rate the branch added after 0.26.0 (the still's head solve and batch integration,
  the cooling tower's fill, the column's operation) goes through main's `SimulationTime`, as main converted the
  code it had.
- The open vessel: main's blanketed or relieved vessel is closed; the branch's crossing rule
  (`FluidThermodynamics.crossing(..., rigid, open, ...)`) takes `open` from the inventory, unchanged.
- Tests: `FluidFactsCompatTest` keeps main's cases for 22 and 23 and the branch's reading by id (`savedBasis`,
  `onto`), and gains 24; main's new tests take the fixtures by id (W6b's helpers).
- `documentation/INDEX.md`: every row of both sides. `fluid-runtime.md`: both sides' text.
- `gradle.properties`: main's `mod_version` 0.27.1; no bump here.

## 4. Steps

1. M0, read only: main's batch READMEs (`2026-11-11-gas-blanketing`, `2026-11-11-valve-followups`, the time
   factor and drum batches) for what its code does and its owner decisions.
2. M1: `git merge --no-ff --no-commit main` in this worktree. Resolve as section 3. The merge is committed
   only when `compileJava compileTestJava compileFluidGameTestJava` pass. It holds resolutions and the reads
   moved to the three-slot state, nothing else. No rebase, no amend after it.
3. M2, saves: schema 24, format 49, the corpus images, `FluidFactsCompatTest`, `FluidCheckpointStoreTest`.
4. M3, the checks of section 5, each by one filtered test class or one reading of the code.
5. M4, filtered runs (4 workers): main's two batches' test classes, the saves classes, the batch's gate classes
   (the engine and network gates of W3, `StillRuntimeTest`, `BatchStillTest`, `V3AqueousColumnTest`,
   `ColumnWaterTest`, `EthanolMainDataTest`, `DerivedPairsTest`), `DeviceColumnTest`. Not the suite, not the
   GameTests, not the campaign: the final lane runs once after this.
6. M5: the guide and the index; one report.

## 5. Physics checks after the merge

- A blanketed tank of water under nitrogen at 20 C drawn down: the valve admits nitrogen and the gas slot holds
  nitrogen with water at its vapour pressure (2.3 kPa); the liquid dissolves nitrogen by its Henry pair
  (about 1e-5 mole fraction at 1 bar). The flow admitted equals the volume drawn within 1 %.
- A relief valve on a closed vessel heated at constant volume lifts at its set pressure and releases gas from
  the gas slot; a vessel full of liquid releases liquid.
- A wash still (W4) at time factor 4 boils 60 L in a quarter of the ticks, with the same heat per simulated
  second; its head solve cadence is in simulated seconds.
- main's tests that pin numbers move by the batch's equation of state (derived pairs for methane and
  nitrogen, water in the cubic). A move under 1 % is re-pinned with that cause; a larger one is a stop.

## 6. Stops

- A fact of main's 22 or 23 that the merged reader cannot keep.
- A main rule that contradicts an owner decision of this batch, or the reverse (name both).
- A pinned number of main's tests that moves by more than 1 %, or a hold in a main test that passed on main.
- A W3 to W6 gate number that moves.

## 7. After it

The final lane the owner approved ("Run final lane": a new sub-thread on Opus medium) starts on the merged
branch: `crude_unit`'s re-record, then `tools/merge-lane/lane.sh head` with `LANE_OUT=tmp/final-lane`, against
the 0.27.1 lane archive (`valve-followups-final-lane-logs-2026-11-11.tar.gz`, the campaign at factor 4).
`LanguageFilesTest` and `UnitTextTest` fail in it until the translation commit.

## 8. Delegation request

**Sub-thread** (`start_session`, 1-h cache), reusing the live `w5-column`: it does steps M0 to M5. Done is the
merge commit and its follow-up commits on this branch, the filtered runs of M4 passing, the tree clean, one
report with every re-pin and its cause. Not main-thread work: compiles and about ten filtered Gradle runs to
wait on, 2 to 4 hours. Model and effort: Opus high (hard: a merge across the save formats, the solver's state
and main's new devices). Reuse: it is live, on that model and effort, and holds the batch's fixtures, column
and data context; its brief is this plan. It reports every 50 minutes, at once on a stop of section 6, and at
the end.

## 9. Owner decision (2026-11-12)

"Approve": the merge as planned, built by the live sub-thread `w5-column` (Opus high). The main thread commits
no document while the merge is open in the working tree (git refuses a commit by path during a merge); the
record of the merge follows its commit.
