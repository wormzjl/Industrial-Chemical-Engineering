# D-R5 The saved facts in sections (R12): report

Batch D-R5 of `WAVE_D_BRIEFS.md` (subagent, Opus high). The branch was fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `743512ec1`. Two code commits: `967e561b0` (step 2, the sections) and `49a4976b5` (step 3, the builder). Then this report, with the code guide. **Nothing was compiled or run** (the brief: no Gradle, no JVM). This is structure only: the stream, the schema (25), every field's name, order, width, tag and default, the validation and the refusal of a damaged section stay as they were. No corpus image was added or edited.

Summary:
- **Step 1 (the table): done**, in section 1.
- **Step 2: done.** Six sections, one per subsystem, are listed in `FactsSection.ALL` in today's stream order. `FluidFacts.world` walks the list.
- **Step 3: done.** `World.builder()` replaces the five telescoping constructors. Two call sites changed, both tests.
- **Step 4: left as it is.** Section 4 gives the reason.
- `FluidFacts.java` went from 1,128 to 904 lines. New files: `FactsSection` 28, `RegistryFacts` 64, `ColumnFacts` 79, `MachineFacts` 67, `BuildFacts` 76, `StillFacts` 47, `ConverterFacts` 44.
- **Checks** (node scripts in `tmp/dr5/`, gitignored):
  - `split.mjs` made every moved line by copying it from the original by line number.
  - `verify.mjs` checks four things:
    - **Encoder and decoder, call by call.** It lists the encoder's write calls and the decoder's read calls with their argument text, in text order (the stream order), with qualifiers normalised. It does this before and after. The encoder has 59 calls before and the same 59 after; the decoder has 78 and 78.
    - **Every record codec, moved or kept.** Its call list is the same before and after (29 methods).
    - **The state and island codecs.** Their text is unchanged (7 methods).
    - **Removed lines.** Each line removed from `FluidFacts` reappears elsewhere, except 28 lines, all named in section 3.3. Braces, parentheses and brackets balance in every touched file, and every touched file is CRLF.

## 1. The table (step 1)

The topology unit's facts section is one root record (`FluidFacts.section`, a text table, then the record). Its fields, in stream order. "Before" is `FluidFacts.java` at `743512ec1`.

| # | Field (record type) | `World` component | `Snapshot` component | Subsystem, section now | Encoded / decoded before (lines) | Default when absent | Since schema |
|---|---|---|---|---|---|---|---|
| 1 | `count` | `nextIdentity` | `nextIdentity` | registry, `RegistryFacts` | `world(Snapshot)` 264 / `world(byte[])` 319 | 0 | 1 |
| 2 | record: package id, components, retired text, revision | `basis` | `basis` | registry | 264 / 322-324 | null, refused "missing basis" | 1 (revision 4) |
| 3 | list of `registration` | `registrations`, `removedMovers` (derived) | `active` | registry | 265-266 / 325-327 | empty | 1 (its fields grow inside: 2, 3, 5, 6, 7, 22, 23) |
| 4 | record `total` | `constructed` | `constructed` | registry | 267 / 328 | null, refused | 1 |
| 5 | record `total` | `destroyed` | `destroyed` | registry | 267 / 328 | null, refused | 1 |
| 6 | list: uuid, position, player, solids, energy | `recoveries` | `recoveries` | registry | 268-270 / 329-331 | empty | 1 |
| 7 | record: energy, removed moles, removed solids | `restated` | `restated` | registry | 271-273 / 332-335 | `Restated.NONE` | 1 |
| 8 | list of `column` | `columns` | `columns` | columns, `ColumnFacts` | 275 / 336 | empty | 6 (its fields grow inside: 8, 9, 21) |
| 9 | list of `shaft` | `shafts` | `shafts` | machines, `MachineFacts` | 277 / 338 | empty | 10 |
| 10 | list: id, J, metered | `stores` | `stores` | machines | 279 / 340-343 | null: unmetered stores for the powered units (`electricStores()`) | 11 |
| 11 | list: id, health | `health` | `health` | machines | 281 / 345-348 | empty: every pump new | 12 |
| 12 | list: id, two paints | `builds.paints` | `builds` | build facts, `BuildFacts` | 285 / 350-353 | empty | 15 |
| 13 | list: origin, shape text, paints, name | `builds.structures` | `builds` | build facts | 286-287 / 354-360 | empty | 15 |
| 14 | list: id, facing, rack shape | `builds.racks` | `builds` | build facts | 289 / 362-365 | empty | 15 |
| 15 | list: id, name | `builds.names` | `builds` | build facts | 291 / 367-370 | empty | 15 |
| 16 | list of `still` | `stills` | `stills` | stills, `StillFacts` | 293 / 372 | empty | 16 |
| 17 | list of `converter` | `converters` | `converters` | converters, `ConverterFacts` | 295 / 374 | empty | 24 |

