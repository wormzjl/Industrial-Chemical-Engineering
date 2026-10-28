# Documentation index

One row per batch of work, newest first. A row holds only the purpose and the current status; the batch folder's `README.md` holds its history, documents, code areas and open items. Layout and update rule: [README.md](README.md). Research: [../research/INDEX.md](../research/INDEX.md). Tools: [../tools/INDEX.md](../tools/INDEX.md). Release history: `CHANGELOG.md` in the repository root. A document cited by its old flat path `documentation/<FILE>`: [repository/FILE_LOOKUP.md](repository/FILE_LOOKUP.md).

Status words:

- **Implemented YYYY-MM-DD (version)**: the code is on `main`; the date is the day it reached `main`.
- **In progress since YYYY-MM-DD**: work has started on a branch and has not merged.
- **Planned**: a plan exists and no implementation has started.
- **Concluded YYYY-MM-DD**: research or measurement that was finished and was never meant to change code.
- **Abandoned YYYY-MM-DD** / **Superseded YYYY-MM-DD by ...**: stopped, with the reason in the batch `README.md`.

## Batches

| Batch | Purpose | Status |
|---|---|---|
| [2026-10-28-native-column-default/](2026-10-28-native-column-default/README.md) | The C++ column library by default, the Java path where it is missing | In progress since 2026-10-28 |
| [2026-10-28-column-operation/](2026-10-28-column-operation/README.md) | Run the column simulator in the built plant: buffered ports, a split table, solves on drift | Planned |
| [2026-10-28-gui-toolkit/](2026-10-28-gui-toolkit/README.md) | An own small GUI toolkit, first on the heat Control tab | Implemented 2026-10-28 (0.17.0) |
| [2026-10-28-gui-toolkit-evaluation/](2026-10-28-gui-toolkit-evaluation/README.md) | LDLib2 or an own toolkit for the GUI: evaluation and options | Concluded 2026-10-28 |
| [2026-10-27-heat-exchanger-design/](2026-10-27-heat-exchanger-design/README.md) | Heat exchanger system: UA from size, coupling of the two sides, built-in control | Implemented 2026-10-28 (0.17.0) |
| [2026-10-28-main-test-repair](2026-10-28-main-test-repair/README.md) | Fix main's 16 failing quick-suite tests | Implemented 2026-10-28 (0.16.2) |
| [2026-10-28-old-mover-removal](2026-10-28-old-mover-removal/README.md) | Remove the old one-block pump and compressor and their engine form; in-line movers replace them | Implemented 2026-10-28 (0.16.2) |
| [2026-10-27-column-pressure-drop/](2026-10-27-column-pressure-drop/README.md) | Keep published tray pressure profiles consistent on the aggressive solve path | Implemented 2026-10-27 (0.16.0) |
| [2026-10-27-hold-class-wall-deadline/](2026-10-27-hold-class-wall-deadline/README.md) | Audit an intermittent HoldClassWorldsTest wall deadline | Implemented 2026-10-27 |
| [2026-10-27-column-bench-audit/](2026-10-27-column-bench-audit/README.md) | Audit main-versus-fork column benchmark data | Implemented 2026-10-27 |
| [2026-10-27-creative-tab/](2026-10-27-creative-tab/README.md) | Keep IChemE items in the mod creative tab | Implemented 2026-10-27 |
| [2026-10-26-v3-solver-performance/](2026-10-26-v3-solver-performance/README.md) | V3 distillation solver performance and aggressive warm-start path | Implemented 2026-10-27 |
| [2026-10-27-constructor-import/](2026-10-27-constructor-import/README.md) | Equipment Constructor import always answers and preserves the draft | Implemented 2026-10-27 (0.15.3) |
| [2026-10-27-column-sump/](2026-10-27-column-sump/README.md) | Distillation column sump sized from engineering references | Implemented 2026-10-27 (0.15.1) |
| [2026-10-27-skirt-shadow/](2026-10-27-skirt-shadow/README.md) | Skylight on the floor inside vessel skirts | Implemented 2026-10-27 (0.15.1; GameTest fix 0.15.2) |
| [2026-10-26-multi-machine-framework/](2026-10-26-multi-machine-framework/README.md) | Multi-machine assemblies, first stage: the distillation column structure | Implemented 2026-10-27 |
| [2026-10-26-process-control/](2026-10-26-process-control/README.md) | Process sensors and local regulation | Implemented 2026-10-27 |
| [2026-10-26-liquid-level-gauge-accessory](2026-10-26-liquid-level-gauge-accessory/README.md) | Attachable full-height floating-roof tank level indicator | In progress since 2026-10-26; delivered b1477c09; rebase on current main600549d9 confirmed2026-10-26, main merge pending |
| [2026-10-26-pipe-laying-system](2026-10-26-pipe-laying-system/README.md) | Pipeline laying tool, layered pipe racks, port highlight, off-centre equipment connections | Implemented 2026-10-27 |
| [2026-10-26-accessory-store-compat](2026-10-26-accessory-store-compat/README.md) | Accessory store under the saves rule: facts kept, unreadable stores refuse | Implemented 2026-10-26 (0.13.1) |
| [2026-10-26-chunk-loading-review](2026-10-26-chunk-loading-review/README.md) | Chunk load and unload behaviour of the fluid network and equipment: review and fixes | Implemented 2026-10-26 (0.13.2) |
| [2026-10-26-backward-compat](2026-10-26-backward-compat/README.md) | Backward compatibility for the fluid network and equipment models: facts kept, state rebuilt | Implemented 2026-10-26 |
| [2026-10-26-air-cooler-chunk-rendering](2026-10-26-air-cooler-chunk-rendering/README.md) | Air cooler "Moving parts" switch reloads every chunk section: investigation and fix | Implemented 2026-10-26 (0.12.3) |
| [2026-10-26-index-consolidation](2026-10-26-index-consolidation/README.md) | Documentation and tools indexes cut to one short row each; detail moved into folder `README.md` files | Implemented 2026-10-26 (0.12.1, 0.12.2) |
| [2026-10-25-accessory-refinements](2026-10-25-accessory-refinements/README.md) | Gauge geometry, immediate accessories, tapping mounts, compact GUI, filter accessory | Implemented 2026-10-26 (0.12.0) |
| [2026-10-25-inline-pump-accessory](2026-10-25-inline-pump-accessory/README.md) | Pipe-applied horizontal pump/compressor accessory | Implemented 2026-10-26 (0.12.0) |
| [2026-10-25-sight-glass-bubbles](2026-10-25-sight-glass-bubbles/README.md) | Multiphase sight-glass animation and NBP-derived crude colour palette | Implemented 2026-10-26 (0.12.0) |
| [2026-10-25-sight-glass-flow-direction](2026-10-25-sight-glass-flow-direction/README.md) | Sight-glass bubbles travel in the accepted flow direction; paired windows agree | Implemented 2026-10-26 (0.12.0) |
| [2026-10-25-sight-glass-ratio-seams-sound](2026-10-25-sight-glass-ratio-seams-sound/README.md) | Gas/liquid bubble count, enclosed large fitting necks, size-scaled sound | Implemented 2026-10-26 (0.12.0) |
| [2026-10-25-tank-gauge-enclosure](2026-10-25-tank-gauge-enclosure/README.md) | Clear level-indicator sleeve and round tank/endcap connections | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-cutoff-handle-flicker](2026-10-24-cutoff-handle-flicker/README.md) | Coincident cutoff-valve handle faces that flickered removed | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-floating-roof-indicator-ports](2026-10-24-floating-roof-indicator-ports/README.md) | Front tank instrument, centred front/back ports and side access | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-fluid-gui-consistency](2026-10-24-fluid-gui-consistency/README.md) | Uniform fluid readings, pressure units and phase bases | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-gauge-accessories](2026-10-24-gauge-accessories/README.md) | Pressure and temperature gauges; shared accessory lifecycle | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-mover-equipment-arrows](2026-10-24-mover-equipment-arrows/README.md) | Equipment-side pump/compressor arrows; one connection flange on pumps | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-pipe-end-hitboxes](2026-10-24-pipe-end-hitboxes/README.md) | Dead-end pipe hitboxes match their drawn sections | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-pipe-flange-spacing](2026-10-24-pipe-flange-spacing/README.md) | Flanged spool spacing on straight pipes doubled (6 to 12 blocks) | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-pipeline-check-valve](2026-10-24-pipeline-check-valve/README.md) | One-way check valve accessory for straight pipes | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-pressure-valve-accessory](2026-10-24-pressure-valve-accessory/README.md) | Pipe-applied pressure control valve, tall actuator, heavier cutoff lever | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-pump-inlet-adapter](2026-10-24-pump-inlet-adapter/README.md) | OH2 pump inlet and outlet castings fitted to the connected pipes | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-tank-gauge-cap-flicker](2026-10-24-tank-gauge-cap-flicker/README.md) | Full-level gauge cap flicker removed (hollow collar) | Implemented 2026-10-26 (0.12.0) |
| [2026-10-24-wti-pumping-rate-hold](2026-10-24-wti-pumping-rate-hold/README.md) | Mover-limit rate basis, pump errors, remaining-hold inventories, cold 430/431 traces | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-deferred-fluid-hold](2026-10-23-deferred-fluid-hold/README.md) | Running-world refinement holds captured for later debugging | Concluded 2026-10-23 |
| [2026-10-23-fluid-device-controls-display](2026-10-23-fluid-device-controls-display/README.md) | Void composition, generator On/Off and phase ratios, /s and /h rates, reservoir capacity | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-fluid-display-followups](2026-10-23-fluid-display-followups/README.md) | Larger accessory inventory icons; generator Volume/Mass phase selector | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-fluid-format-reset](2026-10-23-fluid-format-reset/README.md) | Incompatible fluid formats reset and devices rebound instead of a startup crash | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-functional-pumps-compressors](2026-10-23-functional-pumps-compressors/README.md) | Constructed pumps and compressors work through their process nozzles | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-mover-port-markings](2026-10-23-mover-port-markings/README.md) | Word-free suction/discharge arrows on the pipes of constructed movers | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-pipeline-shutoff-valve](2026-10-23-pipeline-shutoff-valve/README.md) | Manual ball-valve shutoff accessory with axis-locked network isolation | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-pipeline-sight-glass](2026-10-23-pipeline-sight-glass/README.md) | Sight glass accessory showing liquid colour and turbidity | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-pipeline-tool](2026-10-23-pipeline-tool/README.md) | Creative pipeline diameter tool; new pipes inherit the adjacent diameter | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-pump-compressor-switch-sound](2026-10-23-pump-compressor-switch-sound/README.md) | On/Off, running sound and animation for constructed pumps and compressors | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-square-flanges](2026-10-23-square-flanges/README.md) | Square flange shoulders, visible joint seams, debugger-only pipe inspection | Implemented 2026-10-26 (0.12.0) |
| [2026-10-23-valve-regime-convergence](2026-10-23-valve-regime-convergence/README.md) | Equal-setpoint drum outlet convergence: accepted-only valve-mode continuation | Implemented 2026-10-26 (0.12.0) |
| [2026-10-21-agents-md-consolidation](2026-10-21-agents-md-consolidation/README.md) | AGENTS.md restated as plain instructions; obsolete pointers fixed | Implemented 2026-10-21 (0.11.2) |
| [2026-10-21-floating-roof-tanks](2026-10-21-floating-roof-tanks/README.md) | Floating-roof tanks and a storage sphere as open-vessel fluid reservoirs | Implemented 2026-10-26 (0.12.0) |
| [2026-10-21-rename-icheme](2026-10-21-rename-icheme/README.md) | Mod renamed to Industrial Chemical Engineering (`icheme`) | Implemented 2026-10-21 (0.11.0) |
| [2026-10-21-test-review](2026-10-21-test-review/README.md) | Every test reviewed against one bar; slow gates split from `test` | Implemented 2026-10-21 (0.11.1) |
| [2026-10-20-crystal-junction-holds](2026-10-20-crystal-junction-holds/README.md) | Cold holds exposed by 0.9.1: crystals melt on arrival, closure carry (D61, D62) | Implemented 2026-10-20 (0.9.2) |
| [2026-10-19-junction-seeding](2026-10-19-junction-seeding/README.md) | One quasi-steady junction seed replaces the incremental start machinery | Implemented 2026-10-19 (0.9.0) |
| [2026-10-19-post-release-followups](2026-10-19-post-release-followups/README.md) | Post-0.9.0 follow-ups: junction buffer sizing, still-pipe rule, ice refusal | Implemented 2026-10-19 (0.9.1) |
| [2026-10-18-fluid-solver-simplification](2026-10-18-fluid-solver-simplification/README.md) | Fluid solver simplifications from the m11 hold mechanisms (forms A, C, B) | Implemented 2026-10-19 (0.9.0) |
| [2026-10-18-unified-thermo-consolidation](2026-10-18-unified-thermo-consolidation/README.md) | Behaviour-free consolidation of the unified thermo port | Implemented 2026-10-19 (0.9.0) |
| [2026-10-16-minecraft-mcp-mouse-input](2026-10-16-minecraft-mcp-mouse-input/README.md) | MCP bridge moved the desktop mouse while the client was in the background | Implemented 2026-10-26 (0.12.4, main c038ceaa, not pushed) |
| [2026-10-15-code-consolidation](2026-10-15-code-consolidation/README.md) | Dead code, checks and tests removed (245 to 192 main files) | Implemented 2026-10-15 (0.7.2) |
| [2026-10-15-code-guide-findings](2026-10-15-code-guide-findings/README.md) | Code-guide rewrite findings: cached reads, queued recovery, compatibility paths removed | Implemented 2026-10-15 (0.7.2) |
| [2026-10-15-custom-equipment-rendering](2026-10-15-custom-equipment-rendering/README.md) | Parametric in-world equipment: constructor, drums, columns, units, machines, heaters | Implemented 2026-10-20 (0.10.0); follow-up 2026-10-21 (0.10.1) |
| [2026-10-15-fluid-tank-column-gui-fixes](2026-10-15-fluid-tank-column-gui-fixes/README.md) | Owner list of tank and column GUI fixes; held islands accept queued topology events | Implemented 2026-10-15 (0.8.0) |
| [2026-10-14-empty-start-progressive-volume](2026-10-14-empty-start-progressive-volume/README.md) | Empty start: a vessel's volume grows with its contents (D52 to D57) | Implemented 2026-10-14 (0.7.0, inside the phase-ports merge) |
| [2026-09-27-jdk-determinism](2026-09-27-jdk-determinism/README.md) | Bitwise-identical fluid results on every JDK (`DeterministicMath`) | Implemented 2026-10-14 (0.7.0, inside the phase-ports merge) |
| [2026-09-26-phase-ports-and-compressor](2026-09-26-phase-ports-and-compressor/README.md) | Phase ports on tanks, liquid-only pump, gas compressor | Implemented 2026-10-14 (0.7.0) |
| [2026-09-25-non-full-block-occlusion](2026-09-25-non-full-block-occlusion/README.md) | Face culling around partial fluid-device models | In progress since 2026-09-25 (not committed) |
| [2026-09-24-column-gui](2026-09-24-column-gui/README.md) | Editable column GUI, JSON presets, shared process GUI parts | Implemented 2026-09-24 (0.5.0) |
| [2026-09-24-coolprop-low-temperature](2026-09-24-coolprop-low-temperature/README.md) | Unified multiphase thermo engine: cubic extension, crystals, ice, range extrapolation | Implemented 2026-10-19 (0.9.0) |
| [2026-09-24-minecraft-mcp-skill](2026-09-24-minecraft-mcp-skill/README.md) | Agent skill for the dev-client MCP bridge; transcript mining | In progress since 2026-09-24 (not on `main`) |
| [2026-09-24-mixed-gas-junction](2026-09-24-mixed-gas-junction/README.md) | Mixed-gas junction start-up: backward-Euler basis, owned junction holdup | Implemented 2026-09-26 (0.6.0) |
| [2026-09-24-pipe-thermal-loss](2026-09-24-pipe-thermal-loss/README.md) | Zero-inventory pipe heat-loss assessment and 561-case experiment | Concluded 2026-09-24 |
| [2026-09-23-fluid-followups](2026-09-23-fluid-followups/README.md) | Pumped fills, start-up determinism, placement cost, checkpoint storage | Implemented 2026-09-24 (0.4.0) |
| [2026-09-23-fluid-scheduling-rest](2026-09-23-fluid-scheduling-rest/README.md) | Island scheduling, REST/STEADY certificates, engine-owned presentation | Implemented 2026-09-23 (0.3.0) |
| [2026-09-18-solid-phase-fluid-system](2026-09-18-solid-phase-fluid-system/README.md) | Solid phases, slurry transport and inline filtration | Implemented 2026-09-23 (0.2.0) |
| [2026-09-18-tray-pressure-drop](2026-09-18-tray-pressure-drop/README.md) | Flow-dependent sieve-tray pressure drop with a player column diameter | Implemented 2026-09-22 |
| [2026-09-17-crude-regrouping](2026-09-17-crude-regrouping/README.md) | Heavy-cut regrouping to `crude_pc01..12`; regrouped initializer | Implemented 2026-09-18 |
| [2026-09-15-fluid-network](2026-09-15-fluid-network/README.md) | Simulation engine and fluid network | Implemented 2026-09-17 |
| [2026-09-10-v4-neural-initializer](2026-09-10-v4-neural-initializer/README.md) | Transformer initializer for the V3 column | Implemented 2026-09-14 |
| [2026-09-09-dwsim-chemsep-trials](2026-09-09-dwsim-chemsep-trials/README.md) | DWSIM and ChemSep as external references for V3 | Concluded 2026-09-10 |
| [2026-09-09-solver-feasibility-studies](2026-09-09-solver-feasibility-studies/README.md) | Feasibility of a DSTWU shortcut and of the Bend language | Concluded 2026-09-18 |
| [2026-09-09-v1-calculator-removal](2026-09-09-v1-calculator-removal/README.md) | V1 column calculator removed; solver names cleaned up | Implemented 2026-09-09 |
| [2026-09-09-v3-convergence-time](2026-09-09-v3-convergence-time/README.md) | Measured V3 cost structure and its optimizations | Implemented 2026-09-09 |
| [2026-09-08-v3-free-water-trays](2026-09-08-v3-free-water-trays/README.md) | Free water on cold trays instead of a dew-point rejection | Implemented 2026-09-08 |
| [2026-09-08-v3-newton-alternatives](2026-09-08-v3-newton-alternatives/README.md) | Alternatives to Newton (DF-SANE) and timing weak spots | Abandoned 2026-09-09 (DF-SANE found no root) |
| [2026-09-07-v3-trace-scaling-and-truncation](2026-09-07-v3-trace-scaling-and-truncation/README.md) | Trace-spike singularity, row scaling, per-phase truncation, closure knob | Implemented 2026-09-08 |
| [2026-09-06-v3-literature-cdu-pumparounds](2026-09-06-v3-literature-cdu-pumparounds/README.md) | Literature CDU (Ledezma 2019): pumparounds as stage heat, wet condenser fix | Implemented 2026-09-08 |
| [2026-09-05-v3-solver-core-review](2026-09-05-v3-solver-core-review/README.md) | V3 numerical core review, cold-core benchmark, first core fixes | Implemented 2026-09-05 |
| [2026-09-01-reaction-thermochemistry-tool](2026-09-01-reaction-thermochemistry-tool/README.md) | Standalone reaction enthalpy and kinetics estimator (`rxnthermo`) | Planned |
| [2026-09-01-v3-full-cdu-draw-wall](2026-09-01-v3-full-cdu-draw-wall/README.md) | Full CDU plan and draw-rate wall experiments | Superseded 2026-09-06 by 2026-09-06-v3-literature-cdu-pumparounds |
| [2026-09-01-v3-holland-benchmark](2026-09-01-v3-holland-benchmark/README.md) | Holland Example 3-2 benchmark and in-game preset | Implemented 2026-09-01 |
| [2026-09-01-v3-steam-stripping](2026-09-01-v3-steam-stripping/README.md) | Steam stripping with a known-profile free-water contract | Implemented 2026-09-01 |
| [2026-08-31-v3-side-draws](2026-08-31-v3-side-draws/README.md) | Rate-specified liquid side draws on the V3 column | Implemented 2026-09-01 |
| [2026-08-31-v3-stage-trace-truncation](2026-08-31-v3-stage-trace-truncation/README.md) | Stage-trace truncation for the V3 column; the 50-55 kPa wall | Implemented 2026-09-01 |
| [2026-08-31-v3-vdu](2026-08-31-v3-vdu/README.md) | Vacuum distillation unit: literature case, thermo limits, case A plan | Planned |

## Other folders

| Folder | Holds |
|---|---|
| [reference](reference/README.md) | Living guides across batches, the code guide first |
| [repository](repository/README.md) | Documentation organization, branch records, old-name lookup, archived one-file index |
