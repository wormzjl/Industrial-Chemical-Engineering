# Junction rollback

The owner set a 30-minute recovery deadline at approximately 17:12 Singapore time on November 19, 2026. The final candidate still used 1.92× the pre-task CPU and 1.26× its allocation rate, and one of its two saved-plant trials held. The worktree reverted to pre-junction mixing at 17:30:06 in commit `affd38064`. No original save, main checkout or remote branch was changed.

## Saved-plant results

The same verified save has 14 islands, 1,362 devices and 75 forced chunks. Each trial uses a fresh headless JDK 21.0.11 process, G1, a 512 MiB initial heap, a 4 GiB cap and the same paced 6,000-tick observer. Process measurements cover ticks 1,000–6,000. The first two rows use the earlier matched controls. The final candidate used two fresh JVMs and completed both measurement windows.

| Build | Process CPU (s) | Allocated (MiB/s) | Mean / peak resident RAM (GiB) | Hold warnings by run |
| --- | ---: | ---: | ---: | --- |
| Pre-task `e9feeffde` | 50.3 | 69.1 | 2.685 / 2.745 | 0, 0 |
| Junction `e9b43f0ef` | 147.8 | 232.4 | 2.790 / 3.143 | 1, 1 |
| First optimization `8b5ab228c` | 112.2 | 134.1 | 2.892 / 3.509 | 5, 0 |
| Rejected final candidate `282fb135f` | 96.6 | 87.3 | 2.792 / 3.026 | 1, 0 |
| Restored worktree `affd38064`, one fresh run | 46.1 | 69.4 | 2.730 / 2.767 | 0 |

The candidate recovered 52.5% of the excess CPU and 88.8% of the excess allocation relative to the matched junction and pre-task controls. Its two runs both ended with the column running, with charge progress of 5,010 and 5,020 ticks. The first logged a round deadline at charge tick 96,197. The second logged no holds.

## Why the fix was rejected

At tick 96,397 the feed command reopened to 132.19 kg/s from a stopped pump. The preliminary hydraulic balance seeded the feed valve at 454.24 kg/s while the pump delivered zero. It had failed its own balance by 395.29 kg/s. Discarding that starting point allowed the exact four-second command to finish in one accepted step with zero rejections, keeping the physically stopped pump and the internal heater-branch circulation. The later full-world trials exposed the separate deadline at 96,197.

Independent checks on the follow-up solver found faster mixed-gas junctions and a faster fixed-input column. All 64 fixed liquid/gas checkpoints matched the unoptimized junction implementation byte for byte on the final candidate. The early cost reductions did not bring whole-plant CPU close to its pre-task level. Commit `9536c225d` preserves the candidate’s investigation and fixed-input measurements in its historical tree. Raw plant runs, digests and JFR data are under `tmp/junction-lag/perf-prepost/`.

## Rollback scope

The restored production network solver, column solver and checkpoint codec match `e9feeffde` byte for byte. The plan’s same-slice heat and composition change is absent. The pre-task mixing, conservation and replay rules apply again. Existing unloaded-equipment changes, furnace visuals and material-tab work predate the junction baseline. Independent Curios, sight-glass, acoustic-bound, exchanger wake, shutdown and test-harness corrections were preserved.

The facts schema remains 33. The restored state format is 53. A format-57 save from the junction implementation must reconstruct derived state from facts. The original saved world was used only as a copy source.


## Restored worktree verification

The original save loaded on the exact route and restored all five saved certificates. The rollback trial advanced 5,000 charge ticks, averaged 158.78 kg/s of feed, finished with the column running and emitted zero hold warnings. This is one post-rollback observation.

The format-57 save from the first final candidate trial was also read in both builds. Its writer loaded exactly. The restored reader took its facts-rebuild route at the same saved tick 97,321, reconstituting 14 islands and two vessels. All 1,362 serialized device registrations, the constructed and destroyed material/energy ledgers and seven other topology facts sections matched. Rebuilding 14 island identities advanced the next identity from 1,440 to 1,454. Both reads reported zero restated energy and no removed material. The compatibility check was read-only.

All 3,997 affected tests and all 140 required GameTests passed. The 100-node exact regression matched its reference for state, moles, temperature, phase fraction and flow. In the restored watch list, six of 14 cold worlds and seven of 13 bundled worlds held. On the common seeds, bundled 145 and 941 were newly observed budget holds compared with the preceding junction campaign. Under one worker, 145 reached the interval substep limit and 941 reached a wall deadline. These are open synthetic-world failures, separate from the zero-hold saved-plant run. Commit `347700ccf` updated only the two campaign diagnostic writers to print the restored inventory fields.
