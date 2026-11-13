# C4c One port arithmetic for columns and stills (C6): report

Batch C4c of `WAVE_D_BRIEFS.md` (subagent, Opus high), branch `worktree-agent-a66aecced74957ef7` after a fast-forward to `claude/codebase-review-refactor-8a85ef` at `f2543d8fa`. One code commit, `063531ac3`. **Nothing compiled or run** (the brief: no Gradle, no JVM); every changed file was re-read and every changed symbol grepped in `src/main`, `src/test`, `src/fluidGameTest` and `src/mcpCompat`. No saved field, name, unit or format changes; `Stills`' solver construction, every `StillColumn` reference and `ColumnOperation`'s solve dispatch are untouched.

**Result: a small extraction.** The review counted about 150 duplicated lines. Read side by side (table below), the two units share far less: the constants, the list mapping, the mass, the account-as-of-the-fence step, the idle plan, the outlet debit and the dated-log window. Every other block that looks alike computes something different. `science/plant/port/PortAccounts` (85 lines with its docs) holds the shared part: `CYCLE_TICKS`, `FENCE_TICKS`, `onIsland`, `onWorld`, `mass`, `less`, `idle`, `debit` (with `Overdrawn` and `Debit`) and `keep`. Both units call it. In the units, `src/main` loses 51 lines and gains 31. **No `UtilityDebt` class:** the two utility debts have no operation in common (rows 9 to 12), so a shared class would only put two different laws in one file.

Differences found between the copies. All are kept, none is unified:

- **F1 (trace guard):** the column's outlet plans nothing if its account holds 1e-9 kg or less (`TRACE_KG`). The still has no such guard. The column keeps the guard in its own code.
- **F2 (gift aggregation):** the column adds the signed booked moles first and clamps the sum at 0 in its debit. The still clamps each EXCHANGE boundary at 0 before it adds them. The two differ when one outlet books entries of opposite sign in one commit. The still keeps its per-boundary clamp in `booked`. The shared debit clamps `max(0, g)`, and on the still's sums this clamp gives the same bits: each sum starts at +0.0 and adds values that are +0.0 or more (or NaN), and `Math.max(0, x)` returns `x` unchanged for those values.
- **F3 ("water always"):** the column's `compounds` adds the literal `SplitTable.WATER`, even on an axis without water, and uses a `HashSet`. The still's adds the last entry of the axis, whatever it is, and uses a `TreeSet`. Not extracted.
- **F4 (heat read):** the column's water side reads its transit as of the fence (`due` = transit less what it owed after *start* − fence). Its rate is the side's last-cycle rate, plus the backlog beyond `rate·(seconds + cycle)` spread over the 60 s hold. The still's water side reads the live transit with no fence. Its rate is the pot heat plus the backlog beyond `hold·heat`, spread over 6 holds. The still's read can therefore depend on when the pot island's or the outlet island's booking lands. I did not measure this, and I found no still test that pins timing independence. This is a finding for the owner, not changed here.
- **F5 (window edge):** the column counts heat owed with tick > *start* − fence (strict). The still's `condensed` counts payments with tick ≥ *now* − fence.
- **F6 (log windows):** the column keeps its logs for fence + `KEEP_TICKS` (2,840 ticks). The still keeps them for 2·fence (880 ticks). `keep` takes the bound from each caller, so both windows stay as they were.
- **F7 (fence wait):** the column's `ready` refuses while its own next cycle is at or before *start* − fence. The still's `ready` refuses until the pot's island is through *start* − fence (`coordinator.through`). These are different mechanisms, and each unit keeps its own.
- **F8 (debit loop bound):** the column looped over the length of the given array and the still over the account's length. `debit` loops over the account's length. Both are the world axis (FluidFacts maps saved accounts onto it, and both units size the given array on it), so the results are the same. If the lengths ever differed, the column would now ignore extra entries (or throw on a short array) where before it threw (or ignored them).
- **F9 (constants):** both units had 100 and 440. One declaration now holds them. The types stay as they were: the column's `CYCLE_TICKS` is an `int` and the still's a `long`, both compile-time constants.

What was left, and why: the column's `asOf`, `givenSince`, inlet plan, outlet rate and `room`, `rate`, `owe`, `owed`, `bound`, `heatRead`, and the still's outlet rate, `owe`, `condensed`, `heatRead` and `compounds`. These are one-unit code, or two different laws (table). `Account` stays in `ColumnCycle`, although the review's module map moves it to `plant/port`: it is a fact type of `FluidFacts`, `ColumnUnit`, `StillUnit` and their tests, and moving it is a separate item. So `plant/port` imports `plant/column`'s `Account` (inside `science/plant`; `LayeringTest` is unaffected). The clamp `Math.max(1, T − APPROACH_KELVIN)` (four column sites, one still site) is the one alike utility expression. I did not extract it: it is one line, and it belongs beside `HeatSides.WATER_APPROACH` in equipment. It is an option for later.

