# Effects, process sounds and far silhouettes

**Snapshot:** 971dd8346 (branch `claude/custom-shader-system-caff9e`), 2026-11-10. Batch `2026-11-10-custom-shader-system` (with `2026-11-10-steam-ejector-flow`, `2026-11-10-chimney` and `2026-11-10-prime-movers` merged in), to merge as 0.25.0: flames, smoke, plumes and heat haze drawn from published state, the instrument wiring's normal-mapped textures, one client loop budget for every process sound, the noise watch, the hand valve turn, painted equipment silhouettes in Distant Horizons. New chapter.

Up: [code guide](README.md)

## Read this when

- A flare, still, cooling tower, fired heater, chimney or prime mover shows no flame, smoke or plume, the wrong one, or one that clouds paint over.
- The effects break, flicker or cost frames with Iris, a shader pack, Sodium or Distant Horizons; the F3 line's numbers look wrong.
- A process sound (valve, vent, fan, falling water, burner, gearbox, kettle, ejector, flare, still, mover, driver) is missing, too loud or never stops; a hand valve does not turn.
- Distant Horizons' far terrain shows grey voxels where equipment stands.

## In one paragraph

Everything here is presentation. The server publishes small process states on its own schedule (about every 5 s) to clients that watch them, and answers a noise watch and a silhouette subscription from what islands last accepted and what the structure registry holds. Nothing solves, nothing is created and nothing is saved for it (AGENTS.md, "Engine schedule"). The client glides each state over 2 s (`FxGlide`), aims emitters at it and draws them in one batch per frame, through the mod's own core shaders when no shader pack is in use (SHADER path) or through vanilla entity render types otherwise (COMPATIBLE path). Sounds from every source share one budget of 12 loops. The optional Iris and Distant Horizons integrations are loaded by name and guarded, so no version of either stops the game.

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role |
|---|---|
| **Core (`client/fx/`)** | |
| [`FxMode`](../../../src/main/java/com/wormzjl/icheme/client/fx/FxMode.java), `ClientConfig` `[effects]` | `mode` AUTO (default), COMPATIBLE, OFF; `pbr_textures` true; `heat_haze` false; `dh_silhouettes` true. Drawing only; the server never reads them. |
| [`FxPath`](../../../src/main/java/com/wormzjl/icheme/client/fx/FxPath.java) | The frame's path, chosen once per frame: SHADER only when Iris is confirmed absent or has no pack loaded; COMPATIBLE otherwise or when a shader failed (`FxShaders.failed`). |
| [`FxBatch`](../../../src/main/java/com/wormzjl/icheme/client/fx/FxBatch.java) | Draws every registered `FxEmitter`: at `AFTER_WEATHER` (after clouds and rain), at `AFTER_PARTICLES` under Fabulous or a pack; haze first, then flames, smoke and plumes farthest first; nothing in an Iris shadow pass; emitters within the vanilla render distance and the frustum, ranked by covered pixels. SHADER path: one cached `VertexBuffer` per emitter, rebuilt only on a new glide (`GlideEmitter.revision`). |
| `FxBudget` | `FRAME_QUADS` 2000 per frame, `PACK_DENSITY` 0.5 under a pack. |
| `FxSink`, `FxPackedSink`, `FxImmediateSink` | One quad description with old and new values. Packed: both into vanilla's `NEW_ENTITY` layout for the vertex shaders to ease and face. Immediate: eased and faced on the CPU this frame (COMPATIBLE). Allocation-free per frame (bench 2026-11-10). |
| `FxShaders`, `FxRenderTypes`, `FxKind` | Core shaders `icheme:fx_flame`, `fx_plume`, `fx_haze`, `rendertype_wiring` (`assets/icheme/shaders/core/`); COMPATIBLE: `entityTranslucentEmissive` (flames), `entityTranslucentCull` (smoke, plume) on `textures/fx/` (`flame_flipbook` 8 frames with LabPBR `_s`, `smoke`, `plume`; baked by `tools/fx-textures/`). `defaultUniforms` sets samplers without vanilla's per-draw strings. |
| `FxGlide`, `FxWind`, `FxAmbient`, `FxPlume`, `FxColour` | The 2 s smoothstep glide in online game time; one deterministic wind per dimension from game time; the visual ambient from biome, height, rain and night (the server keeps 25 °C and 50 %); the mixing-line plume rule (Buck saturation, `JET_DILUTION` 5); the CPU colours of the shaders. |
| `FxGuard`, `FxCompat`, `FxStats` | A breaker per integration and per effect kind (the first `LinkageError` or `RuntimeException` turns it off for the session, one warning); the optional mods behind small interfaces, loaded by name; always-on counters and one F3 line (path, quads, emitters, loops). |
| **Emitters (`client/fx/emitter/`)** | |
| [`FxSources`](../../../src/main/java/com/wormzjl/icheme/client/fx/emitter/FxSources.java) | Every quarter second reads `ProcessStates` and keeps one source per watched structure, placed by the shape's marks: flare (`flare_tip`: flame, pilots, soot smoke or an unburnt plume, haze), cooling tower (`fan_exhaust_<i>`, `throat`: white plume), fired heater and prime mover stack (`stack_top_<i>`: plume, smoke for liquid or solid fuel, an engine's pulsing smoke and 4 s dark start, haze), still (`door`, `fire_bed`, `burner`, `flue_top`), chimney (`ChimneySource`: smoke by its opacity). Re-aims every `RETARGET` 5 s for wind and ambient. `seed` mixes the cell key. |
| `FlameModel`, `PlumeModel`, `StillModel`, `GlideEmitter` | Pure: API 521 flame length `exp(0.4562 ln Q − 5.3603)` and its cross-wind bend (table read from memory, open item), at most 27 quads; Pratte and Baines jet path, visible part by the mixing line, puffs that grow downwind, `CAP` 200 m, `Style.pulsing` for engines; the still's ember bed or burner jet; one glided value vector per effect. |
| **States (`client/fx/state/`)** | |
| `CellWatch`, `ProcessStates` | One watch per kind at the structure's fluid cell, at most `FluidNetwork.MAXIMUM_WATCHED` (64) nearest, asked once a second: `FLARES` (`flare-2`), `STILLS` (`still-1`), `TOWERS`, `HEATERS` (fluid-21), `CHIMNEYS` (fluid-23). Replaces `FlareFlames` and `StillFires` (deleted with their particles). |
| `SilhouetteStore` | The client's far silhouette records by origin, on the subscription only. |
| **Sounds (`client/fx/sound/`)** | |
| [`FxSounds`](../../../src/main/java/com/wormzjl/icheme/client/fx/sound/FxSounds.java), `SoundBudget` | Sources: the noise watch's answer (polled once a second, `FluidNetwork.requestNoise`), flares and stills from their states, `MoverSounds.emitters` and `DriverSounds.emitters`. Four times a second: one kind merged within `MERGE` 6 blocks (energy sum), at most `LOOPS` 12 by level at the listener `Lw − 20 log10 r`, `HYSTERESIS` 3 dB, `AUDIBLE` 30 dB; volume `10^((L − FULL)/20)` with `FULL` 110 dB (owner: "too loud", was 100), above it `3^((L − 110)/20)` up to 3 (a longer reach). |
| `DriverSounds` | Also the prime movers' start and stop one-shots on a speed crossing zero between two publications it saw (`engine_start_air` at 750 rpm and under, `engine_start_electric`, `engine_stop`, `gas_turbine_start`, `gas_turbine_stop`). |
| `ValveTurns`, `ValveTurnRenderer` | A hand valve's turn at the click: `FluidDeviceBlock.TURN_EVENT` block event to every client in range, the clicking client predicts; 1.0 s eased turn, wait for the block state, back after `PATIENCE` 10 s, `LINGER` 0.25 s. The part leaves the chunk mesh (`FluidDeviceBlockEntity.APART`) and is drawn through `MachineAnimation`'s buffers. One-shot `valve_turn` (a cleaned CC0 recording, `tools/process-sounds/valve_turn_cc0.py`). |
| **Far silhouettes (`client/fx/silhouette/`, `client/fx/compat/`)** | |
| `FarSilhouettes`, `SilhouetteShapes`, `SilhouetteBudget`, `SilhouetteBox`, `FarRenderer` | Subscribes only with DH present and `dh_silhouettes` on, radius DH's, at most `Silhouettes.MAXIMUM_RADIUS` 2048; shapes built on one low-priority worker from `ShapeRasterizer.layout` cells, finish per cell, greedy boxes grown `GROW` 0.02 m (at most `STRUCTURE_BOXES` 8192, else the hull), hull at most `HULL_BOXES` 16 grown `FAR_GROW` 1 m; `BOXES` 50,000 for all; inactive within the vanilla render distance, near set to `FAR_TIER` 4 render distances, hull past it; replan after `REPLAN` 256 blocks. |
| `compat/iris/IrisBridge`, `compat/dh/DhBridge`, `DhGroups` | The only classes naming Iris or DH types. Loaded by name (`FxCompat.IRIS_BRIDGE`, `FarSilhouettes.BRIDGE`), so `tools/merge-lane/main-reach.pl` lists them as unreachable; they stay. DH gate: API major 7, minor 2 or later (compiled against API 7.2.0, DH 3.3.3). In singleplayer the client level's render register comes from `getSinglePlayerLevel`. |
| **Server (`runtime/fluid/`, `network/`)** | |
| [`Noise`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Noise.java) | Lw per source once per publication per island, by chunk, no solver run: valve by its own drop (`valveDrop` on the row; `VALVE_CAVITATING`, `VALVE_FLASHING` by `PassiveStepSolver.cavitation`, σ against 1/x_Fz 0.3), vent (Lighthill 1e-4 M⁵), fans (`COOLER_FAN` 97, `TOWER_FAN` 100 dB, 50 log10 speed), falling water, burners, gearboxes, kettles, the pipe knock (Joukowsky, `KNOCK_FLOOR` 0.2 MPa, a = 1200 m/s), the ejector's jet (`EJECTOR_CASING` 35 dB off). `CAP` 120 dB; the noise watch answers the `MAXIMUM_SOURCES` 16 loudest within `RANGE` 48 blocks. |
| [`Silhouettes`](../../../src/main/java/com/wormzjl/icheme/runtime/fluid/Silhouettes.java) | Subscriptions on `equipment-23` (`SilhouetteWatch`, `SilhouetteBatch`): a full set at once, at most one per `REQUEST_TICKS` 100 per player, the `MAXIMUM_STRUCTURES` 256 nearest; differences on the presentation cadence after a registry change or a `MOVE` of 64 blocks; boxes kept per shape object. Ends at logout, dimension change or request. |
| `FluidWorldAuthority` | `publishFlares`, `publishStills`, `publishTowers`, `publishHeaters`, `publishChimneys`, `publishNoise`, `publishSilhouettes`: only with a watcher or subscriber. States: flare soot and exit velocity, tower exhaust (`TowerTransfer.exhaust`; no plume on a reversed draught), heater flue (`ConvectionCoil.stackTemperature`), chimney mass flow and opacity, prime mover exhaust. |
| `ProcessUnit` marks | `flare_tip`, `stack_top_<i>`, `fan_exhaust_<i>`, `throat`, `door`, `fire_bed`, `burner`, `flue_top` (`ShapeRasterizer.marks`; `EffectMarksTest`). |

