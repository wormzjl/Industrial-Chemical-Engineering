# Independent code review before the merge (2026-11-02)

Reader: one Opus subagent that did not write this code, read only. Pinned: d5 = d5f7a3413 (column operation), a8 = a8e198a48 (solver fix). Verdict: not ready to merge. Items 1 to 4 are serious. No rig has a pump or a second valve on a port line, so the gates cannot see items 1 and 2.

## Orchestrator's check of the review

- Finding 2, RUN, not only read. EventPathWorld, `runTo(800)`: water generator 2 bar 300 K, pipe, `Kind.PUMP` with `new FlowControl.Pump(.01,500000)`, pipe, pipe with `ControlValve(MASS_FLOW,target,...)`, pipe, void 2 bar, bore 0.1 m.
  - target 5 kg/s: `HELD: Substep refinement exhausted: Singular Newton Jacobian`, active-set pass 1.
  - target 0: the same hold.
  - target 50 kg/s: `STEADY: replaying 9.966 kg/s`, modes [PASSIVE, PUMP_TARGET, VALVE_OPEN].
  - The same three results on a8e198a48 and on main's base 79b853e0d. So the defect is older than this batch and reaches every plant with a pump ahead of a flow valve set below the pump's rate, not only a column.
- Finding 1: `FlowControlTest.twoFlowValvesInSeriesDoNotCreateADependentSolve` asserts VALVE_CONFLICT on both valves, so the conflict is built behaviour. Read in `ControlConstraints.dependent`, `PassiveStepSolver.solve` (`heldOpenings`) and the mode decision (a fixed opening returns before the target-zero check). Plan 4.3 says conservation does not depend on the valve; plan Q3 rejected generator outlets because their conservation is not by construction; the C3 decision used a generator anyway.
- Findings 3, 4, 5, 11, 13: read at the pinned commit, they agree. 3 rests on `registry.applyPending()` leaving an event unapplied at `capture()`; the chain was read to there, not executed.
- Finding 8: read `AssemblyPresentation.open`, `AssemblyNetwork` `view`, `FluidNetwork.opening`, `FluidWorldAuthority.view`; `IslandCoordinator.snapshot` materialises a certified island.
- Finding 7(b): `BalanceNet` does write `modes` in place. Whether that is a defect at the new call site is not decided.
- Not checked by the orchestrator: 6, 7(a), 9, 10, 12, 14, 15, 16.

## Findings, most severe first (the reviewer's text)

1. `ControlConstraints.java:23-25`, `PassiveStepSolver.java:351,773,1916-1920`, `ColumnOperation.java:550` (d5). An outlet's account is protected only by its valve's mass target, and two MASS_FLOW valves in series lose that target.
   Scenario: column A's bottoms piped straight to B's feed, or a player's flow valve on a product line. Both valves go VALVE_CONFLICT and ignore the target, even 0. The opening defaults to 1.0 at each new target, because `acceptedOpenings` keys on the full identity (a8:248,359). At 0.9 bar over 20 m of 0.1 m liquid line the generator gives 44 kg/s against 5 kg/s made. The 600 kg buffer lasts 15 s; then the clamp at :550 creates about 195 kg per slice, and B's account grows without bound. Plan 4.3 says conservation does not depend on the valve; it does.
   CONFIRMED (`FlowControlTest:68` shows the conflict). A valve shared by two columns: last plan wins in `ports`, PLAUSIBLE.
   Proposal (owner): the lowest target in a series group regulates, the others stand open; limit the gift in the solve, or refuse Start.
2. `PassiveStepSolver.java:2400` (d5; a8:2371, 2428, 793, 799). A pump at PUMP_TARGET in series with a regulating flow valve has two prescribed flows, and `anchors` treats that valve as a pressure anchor.
   Scenario: tank, default pump (0.01 m3/s, about 8 kg/s), inlet valve, design 5 kg/s. Target 0 before the first table reaches this at once; a target below 8 kg/s reaches it at about 40 % account fill. Rows: q_pump, q_valve and their junction's continuity in 2 unknowns, so the pass is singular at every step size and the feed island holds. `relieveSealedPockets` even turns a closed flow valve to regulating.
   CONFIRMED by counting rows, not executed.
   Proposal: only a pressure valve anchors; the pump then goes to its head limit, or to shutoff behind a closed valve.
3. `WorldTopologyLedger.java:131`, `FluidWorldAuthority.java:490-493,851` (d5). Dismantling a running column removes the unit while `active` still holds devices naming it.
   Scenario: dismantle, then autosave or quit before alignment. `snapshot()` throws IAE, uncaught in `FluidSavedData.save`: the server crashes and loses everything since the last save. CONFIRMED.
   Proposal: remove the unit when its release event commits.
4. `FluidWorldAuthority.java:469-470`, `ColumnOperation.java:126,520,523` (d5). Stop clears `portUnits` at once while the ports stay in islands until the event applies.
   Scenario: every Stop, one or two slices. Inlet arrivals vanish (46 mol/s for 5 to 10 s is 230 to 460 mol), outlet gifts are not debited and are given again at restart, and the condenser mismatch goes unbooked. CONFIRMED.
   Proposal: keep the mapping and the run until the removal commits (`RegistryHost.committed`).
5. `FluidFacts.java:594,607,610` (d5). `Restated` copies its maps at :607, before the accounts are mapped into `removed`, so a component removed from an account never reaches the ledger or the status line. Account energy is neither recomputed nor booked, against AGENTS saves. Scenario: a data change that drops a component held in an account. CONFIRMED.
   Proposal: map accounts first, book the energy difference.
