# Junction lag: plan

Owner, 2026-11-18: "the pipe does not have holdup, so the heated oil should reach the column instantly".

## 1. Expected, and what the code does

**Expected.** A pipe holds nothing (quasi-steady lines), and a junction owns nothing (owner decision D46). With the radiant coil at its set point, the stream that leaves the coil reaches the column within the same slice.

**Measured** (the fresh column test world, `tmp/column-soak/check-coil`, 20 ticks a second, time factor 4):

- From tick 1000 the coil fires at its set point: firing 1, so its heat is `m_dot (h_set - h_in)` on the donor's heat curve. The trace records the setpoint and endpoint junction state. Delivered enthalpy needs a separate measurement.
- The junction after the coil reads 535 K (tick 1000), 593 K (1200), 611 K (1400), 626 K (1600) and 633 K (1900), at 83 kg/s.
- The gap to 638 K shrinks about 23 % every 100 ticks (20 s of process time): a first-order lag of about 70 s of process time.

**Read in code:**

- A junction mixes by its backward-Euler rows, `(m_J/dt + Q) w = (m_J/dt) w_old + sum q w_e`, and the same for its enthalpy (`StepEquations.junctionInflow`). Its lag is `m_J / Q`.
- `m_J` is sized once, when the junction is minted, by `PassiveNetwork.sizeJunctionHoldups`. The rule is `holdupTau × max over its connections of rho_seed × A_min × velocityLimit(seed)`, with `holdupTau` = 0.05 s × the time factor and the velocity limit 100 m/s. Between mints the mass stays pinned at `m_J` (`ConservativeTransport.pinnedFlows`).
- `PassiveStepSolver.holdupTau` states the intent: "its mixing lag stays 1 % of a slice". That is 0.2 s at factor 4, and it holds only at the cap flow (100 m/s).

**Computed** (the coil outlet sits on the 0.6 m charge line, `CrudeUnitPlant` sizing log `charge bore=0.6`; seed density assumed 870 kg/m³, cold crude):

- `m_J` = 0.2 × 870 × 0.283 × 100 ≈ 4.9 t.
- At 83 kg/s that is a 59 s lag, against the 70 s measured.
- The oil really moves at about 0.3 m/s, so the lag is about 300 times the intended 1 % of a slice.

**Why it was sized at the cap flow, and why that no longer holds** (read in `documentation/2026-09-24-mixed-gas-junction/`):

- D2 (2026-09-25) sized `m_J` at 0.05 s of cap flow to meet a positivity bound, `dt Q/m_J <= 4.8`. That bound comes from the second stage of TR-BDF2 (`HANDOFF_REVIEW.md` 7.9 (b), 7.10).
- D9 (2026-09-26) removed TR-BDF2. The integrator is backward Euler.
- Backward Euler's row is a convex combination of `w_old` and the inflow fractions at any `dt Q/m_J`. So the bound no longer applies.
- What the buffer still does: it keeps the rows regular at `Q = 0` (a mixing ratio is undefined with no through-flow) and sets the scale of the Newton's junction rows.
- D14 (2026-09-26) kept the cap sizing for liquids "unless a benefit is proven". The benefit is now shown: the 70 s lag above.

## 2. Conservative mixing within a step

The owner selected a 0.5 K first-slice tolerance for all thermal fronts, including passive hot and cold inlets. Every physical junction keeps the same positive D46 buffer mass M for its lifetime. The cap, its mint density and the existing 1 kg fallback for no eligible pipe select M at mint. No interval resize is permitted.

**Material.** Use the existing pinned backward-Euler transport and conservative reconstruction for components and solid populations. Their lifetime terms telescope from the original inventory. Test alternating streams, heat and solids.

**Heat.** Let Q be the total delivered inlet mass rate and P the sum of actual incoming enthalpy rates after heat exchange, shaft work, filtering and conversion as the existing energy equation defines them. Let E be buffered enthalpy, including any booked melt offset. A participating thermal mass A in (0, M] gives the flowing enthalpy h = (P + (A/dt)(E/M)) / (Q + A/dt).

Keep the whole canonical stock. The existing projection books actual signed energy transfers at both ends of each edge. For a mass-balanced junction, ΔE = dt (P − Qh) = A (h − E/M). The new E/M is a convex combination of the previous E/M and the flowing h. Its change telescopes for any sequence of A, including flow and composition reversals. There is no resize credit. Rate-only evaluation retains its frozen ledger. A draining real vessel retains its real heat capacity.

**Numerical qualification.** Choose A using actual trial rates, including every inlet at a manifold. The current prototype takes the smaller of M and Q dt / (1 + |E/M − P/Q| / L), subject to a positive numerical floor. L = 100 J/kg is a trial enthalpy allowance per fitting, equivalent to 0.05 K for a liquid at 2 kJ/(kg K). The enthalpy bound applies above the floor. A specific energy does not give a universal temperature tolerance across phase changes or several junctions. Use a downstream-pressure PH flash for the owner's 0.5 K criterion. At zero transport there is no stream to time. Test transitions into and out of rest, reversals, mixing branches and recirculation. A positive thermal term anchors an otherwise undetermined closed loop. The species and thermal rows have separate flux weights. A failure to converge keeps this formulation experimental.

## 3. Checkpoints and certificates

Publish the whole committed inventory and the intensive stream state atomically. Both already have fields in the checkpoint. Exact restores also retain the accepted endpoint flows if present. An active certificate extrapolates its recorded canonical deltas without calling the solver. Invalidate old-law certificates by their declared replay revision. A changing junction buffer or stream must not be extrapolated indefinitely as a constant transfer. Verify fresh solves, replay, wake and save/reopen through the production paths. Keep the physical inventories, topology and player facts on upgrade.

## 4. Acceptance and implementation order

1. Qualify the single-node balance with changing flow, reversed composition, enthalpy and solids. Keep the D46 lifetime gate and verify the inactive and active stocks sum to the pinned mass. Exercise no-flow, abrupt start/stop, several inlet branches and recirculation.
2. Implement the shared mixing law in the solver and conservative projection, with identical stock and conversion conventions. Seed the solver from the same thermal balance and the canonical material balance. Verify old and new exact checkpoints, invalidation and newly issued replay certificates.
3. At factor four, compare a 20 s committed interval at roughly 83 kg/s on the 0.6 m charge line. Measure delivered energy per kilogram and component amounts on the coil and feed station over identical committed boundaries. Compare an equilibrium temperature at the downstream pressure. Require the first slice within 0.5 K under established full heating. Record actual substeps. The displayed cycle temperature and the 638.15 K setpoint alone cannot establish delivery.
4. Run the affected conservation, liquid, phase, stagnant and replay gates. Record moved references and run the repository's watch-seed campaign in its designated lane. Qualify the saved plant through the column-soak harness. A failure of the acceptance case leaves the feature open.
