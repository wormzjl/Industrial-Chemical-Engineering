# Custom shader and effects system

Status: **In progress since 2026-11-10**. Branch `claude/custom-shader-system-caff9e`.

## Scope

Owner, 2026-11-10: a custom shader system for normal textures (first the instrument wiring laid out on equipment from sensors to the data port), better flare stack effects, a cloud effect for the two cooling towers and the furnace chimney, and effects for the fuelled still. Industrial realism; it must work with FPS optimization mods and the usual shader packs; it needs a config switch.

## Documents

- [CUSTOM_SHADER_SYSTEM_PLAN.md](CUSTOM_SHADER_SYSTEM_PLAN.md): the readings of the request, physics in numbers (white plumes as mixing-line condensation: none at the mod's 25 °C / 50 % ambient), the two render paths (own shaders without a pack, vanilla entity render types with LabPBR under a pack), the config switch, normal maps for the instrument wiring (tiled textures, our own entity shader without a pack, LabPBR under one), the effects and sounds, budgets, the compatibility matrix, W0 to W9, owner decisions X1 to X12 (X1 decided), the ATEX connection practice the wiring follows.
- [SOFT_DEPENDENCY_REVIEW.md](SOFT_DEPENDENCY_REVIEW.md): which related mods to depend on optionally: Iris API now; Sodium, Embeddium, Flywheel and the effect libraries not; Distant Horizons and LambDynamicLights later; where the speed comes from instead; Distant Horizons added (X11 A); the guards against changes in those mods.
- [PROCESS_SOUNDS_REVIEW.md](PROCESS_SOUNDS_REVIEW.md): which other equipment makes noise (valves by stream power and cavitation, vents, fans, falling water, burners, gearboxes, kettles), one server-culled noise watch and one client budget of 12 loops; decisions S1 to S4.

## History

- 2026-11-10: plan written; it waits for owner decisions X1 to X8.
- 2026-11-10: owner: the wires are the instrument wiring on equipment, from sensors to the data port, not the laid cable conduits. Section 4.4 rewritten: tiled branch and trunk textures with v in metres, `icheme:rendertype_wiring` without a pack, LabPBR under one; X5 now covers only later chunk sprites.
- 2026-11-10: owner: check how an ATEX connection looks. Findings in plan 4.4: a gland on the head with a drip loop, one light-blue single-pair armoured cable per transmitter, the branch cables side by side on a perforated tray to an Ex e 316L junction box with bottom glands, one multi-pair main cable out. Design follows: tray textures by cable count, an ATEX head entry (X9), bottom or side entries; new decisions X9 and X10.
- 2026-11-10: owner: performance first; check soft dependencies. Review written: only the Iris API is needed (compileOnly, optional, version range); Flywheel breaks under packs without a third-party add-on; speed comes from GPU-animated cached buffers in the SHADER path, budgets, half density under a pack and a shadow-pass guard. Plan 4.2, 4.6 and W1 follow; new decisions X11 and X12 (later batches).
- 2026-11-10: owner: add DH silhouettes (X11 A); make sure a change in those mods cannot crash the game. Plan 4.7: painted, cell-accurate box silhouettes from the server registry on the client's request, covering DH's grey voxels, inactive within the vanilla render distance, hull boxes far away, W10. Plan 4.8: no direct link, runtime version gate and no load-time range, every call guarded, safe fallbacks (COMPATIBLE path; DH's own voxels), gates with stub APIs. The review's claim that equipment vanishes in DH was wrong and is corrected.
- 2026-11-10: owner: go with every recommended option (plan section 8); merge main before starting; implementation agents at medium reasoning effort.
- 2026-11-10: phase 1 started (fx-core, fx-server-states, fx-sounds). fx-sounds done: five synthesised loops, 281b8bb7e (not compiled yet).
- 2026-11-10: owner: think about sounds for other equipment, valves included, and update performance. Review written. Owner: valve volume from its pressure drop alone (while it flows), the rest as recommended (S1 A to S4 A); fx-noise agent approved for after phase 1.

## Code areas (planned)

New `client/fx/` (settings, Iris detection, core shaders, render types, batch, wind, ambient, emitters); `client/FlareFlames`, `client/StillFires`, `client/InstrumentRenderer`, `science/equipment/InstrumentWiring` (v in metres), `client/ClientConfig`; `network/FluidNetwork` (new watches `TOWERS`, `HEATERS`, more fields on the flare state); `runtime/fluid/FluidWorldAuthority` (their states); `science/equipment/ProcessUnit` (part marks); `assets/icheme/shaders/core/`, `textures/` (`_flat`, `_n`, `_s`), `sounds/`; tools `tools/pbr-textures/`, `tools/process-sounds/`.

## Open items

- W0 compatibility questions (plan section 7). They need dev-client checks, which the owner starts.
