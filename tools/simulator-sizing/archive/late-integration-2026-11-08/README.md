# Late integration investigation archive

Batch: `2026-11-06-simulator-sizing`, retained 2026-11-08. The source inventory records each original scratch path, retained path, size and SHA-256.

This archive preserves the line ownership audit/consolidation, DCS/GUI follow-ups and native equipment-interface implementation probes. Product code and permanent gates remain in their source sets. Current native drawing generation is `tools/equipment-drawings/update.py`; current server selections use `tools/simulator-sizing/focused-gametests.init.gradle` and invoke permanent bodies. Neither workflow loads this historical archive.

## Reuse

- Read a source before running it. Find/replace implementation scripts describe historical edits, not migrations or safe commands for the current branch.
- Geometry/render probes may be compiled against their recorded historical revision using JDK 21 and that revision's development runtime classpath. Put compiled classes and figures under a new indexed `tmp/<name>/` output. Sources that assumed their old scratch path need that path supplied or restored locally.
- `pipe-guide-audit/` holds the original audit readers/verdict scripts; the 838-entry matrix and ownership notes are local evidence, not an alternate framework.
- `operator-gui/` contains offline prototypes/implementation steps; shipped screen/builders are authoritative.
- `equipment-control-boxes/` and root scripts retain proposal and integration experiments. Accepted native owners are `EquipmentServices`, `EquipmentInstruments` and `InstrumentWiring`.
- There is no detached production class or removed production method to reattach. No Gradle gate loads this folder. Timing probes must run alone; renders are not in-game qualification.

History: archived during the final 0.22.0 preparation after code `fd5603249`; generated meshes, screenshots, logs, worlds and bytecode remain local. Original files already retained by the earlier 174-entry archive are not duplicated here.
