# Chimney

Status: **In progress since 2026-11-10** (built on the branch, not merged). Branch `claude/chimney-b7484c`, cut from `claude/custom-shader-system-caff9e` at `6e95356b5` (its plume, smoke and VENT sound).

## Scope

Owner, 2026-11-10: a chimney that releases gas, and any particulates it carries, to the atmosphere. Two forms (a self-supporting steel stack on a plinth; a tapered brick or concrete chimney with a steel liner), scalable in height and diameter, sized by exit velocity with the 20 % spare and by the nearby structures' height; particulates released, shown in kg/h and drawn as grey-to-black smoke.

## Documents

- [CHIMNEY_PLAN.md](CHIMNEY_PLAN.md): research (exit velocity, GEP height, draft, opacity), proposal Q1 to Q10, owner decisions C1 to C7 and answers, as built (section 6), the references C1 B moved with old and new values (section 7).

## History

- 2026-11-10: research and plan (601030ae7); decisions C1 to C7 sent to the owner.
- 2026-11-10: owner "All recommended". C1 B, the atmosphere falls with height at open boundaries (170477cc9, fluid state 44). Kinds, design, engine view, data port (f062456e7). Plume watch fluid-23, effects, VENT sound, data socket (1c4a32b18). Simulator unit, sizing, tile, door, notes, device page, English lines (84bdb1386). Tests, layout rows, re-recorded references, steel stack rest decks (aec962141). Targeted tests only, all green; drawings regenerated and inspected.

## Code areas

`science/fluid/solver/PhaseLayout` (atmosphere), `science/fluid/network/PassiveNetwork` (inventory ambient), `Chimney`, `science/equipment/ChimneyDesign`, `ProcessUnit` (steelStack, brickChimney), `EquipmentServices`, `runtime/fluid/Chimneys`, `FluidWorldAuthority` (placeChimney, watch, noise), `PhysicalFluidTopology` (chimney device, stack section), `network/FluidNetwork` (fluid-23), `client/fx/emitter/FxSources` (ChimneySource), `science/flowsheet/unit/ChimneyUnit`, `sizing/ChimneySizing`, `FluidDeviceScreen`.

## Open items

- Closed slack bags stay at 101,325 Pa (C1 B exception): a bag also stands in the open air.
- Seed paths (`emptyBagSplit`, `flashSeeds`, `supplyFlow`) and the batch still's head (`BatchStill.ATMOSPHERIC`) keep 101,325 Pa.
- The flare's void stands at the atmosphere of its inlet cell (its run carries no stack column).
- The chimney's form is held in the device facing; a chimney is recognised by its device shape (a passive one-port VOID off the port lane).
- Ledger: released gas is booked as the void's EXCHANGE, not a separate ATMOSPHERE kind.
- The simulator tile reuses the flare symbol until a chimney symbol is drawn.
- A large brick chimney passes more gas than one 0.8 m inlet pipe delivers.
- Final lane: fluid GameTests (CoolingTowerGameTests), chain-100 regression, holdGates, campaign.
- In-game check (owner's choice): placement, plume and smoke, page meters.
- Translation commit, CHANGELOG, version and code guide at merge (merge-prep).