6. `IslandCoordinator.java:810` and `PassiveNetwork.java:115` (d5; `sameStructure`). Identity comparisons left on the strict form. `sameLines` in a8:421 has no production effect: `island.graph` always matches `committed.graph()`, `ports` runs after the replay, and a hand-set target arrives as a topology event.
   Scenario: a hand-edited target on a running hot line loses its committed interval and restarts cold, the door OUTLET_HOLD_REVIEW section 3 says is closed; `RegulatingValveHoldTest:124` feeds `RetainedSolver` a graph production never sends. A port island replaced never matches either (COLUMN_SENSOR against the registry's control). Also a8:2338 `closeIllegalStarts` is skipped on each job's first solve after a new target.
   Path CONFIRMED, the hold PLAUSIBLE.
   Proposal: `sameLines` at all three, with a port valve compared to its registry control.
7. `PassiveStepSolver.java:614` (a8). Two side effects of the regulated pass.
   (a) It clears the carried point. If that pass fails, the retry finds `needsBalance` true and starts from the balance's delivered mixture, which the comment at 611-612 says goes singular.
   (b) `BalanceNet` writes the shared `modes` list in place (2959-2977), moving pumps and pressure valves mid-pass, against A272 and "on these modes".
   PLAUSIBLE; it may explain the worse cold 431 and 475.
   Proposal: a per-solve flag instead of clearing; pass a copy of `modes`.
8. `AssemblyPresentation.open`, `AssemblyNetwork` about :369, `FluidNetwork.java:250`, `ColumnOperation.java:528-531` (d5). A menu open reads a valve view, which materialises an island, books accounts, can run a cycle and can submit a solve. Scenario: open the Operation tab while a port island is certified. CONFIRMED against the engine rule.
   Proposal: build the operation view on the bucket, no materialise.
9. `ColumnOperation.java:373,380,507,555` (d5). Room, transit and an outlet's temperature read live values, not values dated at the fence. Scenario: near a full buffer, a condenser limit or a table application, results depend on job timing; the timing gate covers none of these. CONFIRMED by reading.
   Proposal: dated debits, and the table in force at the fence.
10. `ColumnOperation.java:523-526` (d5). Solids at an inlet void are dropped: `credit` ignores `b.solids()` while plan 5.2 sends them to the bottoms (the energy share is unverified). Not in the README open items. CONFIRMED for mass.
    Proposal: book them, or refuse the port.
11. `PassiveStepSolver.java:1916-1917` (d5). The deadband's +1e-6 keeps a stale target up to about 1e-6 kg/s after the plan says 0. Scenario: feed stopped about 16 min; each outlet gives from an empty account forever, one ERROR line per component per slice, and the island never rests. CONFIRMED by reading.
    Proposal: a zero target always applies.
12. `FluidWorldAuthority.java:408,434-435` (d5). Two valves sharing an inside cell pass the check, then `submit` throws uncaught with the running unit installed. CONFIRMED path, rare.
    Proposal: check duplicates, install after `submit`.
13. `Setpoints.java:26`, `AssemblyService.setpoints` (d5). +Infinity condenser temperature is accepted and saved; `ColumnOperation.java:581` then throws on the server thread, also after reload. The client refuses it, so a modified client is needed. CONFIRMED by reading.
    Proposal: finite check in the ctor.
14. `IslandCoordinator.closeRound`, `ColumnOperation.schedule` (d5). A hold does not notify ports, and overdue cycles have no deadline: a column whose outlets have stopped booking freezes behind a held inlet. PLAUSIBLE.
    Proposal: a retry deadline while a cycle is overdue.
15. `FluidFacts.java:613` (d5). Queued events are dropped on the facts path. A Stop in the queue leaves devices held by a stopped unit: Start refuses and Stop does nothing, for good. PLAUSIBLE.
    Proposal: reconcile at load.
16. Lower. `compat/fluid/schema-6` predates `condenser`, so a nonzero value is not round-tripped (never released). `FluidWorldAuthority.java:426-428`: a redesign with fewer ports refuses Start for good while the comment says keep. `EquipmentStructure.at`: `getBlockEntity` over neighbouring chunks may sync-load (PLAUSIBLE). Cycles keep running during a property suspension.

## Checked and clean (the reviewer's text)

- Saves: schema 5 defaults, enums by name, unit and account validation, a missing unit refuses the world, optional column state, planned generator state carried through `PhysicalRegistry.apply`.
- Lifecycle: Prepared staleness, no ready/through deadlock, every solve path reaches `done`, Start/Stop/Apply on the bucket, `withStations(false)` clears state, valve settings restored after Stop, `removeColumn` books accounts, transit conservative at Stop, save order, slot mapping (`ColumnStarts:62`).
- Solver fix:
  - `line()`: `withTarget`'s revision bump is symmetric; `couple` and `ports` keep the revision.
  - Reverse flow is impossible through a configured valve.
  - The `stated` branch of `balanceStart` is sound.
  - `PhaseLayout`: the closure is frozen per layout, so it is the same in residual, sweep, polish and gate. No consumer reads junction row order and sparsity is unaffected. It can flip between passes and steps; a chord Jacobian across a flip costs a refresh only, because the key omits the basis. Roots are unchanged.

Not checked: `ColumnV3Network` diff, `FluidCheckpointStore` load catches in detail, a boundary valve set against the stream at Start.