`Snapshot` has two components that this section does not hold:
- `onlineTick` is the checkpoint core's clock, given to `World.snapshot`.
- `events` are state: the topology state section, `FluidFacts.topologyState`.

The required checks (basis, constructed, destroyed) ran after every field was read (line 375). They still do.

Who built a `World` before:

| Source set | Call site | Constructor |
|---|---|---|
| main | `FluidFacts.world(byte[])`, line 376 | canonical (15) |
| main | the telescoping chain itself: 14→15, 13→14, 12→14, 11→12, 9→11 | |
| test | `ConvertersTest` 215 | 14 (without converters) |
| test | `FluidFactsCompatTest` 708 | 13 (without stills) |
| fluidGameTest, tools | none (grep `new FluidFacts.World(`, `FluidFacts.World`; every `new World(` hit there is a rig class of its own) | |

## 2. The sections (step 2)

`FactsSection` (an interface) has two methods: `write(Fields f, Snapshot s)` and `read(Record f, World.Builder world)`. `ALL` = `RegistryFacts`, `ColumnFacts`, `MachineFacts`, `BuildFacts`, `StillFacts`, `ConverterFacts`. The grouping follows the table's subsystems. Each schema batch's root fields form one run, so no section is split.

| Section | Root fields | Holds | Shared records it calls in `FluidFacts` |
|---|---|---|---|
| `RegistryFacts` | 1-7 | `total` codecs | `registration`, `uuid`, `position`, `solids`, `required`, `valid`, `warn` |
| `ColumnFacts` | 8 | `column` codecs | `setpoints`, `account`, `position`, `solids`, `inventory` |
| `MachineFacts` | 9-11 | `shaft` codecs | `position` |
| `BuildFacts` | 12-15 | `ORIGIN_ORDER`, `structure` (with the shape's SNBT read, the only `net.minecraft` use) | `position`, `warn` |
| `StillFacts` | 16 | `still` codecs | `account` |
| `ConverterFacts` | 17 | `converter` codecs | |

What stays in `FluidFacts`:
- The records that the topology state (the queued events' registrations, the column states) or the island facts share stay there, made package-private: `registration` and its parts (`control`, `tower`, `heatUnit`, `geometry`), `account`, `setpoints`, `solids`, `uuid`, `position`, `required`, `valid`, `warn`.
- The state codec is untouched (`topologyState` keeps `FluidFacts::registration`).

The class comment of `FactsSection` states the rule this layout implies. The root record is positional, so a section that is not last never gains a root field. Its new facts go inside one of its nested records, or into a new section appended to `ALL`.

### 2.1 Why the bytes are identical

| Before: the lambda of `world(Snapshot)` | After: `ALL` in order |
|---|---|
| 264-273: identity counter, basis, sorted registrations, two totals, recoveries by uuid, restated | `RegistryFacts.write` (the same 10 lines) |
| 274-275: column units by identity | `ColumnFacts.write` |
| 276-281: shafts, stores, health | `MachineFacts.write` |
| 282-291: paints, structures by `ORIGIN_ORDER`, racks, names | `BuildFacts.write` |
| 292-293: stills | `StillFacts.write` |
| 294-295: converters | `ConverterFacts.write` |

- Every section writes into the same root `Fields` object, so the root record's field count, and so the record header, is the same.
- The text table is built from the `text` calls in the order they run. That order is unchanged, so every text index is the same.
- The sorts (`TreeMap`, the comparator on device id, `ORIGIN_ORDER`) moved verbatim.
- The decoder reads the same 78 calls, with the same defaults, in the same order, from the same root `Record`. `FluidFacts.read` still ends the record after the reader returns, so a record with more fields than this build reads is refused as before. Every refusal message is the same text.

### 2.2 Two reads that now run a little earlier (no observable change)

- `BuildFacts` makes its `WorldTopologyLedger.Builds` before the stills and converters are read. Before, it was made inside the final `new World(...)`. The compact constructor of `Builds` throws only on these entries:
  - a name with id ≤ 0, or one that `named` rejects;
  - a bare paint, or a paint with id ≤ 0;
  - a structure origin on a lane;
  - a rack with id ≤ 0.

  The reader has already refused each of these, with its own message. So the constructor cannot throw on data the reader accepted, and no refusal changes.
- `RegistryFacts` hands over `List.copyOf(removedMovers)` after the registrations, not at the end. The list is complete by then and holds no nulls.

## 3. The constructors (step 3)

`World.builder()`:
- Every component starts at the default that its read gives a world written before it: the lists and maps empty, `restated` `NONE`, `builds` `NONE`, `stores` null.
- The identity counter starts at 0. The basis and the two totals start at null: the reader requires them.
- `build()` calls the canonical constructor, now the only one. Its compact constructor (copies of health, stills and converters) is unchanged.
- The decoder uses the builder (step 2).
- The five telescoping constructors are gone.

Call sites changed:
- **main:** 0 (the reader moved to the builder in step 2).
- **test:** 2.
  - `ConvertersTest` 215. Its old call passed `Map.of()` for the stores, so it sets `.stores(Map.of())`: the builder's default is null.
  - `FluidFactsCompatTest` 708-709. All 13 old arguments are set, and stills and converters stay at their defaults, as the 13-argument constructor gave.
- **fluidGameTest:** 0.
- **tools:** 0.

### 3.3 Lines not matched by `verify.mjs`, with reasons

- 2 lines: the old `return new World(...)`. It is now the builder setters in each section and `world.build()`.
- 1 line: `ColumnFacts` line 59. `s.id,s.revision,s.density,s.heatCapacity` became `s.id(),…`. Outside the `FluidFacts` nest, a `Solid` record's private fields are reached through its accessors, which give the same values.
- 25 lines: the five removed constructors (their comments, signatures and `this(...)` calls).

## 4. The ledger's snapshot (step 4): left as it is

Regrouping `WorldTopologyLedger.Snapshot` into the sections would change what a snapshot constructs and when:
- **Its compact constructor validates across the groups**, so a section's values cannot be checked alone. It checks:
  - a column's identity against `active` and `nextIdentity`;
  - a held device against the columns;
  - a shaft's identity against `active` and the columns;
  - the stores, health, stills, converters, paints and racks against `nextIdentity`;
  - every account against the basis axis.
- **Its accessors are read in place.** `WorldTopologyLedger.snapshot()` builds a snapshot from the live maps when the ledger's version moves. It also rebuilds one in its cheap branch, where only the online tick or the build facts change, reusing the shared parts by identity (`shared.builds!=builds`). Section records would add an object per section at each of these builds, and change what the cheap branch shares.
- **It is a public record with 16 components and eleven public constructors (ten telescoping).** It is built at 8 sites in main, 28 in test, 1 in the GameTests and 4 in tools. Its accessors are read across `FluidWorldAuthority` and the plant classes, which the brief leaves out of this batch.

The facts sections read and write the snapshot's components through its existing accessors, so the R12 goal does not depend on step 4. A new subsystem still adds a snapshot component, a setter and a section.

## 5. Tests for the main thread

Files changed since the last pass:
- **main:** `FluidFacts.java`, and the seven new files `FactsSection`, `RegistryFacts`, `ColumnFacts`, `MachineFacts`, `BuildFacts`, `StillFacts`, `ConverterFacts`.
- **test:** `ConvertersTest.java` and `FluidFactsCompatTest.java`.
- **documents:** the code guide (`fluid-runtime.md`, `README.md` counts).

| Task | Classes | What it proves |
|---|---|---|
| `compileJava`, `compileTestJava`, `compileFluidGameTestJava` | all | the static imports, the package-private helpers, the builder and the two rewritten tests compile |
| `test --tests` | `FluidFactsCompatTest` | every corpus image under `src/test/resources/compat/` reads, and a world re-encodes byte for byte; refusals of damaged and newer sections; the restated world built with the builder |
| `test --tests` | `FluidCheckpointFormatTest`, `FluidCheckpointStoreTest`, `FluidCheckpointCodecTest`, `VesselRebuildTest`, `MixedSetMergeTest` | the envelope, the store's load ladder, the facts route (`rebuild` on a `World`) |
| `test --tests` | `WorldTopologyLedgerTest`, `StillRuntimeTest`, `ConvertersTest`, `ColumnRunTest`, `ShaftCouplingTest`, `MachineHealthTest`, `DrumReservoirTest`, `OpenTankRuntimeTest`, `PipelineFittingTest`, `PhysicalFluidTopologyTest` | each section's round trip (stills, converters, columns, shafts, stores, health, builds, registrations) and the facts-never-carry-a-hold checks (`assertArrayEquals` on `FluidFacts.world` bytes) |
| `test --tests` | `AccessoryStoreCompatTest`, `DeviceNamesCompatTest`, `PipeLineCompatTest` | the brief's compat classes (grep `*CompatTest`); none loads a changed file unless `affected.mjs` says so |
| `test --tests` | `FormatsTableTest`, `LayeringTest` | `FluidFacts#SCHEMA` still declared where FORMATS.md says (line 82; `ALIASES` line 84 unchanged); the moved `net.minecraft` use in `BuildFacts` stays within the runtime layer's rules |
| final lane | `EquipmentCompatGameTests` and the fluid GameTests | the compat GameTests through the saved data |

`tools/merge-lane/affected.mjs` lists the classes whose load set holds a changed file. Its list supersedes the rows above where they differ.

## 6. What a compile must confirm

Each of these was checked by reading and by grep, not by a compiler:
- **`RegistryFacts.java` 27, 30, 48: method references** `FluidFacts::registration` and `FluidFacts::uuid` from another class. They resolve to the overloads that are now package-private. `registration(Record)` stays private, so it is not a candidate (BiConsumer arity 2 versus Function arity 1).
- **Single static imports of overloaded names** in the six section files (`solids`, `position`, `uuid`, `account`, `setpoints`, `registration`, `required`, `valid`, `warn`, `inventory`). All their overloads are accessible except the private `registration(Record)`. No section declares a method of an imported name.
- **`import …FluidFacts.Record`** in `FactsSection` and the six sections. It shadows `java.lang.Record` there; no file uses the latter.
- **`FluidFacts.java` 277:** `world.basis`, `world.constructed` and `world.destroyed` read private fields of `World.Builder` from the enclosing class (nestmate access).
- **`ColumnFacts.java` 59:** `Solid` accessors in place of field reads.
- **`ConvertersTest.java` 215:** `.stores(Map.of())` infers `Map<Long, ElectricStore.Facts>` from the setter.
- **`FluidFactsCompatTest.java` 708-709:** `ArrayList` arguments to `List` setters.
- **`FactsSection.ALL`:** builds each section through its implicit package-private constructor.
