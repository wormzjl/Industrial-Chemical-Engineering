# Custom shader and effects system

Status: **Planned** (2026-11-10). Branch `claude/custom-shader-system-caff9e`.

## Scope

Owner, 2026-11-10: a custom shader system for normal textures (first the instrument wiring laid out on equipment from sensors to the data port), better flare stack effects, a cloud effect for the two cooling towers and the furnace chimney, and effects for the fuelled still. Industrial realism; it must work with FPS optimization mods and the usual shader packs; it needs a config switch.

## Documents

- [CUSTOM_SHADER_SYSTEM_PLAN.md](CUSTOM_SHADER_SYSTEM_PLAN.md): the readings of the request, physics in numbers (white plumes as mixing-line condensation: none at the mod's 25 °C / 50 % ambient), the two render paths (own shaders without a pack, vanilla entity render types with LabPBR under a pack), the config switch, normal maps for the instrument wiring (tiled textures, our own entity shader without a pack, LabPBR under one), the effects and sounds, budgets, the compatibility matrix, W0 to W9, owner decisions X1 to X8 (X1 decided).

## History

- 2026-11-10: plan written; it waits for owner decisions X1 to X8.
- 2026-11-10: owner: the wires are the instrument wiring on equipment, from sensors to the data port, not the laid cable conduits. Section 4.4 rewritten: tiled branch and trunk textures with v in metres, `icheme:rendertype_wiring` without a pack, LabPBR under one; X5 now covers only later chunk sprites.

## Code areas (planned)

New `client/fx/` (settings, Iris detection, core shaders, render types, batch, wind, ambient, emitters); `client/FlareFlames`, `client/StillFires`, `client/InstrumentRenderer`, `science/equipment/InstrumentWiring` (v in metres), `client/ClientConfig`; `network/FluidNetwork` (new watches `TOWERS`, `HEATERS`, more fields on the flare state); `runtime/fluid/FluidWorldAuthority` (their states); `science/equipment/ProcessUnit` (part marks); `assets/icheme/shaders/core/`, `textures/` (`_flat`, `_n`, `_s`), `sounds/`; tools `tools/pbr-textures/`, `tools/process-sounds/`.

## Open items

- Owner decisions X2 to X8, and X1's period and jacket colour (plan section 8).
- W0 compatibility questions (plan section 7). They need dev-client checks, which the owner starts.
