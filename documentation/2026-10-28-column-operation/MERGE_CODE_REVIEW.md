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

## Dispositions (2026-11-02, the column session)

Each fix has a test that failed first, or a mutation that makes it fail; "mutation" marks the second kind.

| # | Disposition | Commit | Gate |
| --- | --- | --- | --- |
| 1 | Fixed. Owner 2026-11-02: "Lower target wins". See below | 88600d64d | `flowValvesInSeriesCarryTheLowerTarget`, `aPortKeepsItsRowInABranchedGroup`, `aValveBoundingTwoColumnsTakesTheLowerPlan`, `aSecondFlowValveAboveThePlanStandsOpen`, `aSecondFlowValveBelowThePlanRegulatesTheLine`, `aColumnsOutletFeedsAnother` (mutation: without the rule the line holds) |
| 2 | Fixed. Only a pressure valve anchors; a regulating flow valve prescribes a flow. A pump in line goes to its head limit, or to shutoff behind a closed valve; a closed flow valve with a target opens. Above the pump's rate nothing changes. Targets 5, 0, 50 kg/s give HEAD_LIMIT + REGULATING, CLOSED + CLOSED, TARGET + OPEN | 56b083702 | `PumpAheadOfFlowValveTest` (5); `aPumpOnTheFeedLineRidesBehindItsValve` |
| 3 | Fixed. Stop has two phases: the unit stays until its release event commits. A dismantled column carries a `leaving` fact and goes then | 460a98836 | `aDismantledColumnLeavesWithItsPorts` (mutation) |
| 4 | Fixed, same change. The ports stay mapped until the event commits, so what they take and give is booked | 460a98836 | `aStopBooksWhatItsPortsCarryUntilTheyLeave` (mutation) |
| 5 | Moles fixed: columns map first, so removed components reach the ledger and the status line. Energy: owner chose option A, below | 460a98836, 27faf5da9 | `aComponentRemovedFromAColumnAccountIsBooked` |
| 6 | Fixed: `Identity.sameLine` at `IslandCoordinator` replacement, `withJunctionStates`, `replayStart`, and, last, `closeIllegalStarts`. A port's worked valve compares equal to its registry valve. `OUTLET_HOLD_REVIEW` corrected | 56b083702, 88600d64d | `aHandSetTargetKeepsTheCommittedInterval` |
| 7(a) | Fixed. A per-solve flag, `lawFlowsFromBalance`, replaces clearing the carried point | 56b083702 | `RegulatingValveHoldTest` |
| 7(b) | Fixed. The regulated pass hands `balanceStart` a copy of `modes` | 56b083702 | `RegulatingValveHoldTest` |
| 8 | Fixed. A menu open reads stored islands (`FluidWorldAuthority.stored`). The view stays on the bucket path as it was: the read is what changed | 460a98836, 0ada03253 | GameTest `aMenuReadAdvancesNoIsland`, with a live read as its control |
| 9 | Fixed. A cycle's room counts gifts through the fence, its heat owed counts outlet bookings through the fence, an outlet's temperature is the table in force at the fence, and a cycle waits for its outlets through the fence | b22f33a2f | `aFullBufferDoesNotDependOnWhenItsIslandsJobsComplete` |
| 10 | Fixed. See below | 099a4ee23 | `solidsInTheFeedLeaveByTheBottoms` (mutation), `aCrystalMeltsAsTheColumnTakesIt`, corpus |
| 11 | Fixed. A zero target always applies | 56b083702 | `aPlannedZeroAlwaysApplies` |
| 12 | Fixed. Start refuses two ports in one cell, and a failed submit restores the unit | 460a98836 | `twoPortsCannotShareAPlace` |
| 13 | Fixed. `Setpoints` checks finite | 460a98836 | `aCondenserTemperatureMustBeFinite` |
| 14 | Fixed. A cycle overdue looks again a cycle later. It reproduced only when a job ran past the last wake, then held: with every job done in its tick, no overdue cycle arises | b22f33a2f | `heldPortsDoNotFreezeTheColumn` |
| 15 | Fixed. Facts settle a unit's queued events on what stands (`FluidFacts.settled`): orphan ports go, a leaving unit goes, booked | 460a98836 | `aColumnsQueuedEventsSettleOnWhatStands` |
| 16 | Corpus and fewer ports fixed: a nonzero `condenser` in the corpus; a station its design lost keeps its account with device 0 (`ColumnUnit.lost`). `EquipmentStructure.at` sync-load and cycles during a property suspension stay open, lower, not in this round's scope | 460a98836 | `aStationItsDesignLostKeepsItsAccount` |

No finding proved wrong. Finding 14 was narrower than written: see its row.

**Finding 1, the owner's decision: "Lower target wins".** Flow valves in series carry one flow. The lower target regulates, every other valve stands fully open, and a target of 0 closes the line. In a branched group a column port's valve keeps its row. A valve that bounds two columns takes the lower plan.

