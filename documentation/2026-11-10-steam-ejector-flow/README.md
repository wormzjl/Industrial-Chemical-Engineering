# Steam ejector as a flow element, and its sound

Status: **In progress since 2026-11-10** (plan revision 2; waits for owner decisions E1 to E8). Branch `claude/ecstatic-dubinsky-c24b8a`, built on `c05453b05` of `claude/custom-shader-system-caff9e` (the plant noise system).

## Scope

Owner, 2026-11-10: "add sound for steam ejector as well"; option B: the in-world steam ejector becomes a real flow element first (motive steam through its nozzle to its discharge), and its sound reads that flow, as a valve's does.

## Documents

- [EJECTOR_FLOW_PLAN.md](EJECTOR_FLOW_PLAN.md): revision 2, the owner's "mover with a curve": the suction-to-discharge mover on the positive-displacement row with a vendor capacity curve (rated load from the suction at the sizing speed, none at the deepest vacuum, motive factor and MDP from the motive pressure), the motive steam on a planned valve into an internal junction, the jet sound (110.5 dB small, the cap large) and loop candidates, saves, the simulator; decisions E1 to E8.

## History

- 2026-11-10: plan written (2cbdadb10: a passive motive passage through an equivalent throat).
- 2026-11-10: owner: "Need a simplified model", then "Mover with a curve". Plan revision 2; it waits for the owner's decisions E1 to E8.

## Code areas

`science/equipment/ProcessUnit` (ejector throat), `science/fluid/topology/TopologyCompiler`, `runtime/fluid/PhysicalFluidTopology`, `FluidFacts`, `world/level/block/EquipmentStructure`, `runtime/fluid/Noise`, `FluidWorldAuthority.islandNoise`, `client/fx/sound/`, `tools/process-sounds/`.

## Open items

- Decisions E1 to E8 (plan section 7).
