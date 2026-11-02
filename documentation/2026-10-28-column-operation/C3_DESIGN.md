# C3-C6 runtime design (working note, batch 2026-10-28-column-operation)

Decided 2026-10-29 (reversible; recorded in the batch README at the WP commits).

## Ports (plan 4.2): fixed nodes, no vessel change
- Inlet (feed, steam i): a registered VOID at the port pressure. It takes only (boundaryAllowed).
  The island books arrivals as BoundaryTransfer EXCHANGE at the void id (negative moles); the column's
  inlet account takes exactly that, per committed or replayed span (IslandCoordinator.booked hook).
- Outlet (off-gas, sour water, distillate, draw i, bottoms): a registered GENERATOR. It gives only. Its
  state is a slice input planned at dispatch: (T_k, P_port, the account's composition), flashed in the
  worker. The boundary valve's MASS_FLOW target is at most (the account's mass planned through s-L) / slice.
  The booked generator transfer debits the account: the same composition, mass <= content, so the account
  never goes negative (D14). The plan's "constant-pressure buffer, an account of mass in and out, in any
  phase; the wall row unchanged" holds with no solver-core change.
- Port devices stand on PORT_LANE = RACK_PLACES + 1 (15) at the valve's inner-neighbour cell, with one
  explicit port (that cell, the face toward the valve). `across` returns the port device before a lane-0
  device in that cell. `joins` refuses a suspended device.

## Suspension
- Device field `suspendedBy` (the master's origin as a long; 0 = none). Facts schema 6 appends it to the
  registration. Start and Stop are one ledger event each: the inside devices are replaced by their suspended
  (or released) copies, and the port devices are added (or removed). Stock is kept (no respec).
- Inside set: a walk from the column's nozzle cells through joins, stopping at the boundary valves of the
  report. Utility sides (exchanger tubes, kettle tubes) are separate devices and are never reached.

## Per-slice plans (worker side)
- FluidIslandCommand gains `ports`: Map<node id, PortPlan>. PassiveIntervalSolver.solve applies them before
  `couple`: generator state, valve MASS_FLOW target (VALVE_DEADBAND keeps a steady graph).
- The coordinator asks a ColumnPorts provider at dispatch (plans), at commit/replay (booked) and in
  nextReady (the fence: outlet credits through s-L are booked).
- A port node never runs without a plan: no plan -> valve target 0.

## Accounts and facts (schema 6)
- Column unit facts in the world section: master, setpoints, run switch, the port list (port, index,
  device id), the accounts (moles, energy), the totals (taken, produced, returned, duties). Corpus schema-6.
- State: split table, warm seed, cycle clock, booked windows, pending solve.
