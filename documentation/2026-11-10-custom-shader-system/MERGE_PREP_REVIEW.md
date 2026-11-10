# Merge preparation review: custom shader batch and its five merged batches (0.25.0)

Branch `claude/custom-shader-system-caff9e` at 971dd8346 over `main` 79d0a6254: 106 commits, 372 files. Batches `2026-11-10-custom-shader-system`, `-rigid-reservoir`, `-steam-ejector-flow`, `-chimney`, `-vacuum-pump`, `-prime-movers`. Checklist: `.claude/skills/merge-prep/SKILL.md`; translations and tests are not part of this review.

## Tooling cleanup

- `perl tools/merge-lane/main-reach.pl .`: 754 classes, 3 unreachable: `client/fx/compat/dh/DhBridge`, `DhGroups`, `client/fx/compat/iris/IrisBridge`. They stay: product code, loaded by name only when the optional mod is present (`FxCompat.IRIS_BRIDGE` and `FxCompat.load`; `FarSilhouettes.BRIDGE`; `DhGroups` is reached only from `DhBridge`'s guard), so that no other class names an Iris or DH type (plan 4.8). `DhBridgeTest` tests the bridge against a stub API.
- `src/main`: no measurement code. `client/fx/FxStats` stays (always-on counters and the player's F3 line); the bench adds its timers from outside `src/main` (`tools/fx-bench/README.md`). The frame probe is attached uncommitted by `lane.sh` and `record.sh` only.
- `src/test`: only test classes and the helper `RecordingSink`; every class runs in `test` or `equipmentGates`.
- `build.gradle`: kept. The Modrinth repository, `compileOnly` Iris and DH API (product compile needs), `-PwithIris` / `-PwithDh` (dev runs for the owner's manual compatibility check, plan W0), `equipmentGates` `Chimney*` and its 1 GiB heap (gate needs). No switch serves only a probe.
- Run configurations: none added. `documentation/` batch folders: text only, no scripts.
- Moved: `tmp/flame-preview/preview.py` (the numpy port of `fx_flame.fsh` that FX_VISUAL_REVIEW.md item 1 cites) to `tools/fx-bench/flame_preview.py`, README section and history; the review's link now points there.
- Left in `tmp/` (git-ignored, deleted with the stage per the scratch rules): one-off edit and splice scripts (`dh-trace/`, `merge-rigid/`, `wiring-alloc/`), `fx-bench/rerun.sh` and `verify.sh` (drivers of runs already reported), `fx-core-ref/plume.js`, `fx-effects/check.py`, `fx-wiring/preview.py`, `valve-turn/analyse_r3.py`. No document reproduces a result from them.

## Tool folders

- `tools/fx-bench/` (bench lanes, recorder, flame preview): README history brought up to date; index row status "In use since 2026-11-10".
- `tools/process-sounds/`, `tools/fx-textures/`, `tools/pbr-textures/`: unchanged, documented; the process-sounds index row now names the valve turn and prime mover sounds.
- `tools/custom-equipment-rendering-poc/frame-time-probe/` (`mode=fx`), `tools/machine-sounds/`, `tools/equipment-drawings/`: changed by the batches, documented in their READMEs.

## Code guide

- New chapter `effects.md` (effects, process sounds, noise watch, hand valve turn, far silhouettes) with a code-map row and a routing row in `README.md`.
- Updated, snapshot 971dd8346: `README.md` (summary, snapshot, versions line, code-map counts and rows), `fluid-science.md`, `fluid-runtime.md`, `equipment.md`, `rotating-machinery.md`, `heat-exchangers.md`, `cooling-towers.md`, `instrument-accessories.md`, `wired-control-power.md`, `machine-assemblies.md`, `process-simulator.md`, `client-gui.md` (the `FlareFlames` / `StillFires` item), `verification.md`.
- Versions read from the code: fluid facts schema 20, state format 46, envelope 1; `fluid-23`, `flare-2`, `still-1`, `mover-4`, `sight-glass-3`, `instruments-1`, `rotation-1`, `pipeline-tool-5`, `lines-1`, `control-3`, `machine-4` (fb944b7d2), `rack-3`; `equipment-23`, shape fields 12; `assembly-4`, store format 2; accessory facts 4, state 1.

## Test classes the cleanup can affect

None. The cleanup changed only `tools/` and `documentation/` (AGENTS.md, "No repeated tests": no test run). The final lane runs once for the batch.

## Lane fixes after the final lane

- The final lane ran once, on 7a7a1373c. Its failures were fixed in c6dc5426c (the kettle restart, the unit shell tables, the prime mover and vacuum pump defects) and 7fb9c03cb (seed 575). Each fix was checked by filtered runs of the classes it reaches (README history). No second final lane (AGENTS.md); test classes the fixes cannot reach stand from that lane.
- Code guide, snapshot 7fb9c03cb: `fluid-science.md` (the allowed-direction restart before a boundary closes; a device closed toward a forbidden flow stays closed), `equipment.md` (unit shell facets), `rotating-machinery.md` (the generator and motor frame helpers in `ProcessUnit`).
- `mod_version` 0.25.0 (owner: "Start merge prep" on the 0.25.0 proposal); `[Unreleased]` moved under `[0.25.0] - 2026-11-10`; the six batch rows and READMEs set to Implemented 2026-11-10.

## Finding for the owner

- `machine-3` gained the prime movers' fuel group (`MachineNetwork`, `MachineView.Fuel`) and two `ShaftNetwork.Kind` constants without a protocol bump: bumped to `machine-4` in fb944b7d2. Late owner changes in the same commit: nozzle pipes may touch (the `pipesApart` gate stays only for columns; a drum's connections clash on overlapping flanges). The final lane covers both. Commits of the preparation: translations 22b5e3924, cleanup a3009fb74, guide 44bd6176d, code fb944b7d2.

## Open items carried from the batch READMEs

- Custom shader: W0 dev-client compatibility checks (packs, Sodium, DH detail levels, the drip loop's shape); DH hull growth 1 m against 8-block voxels; Iris shadow pass for dials and heads; the sound levels the review left open; the API 521 flame-bend table read from memory; the still's burner jet across the door; gas-stack plumes only beyond 5D/f; copper as a sixth silhouette finish; unsounded diverter flow, governor valve; the turning lever's untinted bars on painted lines; a diverter position pointer; wiring CPU estimated 0.25 to 0.32 ms against 0.3 (not benched) and the remaining allocations.
- Rigid reservoir: no vacuum notice in the GUI (V1 A).
- Steam ejector: `VACUUM_SPEED` past the 100 m/s cap below 0.65 kPa; the set's status line reads "pump ..."; the vacuum column start (`ColumnStarts`); a narrow discharge line chokes the set (constructor hint).
- Chimney: closed slack bags, seed paths and `BatchStill.ATMOSPHERIC` at 101,325 Pa; the flare's void at its inlet cell's atmosphere; form held in the device facing; release booked as the void's EXCHANGE; the tile on the flare's symbol; a large brick chimney passes more than one 0.8 m pipe delivers; in-game check.
- Vacuum pump: in-game check; a vacuum column still refuses to start and its simulator block shows no pump part; no parallel pumps past the largest model; the separator about 4 m tall on model 3.
- Prime movers: in-game listen and look.
