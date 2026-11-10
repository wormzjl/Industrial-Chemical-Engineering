# Steam ejector as a flow element, and its sound

Status: **Implemented 2026-11-10** (0.25.0, with the custom-shader-system batch). Branch `claude/ecstatic-dubinsky-c24b8a`, built on `c05453b05` of `claude/custom-shader-system-caff9e` (the plant noise system).

## Scope

Owner, 2026-11-10: "add sound for steam ejector as well"; option B: the in-world steam ejector becomes a real flow element first (motive steam through its nozzle to its discharge), and its sound reads that flow, as a valve's does.

## Documents

- [EJECTOR_FLOW_PLAN.md](EJECTOR_FLOW_PLAN.md): revision 3 (sections 1a "Correlation form" and 1b "In line with the column ejector": the sheet's correlation and law, the smooth vacuum speed) over revision 2, the owner's "mover with a curve": the suction-to-discharge mover on the positive-displacement row with a vendor capacity curve (rated load from the suction at the sizing speed, none at the deepest vacuum, motive factor and MDP from the motive pressure), the motive steam on a planned valve into an internal junction, the jet sound (110.5 dB small, the cap large) and loop candidates, saves, the simulator; decisions E1 to E8.

## History

- 2026-11-10: plan written (2cbdadb10: a passive motive passage through an equivalent throat).
- 2026-11-10: owner: "Need a simplified model", then "Mover with a curve". Plan revision 2.
- 2026-11-10: owner: "All recommended" (E1 A to E8 A). Phase 2: loop candidates J1 to J3 (`tools/process-sounds/ejector_candidates.py`, main checkout `research/2026-11-10-steam-ejector-flow/sounds/`); `SteamEjector`, `FlowControl.Ejector`, `TopologyCompiler.Kind.EJECTOR` with its two junctions, `Ejectors` (the motive slice inputs), `Noise.Kind.EJECTOR` and `ejector_jet` (J1 stands in until the owner picks), facts schema 17.
- 2026-11-10: owner: "Make sure it's in-line with the column steam ejector" (plan 1b), loop pick "J2" (`ejector_J2_bright_screech.ogg` is `ejector_jet.ogg`), and "Yes, correlation" (plan 1a): capacity the sheet's area at `VACUUM_SPEED` times the motive share, steam the sheet's law on the motive valve (`ValveSource.EJECTOR`), `VACUUM_SPEED` smooth (`Bound.Step.rising`); the equivalent throat, P_r, P_0 deleted. Corpus `compat/fluid/schema-17`. Targeted tests pass (see the commit).

## Code areas

`science/machine/SteamEjector`, `science/flowsheet/sizing/Bound`, `Limit`, `science/fluid/network/FlowControl`, `science/fluid/topology/TopologyCompiler`, `runtime/fluid/Ejectors`, `runtime/fluid/PhysicalFluidTopology`, `FluidFacts`, `world/level/block/EquipmentStructure`, `runtime/fluid/Noise`, `FluidWorldAuthority.islandNoise`, `client/fx/sound/`, `tools/process-sounds/`.

## Open items

- A closed vessel cannot be pulled below the ambient (its wall goes slack, D54): the set holds a vacuum only on a node that keeps its pressure (a column top, a generator). A rigid vacuum drum is the owner's call.
- `VACUUM_SPEED` below 0.65 kPa passes the world's 100 m/s cap (the cap holds the line first); a clamp at 100 m/s is the owner's call.
- A set's status line still reads "pump ..." (`PipePresentation.pumpStatus`); the vacuum column start (`ColumnStarts`, E6 A) is its own batch.
- The discharge line carries the load and the steam: at the rated point about 0.27 kg/s at 1.05 bar on a 0.1 m set, which the 100 m/s cap passes only from about a 0.09 m bore (the drawn discharge nozzle is 0.75 s); at atmospheric suction the load is about ten times its rated mass. A line too narrow chokes, its junction rises past the set's maximum discharge and the set breaks. Tests use a 0.2 m line on a 0.1 m set; a constructor hint is the owner's call.
