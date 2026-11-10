# Steam ejector as a flow element, and its sound

Status: **In progress since 2026-11-10** (plan; waits for owner decisions E1 to E8). Branch `claude/ecstatic-dubinsky-c24b8a`, built on `c05453b05` of `claude/custom-shader-system-caff9e` (the plant noise system).

## Scope

Owner, 2026-11-10: "add sound for steam ejector as well"; option B: the in-world steam ejector becomes a real flow element first (motive steam through its nozzle to its discharge), and its sound reads that flow, as a valve's does.

## Documents

- [EJECTOR_FLOW_PLAN.md](EJECTOR_FLOW_PLAN.md): the choked motive flow as the 100 m/s cap through an equivalent throat (no new solver law), the throat rule from the suction, where the steam goes, entrainment later, the jet's sound level (98.5 dB small, the cap large) and loop candidates, saves, the simulator; decisions E1 to E8.

## History

- 2026-11-10: plan written; it waits for the owner's decisions.

## Code areas

`science/equipment/ProcessUnit` (ejector throat), `science/fluid/topology/TopologyCompiler`, `runtime/fluid/PhysicalFluidTopology`, `FluidFacts`, `world/level/block/EquipmentStructure`, `runtime/fluid/Noise`, `FluidWorldAuthority.islandNoise`, `client/fx/sound/`, `tools/process-sounds/`.

## Open items

- Decisions E1 to E8 (plan section 7).
