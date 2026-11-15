# Working together on IChemE: a guide for agents

Status: in force since 2026-11-13 (batch `2026-11-13-codebase-review`; taken by the main thread under the owner's grant of that day, and named by `AGENTS.md`, "Parallel batches"). `AGENTS.md` and the owner's own instructions win where they differ. This guide adds what they do not cover: how several agents work on one code base at the same time without undoing each other.

## 1. The project in one page

Industrial Chemical Engineering (IChemE) is a Java 21 mod for Minecraft 1.21.1 (NeoForge). It simulates process equipment with real thermodynamics: a distillation column solver (V3), a fluid network solver on islands, a multiphase equilibrium engine, rotating machinery, heat units, a process simulator and an equipment constructor. At `259d22a2c` (0.30.0): 861 production files, 208 000 lines; 622 test files.

What never changes without the owner:

- Saves keep facts (what the player built and put in) and rebuild state. A fact is never dropped; fact fields are append-only; enums are saved by name with aliases.
- The engine publishes on its own schedule; nothing computes process state on the tick or in a packet handler.
- Results are deterministic: the same inputs give the same results on every JDK and platform. A reference may move only with a stated, justified shift.
- Physics first: a defect report states the expected behaviour, what the code does, and a proposal; a change that moves a reference is the owner's option.

Where to read: `AGENTS.md` (rules), `documentation/reference/code-guide/README.md` (what the code does and where), `documentation/INDEX.md` (every batch), the batch folder's `README.md` (its history and open items).

## 2. Lanes: who may write where

A lane is a set of packages with one writer at a time. A batch names its lane in its README's first lines. Two batches in different lanes may run side by side; two in one lane run one after the other.

| Lane | Packages | Hot files (one writer, always) |
|---|---|---|
| thermo | `science/math`, `science/thermo/**`, `science/material` | `PengRobinsonKernel`, `FluidTpEquilibrium`, `MaterialCatalog` |
| column | `science/column/**`, the Rust column crates | `V3ColumnCalculator`, `V3SimultaneousColumnSolver` |
| fluid step | `science/fluid/network/PassiveStepSolver` and its split, `fluid/solver`, `fluid/linalg` | `PassiveStepSolver` |
| fluid laws | the rest of `science/fluid/**` | `ConservativeTransport`, `FlowControl` |
| plant | `science/plant` (when it exists), `science/plant/column`, the still, `runtime/fluid` column and still classes | `ColumnOperation`, `Stills` |
| runtime | `runtime/**` but the plant and machine classes, `network/FluidNetwork` | `FluidWorldAuthority`, `IslandCoordinator`, `FluidFacts`, `FluidCheckpointCodec` |
| machines | `science/machine`, the shaft, store and health classes of `runtime/fluid`, `compat/create` | `ShaftTrains` |
| equipment | `science/equipment`, `world/**`, `registry` | `ProcessUnit`, `ShapeRasterizer`, `EquipmentStructure`, `EquipmentShape` |
| client | `client/**`, `science/tutorial`, `science/display` | `FluidDeviceScreen`, the kit |
| flowsheet and control | `science/flowsheet`, `science/assembly`, `runtime/control`, `power`, `assembly`, `instrument`, the other `network/` files | `SheetSizing`, `ControlFacts` |
| documents and tools | `documentation/`, `tools/`, skills, `AGENTS.md` | the three index files |

Rules:

1. A batch that must touch another lane's hot file says so in its plan, and the orchestrator checks that no other batch holds that lane.
2. A change that only moves code (a split, a package move) is its own batch, never mixed with a change of behaviour, and it merges before anything else touches the moved files.
3. Shared append files are edited row by row: `en_us.json` (keys appended in the batch's own block; other languages only in the translation commit), `documentation/INDEX.md`, `tools/INDEX.md`, `CHANGELOG.md`. On a conflict keep every row of both sides.

## 3. Numbers

Facts schemas, state formats, shape fields, the certificate replay revision and protocol versions are single counters that parallel batches would otherwise both take.

- The current value of each is in `documentation/reference/FORMATS.md` (to be created by the documentation batch; until then, in the constant the code declares).
- A batch that needs a new number reserves it before its first code commit: a documentation-only commit on `main` that adds a row "reserved by `<batch>`" to the table. The next batch takes the next number.
- A batch that ends without using its number removes its row.
- Wire protocols need no compatibility; they still need a new number at any payload change.

## 4. Sizes

- One batch changes one module, or about 1 500 lines of production code. A larger change is split into batches that each leave `main` working.
- A batch states its class in its README: STRUCTURE (references exact), PERF (exact, or a stated shift), PHYSICS (owner's option, with numbers), DEFECT.
- A PHYSICS or shifting PERF batch compares at most three forms on the quick check and runs one campaign for the form chosen (AGENTS.md).

## 5. Delegating

The owner's instructions set the three kinds of thread (main thread, subagent, sub-thread), their models and the approval each needs. This section is how to write the brief.

A brief is complete without the conversation. It holds, in this order:

1. The task in two lines and why it exists.
2. The worktree's full path and the branch.
3. The scope by file or package, and what is outside it (who owns that).
4. The AGENTS.md sections and skills that bind the task, by name.
5. What is already established (with the document that holds it) and what is ruled out.
6. For code: each step with its files, classes and methods; the physics or logic it rests on with the expected numbers; the pitfalls; the exact test commands and what each proves.
7. What done looks like, and the output's format.
8. For a sub-thread: what it waits on, how long, and the reporting cadence.

Keep the briefs of one batch in one tracked file in the batch folder (`BRIEFS.md`), with the common rules once at the top. The call that starts a thread then names the file and the section.

For a review:

- Give every package its own id prefix (`S3-P01`, not `P-01`).
- "Every finding cites a file and line you read. Say what you did not read."
- "A draft table in the plan is a hypothesis: say where the code refutes it."
- Ask for where the plan is wrong.
- Fix the output format: a summary of at most 50 lines, a module map, a findings table (id, file and line, what it is, proposed change, class, lines, risk, proof), open questions.
- A reviewer cannot measure. Start the thread that measures first; its numbers order the findings.

### Implementation by a subagent that does not build

A subagent has a short cache and must not wait, so it runs no Gradle, cargo or JVM. It can still write code that a build accepts, if the main thread does the building. The pattern, as batch `2026-11-13-codebase-review` ran it (eleven packages; every one compiled at the first build, two had one failing test each, both in the test's own fixture or assertion):

1. The brief is a section of the batch's briefs file. The call that starts the subagent adds only the frame: fast-forward your worktree to the batch branch first; run no build; re-read every changed file and grep every changed symbol in every source set; the line-ending rule; one commit per item; what is already known; what done looks like.
2. The brief says which of two kinds the task is: **structure only** (every result bit for bit; "if it cannot be done without changing an operation, a saved name or a message, leave it and say what blocks it") or **a stated shift** (the quantity and its bound, in its own last commit, so that it can be dropped).
3. The brief names the pitfalls that only the build shows: the gate groups' class lists in `build.gradle`, saved and wire names, a package-private access lost by a move, a text a test pins.
4. The subagent's report lists the exact test classes with their Gradle task, what each run proves, and every point where it was unsure the code compiles.
5. The main thread test-merges the branch without committing (`git merge --no-commit --no-ff`), compiles every source set, runs the named classes, reads the diff of anything that surprises, fixes small faults there, and commits the merge with the counts in its message. A branch that fails its premise stays unmerged and is named in the batch README.
6. Lanes are packages. Two subagents never edit one file, except lists that grow at the end (a rules list, an index): each adds its rows on its own lines and the main thread resolves the merge.

What the main thread keeps for itself: anything that needs a measurement, any decision the physics does not force, and every merge.

## 6. Receiving

- A report is a claim. Before a finding is passed to the owner as a fact, read the cited lines. Say which findings were checked and which were not.
- A thread's files are not in this checkout until its branch is merged; read them from its worktree, and merge only on the owner's word.
- A review's cost estimate is a count until something measures it. Measure before a batch is built on it: in batch `2026-11-13-codebase-review` three such estimates did not survive their measurement (a native band solve was slower than Java's, a cache of the sparse LU's reach saved nothing on a loaded island, a preconditioner cost more than it saved), and one refactor's premise failed its first test run.
- A subagent's decision request is answered from the physics, in the order of AGENTS.md ("Physics and defects"), before its branch merges.
- While a final lane's campaign runs, start no second build and keep other work on the machine light: wall-deadline holds appear under load and each costs a replay.
- Commit the reports as they are; write the synthesis beside them, with the corrections named.

## 7. Results that move

- A STRUCTURE batch proves itself by the lane's exact references.
- A batch that moves a reference writes, in its review: the quantity, the old and new values, the largest relative shift, and the bound it was expected to keep with the reason for that bound (the solver's own tolerance, a truncation order, a rounding count). Then the lane re-records, once.
- A shift larger than its bound is a defect of the change, not a new reference.
- Conservation on the ledger is exact in every class.

## 8. Java and Rust twins

Once a solver has a Rust twin ([RUST_COLUMN_PORT_PLAN.md](../2026-11-13-codebase-review/RUST_COLUMN_PORT_PLAN.md), section 9):

1. Change Java first; it is the reference and the publication audit.
2. Port the change in the same batch, or lower the default native ceiling below the changed layer in the same commit. A stale native layer is never left on.
3. Re-record the parity fixtures when an accepted state moves, under section 7.
4. Run the anchor tool; each changed Java method names its Rust function, or the batch review says why not.
5. Build native code through Gradle only; it counts as a Gradle lane.

## 9. Handing over

- The batch `README.md` is the hand-over: status, one history line per task (commits, gates, decisions), documents, code areas, open items. A new agent reads it and nothing else to continue.
- The owner's decisions are quoted with their date in the README, and in the agent's memory only as rules that outlive the batch.
- An agent that stops in the middle leaves the tree committed (a WIP commit, never a bare stash), the next step written in the README's open items, and any running thread named there.
