# Documentation structure and agent collaboration: plan

Base `259d22a2c`, 2026-11-13. Written by the main thread from [INVENTORY.md](INVENTORY.md), the reviews' remarks on the guide, and what this batch's own delegation showed. The guide for agents is [`documentation/reference/COLLABORATION.md`](../reference/COLLABORATION.md); this file is the plan behind it and the changes it proposes to the documents, the tools and AGENTS.md. Nothing here is applied yet except the guide itself.

## 1. What is wrong today

1. **The code guide cannot be read as AGENTS.md tells agents to read it.** `code-guide/README.md` is 397 lines, 99 KB, about 40 000 tokens; an agent's file read stops at 25 000. Five chapters are over 100 KB (`fluid-science` and `fluid-runtime` 173 KB each). The guide is 191 467 words.
2. **The guide carries its own change log.** Every chapter opens with snapshot paragraphs that grow at each merge (they end at line 92 to 111 in the six largest chapters). The README adds "Facts the old guide got wrong", "Owner decisions applied" and one section per old batch. `CHANGELOG.md` and the batch READMEs already hold this history.
3. **The guide is stale where it states numbers.** `fluid-runtime.md` says protocol `fluid-19`, graph identity 9, state format 40 and facts schema 15; the code has `fluid-24`, 10, 50 and 25 (R17). The rotating-machinery chapter says the overload and overspeed trips exist; neither can fire (M-03). The `gui-creation` skill describes a column calculator screen that is gone (G-C10). File counts in the README's code map are typed by hand.
4. **Format numbers are spread over javadoc histories** (`FluidCheckpointCodec` 20 to 117, `FluidFacts` 40 to 81, `FluidNetwork` 1 to 68) and 15 protocol strings in 11 registrars (P3-22). Two batches took the same facts schema number and one was renumbered at the merge (0.26.0).
5. **`tools/` holds 516 MB tracked in 15 012 files.** 113 folders (171 MB) are named by no build file, test, skill or source and are not "In use" in the index; 76 files are over 1 MB (217 MB, mostly JUnit XML and world region files); one folder vendors numpy with its DLLs (43 MB); `tools/development.gradle` registers 24 tasks, 10 of which name a main class with no tracked source.
6. **Four Gradle tasks re-run selections another task already makes** (`fluidScienceTest`, `fluidRuntimeTest`, `v3HollandBenchmark`, `nativeSmoke`).
7. **No rule covers batches that run side by side**: who may edit a hot file, who takes the next format number, how an exact reference is re-recorded when the owner allows a shift.

## 2. The code guide's new shape

### 2.1 README: under 150 lines

