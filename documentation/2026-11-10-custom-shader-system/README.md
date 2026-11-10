# Custom shader and effects system

Status: **Planned** (2026-11-10). Branch `claude/custom-shader-system-caff9e`.

## Scope

Owner, 2026-11-10: a custom shader system for normal textures (the wires on equipment surfaces first), better flare stack effects, a cloud effect for the two cooling towers and the furnace chimney, and effects for the fuelled still. Industrial realism; it must work with FPS optimization mods and the usual shader packs; it needs a config switch.

## Documents

- [CUSTOM_SHADER_SYSTEM_PLAN.md](CUSTOM_SHADER_SYSTEM_PLAN.md): the readings of the request, physics in numbers (white plumes as mixing-line condensation: none at the mod's 25 °C / 50 % ambient), the two render paths (own shaders without a pack, vanilla entity render types with LabPBR under a pack), the config switch, LabPBR textures, the effects and sounds, budgets, the compatibility matrix, W0 to W9, owner decisions X1 to X8.

## History

- 2026-11-10: plan written; it waits for owner decisions X1 to X8.

## Code areas (planned)

New `client/fx/` (settings, Iris detection, core shaders, render types, batch, wind, ambient, emitters); `client/FlareFlames`, `client/StillFires`, `client/model/ConduitModel`, `client/ClientConfig`; `network/FluidNetwork` (new watches `TOWERS`, `HEATERS`, more fields on the flare state); `runtime/fluid/FluidWorldAuthority` (their states); `science/equipment/ProcessUnit` (part marks); `assets/icheme/shaders/core/`, `textures/` (`_flat`, `_n`, `_s`), `sounds/`; tools `tools/pbr-textures/`, `tools/process-sounds/`.

## Open items

- Owner decisions X1 to X8 (plan section 8).
- W0 compatibility questions (plan section 7). They need dev-client checks, which the owner starts.