## Contracts

- No effect or sound infers process state; a structure no longer watched loses its effects. A paused game freezes glides and wind.
- `neoforge.mods.toml` lists `iris` and `distanthorizons` as optional with `[0,)`; `build.gradle` compiles against Iris 1.8.12 and DH API 7.2.0 (`compileOnly`, Modrinth maven); `-PwithIris` (with Sodium) and `-PwithDh` put them on the dev runs only. The default runs, tests and GameTests load neither.
- Sounds are `.ogg` loops and one-shots in `assets/icheme/sounds/` with `sounds.json` and an `en_us` subtitle each; their authoring scripts are in `tools/process-sounds/` (the mover and driver loops in `tools/machine-sounds/`).
- The instrument wiring's tiled textures (`textures/wiring/branch`, `tray_1`..`tray_6`, each with `_n` and `_s`) come from `tools/pbr-textures/`; the wiring itself is in [instrument-accessories.md](instrument-accessories.md).

## Tests that pin it

All in `test` except `EffectMarksTest`, which `equipmentGates` runs. `client/fx/`: `FxAmbientTest`, `FxBudgetTest`, `FxCompatTest`, `FxGlideTest`, `FxGuardTest`, `FxPathTest`, `FxPlumeTest`, `FxSinkTest`, `FxStatsTest`, `FxWindTest`, `WiringTexturesTest`; `emitter/`: `FlameModelTest`, `PlumeModelTest`, `StillModelTest`; `silhouette/`: `SilhouetteBudgetTest`, `SilhouetteShapesTest`; `sound/FxSoundsTest`; `compat/dh/DhBridgeTest` (stub API). `client/ValveTurnsTest`; `runtime/fluid/NoiseTest`, `SilhouettesTest`; `science/equipment/EffectMarksTest`; `science/fluid/network/ValveCavitationTest`. The render cost is measured offline only (`tools/fx-bench/`, [FX_BENCH_REVIEW.md](../../2026-11-10-custom-shader-system/FX_BENCH_REVIEW.md)); no test pins it.
