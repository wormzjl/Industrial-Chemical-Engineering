# 0.22.0 readiness workflow archive

Batch: `2026-11-06-simulator-sizing`, source `050cd109`, 2026-11-08. This folder retains one-off preparation scripts, outside all product and gate source sets. The inventory records original scratch paths and byte hashes.

The scripts document translation deltas, changed-source/filtered-test selections, source-math audit, guide/changelog edits, package inspection, source archiving and native-export comparison. Several are historical find/replace authoring steps; do not replay them against another revision. Run read-only probes from the repository root with Python UTF-8 enabled. Preserve their declared old-input artifacts if reproducing a comparison. The original old sources/results are local evidence, not dependencies shipped to players.

Supported entry points remain `tools/merge-lane/lane.sh` (the canonical full lane, already run once for this batch), `tools/simulator-sizing/focused-gametests.init.gradle` (selected permanent fresh-server tests) and `tools/equipment-drawings/update.py` (current native drawings/proofs). This archive adds no Gradle task or gate breadth.

The final review records 206 distinct JUnit checks and 25 distinct server methods passing through affected follow-ups, including initially failing results. No new campaign/timing or client check was run. Final artifact, native book/proofs, source comparison and verification manifests are canonical local research evidence; compressed execution logs live in the main local tools log folder. No production helper was removed, so no reattachment patch is required.
