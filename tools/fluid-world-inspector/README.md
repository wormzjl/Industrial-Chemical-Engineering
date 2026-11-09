# Fluid world inspector and valve regime trace

Batch: `2026-10-23-valve-regime-convergence`. Diagnosis snapshot: `232b0466`; production validation: `83e88bd0` on `codex/valve-regime-validation`. Tools remain offline and ignored; the feature's three permanent regression tests live in the normal quick suite.

- `inspect_world.py <world>/data`: read-only parser for checkpoint 16 / unit 9 only. Validates per-unit SHA-256; reads active registrations, main graph, controls and accepted endpoint history. Topology parsing stops after active registrations/pending count, and island parsing stops before transfer histories/certificates; it is an inspection tool, not a save writer or full codec substitute.
- `world-inspection.json`: frozen numeric inspection of the current-format case, epoch 11999 / island 99 committed 7435. Kept so reproductions never touch a live world. It holds the WTI (ExxonMobil) composition, so since O5 of batch 2026-11-04-open-thermo-data (2026-11-05) it is in the main checkout's git-ignored `research/2026-11-04-open-thermo-data/xom-copies/tools-fluid-world-inspector/`, where `run_audit.ps1` reads it.
- `PropertyAudit.java`: compares carried phase states with TP/UV equilibrium and the vessel's existing own-split check; never advances an interval.
- `StepAudit.java`: reconstructs the copied graph and endpoint history and runs bounded single-step/interval diagnostic replays. It asserts no solids/filter/mover in this particular case; does not handle arbitrary equipment.
- `AuditTrace.java`, `prepare_trace.py`: prepare trace-only copies of PassiveStepSolver, PassiveIntervalSolver and SparseNewton into `trace-src/` and compile them into `trace-classes/`. They do not edit or enter the production classpath. Logging uses existing numeric values only.
- The optional `-Dfluid.audit.carryValve=true` proposal passes accepted endpoint valve modes from the interval controller before each attempt. Existing equations and mode inequality checks stay intact. Mutable failed-pass carry was an exploratory version, superseded by accepted-only carry.
- `run_audit.ps1`: reproduction with JDK 21, already-built source classes/resources, cached Gson 2.10.1 and EJML 0.44.0, no Gradle. Override SourceRoot/JavaRoot/GradleCache/Python as needed. Baseline must reproduce the exact refinement failure; accepted-only carry completes 21 x 4.5 s intervals. Requires this code snapshot's class API.

The experimental UV-seed path has a 500,000-operation limit and reported that limit rather than completion; do not treat that as proof no physical solution exists. The accepted-only carry result is convergence on this copied case, not broad qualification or a perfectly time-independent outlet quality.

## Deferred hold capture, 2026-10-23

Batch `2026-10-23-deferred-fluid-hold`: `capture_deferred_hold.py` reads the current format-17 core and immutable packs from the running integration test world, validates each copied unit's SHA-256/header (unit 10), and archives logs, source/config and save metadata. It performs no Minecraft command, process update, save or solve. It uses only inspect_world.py's generic NBT reader; the older unit-9 inspector/trace path remains unchanged and is not compatible with this new capture. Run with the bundled Python when recording evidence only. `deferred-hold-capture.json` points to the newest timestamped logs/deferred-hold-logs-*.tar.gz archive; earlier captures remain. Disk snapshots can predate the live hold; compare timestamps/epoch rather than claiming an exact held-state reproduction. Root-cause work was deferred by the owner.

Logs and copied client warnings: `logs/valve-regime-logs-2026-10-23.tar.gz`. Summary and expected results: the batch review. No source was detached from a tracked path; no removing commit or reattachment patch is needed.

## Production validation

`validate_campaign.py` uses the owned worktree's already-built classes, JDK 21 and cached libraries. `compile` compiles the existing `tools/unified-thermo-port-forms/hold-fuzz-{cold,bundled}` harnesses and two baseline solver classes from `232b0466` into ignored `validation/` class directories. No Gradle source sets, product flags or gate suites change. `baseline-gates` verifies the five unrelated suite failures against those original solver classes.

Run `python tools/fluid-world-inspector/validate_campaign.py <phase>` from the canonical main checkout, with all other measurements/clients stopped:
- `compile`, then `baseline-gates` (requires the owned worktree's compiled test classes).
- `quick`: baseline and candidate, seeds 1–200, cold/random/default distributions, four workers, serial JVMs.
- `full`: selected candidate only, seeds 1–1000 in all three distributions, eight workers, serial JVMs.
- `followup`: the baseline's 201–400 cohort in all distributions, to check full-campaign bundled 241 without replaying a worse Newton seed alone; then one batched C/F-only replay lane (cold 244 and bundled 6, one worker).
- `summary`: initial 200-seed comparisons, counters, first-hold classes and full selected-form results in `validation/campaign-summary.json`. Full chosen-form coverage is not a new 1000-seed baseline comparison.

Reports use the existing `tools/phase-ports-probes/hold-fuzz/classify.sh`. The archived floating-roof campaign is a historical reference, not an identical replacement for the current baseline cohorts (its bundled harness included open tanks). Do not infer a new regression just from that report.

Production validation logs, gate XMLs, reports, counters and the source manifest: `logs/valve-validation-logs-2026-10-23.tar.gz`. The compact campaign/gate summary JSONs remain outside the archive. The initial trace scripts target the diagnosis snapshot; export that source revision before rerunning the prototype instrumentation on a later implementation.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Read-only format-16 inspection/trace, accepted-mode validation and new format-17 deferred-hold evidence capture

Batch: 2026-10-23-valve-regime-convergence; 2026-10-23-deferred-fluid-hold

Status: Archived 2026-10-23. Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `run_audit.ps1`, `inspect_world.py`, `world-inspection.json` (in research/ since 2026-11-05), `validate_campaign.py`, `capture_deferred_hold.py`, `deferred-hold-capture.json`, `logs/valve-regime-logs-2026-10-23.tar.gz`, `logs/valve-validation-logs-2026-10-23.tar.gz`

### History

- Archived 2026-10-23 (validated `19117af7`; new `beb5c2fc` hold evidence recorded, debugging deferred)
- 2026-11-05: `world-inspection.json` moved to the main checkout's research/ (ExxonMobil WTI composition; O5 of batch 2026-11-04-open-thermo-data)
