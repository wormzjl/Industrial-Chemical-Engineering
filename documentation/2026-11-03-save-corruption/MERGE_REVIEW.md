# Merge review: 0.23.5

Branch `claude/save-corruption-prevention-2bd7ac`, batch `2026-11-03-save-corruption`: registry first (W5, steps 1 to 8; W5c, C1 to C5). Main 0.23.4 merged in (`ddfd9cbd3`); main has not moved since (`08cf75ea6`). The owner, 2026-11-09: "merge", version 0.23.5 (my proposal was 0.24.0, a minor bump for a new feature).

## Merge preparation

- Translations `76817cbea`: `message.icheme.device_held` and `devices_held` rewritten in zh_cn, fr_fr, es_es, ja_jp (W5c C5 changed their English). `check.js`: keys and arguments match; `stale-translations.js`: only those two lines were stale.
- Tooling cleanup `b0e552913`: the rack-frame probe moves to `tools/rack-frame-probe/` (README, `ambiguity.jsh`, a `tools/INDEX.md` row). `main-reach.pl`: 675 of 675 main classes reachable, none unreachable.
- Code guide `17b3cb6ce`: eight chapters at `2f86539b0` (fluid-runtime: the "Registry first" section, two class-map rows, facts schema 15; rotating-machinery, wired-control-power, pipe-laying, equipment, instrument-accessories, verification, README).
- `mod_version` 0.23.5 and the CHANGELOG entry under `[0.23.5] - 2026-11-09`: the commit that adds this file.
- `git ls-files -i -c --exclude-standard`: empty.

## Final lane (`17b3cb6ce`, 2026-11-09)

`bash tools/merge-lane/lane.sh head`, alone on the machine. 71 files changed since main.

- Unit tests of the affected classes: 1005 tests in 142 classes (`test` 128 of 331, `holdGates` 10 of 12, `equipmentGates` 4 of 25, `columnGates` none), 0 failures. `LanguageFilesTest` and `UnitTextTest` among them.
- chain-100 skipped: it loads nothing that changed.
- Junction lines: 33 of 33 identical to the 0.23.0 lane, floats included; `MIXED_GAS_COST` 300 Newton solves, 164.2 MB (163.4 MB).
- Campaign, 1000 seeds at 8 workers. Baseline: the 0.23.0 final lane (`tools/open-thermo-data/logs/open-thermo-data-final-lane-logs-2026-11-09.tar.gz`), the last lane that ran it; 0.23.1 skipped it.
  - Cold random 992 (992), the same holds; Newton solves 296.6k (299.9k).
  - Bundled random 990 (989): 678 clean (F there, a load hold it also cleared when replayed alone); 431 E6 (F there; E6 is its class on main). New holds: none. Newton solves 184.7k (183.8k).
  - Bundled defaults 1000 (1000), counters identical.
- Fluid GameTests (run id `lane-1109-1518`): 85 of 87. The run id `save-merge-recheck` on the same code: 87 of 87.
  - `identityBindingOnAChunkLoadCarriesNoViewData`: "No view at the bucket 4438: null". The test passed in all 21 earlier runs of this batch (W5 and W5c code), and nothing it loads changed since its last pass (`2f86539b0`; only lang files, documents and tools since). The re-alignment did nothing to its reservoir (no `fluid_realign` line: the standing block takes the NOTHING path, which never touches the block entity).
  - `registeredPipesAndReservoirsKeepSimulatingAcrossUnloadedChunks`: "Waiting for all cleanup transactions". It follows from the first: the failed test left its reservoir, a second island the property-reload test holds (two HELD lines; one in green runs).
  - Expected: a reloaded device's view arrives at the first bucket of its island after the load. Found: once in 23 runs it did not arrive at that bucket. The guess at merge time (the island's first publication) was wrong; the cause is below.

## Open items carried to main

- W1-W4 (the corruption ladder) and W6 (save encoding off the server thread): not built, owner to decide.
- The sight glass axis, GameTest i, the `SolidMaterialCatalog.validate` text, one in-game check of a rebuilt structure: see [README.md](README.md), open items.
## Merge

- Merged to `main` as 0.23.5 on 2026-11-09 (owner: "merge"). Not pushed.
- Lane outputs and the batch's scratch logs: archived in the main checkout, `tools/merge-lane/logs/save-corruption-scratch-logs-2026-11-09.tar.gz` (local); the final lane there is the next merge's baseline.

## After the merge: the flaky presentation test (owner: "Yes, fix the test")

- Expected: the load marks the device, and the view arrives at the device's first bucket after the mark.
- What happened: within one server tick, a GameTest ticks inside `tickChildren`, before `ServerTickEvent.Post`; the authority's tick there advances the online tick to v+1 and flushes the bucket of v+1. The test reloads the chunk and reads online tick v. The load's two marks come after that flush: the chunk-load handler is a queued `TickTask` (49e57860), and the block entity's `onLoad` runs on the next level tick. So the device's first bucket after the mark is the first after v+1. The test counted from v: when the device's bucket was v+1 (1 in 100 runs), it expected the view at v+1, and the view came at v+101. The engine follows its rule; the test's count was one tick early.
- Fix: the test counts the bucket from the tick it reads the mark (v+1). No product change. `8642e6308`; fluid GameTests 87/87 (run id `presentation-flake-fix`).
- Merged to `main` as 0.23.6 on 2026-11-09 (owner: "Merge as 0.23.6"). Not pushed. No second lane: nothing else changed. Scratch log archived in the main checkout, `tools/merge-lane/logs/presentation-flake-scratch-logs-2026-11-09.tar.gz`.
