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

The owner selected a 0.5 K first-slice tolerance for all thermal fronts and subsequently requested same-slice delivery for every composition front, including an isothermal change. An energy-only adjustment cannot do this. A cold blend change received 83,404.692 J/kg, exactly the enthalpy supplied, but its retained composition gave 462.610 K at feed pressure against the incoming 463.401 K.

Each physical junction retains its lifetime mass M, selected from the existing cap at mint, including the 1 kg fallback. A numerical step mixes a part A of the **canonical** stock with all arrivals. The remaining stock stays in place for that step. Different steps may use different A, yet no step creates or removes stock by resizing. A draining physical vessel retains its own inventory.

For delivered mass D = Q dt, the outgoing composition is (A w_old + D w_in)/(A + D). Enthalpy and solid populations follow the same balance, with the existing filter and conversion terms. The published constituent stock is the sum of the unmixed (M − A) w_old and the moving part A w_out. Its increment equals D (w_in − w_out). The corresponding signed enthalpy transfer updates canonical E. These identities telescope for arbitrary A, including reversals. For a mixture with no conversion, the new canonical fractions and specific enthalpy remain between the old stock and the incoming stream. At the first accepted step that exchanges more fluid than its participating mass, keep the untouched fluid in a reserve. Record the reserve's component amounts and enthalpy. The moving stock includes all owned solids and enough carrier drawn proportionally from the reserve to support the observed slurry. Rate evaluations can use that carrier in their trial state and return the exact original inventory.

For a new junction the transport term for A is 0.00025 Q τ, where τ = 0.05 process seconds times the world's time factor. A stagnant-flow term returns A toward M as delivered mass goes to zero. Once a parcel has been established, subsequent steps circulate that parcel. If a lower throughput leaves too much of a measured thermal or composition front in the fitting, repartition the existing inventory and resolve the same step from its original state. Estimate throughput over the committed window, including the mass already delivered by accepted steps. Give no credit to a rejected trial. The comparison covers each inlet's composition range and the 0.5 K temperature tolerance. Qualification includes short event intervals, a two-inlet/two-outlet manifold, the rate reductions from 83 to 10 and 1 kg/s, and an isothermal blend reversal.

## 3. Checkpoints and certificates

Publish the whole committed inventory and the intensive stream state atomically. Format 56 stores the retained amounts and enthalpy; format 55 introduced a separate representation for flowing solids. Formats 53 and 55 decode with no retained parcel until the first qualifying step. Exact restores also retain the accepted endpoint flows if present. An active certificate extrapolates its recorded canonical deltas without calling the solver. Invalidate old-law certificates by their declared replay revision. A changing junction buffer or stream must not be extrapolated indefinitely as a constant transfer. Bound the entire projected junction heat increment by the existing stationarity tolerance, in addition to the ordinary inventory and horizon limits. Replay revision 4 refuses older certificates. Refuse issuance on any interval that changes the reserve, and use only the circulating amounts and energy when bounding a later certificate. Verify fresh solves, replay, wake and save/reopen through the production paths. Keep the physical inventories, topology and player facts on upgrade.

## 4. Acceptance and implementation order

1. Qualify the single-node balance with changing flow, reversed composition, enthalpy and solids. Keep the D46 lifetime gate and verify the inactive and active stocks sum to the pinned mass. Exercise no-flow, abrupt start/stop, several inlet branches and recirculation.
2. Implement the shared mixing law in the solver and conservative projection, with identical stock and conversion conventions. Seed the solver from the same thermal balance and the canonical material balance. Verify old and new exact checkpoints, invalidation and newly issued replay certificates.
3. At factor four, compare a 20 s committed interval at roughly 83 kg/s on the 0.6 m charge line. Measure delivered energy per kilogram and component amounts on the coil and feed station over identical committed boundaries. Compare an equilibrium temperature at the downstream pressure. Require the first slice within 0.5 K under established full heating. Record actual substeps. The displayed cycle temperature and the 638.15 K setpoint alone cannot establish delivery.
4. Run the affected conservation, liquid, phase, stagnant and replay gates. Record moved references and run the repository's watch-seed campaign in its designated lane. Qualify the saved plant through the column-soak harness. A failure of the acceptance case leaves the feature open.