- `ControlConstraints.standing` first finds the series: rows equal once the junction balances are out. Port rows first over the whole group, the form suggested, would let a port's target beat a player's lower one on a plain series.
- It then inserts each series' regulator, ranked outlet ports, inlet ports, the others, each ascending by target. A row found dependent stands open, and only that one.
- Outlets rank before inlets, a small addition to the decision. Only an outlet's valve bounds what its account gives. Outlet 5 into inlets 3 and 4 would otherwise give 7 from a plan of 5.
- Removed for flow valves: the hold on the last accepted opening, its default of 1.0, and `VALVE_CONFLICT`. A flow valve carrying that mode in old state starts open. Pressure valves on one node keep all three.
- Actual-volume valves stay out: their rows depend on the local density, so they fix no constant mass flow.
- The clamp and its ERROR line stay as a guard. The gates close every component and the energy, so nothing is given that the account did not hold.
- The status line reads `STANDING_OPEN`; the valve screen reads "Standing open; a lower flow target on this line regulates".

**Finding 5, energy: owner chose A (2026-11-02), built in 27faf5da9.** Expected: a removed component leaves with its energy. An account has no temperature, so its share is not forced by the physics. An account of 100 mol that loses 3 mol of one component:

| Option | What it does | Rests on |
| --- | --- | --- |
| A, chosen | Book 3 % of the account's energy as removed; about three lines | Uniform specific energy over components |
| B, as first built | Leave the energy; the next cycle's closure gives the condenser that 3 % once | Nothing, but specific energy shifts for one cycle |
| C | Restate at the table's product temperature, or save a temperature on the account | A table, which the same change may drop, or a new fact |

Conservation holds in all three. The case needs a data change that drops a held component. In the gate 1.5 of 5.25 mol leaves: the account goes from -1.2 MJ to -857 kJ, and the Restated line takes the difference.

**Finding 10, size.** About 100 lines over seven files, so it went in without a stop. A station gained `solids`, schema 6 being unreleased; `PortPlan` gained `solids`. Crystals melt on arrival, each its component, with its stored energy made true by its offset. An inert solid waits at its inlet, a cycle moves what arrived by its tick, and the bottoms' generator gives it at its ratio to the moles.

- Measured: 0.646 g arrived = 0.005 g at the inlet + 0.179 g in the bottoms + 0.463 g given.
- A column whose table has no bottoms keeps its solids at the inlet.
- A bottoms holding solids restates its generator each slice, so that island does not certify while they last.
- A credit's solids are state, not saved: after a reload, those inside the fence read as already there.
- Slow lines plug. At 18 g/s in a 5 cm bore the rig's feed reached a solid closure (`SolidMobility`, deposition velocity). That is the model, not a defect; the gate's lines are 5 mm.

**Found in this round.**

- Any change of the axis refused the world ("Column totals on the accounts' axis"): `withStations` then `withTotals` validated between the two. Fixed in 460a98836; the finding 5 test exposed it.
- Orphan port devices on the facts path: fixed in 460a98836; the finding 15 test exposed it.
- Heat beat, not a defect. Water slices run 4.05 to 4.5 s against 5 s cycles, so one slice in 5 to 10 takes little heat and the outlet swings: four slices 29 K, one about 19 K, mean 27 K. Energy closes. The GameTest reads a time-weighted mean.
- At 30 C supply the 5 K approach leaves 24 K of the 27 K rise, and the column reaches `LIMITED: condenser` after about 5.5 min. The review plant's water is now 25 C. An owner item: approach, water rate, or leave it.

## Checked and clean (the reviewer's text)

- Saves: schema 5 defaults, enums by name, unit and account validation, a missing unit refuses the world, optional column state, planned generator state carried through `PhysicalRegistry.apply`.
- Lifecycle: Prepared staleness, no ready/through deadlock, every solve path reaches `done`, Start/Stop/Apply on the bucket, `withStations(false)` clears state, valve settings restored after Stop, `removeColumn` books accounts, transit conservative at Stop, save order, slot mapping (`ColumnStarts:62`).
- Solver fix:
  - `line()`: `withTarget`'s revision bump is symmetric; `couple` and `ports` keep the revision.
  - Reverse flow is impossible through a configured valve.
  - The `stated` branch of `balanceStart` is sound.
  - `PhaseLayout`: the closure is frozen per layout, so it is the same in residual, sweep, polish and gate. No consumer reads junction row order and sparsity is unaffected. It can flip between passes and steps; a chord Jacobian across a flip costs a refresh only, because the key omits the basis. Roots are unchanged.

Not checked: `ColumnV3Network` diff, `FluidCheckpointStore` load catches in detail, a boundary valve set against the stream at Start.
