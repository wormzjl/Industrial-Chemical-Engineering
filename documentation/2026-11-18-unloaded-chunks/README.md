# Unloaded chunks

DEFECT · plant, runtime, machines, equipment, controls and power. In progress since 2026-11-18.

The owner's save lost both heaters when its creative supplies unloaded. IChemE now resolves its FE terminals and column design from saved plant records. The same rule applies to control inputs, beacon decisions, the solid still fuel slot and pollution damage. A foreign FE endpoint participates while its capability is available. A metered motor stops on loss of live supply and retains its stored charge.

## Documents

- [UNLOADED_CHUNKS_PLAN.md](UNLOADED_CHUNKS_PLAN.md): the audit of chunk-bound engine paths, the design, owner decisions D1 and D2, steps.
- [UNLOADED_CHUNKS_REVIEW.md](UNLOADED_CHUNKS_REVIEW.md) records the implementation, the headless save check, the regression results and the remaining qualification limits.
- [EQUIPMENT_CHUNK_DEPENDENCIES_REVIEW.md](EQUIPMENT_CHUNK_DEPENDENCIES_REVIEW.md) traces remaining load dependencies in exchanger controls, water intakes, column operation, blast damage and equipment binding.

## History

- 2026-11-18: independent high-effort Sol review of `c25abe8a0` and source cross-check. The follow-up review records five findings, recovery limits and regression cases for a future fix.
- 2026-11-18: plan written after the owner's test save lost its burners' power on a headless server (no player, creative sources not ticking). Measured: no `NO POWER` with the plant's chunks force-loaded.
- 2026-11-18: implemented columns and controls (`1681e72e4`), FE (`de617db23`), solid fuel (`690e9db55`) and environmental damage (`6100b5a04`). Corrected the chained-column test fixture with owner approval (`c3a7d60c2`). The owner's save passed 6,000 unattended ticks. The final lane passed 1,616 selected tests and 134 GameTests with no change to watch-seed hold classifications. Fluid schema 32 and control format 6 are recorded on this branch under the owner's worktree-only instruction.