Level 1 (what the mod calculates, the engine's flow, the four standing rules), level 2 (the code map and the routing table), the shortest reading paths. Nothing dated. The code map's file and line counts come from a script (2.4). Everything else in today's README moves to `code-guide/HISTORY.md` (kept, not maintained: new history goes to `CHANGELOG.md` and the batch README).

### 2.2 One chapter per module, one template

A chapter covers one module of the target layers ([SOLVER_DECOUPLING_PLAN.md](SOLVER_DECOUPLING_PLAN.md), section 2) and stays under 40 KB. The five chapters over 100 KB split along the modules the reviews drew (for example `fluid-science` into step, laws, booking and interval; `fluid-runtime` into engine, registry, save, present).

| Section | Holds | Limit |
|---|---|---|
| Read this when | symptoms that send a reader here | 10 lines |
| What it does | the module in plain words | 15 lines |
| Class map | class, role, lines; generated header, hand-written roles | a table |
| Main flow | entry to exit, as a call list | 30 lines |
| Contracts | what callers may assume; invariants; threads | |
| Laws and constants | each law once, with its file and line | |
| Formats | a link to the format table (2.3), never a number | |
| Tests and gates | which test proves which rule; the `--tests` filter | |
| Traps | misleading names, known limits | |
| Snapshot | one line: commit and date | 1 line |

No section says what changed. A batch that changes a module edits the sections that became wrong.

### 2.3 One format table

`documentation/reference/FORMATS.md`: one row per saved or wire format (facts schemas, state formats, shape fields, envelopes, the graph identity, protocols), with its current value, the constant that declares it, facts or state, and the batch that last moved it. The javadoc histories in the codecs reduce to the current meaning. With one `NetworkProtocol.VERSION` (P3-22) the wire side is one row.

The table is also where a batch reserves its number ([COLLABORATION.md](../reference/COLLABORATION.md), "Numbers").

### 2.4 Generated, and checked

- `tools/code-guide/map.mjs`: prints files and lines per package and the import matrix between the layers; writes the README's count columns and each chapter's class-map header. Merge preparation runs it.
- `LayeringTest` (quick suite): asserts the import rules that hold, starting with the one that holds today (`science` imports nothing from `runtime`, `world`, `network`, `client`), and gains a rule each time a cycle of CODEBASE_REVIEW section 3.1 is removed. A rule never leaves the test.
- `FormatsTableTest`: every constant the table names has the value the table states.

### 2.5 Stale texts to correct with the restructure

The numbers of `fluid-runtime.md`; the trips in `rotating-machinery.md`; "two job families" (there are three); the `gui-creation` skill's calculator screen; `PumpHealth`'s "online ticks"; `PhaseDraw`'s port order (L-F21); the class comment of `ProcessSolveServices` that names removed calculator jobs (R19).

## 3. `documentation/` and `tools/`

- `documentation/` is healthy: 142 batch folders, every one with a README, no file over 1 MB. Three rows use a free-text status ("Merged into ..."): make them "Implemented" with the version.
- `tools/`: apply the 1 MB rule of `documentation/` (AGENTS.md amendment below). In one documentation-only batch: move the 76 files over 1 MB to the main checkout's `research/` at the same path and git-ignore their types; replace the vendored `python-deps` by a `requirements.txt`; delete the 10 task registrations with no source; mark each of the 113 unreferenced folders "Concluded" or "Retired" in `tools/INDEX.md` and move the retired ones under `tools/retired/` (history stays in git; the repository's size on disk does not fall without a history rewrite, which is not proposed).
- Delete the four duplicate Gradle task selections, or state in `verification.md` that they are conveniences the lane never runs.

## 4. Proposed amendments to AGENTS.md (owner applies or rejects each)

1. **Results.** Replace "bitwise identical" as the only proof of a refactor: "A change that only moves code keeps every reference exact. A change that moves a reference states, in its review, the old and new values and the bound it was expected to keep; the bound comes from the solver's own tolerances. The final lane then re-records the reference, once, with both values in the lane's output. Conservation of mass, energy and components stays exact."
2. **Reports.** "Reports are at most 50 lines" becomes "A report's summary is at most 50 lines; a findings table may follow."
3. **Large files.** The rule for `documentation/` also covers `tools/`.
4. **Code guide.** "Read `README.md` first" stays, with the README under 150 lines; "the guide holds no history".
5. **Parallel batches.** One paragraph that points to `documentation/reference/COLLABORATION.md` for lanes, hot files and numbers.
6. **Native code.** "A Java method with a Rust twin changes in both in the same batch, or the batch lowers the native ceiling below it" and "a cargo build counts as a Gradle invocation; run it through Gradle" (the guard script then needs a line for `cargo`).
7. **No content hashes** as a written rule (today it is in the owner's memory and applied to material data only): "An identity is a declared revision. No saved or cached value is keyed by a hash of content." Three places then change: the layout stamp (E20), the certificate's graph digest (R11) and the built-in channel ids (P3-06).

## 5. What this batch's delegation showed

Twelve read-only reviewers and one bench thread ran in parallel on briefs kept in one tracked file. What worked and goes into the guide:

- A brief that names the scope by file, the rules by AGENTS.md section, what is already established and what is ruled out, and a fixed output format, gave reviews that could be merged without rework.
- "Every finding cites a file and line you read" and "say what you did not read" made the reviews checkable. The main thread read the cited lines of 28 findings: 20 held in full, 8 held in the part that was read (the rest of each is the reviewer's reading), none was refuted.
- Asking each reviewer where the plan was wrong returned ten corrections to the plan.
- Reviewers given a hypothesis table (the draft failure-mode matrix) refuted four of its rows from the code. A brief should say that a draft is a draft.

What did not work:

- Reviewers cannot measure (no Gradle in a subagent), so every cost is a count until the bench thread reports. A review batch should start its bench thread first.
- Two reviews used the same finding prefixes (F and C); ids must carry the package (the briefs now say so in the guide).
- One approval per delegation made the launch one large request. A standing approval for read-only reviewers inside a named batch would remove the wait without removing the owner's control over anything that writes code.
