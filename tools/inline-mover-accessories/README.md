# Inline mover accessory software verification

Serves batch documentation/2026-10-25-inline-pump-accessory, owner blue pump/CNP TD construction views and owner-selected Sundyne LMC compressor reference. run.ps1 [-SourceRoot <compiled worktree>] compiles/runs InlineMoverRender.java against native compiled geometry and sprites using JDK21. It emits242 software PNGs (seven bores, four headings, two types, full/lower/item looks and14 neighboring-pipe contexts) for two fittings, four horizontal headings, minimum/default/maximum bores, three angles and item looks into the batch renders directory. Native triangle rasterizer adapted from tools/accessory-refinements. No Minecraft is launched. Software rendering uses backface culling; native winding/finiteness gates live in InlineMoverAccessoryTest.

Targeted command: gradlew test --tests '*InlineMoverAccessoryTest' --tests '*PipelinePressureValveTest' --tests '*PipelineFilterAccessoryTest' --tests '*PipelineShutoffValveTest' --tests '*GaugeAccessoriesTest' --tests '*PumpTest' --tests '*CompressorTest' --tests '*PipePresentationTest' --tests '*PhysicalFluidTopologyTest' jar. Final53 tests pass, jar built. Never run while a dev client runs. Required quick/slow remain due before merge. Current-format only, no migrated fixtures. verification.json and compressed logs/test XML retain evidence. No source was detached from a tracked path; no custom Gradle task/switch or gate-scope change.

Lower-model refinement8a910a20 (2026-10-25): curved passages and hanging bowl/open base, separate compressor gearbox and compact machine joints. Re-run only InlineMoverAccessoryTest and PhysicalFluidTopologyTest with jar:20 tests pass, zero failures/errors/skips. lower-detail-verification.json and logs/lower-model-detail-logs-2026-10-25.tar.gz hold this lane; original verification.json/archive remain historical53-test evidence. sheets.py emits full, lower-detail and revised-models contact sheets. Ground sits below overhang and lower crops retain the casing/base. No tracked source detached, no Gradle switch/task added, no gate scope change.

Nozzle interior correction52b9f8b0: one existing mesh test and jar pass.100-view sweep adds pump/compressor item-lower images for the reported port gap; existing sheets show all diameters/headings. Separate evidence nozzle-interior-verification.json and compressed nozzle-interior-logs-2026-10-25.tar.gz. No new test or gate change.

Support revision b2269322: extended existing geometry method checks floor contact/clear supporting cell and block-face flanges at threshold sizes. One test and jar pass;242 software views. support-layout-verification.json and support-layout log archive retain evidence. integration/qualified-before-supports.json stores the earlier ccf3a5d3 full qualification (quick1402/2, slow185/1; all exact baseline matches); integration and client launch were paused by the owner before target update. Required suites must rerun on any resumed integration. No tracked temporary harness/task detached or gate membership changed.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Native pump/Sundyne compressor242-view support/border sweep, lower-casing/reference refinement and focused test evidence

Batch: 2026-10-25-inline-pump-accessory

Status: Concluded 2026-10-26 (batch in 0.12.0). Index: [../INDEX.md](../INDEX.md).

### Notes

- status added 2026-10-26 (the row had none): the batch reached `main` in 0.12.0.

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 577 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/inline-mover-accessories/`, at the same relative paths.
