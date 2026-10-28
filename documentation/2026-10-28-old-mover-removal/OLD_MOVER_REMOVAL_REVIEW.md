# Old one-block pump and compressor removal: review

Branch `claude/vigorous-bell-dab92b`, base `96806b8a` (main 0.16.1). Plan: [OLD_MOVER_REMOVAL_PLAN.md](OLD_MOVER_REMOVAL_PLAN.md) (section 6 is the final step).

## 1. Expected behaviour

A pump or a compressor sits in the line, flanged to its pipe at the line's bore, as the In-line Pump and In-line Compressor do. No one-block mover remains, in blocks, saves or the engine (owner, 2026-10-28: "Remove the old pump/compressor block entirely"). A world that had one loads without it. A mover holds no stock, so every vessel keeps its moles and energy.

## 2. What the code did

- The blocks `fluid_pump` and `fluid_compressor` stayed as command-only blocks with their own drawing and connection rule: they joined only pipes (D20). They registered portless `PUMP`/`COMPRESSOR` devices, which the engine compiled and about 25 test helpers and the hold worlds built directly.
- First step (`83ed8a58`, superseded): block aliases to `fluid_pipe`, conversion on chunk load (`convertOneBlockMover`, options B1/J1/V1) and a ledger kind exception (`sameKind`). The owner then chose to remove the form entirely, with no conversion.

## 3. Change

- Removed: both blocks and their assets (blockstates, models, loot tables, `block.icheme.*` lang lines, tags, valid-block entries), `LineGeometry.pump`/`compressor`, the block case in `FluidDeviceBlock.face`. The in-line items keep their ids, models and lang lines.
- Engine: `PhysicalFluidTopology.Device` refuses a pump or compressor without ports. A mover is constructed (two ports) or in-line (a horizontal `PIPE` carrying its control).
- Saves: no alias, no conversion. `FluidFacts.world` drops a portless `PUMP`/`COMPRESSOR` registration and logs each one (`World.removedMovers`). `FluidCheckpointCodec.topology` then refuses the unit's state, so the load takes the FACTS route and rebuilds the islands without them. This is the owner's override of the no-discard rule, for these registrations only.
- Corpus image `compat/removed-movers`, written by main `96806b8a` (tool [tools/old-mover-removal](../../tools/old-mover-removal/README.md)). `FluidFactsCompatTest.aWorldWithOneBlockMoversOpensWithoutThemAndKeepsEveryVessel` checks four things: the FACTS route with a reason naming the movers, 8 of 10 registrations left, the moles unchanged, and an EXACT reload.

## 4. Tests

- The GameTests that placed the old blocks place in-line movers: `FluidPumpStartupGameTests`, `SolidPhaseGameTests`, `FluidPhasePortGameTests`. `FluidPacketGameTests` no longer lists the old blocks. `LineGeometryTest` loses the two drawings.
- Helpers that lay a pump or compressor by kind now lay an in-line mover (`FluidTestSupport.mechanical`): `EventPathWorld`, `BlockLineHarness` and 15 others.
- Upright movers are rebuilt on their side at the same height, beside an elbow: `ElevatedBlockLineIslandTest` (rising pump), `PumpBottomPortDrainTest` (both drains), `PhysicalFluidTopologyTest` (bottom-face pump). The heads and assertions are unchanged.
- Hold seeds 850 and 743 (`HoldClassWorldsTest`) had dead-end movers hanging down. Each is now an elbow in the mover's cell, with the mover beside it, still discharging away from the line.
- J1 (an in-line mover is a pipe and joins the equipment on its axis) changes three tests:
  - `equipmentSideBySideExchangesNothing` now expects a pump or compressor to join;
  - `theRegistryWalksFollowTheSameRule` uses a valve;
  - `anActuatorTakesTheBoreOfThePipeOnEachSide` covers valves only.
- `PipelineFittingTest` no longer compares with the native form.
- `FluidPumpedFillLineTest`: the compressor between 0.1 m pipes has the 0.1 m bore, as `installPipeMover` keeps the host's bore. With 0.05 m it rested at 102333.2 Pa, 5 Pa under the ratio limit of 102338.25 Pa; with 0.1 m it rests at 102337.7 Pa.

## 5. Rule-off checks (one test each, patch reverted afterwards)

- Seed 850 with `SolidEventIntegrator.still` off refuses 25 solid transitions; the gate fails. The doc's 1146 is from its 2026-10-19 trace; the old layout was not re-measured on current code.
- Seed 743 with `tools/crystal-junction-holds/patches/rule-off-row-seed.patch` (paths renamed to `icheme`) holds at committed 13758 (line search stalled), as documented.

## 6. Gates

No dev client running. On the final tree: `test` 1654/1654 and `slowGates` 188/188. For the fluid GameTests see the README history. No in-game check.

## 7. Open and not changed

- At rest, the in-line compressor's shutoff depends on its bore (5 Pa, or 0.5 % of the rise, at 0.05 m against 0.1 m). It is not caused by this batch. Physically the shutoff at zero flow should not depend on bore; this needs a look by the owner.
- Not changed, all historical and run by no gate: `examples/Generate-Fluid-Models.py`, which still writes a stale `fluid_pump` box model, and the `tools/` rigs that place `icheme:fluid_pump`. Also stale: the hold-fuzz harnesses in `tools/unified-thermo-port-forms/`, which build portless movers.
- Code guide `fluid-runtime.md`: the ModBlocks row, the `Device` row, chunk load, facts, and the in-line mover wording. The snapshot line is set at the merge.
- Found on main and fixed in batch [2026-10-28-main-test-repair](../2026-10-28-main-test-repair/README.md): 16 quick-suite failures.
