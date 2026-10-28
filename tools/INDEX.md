# Tools index

Offline tooling, tracked since 2026-10-27 (`bba47f5a`): training and evaluation harnesses, benchmark supervisors, DWSIM automation. Add or change a tool on the task's branch; it reaches `main` with the merge. Log archives in `<folder>/logs/` stay git-ignored and local to the main checkout. Documents about the work these tools served are in `documentation/<batch>/` ([../documentation/INDEX.md](../documentation/INDEX.md)); datasets are in `research/` ([../research/INDEX.md](../research/INDEX.md)).

Keep the paths below stable: `build.gradle` applies `tools/development.gradle` when it exists, `tools/development.gradle` compiles `tools/neural` as a source directory, a tracked test cites `tools/neural/methane-qualification.md`, and many scripts address sibling folders by relative path. Add a new tool as its own folder with a `README.md` (purpose, batch, how to run, entry points, history), and add one short row here: the folder, the purpose in a few words, the batch and the status with its date. Nothing else goes in the row. The index as it stood before 2026-10-26 is frozen in [index-consolidation/INDEX_2026-10-26_ARCHIVE.md](index-consolidation/INDEX_2026-10-26_ARCHIVE.md).

## Folders

| Folder | Purpose | Batch | Status |
|---|---|---|---|
| [heat-exchanger-design/](heat-exchanger-design/README.md) | Exchanger coupling, cell-count, 1-2 shell, per-phase film, zero-wall relay and game-timing study harnesses; owner-test probe of the review world | `2026-10-27-heat-exchanger-design` | Concluded 2026-10-28 |
| [heat-gui-renders/](heat-gui-renders/README.md) | PNG software renders of the heat Control pages and flowsheet, and the other screens' prototypes | `2026-10-27-heat-exchanger-design`, `2026-10-28-gui-toolkit` | In use since 2026-10-28 |
| [old-mover-removal/](old-mover-removal/README.md) | Writer of the removed-movers corpus image (a world with one-block movers) | `2026-10-28-old-mover-removal` | Implemented 2026-10-28 |
| [column-sump/](column-sump/README.md) | Column sump probes, nozzle sweeps and software renders | `2026-10-27-column-sump` | Concluded 2026-10-27 (batch in 0.15.1) |
| [skirt-shadow/](skirt-shadow/README.md) | Skirt floor renders with the in-game skylight and a skylight probe, before and after; probe of the GameTest's light wait | `2026-10-27-skirt-shadow` | Implemented 2026-10-27 |
| [multi-machine-framework/](multi-machine-framework/README.md) | Column nozzle-row, nozzle-bore and arrow probes, MCP bridge drivers, review-world NBT tool, in-game evidence and logs | `2026-10-26-multi-machine-framework` | Concluded 2026-10-27 |
| [process-control/](process-control/README.md) | Sensors, regulation qualification and native GUI evidence | `2026-10-26-process-control` | Implemented 2026-10-27 |
| [liquid-level-gauge/](liquid-level-gauge/README.md) | Native accessory-state renders and item icon source | `2026-10-26-liquid-level-gauge-accessory` | In progress since 2026-10-26; feature delivered |
| [pipe-laying-ingame/](pipe-laying-ingame/README.md) | Dev-client check of the pipe laying tool, racks and transition pipes through the MCP bridge | `2026-10-26-pipe-laying-system` | Concluded 2026-10-26 |
| [pipe-laying-renders/](pipe-laying-renders/README.md) | Software renders of pipe racks, transition pipes, painted lines and rack flanges | `2026-10-26-pipe-laying-system` | In use since 2026-10-26 |
| [rack-slot-clearance/](rack-slot-clearance/README.md) | Brute-force clearance check of the pipe rack's diagonal slots and elbows | `2026-10-26-pipe-laying-system` | Superseded 2026-10-26 (layered rack) |
| [pipe-laying-system/](pipe-laying-system/README.md) | Route probe, consolidation digest and gate logs of the pipe laying batch's merge | `2026-10-26-pipe-laying-system` | Concluded 2026-10-27 |
| [chunk-loading-review/](chunk-loading-review/README.md) | Verification logs of the chunk-loading fixes (tests, GameTests, rule-off proof) | `2026-10-26-chunk-loading-review` | Concluded 2026-10-26 (batch in 0.13.2) |
| [backward-compat/](backward-compat/README.md) | Final lane and alone replays of the backward-compatibility merge | `2026-10-26-backward-compat` | Concluded 2026-10-26 (batch in 0.13.0) |
| [index-consolidation/](index-consolidation/README.md) | One-shot rebuilds of `documentation/INDEX.md` and `tools/INDEX.md` into short indexes and folder READMEs | `2026-10-26-index-consolidation` | Concluded 2026-10-26 (one-shot) |
| [tank-gauge-enclosure/](tank-gauge-enclosure/README.md) | Custom glass source, native glass/pipework views and focused geometry evidence | `2026-10-25-tank-gauge-enclosure` | Concluded 2026-10-26 (batch in 0.12.0) |
| [sight-glass-flow-direction/](sight-glass-flow-direction/README.md) | Native opposite-flow/window animation and focused direction evidence | `2026-10-25-sight-glass-flow-direction` | Concluded 2026-10-26 (batch in 0.12.0) |
| [sight-glass-ratio-seams-sound/](sight-glass-ratio-seams-sound/README.md) | Native ratio/seam comparison, geometry/sound evidence and full integration qualification | `2026-10-25-sight-glass-ratio-seams-sound` | Concluded 2026-10-26 (batch in 0.12.0) |
| [sight-glass-bubbles/](sight-glass-bubbles/README.md) | Native bubble animation/size views, NBP palette generator and focused evidence | `2026-10-25-sight-glass-bubbles` | Concluded 2026-10-26 (batch in 0.12.0) |
| [inline-mover-accessories/](inline-mover-accessories/README.md) | Pump/compressor accessory views, support and border sweep, casing refinement, focused test evidence | `2026-10-25-inline-pump-accessory` | Concluded 2026-10-26 (batch in 0.12.0) |
| [accessory-refinements/](accessory-refinements/README.md) | Gauge all-angle/pipe tap and all-axis filter software renders, one fresh MCP check, focused verification logs | `2026-10-25-accessory-refinements` | Concluded 2026-10-26 (batch in 0.12.0) |
| [gauge-accessories/](gauge-accessories/README.md) | Instrument model/socket software renders, winding/layer audit, focused test and MCP evidence | `2026-10-24-gauge-accessories` | Concluded 2026-10-26 (batch in 0.12.0) |
| [tank-indicator-ports/](tank-indicator-ports/README.md) | Actual-mesh gauge/port/layout sweeps, guarded integration and cap-overlap proof | `2026-10-24-floating-roof-indicator-ports`, `2026-10-24-tank-gauge-cap-flicker` | Archived 2026-10-24 |
| [fluid-gui-consistency/](fluid-gui-consistency/README.md) | Focused GUI/wire/accepted-flow checks, full qualification and local-edit-preserving integration | `2026-10-24-fluid-gui-consistency` | Archived 2026-10-24 |
| [cutoff-handle-flicker/](cutoff-handle-flicker/README.md) | Actual-mesh tip layers/areas/bounds sweep and before/after software renders | `2026-10-24-cutoff-handle-flicker` | Archived 2026-10-24 |
| [pressure-valve-accessory/](pressure-valve-accessory/README.md) | Production previews, focused/full verification and WIP-preserving integration | `2026-10-24-pressure-valve-accessory` | Archived 2026-10-24 |
| [mover-equipment-arrows/](mover-equipment-arrows/README.md) | Depth-tested equipment/pipe software renders and before/after pump connection fingerprint | `2026-10-24-mover-equipment-arrows` | Archived 2026-10-24 |
| [pipeline-check-valve/](pipeline-check-valve/README.md) | Actual-mesh renders, focused/full merge evidence and local-file-preserving integration | `2026-10-24-pipeline-check-valve` | Archived 2026-10-24 |
| [wti-pumping-rate-hold/](wti-pumping-rate-hold/README.md) | Format-17 reader, replays and traces, hold inventories, cold 430/431 validation | `2026-10-24-wti-pumping-rate-hold` | Concluded 2026-10-26 (batch in 0.12.0) |
| [fluid-display-followups/](fluid-display-followups/README.md) | Actual item mesh/GUI-transform slot bounds and fresh WTI feed volume/mass calculation | `2026-10-23-fluid-display-followups` | Archived 2026-10-23 |
| [pipeline-shutoff-valve/](pipeline-shutoff-valve/README.md) | Actual production-mesh valve render sheets, targeted test/package and resource evidence | `2026-10-23-pipeline-shutoff-valve` | Archived 2026-10-23 |
| [fluid-world-inspector/](fluid-world-inspector/README.md) | Read-only format-16/17 world inspection, accepted-mode validation, deferred-hold capture | `2026-10-23-valve-regime-convergence`, `2026-10-23-deferred-fluid-hold` | Archived 2026-10-23 |
| [fluid-device-controls-display/](fluid-device-controls-display/README.md) | Compressed compile/package evidence only; no harness | `2026-10-23-fluid-device-controls-display` | Archived 2026-10-23 |
| [mover-port-markings/](mover-port-markings/README.md) | Production equipment/pipe arrow software renders and preview sheet, no game or solver work | `2026-10-23-mover-port-markings` | Archived 2026-10-23 |
| [fluid-format-reset/](fluid-format-reset/README.md) | Compressed owner crash, compile/package, integration preservation and startup evidence; no harness | `2026-10-23-fluid-format-reset` | Archived 2026-10-23 |
| [pump-compressor-controls/](pump-compressor-controls/README.md) | Motor audio authoring, codec dependencies and build/asset/merge evidence | `2026-10-23-pump-compressor-switch-sound` | Archived 2026-10-23 |
| [pipeline-sight-glass/](pipeline-sight-glass/README.md) | Sight-glass geometry, orientation and haze renders; target build and client evidence | `2026-10-23-pipeline-sight-glass` | Archived 2026-10-23 |
| [functional-pumps-compressors/](functional-pumps-compressors/README.md) | Compile and local-edit preservation evidence for working constructed movers; no harness | `2026-10-23-functional-pumps-compressors` | Archived 2026-10-23 |
| [square-flanges/](square-flanges/README.md) | Flange, neck and seam side-profile renderer with baseline sources; build and render evidence | `2026-10-23-square-flanges` | In use since 2026-10-23 |
| [pipeline-tool/](pipeline-tool/README.md) | Pipeline tool placement, client-startup and integration evidence; no harness | `2026-10-23-pipeline-tool` | Archived 2026-10-23 |
| [stale-worktrees-archive/](stale-worktrees-archive/README.md) | Restore kit of the 2026-10-21 branch and worktree cleanup (36 branch tips, 11 dirty worktrees) | housekeeping 2026-10-21 | Archived 2026-10-21 |
| [test-archive-2026-10-21/](test-archive-2026-10-21/README.md) | Tests archived by the test review, with source copies and a re-attach patch | `2026-10-21-test-review` | Archived 2026-10-21 (`d13e72d1`) |
| [floating-roof-tanks/](floating-roof-tanks/README.md) | Open-vessel solver impact: bundled hold fuzz with open tanks, traces and prototypes | `2026-10-21-floating-roof-tanks` | Concluded 2026-10-26 (batch in 0.12.0) |
| [storage-vessel-renders/](storage-vessel-renders/README.md) | Software renders of the floating-roof tank and the storage sphere | `2026-10-21-floating-roof-tanks` | Concluded 2026-10-26 (batch in 0.12.0) |
| [crystal-junction-holds/](crystal-junction-holds/README.md) | Thread t56: rule-off patches, form switch, 743 trace probes and logs | `2026-10-20-crystal-junction-holds` | Implemented 2026-10-20 |
| [junction-seeding/](junction-seeding/README.md) | Threads t44/t45: junction seeding prototypes, branch trace, drivers and logs | `2026-10-19-junction-seeding` | Concluded 2026-10-19 (batch in 0.9.0) |
| [post-release-followups/](post-release-followups/README.md) | Post-0.9.0 follow-ups: D46 probe, rule-off patches, drivers and logs | `2026-10-19-post-release-followups` | Implemented 2026-10-19 |
| [unified-thermo-consolidation/](unified-thermo-consolidation/README.md) | Thread t35 consolidation lane: javac-lane bitwise check, dead-code and comment tools | `2026-10-18-unified-thermo-consolidation` | Done 2026-10-18 |
| [fluid-solver-simplification/](fluid-solver-simplification/README.md) | Thread t38: Gradle-free 200-seed quick check (`quick-lane.sh`), form patches, traces | `2026-10-18-fluid-solver-simplification` | Implemented 2026-10-19 |
| [minecraft-mcp-bridge/](minecraft-mcp-bridge/README.md) | Pinned MCP control mod for `runMcpClient`: official v0.4.18 in its background input mode (the local 0.4.7 fix kept in `superseded-0.4.7-osguard/`) | `2026-10-16-minecraft-mcp-mouse-input` | In use since 2026-10-16 (official v0.4.18 on main since 0.12.4, 2026-10-26) |
| [glfw-focus-probe/](glfw-focus-probe/README.md) | Standalone LWJGL probe: a background GLFW window reports focus and moves the cursor | `2026-10-16-minecraft-mcp-mouse-input` | Concluded 2026-10-16 |
| [equipment-geometry-estimate/](equipment-geometry-estimate/README.md) | Single-file estimator of equipment blocks, shell cells, chunk quads and collision boxes | `2026-10-15-custom-equipment-rendering` | Concluded 2026-10-15 |
| [custom-equipment-rendering-poc/](custom-equipment-rendering-poc/README.md) | Proof-of-concept and dev-client checks of the equipment shell, devices, drums and units | `2026-10-15-custom-equipment-rendering` | Concluded 2026-10-20 (batch in 0.10.0) |
| [new-units/](new-units/README.md) | Gradle-free unit-design lanes and renders; OD4 hold probes, rounds 1 to 4 | `2026-10-15-custom-equipment-rendering` | In use since 2026-10-18 |
| [fluid-tank-column-gui-fixes/](fluid-tank-column-gui-fixes/README.md) | Gate runner, bridge driver, screenshots and gate logs of the tank/column GUI fixes | `2026-10-15-fluid-tank-column-gui-fixes` | Concluded 2026-10-15 |
| [jdk-determinism/](jdk-determinism/README.md) | Cross-JDK determinism sweep, `winsweep`, math probes and the determinism prototype | `2026-09-27-jdk-determinism` | In use since 2026-09-27 |
| [cloud-science-harness/](cloud-science-harness/README.md) | Minecraft-free javac + JUnit runner for the fluid gates (fallback, not the gates of record) | `2026-09-26-phase-ports-and-compressor` | In use since 2026-09-26 as a fallback |
| [session-transcripts/](session-transcripts/README.md) | Raw transcripts of the cloud session behind the phase-ports and jdk-determinism batches | `2026-09-26-phase-ports-and-compressor`, `2026-09-27-jdk-determinism` | Concluded 2026-09-27 (reference material) |
| [phase-ports-probes/](phase-ports-probes/README.md) | Measurement drivers and gate logs of the phase-ports batch, one folder per work package | `2026-09-26-phase-ports-and-compressor` | Implemented 2026-10-14 |
| [column-gui-mcp/](column-gui-mcp/README.md) | Detached MCP drag/framebuffer fixes and scale-3 window configuration | `2026-09-24-column-gui` | Detached 2026-09-24 (`43bf7c5`) |
| [pipe-junction-probe/](pipe-junction-probe/README.md) | Mixed-feed startup and finite-tank transient reproductions, Newton trace and alternative initializer patches | `2026-09-24-column-gui`, `2026-09-24-mixed-gas-junction` | Concluded 2026-09-25 |
| [coolprop-parity/](coolprop-parity/README.md) | Generates the CoolProp 8.0.0 parity fixtures for the Java Helmholtz oracle | `2026-09-24-coolprop-low-temperature` | Concluded 2026-10-19 (batch in 0.9.0) |
| [p6b-runtime-failures/](p6b-runtime-failures/README.md) | P6b: Gradle-free checkpoint reader, crystal UV, flush, chain and liquidus probes | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-26 (P6b) |
| [unified-thermo-port-m1/](unified-thermo-port-m1/README.md) | Port M1: deterministic rewrite scripts and checks of the engine port to main 0.8.0 | `2026-09-24-coolprop-low-temperature` | Done 2026-10-15 |
| [unified-thermo-port-m2/](unified-thermo-port-m2/README.md) | Port M2: network model on the equilibrium engine (D24, D25, D26) scripts and checks | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-m3a/](unified-thermo-port-m3a/README.md) | Port M3a: D21 follow-ups (theta, inner UV secant) probes and comparison | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-m3/](unified-thermo-port-m3/README.md) | Port M3: crystals as solids; cold-package hold fuzz and probes | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-m3-arrival/](unified-thermo-port-m3-arrival/README.md) | Trace of the cold fuzz class C holds (LNG into an empty tank) and the D31 probes | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-m4/](unified-thermo-port-m4/README.md) | Port M4: formats, Solids tab, ledger totals; rule-off proofs and GUI checks | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-review/](unified-thermo-port-review/README.md) | Probes of the unified thermo port design review (seeds 48 and 183) | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-test-world/](unified-thermo-port-test-world/README.md) | Owner test world of the unified thermo port: template, mixtures, MCP helpers, rig | `2026-09-24-coolprop-low-temperature` | Prepared 2026-10-16 |
| [unified-thermo-port-forms/](unified-thermo-port-forms/README.md) | Design-review forms on the quick checks; rule-off specs; bundled hold fuzz | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-extrapolation-plan/](unified-thermo-port-extrapolation-plan/README.md) | Probes behind the range-extrapolation plan (PR78, IF97, SR6, viscosity, ice) | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-extrapolation/](unified-thermo-port-extrapolation/README.md) | Probes and rule-off proofs of range extrapolation WP1 to WP3 | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 |
| [unified-thermo-port-ice/](unified-thermo-port-ice/README.md) | WP4 ice Ih: IAPWS check values, c_p segments and freeze-step probes | `2026-09-24-coolprop-low-temperature` | Done 2026-10-16 (report `ICE_IH_WP4.md`) |
| [unified-thermo-port-quick-fixes/](unified-thermo-port-quick-fixes/README.md) | Traces and probes of the quick-check regressions after the range work | `2026-09-24-coolprop-low-temperature` | Done 2026-10-17 |
| [unified-thermo-port-m5/](unified-thermo-port-m5/README.md) | Port M5 measurement lane (`lane.sh`), logs of m5 to m10, worse-world traces | `2026-09-24-coolprop-low-temperature` | Done 2026-10-18 |
| [p6-pilot-acceptance/](p6-pilot-acceptance/README.md) | P6: javac runner, crystal UV cost, bubble-point, chain and runtime-budget probes | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-26 (P6) |
| [p5-solid-liquid-scans/](p5-solid-liquid-scans/README.md) | P5: fusion-enthalpy and liquid-full-vessel probes, javac runner and scans | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P5) |
| [p4-network-rigs/](p4-network-rigs/README.md) | P4 stage 2b: CO2 dilute-gas viscosity nodes and network crystal rigs | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P4 stage 2b) |
| [gas-solid-equilibrium-scans/](gas-solid-equilibrium-scans/README.md) | P4 stage 2a: javac runner, vapour-solid scan and cost probe | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P4 stage 2a) |
| [solid-co2-qualification/](solid-co2-qualification/README.md) | P4 stage 1: runner and measurement probe of the solid CO2 model | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P4 stage 1) |
| [eppr78-table-s4-extraction/](eppr78-table-s4-extraction/README.md) | Node scripts that recover the E-PPR78 Table S4 matrix from the 2022 SI docx | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P3 WP2b) |
| [eppr78-transcription-check/](eppr78-transcription-check/README.md) | Builds the bundled E-PPR78 group interactions from Table S4 and checks them | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 |
| [pilot-volume-anchors/](pilot-volume-anchors/README.md) | Tr = 0.8 volume-translation anchors of the pilot records from the Helmholtz oracle | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-24 (P3 WP3) |
| [pilot-transport-tables/](pilot-transport-tables/README.md) | CoolProp 8.0.0 viscosity tables of the pilot property records | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-24 (P3 WP3) |
| [stability-cost-probe/](stability-cost-probe/README.md) | Detached P1 stability cost probe `TangentPlaneStabilityCostTest` with re-attach patch | `2026-09-24-coolprop-low-temperature` | Detached 2026-09-25 by `8a10bfd` (P3 WP11) |
| [phase-cost-probe/](phase-cost-probe/README.md) | Detached P2/P3 phase cost probe `FluidTpEquilibriumCostTest` with re-attach patch | `2026-09-24-coolprop-low-temperature` | Detached 2026-09-25 by `8a10bfd` (P3 WP11) |
| [wp4-chain-deviation-probe/](wp4-chain-deviation-probe/README.md) | Detached WP4 chain-100 liquid-volume deviation probe | `2026-09-24-coolprop-low-temperature` | Detached 2026-09-25 by `8a10bfd` (P3 WP11) |
| [wp11-closeout/](wp11-closeout/README.md) | P3 WP11 close-out: chain-100 re-capture scripts, gate logs before and after cleanup | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P3 WP11) |
| [wp7d-engine-defects/](wp7d-engine-defects/README.md) | P3 WP7d probes, before/after state surveys and logs of the engine defects | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P3 WP7d) |
| [g3-qualification/](g3-qualification/README.md) | Runner scripts and logs of the ten G3 fixture families | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P3 WP9b) |
| [wp7c-hot-gas-label/](wp7c-hot-gas-label/README.md) | P3 WP7c probes and envelope scans of the hot-gas phase label | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P3 WP7c) |
| [wp7b-engine-fixed-point/](wp7b-engine-fixed-point/README.md) | P3 WP7b probes and logs of the fixed-point wet flash start | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P3 WP7b) |
| [wp7-network-integration/](wp7-network-integration/README.md) | P3 WP7 probes, record-edit scripts, logs and engine patch of the network integration | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P3 WP7) |
| [wp5-legacy-digest/](wp5-legacy-digest/README.md) | Printer of the legacy network thermo path digest (`LegacyNetworkPathPinTest`) | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 (P3 WP5) |
| [wp4-runtime-diagnosis/](wp4-runtime-diagnosis/README.md) | P3 WP4 probes, patches and outputs behind the two runtime failures | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-25 |
| [gerg-bubble-points/](gerg-bubble-points/README.md) | GERG-2008 bubble- and dew-point references of the six pilot binaries (CoolProp) | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-24 (P3 WP8) |
| [methane-ideal-gas/](methane-ideal-gas/README.md) | Methane ideal-gas Cp from ExoMol energy levels, cross-checked | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-24 (P3 WP10) |
| [phase-equilibrium-scans/](phase-equilibrium-scans/README.md) | P2 robustness scans of `FluidTpEquilibrium` over binary and network states | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-24 (P2 phase side) |
| [spine-join-measurement/](spine-join-measurement/README.md) | P2 Node scripts: CoolProp ideal-gas terms against CEA, join choice per species | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-24 (P2 data side) |
| [tangent-plane-stability-scans/](tangent-plane-stability-scans/README.md) | P1 verification scans of the tangent-plane stability test | `2026-09-24-coolprop-low-temperature` | Concluded 2026-09-24 (P1 item 3) |
| [pipe-thermal-loss/](pipe-thermal-loss/README.md) | Zero-inventory thermal estimator, frozen-flow correction study, reference checks and prototype patch | `2026-09-24-pipe-thermal-loss` | Concluded 2026-09-24 |
| [junction-holdup-prototype/](junction-holdup-prototype/README.md) | Owned junction holdup and backward-Euler prototype patches and probes | `2026-09-24-mixed-gas-junction` | Concluded 2026-09-26 |
| [unified-thermo-holds-review/](unified-thermo-holds-review/README.md) | Thread t37: the m11 held seeds and rig holds sorted by mechanism | `2026-09-24-coolprop-low-temperature` | Concluded 2026-10-18 (analysis only) |
| [fluid-in-game-rig/](fluid-in-game-rig/README.md) | In-game fluid performance rig (server and client runs, JFR, samplers), detached mod-side log | `2026-09-23-fluid-scheduling-rest`, `2026-09-23-fluid-followups` | Detached 2026-09-24 (`c1b8464`) |
| [fluid-probes/](fluid-probes/README.md) | One-off probes, patches and run scripts of fluid scheduling WP0-WP4 and follow-ups F1-F4 | `2026-09-23-fluid-scheduling-rest`, `2026-09-23-fluid-followups` | Concluded 2026-09-24 |
| [fluid-paced-analysis/](fluid-paced-analysis/README.md) | Node analysis of `FluidServerBenchmark` report files | `2026-09-23-fluid-scheduling-rest` | In use |
| [mcp-gui-helpers/](mcp-gui-helpers/README.md) | `java.awt.Robot` and image helpers for dev-client GUI checks through the MCP bridge | `2026-09-23-fluid-scheduling-rest` | In use |
| [neural/](neural/README.md) | Neural initializer study code, training, evaluation and model cards (compiled by `development.gradle`) | `2026-09-10-v4-neural-initializer` | Implemented 2026-09-14 |
| [trace-followup/](trace-followup/README.md) | Controlled trace, anchor and exposure continuation follow-ups (origin of F0) | `2026-09-10-v4-neural-initializer` | Concluded 2026-09-12 |
| [training-curation/](training-curation/README.md) | Certified TRAIN curation v1 (905 of 906 profiles) | `2026-09-10-v4-neural-initializer` | Concluded 2026-09-11 |
| [hybrid-learning/](hybrid-learning/README.md) | Native-baseline residual Transformer (N / N+1) experiments | `2026-09-10-v4-neural-initializer` | Concluded 2026-09-11 |
| [hybrid-diagnosis/](hybrid-diagnosis/README.md) | Why the hybrid study underperformed: support ablation, gradients, training analyses | `2026-09-10-v4-neural-initializer` | Concluded 2026-09-11 |
| [capacity-followup/](capacity-followup/README.md) | Two- and four-layer capacity test; `build_branch_handoff.py` writes `BRANCH_HANDOFF.md` in the repository root | `2026-09-10-v4-neural-initializer` | Concluded 2026-09-13 |
| [decoder-floor-followup/](decoder-floor-followup/README.md) | R3: decoder presence floor on frozen F0 | `2026-09-10-v4-neural-initializer` | Concluded 2026-09-13 (negative) |
| [neural-budget/](neural-budget/README.md) | R2: progress-based neural correction budget and phase floor | `2026-09-10-v4-neural-initializer` | Implemented 2026-09-14 (defaults promoted) |
| [liquid-supply-screen/](liquid-supply-screen/README.md) | R6: request-only liquid-supply screen verification | `2026-09-10-v4-neural-initializer` | Implemented 2026-09-14 |
| [path-dependent-infeasible/](path-dependent-infeasible/README.md) | Retyping of the state-dependent heat gates (108 cases) | `2026-09-10-v4-neural-initializer` | Implemented 2026-09-14 |
| [integration/](integration/README.md) | V4 initializer integration notes and codec fix | `2026-09-10-v4-neural-initializer` | Implemented 2026-09-14 |
| [transformer-promotion/](transformer-promotion/README.md) | Production-path promotion campaign for F0 | `2026-09-10-v4-neural-initializer` | Implemented 2026-09-14 |
| [benchmark-population/](benchmark-population/README.md) | Benchmark population cleanup (request-only and never-solved exclusions) | `2026-09-10-v4-neural-initializer` | Implemented 2026-09-14 |
| [lnn-gap/](lnn-gap/README.md) | LNN_ONLY gap experiments E1-E5 (contraction factor 1.0 adopted; ramp handoff opt-in) | `2026-09-10-v4-neural-initializer` | Implemented 2026-09-14 |
| [dwsim/](dwsim/README.md) | DWSIM and ChemSep automation (C#, PowerShell) and V3 export / thermo cross-check Java | `2026-09-09-dwsim-chemsep-trials` | Concluded 2026-09-10 |
| [v3-cold-core-benchmark/](v3-cold-core-benchmark/README.md) | V3 cold-core benchmark supervisor, analyzer and JFR profiler | `2026-09-05-v3-solver-core-review` | Implemented 2026-09-05 |
| [development.gradle](development.gradle) | Registers the local research tasks (listed in the file) and adds `tools/neural` to the test source set | several | In use |
| [retired/](retired/README.md) | Code removed from tracked paths, kept for reference (`EquipmentType.java`, profile replays) | several | Retired 2026-09-14 |

## examples/ (tracked, left in place)

`examples/` is tracked: `MATERIALS.md`, `MaterialPresetsTest`, `src/test/resources/fluid/reference/README.md` and the fluid documents refer to these files by path.

| File | Purpose |
|---|---|
| `examples/material-override/` (`pack.mcmeta`, `data/createcheme/materials/properties/tjl20_methane.json`) | Example datapack that overrides material properties; read by `MaterialPresetsTest` and described in `MATERIALS.md` |
| `examples/material-gametest.gradle` | Adds the test classes to a development launch only, never to the production jar |
| `examples/Fluid-Benchmarks.py` | Fluid benchmark audit, summary and memory subcommands (the default jar is `build/libs/createcheme-<mod_version>.jar`, read from `gradle.properties` since `5f866a9`) |
| `examples/test_fluid_benchmark_summary.py` | Evidence-gate regressions for `Fluid-Benchmarks.py` |
| `examples/Generate-Hydraulic-References.py` | Decimal reference table for `src/test/resources/fluid/reference/hydraulic-friction.json` |
| `examples/Fetch-FluidSteamReferences.py` | Reproduces the NIST vapor compressibility checkpoints |
| `examples/Build-NitrogenFluidProperties.py` | Builds the versioned nitrogen data for the fluid package |
| `examples/Generate-Fluid-Models.py` | Deterministic prototype block models from Minecraft textures |