Code guide: README row (`science/plant/port/**` (1); `map.mjs --check` reports the counts current), `column-operation.md` (class map row, constants, debit, `PortAccountsTest`), `fluid-runtime.md` (stills), `verification.md` (the new test folder). No CHANGELOG line. The batch README history line is left to the main thread.

Tests for the main thread (no gate list changes: `columnGates` and `equipmentGates` copy neither `runtime/fluid` nor `science/plant`):

| Task | Classes | What it proves |
|---|---|---|
| `test` | `PortAccountsTest` (new, 10) | the shared functions on hand-computed cases |
| `test` | `ColumnRunTest`, `StillRuntimeTest`, `ColumnSuspensionTest`, `ColumnProbeTest`, `FluidFactsCompatTest`, `client/gui/common/ColumnConditionTest`, `BuiltInInputsTest`, `SimulationTimeTest` | both units bit for bit; the facts round trip; the constants' callers |
| `test` | `LayeringTest` | `science` still names no `runtime`, `world`, `network` or `client` type |
| `test` / `holdGates` | every class `tools/merge-lane/affected.mjs` names through `EventPathWorld` (48 test files use it) | the units inside the world rigs |
| `compileFluidGameTestJava` | `ColumnOperationGameTests`, `ColumnOperationReviewWorld`, `ColumnFailureModesReviewWorld`, `TutorialUnitGameTests` | compile only |

Points where compiling is not certain:

- The lambdas capture `unit` (a `long` assigned once in `credit`'s loop), the new local `port`, `pot` (a `Long` assigned once) and `device`. Their parameters (`i`, `held`, `g`, `given`) collide with no local in scope: I checked both methods.
- `PortAccounts.keep(owed, Owed::tick, ...)` takes the local `owed` and the record `Owed`. The type is inferred from the `ArrayDeque`.
- `Stills.CYCLE_TICKS = PortAccounts.CYCLE_TICKS` widens an `int` constant to a `long` constant.
- In `PortAccountsTest`, `assertEquals(440, PortAccounts.FENCE_TICKS)` resolves to `(long, long)`, and `assertEquals(15, double)` to `(double, double)`.

## The side-by-side table

| # | Block | Column (`ColumnOperation`) | Still (`Stills`) | Differences | Now |
|---|---|---|---|---|---|
| 1 | Cycle, fence | `int CYCLE_TICKS=100`, `long FENCE_TICKS=440` | `long CYCLE_TICKS=100`, `long FENCE_TICKS=440` | type of the cycle only | both read `PortAccounts` (F9) |
| 2 | World → island list | `onIsland` | `onIsland` | none (list from `coordinator.model`; the still's `models` is `coordinator::model`) | `PortAccounts.onIsland` |
| 3 | Island → world list | `onWorld` | `onWorld` | none, the same text | `PortAccounts.onWorld` |
| 4 | Mass | `mass` | `mass` | none | `PortAccounts.mass` |
| 5 | Account as of the fence | credits after the fence, `max(0, a − n)`, solids too | head or vent arrivals after the fence, `max(0, a − n)` | the log, solids (column only) | the per-entry step is `PortAccounts.less`; the walks stay |
| 6 | Outlet that gives nothing | `PortPlan(node, s.valve(), 0, T, totals)` | `PortPlan(node, node.id(), 0, T, totals)` | the valve; the column queues growth first; F1 | `PortAccounts.idle(node, valve)` |
| 7 | Outlet rate | `min(mass/seconds·TAKE_MARGIN, produced·clamp(mass/aim, 0, 2))` at the table's T | `mass/max(seconds, hold)` at the condensate's or the head's T | different laws | kept in each |
| 8 | Outlet debit | `credit`, else branch | `give` | F2, F8, the log text | `PortAccounts.debit` with each unit's log line |
| 9 | Booking difference | `joule − debited` to the condenser total; to the water if below `waterBelow` | `debited − joule` to `condensed`; to the water if it has a heat side | sign convention, routing rule | kept in each |
| 10 | Heat owed | `owe`: equal shares to n sides, wakes, `Owed(tick, share, booked)` | `owe`: one side, `Paid(end, joule, NaN)`, `controlsMoved` | different records and fan-out | kept; the window trim is `PortAccounts.keep` |
| 11 | Heat read | `due` as of the fence, `rate + excess/hold` | live transit, `heat + max(0, transit − hold·heat)/(6·hold)` | F4, F5 | kept in each |
| 12 | Hold, share | `room`: hold·\|perFeed\|·design − n·max owed | `condensed`: all while transit ≤ hold·need, else what the water took | different laws | kept in each |
| 13 | Dated logs' window | `rates`, `owed`: `size>1 && first < tick − fence − KEEP` | `arrivals`, `paid` (twice): `size>1 && first < t − 2·fence` | the bound only (F6) | `PortAccounts.keep` at all five sites |
| 14 | "Water always" | `compounds` | `compounds` | F3 | kept in each |
| 15 | Fence wait | `ready` on its cycle clock | `ready` on `through` | F7 | kept in each |
