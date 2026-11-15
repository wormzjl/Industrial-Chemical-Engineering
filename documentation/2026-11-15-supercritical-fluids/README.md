# Supercritical fluids

Status: **Planned** (2026-11-15). Branch `claude/supercritical-fluid-support-d0ffa4`, base `e9edc974b` (main 0.31.0).

Purpose: dense and supercritical fluids as ordinary states of the fluid network, the machines, the heat units and the
simulator: continuous properties, no label-driven jumps, no critical-band hold.

## History

- 2026-11-15: limits read from the code and from the 2026-11-13 review's readiness lists
  ([SUPERCRITICAL_LIMITS_REVIEW.md](SUPERCRITICAL_LIMITS_REVIEW.md)); plan proposed
  ([SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md)). Documents only: no build, no run.
- 2026-11-15: the owner asked for data availability and the crude cuts: [SUPERCRITICAL_DATA_REVIEW.md](SUPERCRITICAL_DATA_REVIEW.md);
  the plan gains O8 to O10 and S0 and S3 follow it. Documents only.
- 2026-11-15: S0 measured (sub-thread): `G3F11SupercriticalRegionTest` (8 tests) and `HelmholtzReferenceParityTest`
  (26 tests) green; seven CoolProp fluid files, the Aly-Lee ideal term of the oracle, the supercritical parity rows, the
  viscosity and mixture tables (`tools/coolprop-parity/generate_supercritical.py`). Sizes of O3, O4, O5, O6, O8, O9 in
  [SUPERCRITICAL_QUALIFICATION_REVIEW.md](SUPERCRITICAL_QUALIFICATION_REVIEW.md); found: the network's gas-cut pairs are
  derived E-PPR78 today, not zero (O8's premise). Test and fixture code only.
- 2026-11-15: the main thread checked S0 (stored results: `G3F11SupercriticalRegionTest` 8 of 8; the parity test's 26 passed
  in the run before, after its last change; no file under `src/main` changed; the parity tolerances of four earlier fluids were
  widened by the test's own 1-2-5 rule for the new supercritical rows, all at or below 5e-8). The data review's claim that
  cut-gas pairs are zero was wrong and is corrected; the plan's section 2a holds the recommendations after S0.
- 2026-11-15: M8 measured (sub-thread, commits `ce02cba4e` and the documents after it): compressed liquids at 2-30 MPa,
  `G3F11SupercriticalRegionTest.m8CompressedLiquids` (9 of 9 green) with `compressed_liquid` parity rows (26 of 26, no
  tolerance widened). Liquids hold G3F1's bounds to 30 MPa for CO2, N2, C3H8 and toluene, to 20 MPa for n-hexane;
  ethylene misses cp at 141 K at every pressure. With the supercritical evidence: 30 MPa for CO2, N2, CH4, C2H6, C3H8;
  C2H4 2 (or 20 with two cp declarations); n-hexane 4.57, toluene 6.19 MPa. Test and fixture code only.
- 2026-11-15: the main thread checked M8 (stored results: `G3F11SupercriticalRegionTest` 9 of 9; the parity test's 26 passed in
  the run after the new `compressed_liquid` rows, its tolerances unchanged; no file under `src/main` changed). The plan's
  section 2a states O4's ranges with the liquid evidence.
- 2026-11-15: the owner: "Accept all" (the recommendations of the plan's section 2a). Recorded in the plan's section 2b, with
  two findings of the main thread: boxes 3 and 4 are where the model is least accurate above `Tc` (density to 10.9 %), and a
  "dense" node includes every ordinary gas above its critical temperature, so S2's laws must equal today's below a reduced
  density. Briefs S1 and S3 written (`tmp/supercritical-briefs/BRIEFS.md`).
- 2026-11-15: S1 done (sub-thread, owner decision O3 B): band boxes 3 and 4 removed from `pureBandBox`; `PhaseTraits`
  gains `attraction`, `supercriticalIsotherm()`, `reducedDensity()` (ratio `PhaseIdentification.SUPERCRITICAL_ATTRACTION_RATIO`,
  the kernel's 0.45724/0.07780). Digest re-recorded: 2 of 429 lines moved (pure N2 in old box 4: band 1 to 0, one grade
  `RESEARCH_ONLY` to `ESTIMATED_DECLARED_ERROR`). [S1_ENGINE_REVIEW.md](S1_ENGINE_REVIEW.md).
- 2026-11-15: S3 done (sub-thread, owner decisions O4 A, O10 A, O5 A): nine datasets widen `pressure_max_pascal` (CO2, N2,
  methane, ethane, propane, hydrogen to 30 MPa; ethylene 20 MPa; n-hexane 4.57 MPa; toluene 6.19 MPa) with their evidence
  and revision bumps; a `critical_volume` sub-record in all 47 datasets (`tools/compound-import/critical-volume.mjs`);
  `MixtureViscosity.residual`/`dense` (Jossi-Stiel-Thodos, zero at zero density), not yet called. Moved: the nine revisions,
  the scientific revision of the three packages, every `fluidRevision`, the viscosity revision. Targeted tests green.
  [S3_DATA_REVIEW.md](S3_DATA_REVIEW.md).

## Documents

- [SUPERCRITICAL_LIMITS_REVIEW.md](SUPERCRITICAL_LIMITS_REVIEW.md): expected behaviour, what the code does, the
  findings A1..D4.
- [SUPERCRITICAL_DATA_REVIEW.md](SUPERCRITICAL_DATA_REVIEW.md): the data per need, what lacks (L1..L8), the crude
  cuts (P1..P6), the rule for a lack.
- [SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md): the idea, owner decisions O1..O10, stages S0..S7, order,
  verification, proposed delegation.
- [S3_DATA_REVIEW.md](S3_DATA_REVIEW.md): S3's ranges with their evidence, the critical volumes, the residual viscosity,
  the identities that moved.
- [SUPERCRITICAL_QUALIFICATION_REVIEW.md](SUPERCRITICAL_QUALIFICATION_REVIEW.md): S0's measurements M1..M7 and the follow-up M8, the
  recommended pressure ranges and the sizes of the open decisions.
- Earlier: [../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md](../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md)
  (architecture assessment), [../2026-11-13-codebase-review/SOLVER_DECOUPLING_PLAN.md](../2026-11-13-codebase-review/SOLVER_DECOUPLING_PLAN.md)
  section 7 (the room made in 0.31.0: `PhaseTraits`).

## Code areas (planned)

`science/thermo/phase/` (band, traits), `science/fluid/thermo/` and `science/fluid/network/` (slot mapping, laws on
traits, "no saturation"), `science/fluid/transport/MixtureViscosity`, `science/machine/`, `science/flowsheet/`,
dataset records under `data/icheme/materials/properties/`, the device screens' phase rows.

## Open items

- Decided (plan 2b). Open for the owner: the two findings there; S2's density threshold (an option S2's review will give);
  hydrogen's zero pair with a cut (only if hydrotreating at pressure matters).
- Not checked: liquids M8 cannot reach (ethane at Tr 0.96-1 inside its domain, any liquid between Tr 0.95 and Tc); the
  effect of O9's pairs on mixtures; the error of a cut's estimated constants (qualification review, "Not measured").
  The campaigns do not cover the dense window (limits review, last section).
- The briefs are in `tmp/supercritical-briefs/BRIEFS.md` in the worktree, not tracked (the hook refuses the main
  checkout's `research/`): to be moved there. Its M4 line states the viscosity coefficients ten times too large; the
  test and the qualification review use the published ones.
