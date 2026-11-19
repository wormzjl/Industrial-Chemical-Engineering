# 0.35.3 merge review

Local `main` merged the approved 0.35.3 release on November 19, 2026. Its previous tip was `98254db2c`. Candidate `6d87cc8cd` supplied the qualified code and resources. The [changelog](../../CHANGELOG.md) records the release.

## Delivered behavior

| Batch | Included in the release |
| --- | --- |
| [Column test-world speed](../2026-11-18-column-test-world-speed/README.md) | Flat-grid pipe routing and reuse of the sized plant for matching resources. |
| [Cold-feed column](../2026-11-17-column-cold-feed/README.md) | Bounded pumparound duty retries, radiant outlet temperature at outlet pressure and burner relighting after reload. |
| [Equipment reflections](../2026-11-18-equipment-reflections/README.md) and [furnace model](../2026-11-18-furnace-model/README.md) | Material-specific gloss, joined fuel lines and removal of overlapping leg faces. |
| [Unloaded chunks](../2026-11-18-unloaded-chunks/README.md) | Registered controls, power, column operation, still fuel, water intakes and pollution damage continue across chunk unloads while the server is online. |
| [Constructor](../2026-11-18-constructor-ghost-rendering/README.md) and [device pages](../2026-11-18-device-gui-labels/README.md) | Reusable sorted ghost buffer, reorganized chooser and import, combined furnace readings and engineering labels. |
| [Junction rollback](../2026-11-18-junction-lag/JUNCTION_ROLLBACK_REVIEW.md) | Pre-task junction mixing. Independent shutdown, exchanger wake and respirator fixes remain. The rollback’s original plant trial ran 5,000 measured charge ticks without a hold. |

## Compatibility and packaging

- Mod metadata and the assembled jar name both give version 0.35.3. SHA-256 of the verified local jar: `edef717f32d1904de7c17b7a6621466f307c9a6c499f56511ca917cc8268e38b`.
- Shared wire protocol `icheme-9` carries the partner coil’s presentation. The target has `icheme-7`; `icheme-8` is reserved for the solute batch. Client and server need matching protocol versions. Fluid facts schema 33, state format 53 and certificate revision `replay-1` are documented in [FORMATS.md](../reference/FORMATS.md). The compatibility corpus includes schemas 32 and 33 and the control-6 power image.
- Updated Field Guide startup instructions, coil glossary entries and tours. The translation commit `b168f387e` brings Chinese, French, Spanish and Japanese into English key order. All four passed the offline key, placeholder and newline checks for 4,928 entries. History-based stale scans still flag wording already precise in translation (heat duty, flow velocity, pressure drop and reflux ratio) and four capitalization-only Chinese port names. These were individually reviewed.
- `MapEnvironment` moved to test sources in `508974da1`. The product reachability audit also finds JEI registration and the Iris/Distant Horizons reflection bridges, which have production entry points. The [pollution digest](../../tools/pollution-bench/README.md) still produced `6450680631e3021f` and `5ae9aaa2c9acc5c5`. The fixture is absent from the release jar.
- [Column soak](../../tools/column-soak/README.md) keeps the unloaded-plant review rigs. [Junction cost](../../tools/junction-cost/README.md) keeps the saved-world resource observer and fixed-input circuit and column probes. Its 64-interval digest ran successfully, and its saved-world observer compiled with exactly one resource sampling hook. Exploratory historical results and recordings are outside the shipped jar. The tracked-ignored-file audit is empty.

## Verification

The one final affected lane selected 2,792 tests. Three initial assertions failed: the removed machines chooser key, overlong equipment help, and overlong tour and guide instructions. The constructor width gate now checks all actual categories, full equipment names and WIP tags at the width of a scrolled chooser. The help text was shortened in all five languages. Seven selected classes passed on the corrected sources, including both originally failing classes, the language and guide gates, and the constructor and screen tours. The release jar was rebuilt after those corrections.

All 140 required GameTests passed. The selector skipped the 100-node regression because its loaded sources had no pending change; the rollback lane's exact pass stands. The watch campaign reported 6 of 14 cold worlds and 8 of 13 bundled worlds held in parallel. Against the last completed rollback campaign, the only new hold was bundled 12, a deadline. It completed its prescribed solo replay with all 120 slices clean. The 13 known synthetic-world cases remain documented in the [hold cause review](../2026-11-18-junction-lag/WATCH_HOLD_CAUSES_REVIEW.md). The renderer's live frame rate and shader-pack appearance remain unverified.

Local evidence is under `tmp/prepare-0353/`, including the lane output, comparison, focused retry and isolated seed report. The historical rollback gates are under `tmp/junction-lag/perf-prepost/rolled-back-lane/`.
