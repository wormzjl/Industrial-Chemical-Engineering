# Heat exchanger plan, revision 3: review

Date 2026-10-27. Reviewer: a Fable subagent, read-only, given the plan, the studies, the harness, AGENTS.md, the code guide and the source, but not the author's view. The author checked the code claims of findings 1 to 8 and 16 against the source (✓). All findings are taken into plan revision 4 (same day).

**Blocker.**

1. ✓ **When a slice is planned.**
   - Expected: each side sees the other as committed; a lagging partner is extrapolated for at most one of its slices.
   - The plan (and harness part 7) plan a slice at its start tick and read a partner's open slice at a rate fixed at its dispatch, with a dispatch wait.
   - The game offers a slice `[committed, due]` only once online time reaches `due` (IslandClock.java:53-60). It solves the slice on a worker that never calls back (`FluidIslandCommand`) and commits it up to the 2 s hard budget later. A long-cadence partner has no open slice for up to 400 ticks, and a wait for it would be process work on the server thread.
   - Proposal: before W3, a timing study (W3a, harness part 8: dispatch at the slice end, job latency 0 to 40 ticks) compares three forms; the owner chooses:
     - A: the partner as of the slice start, extrapolated at its last committed rate;
     - B: the partner's records committed within the slice (they exist, since the slice is dispatched after its end), A for the rest;
     - C: a coupling fence (a slice waits until its partner has committed through its start; deterministic, the fast side lags).

**Major.**

2. "Stopped" read from lag fires at every commit of a long-cadence partner (job latency). Fix: take it from explicit states (no island, held, phase-parked, suspended).
3. ✓ The kernel throws outside its curve (`prototype.patch`: "Enthalpy outside the curve"): a hold in the Newton, against "Thermo validity". Fix (W1): a stated span; C1 extrapolation with a notice; a gate.
4. ✓ Knots are placed in T, and a phase change is bisected to 1e-5 K (`PipeThermalStudy.refine`), so a pure plateau has no inner knot. Its film runs from 2000 to 100 across the latent heat (mean about 1050, not 5000). Fix: knots at a vapour fraction of 0.05 and 0.95; gate: U 1430 within 1 %.
5. The give-back's form in the Newton is unspecified: a fixed rate is not 0 at no flow, and a clamp in a residual is a switch. Fix: an enthalpy shift Δh fixed and clamped at dispatch (heat = ṁ Δh); what the flow did not carry stays in transit; gate: zero flow books 0 J.
6. ✓ A device has one owner island; only reservoirs and movers carry ports (PhysicalFluidTopology.java:104-119); a structure binds one identity; the transit belongs to neither island's facts. Fix (W2): a side device per side, and a pair record in the topology unit's facts.
7. ✓ `inputsChanged` does not end a certificate (IslandCoordinator.java:611). Fix (W3): a new discard entry plus `reconsider`, tested against the record the certificate holds.
8. ✓ The heat goes into `PassiveIntervalSolver.Result` beside `pumpWorkJoule`. It then reaches the certificate summary and replay (IslandCertificate.java:91, 476) and the checkpoint state (FluidCheckpointCodec.java:246, `VERSION` 22 → 23). Fix: list them in W1.
9. The quasi-steady wall on curves needs a root per cell in each sweep (the harness sweeps to 1e-12). Fix (W3): tolerance 0.01 K, a sweep cap, a cost gate.

**Minor.** (10) The determinism wording of 5.2 and 13 disagrees. (11) "1.5 m/s, 35 kPa" is 163 kg/s; the example (50 kg/s) runs at 0.46 m/s, about 4 kPa (recalculated). (12) The condenser and kettle duty ratio needs its NTU. (13) The 77.5 K give-back after a hold is missing from the risks. (14) W1 ports and extends the kernel (renamed package, curve builder in the study, partial-fraction integral). (15) A reversed side runs parallel to the dispatch wall. (16 ✓) A valve target in the command must stay out of the certificate digest (IslandCertificate.java:362). (17) A set temperature on a plateau needs a named point. (18) The time of the schema-5 corpus image. (19) Fan power has no sink. (20) The kettle pool is a vessel partner. (21) Removing one side books the transit. (22) Section 2 lacks the dispatch-after-end fact.

**Checked correct** (reviewer): U per pair, the UA ranges, ε (counterflow and 1-2 shell), the example, the tube count and shell passage, the air cooler numbers, the fired heater law, the shaft-work hooks, `BalanceNet`, `arrivingPhases`, `materialise`, FluidFacts schema 4, `PortMarkings`, and the part 7 numbers in the plan.

**Disposition.** Findings 2 to 22 are written into revision 4. Finding 1 makes W3 wait for W3a and an owner choice. W1 (heat law) and W2 (device) do not depend on it, and neither do the one-sided air cooler and fired heater (owner question Q14 on the order).

**Code guide:** no change (no code changed). **Tooling:** none new.
