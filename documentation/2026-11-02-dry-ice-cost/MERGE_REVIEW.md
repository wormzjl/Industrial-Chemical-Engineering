# Merge review

Branch `claude/distillation-column-integration-fef62f` at e7a3a7f2b, on local main 9279657a7 (0.19.1), with no main commit missing. The owner asked to prepare the merge and to stop before merging it. Not merged, not pushed.
## What merges
- Code:
  - eb4d72b87: mode (a), crystals carried in the fluid phases, and form G.
  - eb9ad31cb: density tables in the start balance for liquid and refused junctions.
  - 3ed64f98f: a pass lays a dry vessel out.
  - e7a3a7f2b: that layout comes from the pass's draws.
  - 1bf1a1334: test bound only.
- Documents: the batch folder (eight reviews and one plan), and `solid-phase-fluid-system-plan.md` lines 79 and 93 amended for form G.
- Tools: `tools/dry-ice-cost/`, and `tools/native-column-default/probes/` from the 0.18.0 cleanup (5662e5905).
## Gates
- Final lane at 803970ac1 ([FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md)):
  - `test slowGates`: 2108 tests, one failure (a bound from before form G), fixed in 1bf1a1334.
  - Junction lines as main's, `MIXED_GAS_COST` unchanged, chain-100 exact, 39 GameTests.
  - Printed lines move only in crystal, solid and dry-tank tests, as the reviews expect.
- After the lane, e7a3a7f2b ([DRAWN_ARRIVAL_REVIEW.md](DRAWN_ARRIVAL_REVIEW.md)):
  - 186 tests in 27 classes, with `holdGates`, and 39 GameTests pass.
  - One campaign of the final form: cold 992, bundled random 995, bundled defaults 1000, `refused=0`, no new held seed.
  - Against the base lane: cold 975, bundled 992 and 993.
- The lane was not run a second time (AGENTS.md). The column and equipment gates were restored from the cache: the batch changes no code they load.
## Code guide
Chapters `equilibrium-engine`, `fluid-science`, `verification`, `fluid-runtime` and `README`, snapshot e7a3a7f2b, 2026-11-03:
- carried crystals and the stability filter;
- form G;
- the balance tables and the carried fallback;
- the dry-vessel pass rule and `drawnStream`;
- `filled` as the booking fallback;
- the new gate tests.

Counts the guide had wrong before this batch (runtime files, test source set, `NetworkPhaseEngineTest`) now match the code. Two counts are left as they were, not from this batch: `science/fluid/thermo` files, and the "38 in all" GameTest row.
## Tooling cleanup
- `tools/merge-lane/main-reach.pl`: 424 of 424 `src/main` classes reachable.
- The batch added no Gradle switch or run configuration, and no probe or instrumentation to a tracked source path. The trace hooks and probe live in `tools/dry-ice-cost/` (README: purpose, how to run, entry points, history).
- `git ls-files -i -c --exclude-standard` is empty.
- Logs are local in the main checkout's `tools/dry-ice-cost/logs/`. This step ran no test: documents, the CHANGELOG and the code guide only.
## CHANGELOG and version
- One line for the batch under `[Unreleased]`, and one for the native-default probes.
- Version: the owner chose **0.19.2** (proposed 0.20.0, a minor bump for a solver and model batch).
## At the merge
- Done on the branch before the merge: `mod_version` 0.19.2, the `[0.19.2]` heading, the INDEX rows and the code guide snapshot lines.
- Delete this batch's `tmp/` folders once the merge is recorded.
## Open items carried
Batch README items 1 to 8: the start stage's leading-phase pricing, the PH steps, the carried tables in 244, stalls at a closure's start, 118's equation gate, 555, run totals, and pushing main.
