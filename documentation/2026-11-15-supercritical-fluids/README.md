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

## Documents

- [SUPERCRITICAL_LIMITS_REVIEW.md](SUPERCRITICAL_LIMITS_REVIEW.md): expected behaviour, what the code does, the
  findings A1..D4.
- [SUPERCRITICAL_DATA_REVIEW.md](SUPERCRITICAL_DATA_REVIEW.md): the data per need, what lacks (L1..L8), the crude
  cuts (P1..P6), the rule for a lack.
- [SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md): the idea, owner decisions O1..O10, stages S0..S7, order,
  verification, proposed delegation.
- [SUPERCRITICAL_QUALIFICATION_REVIEW.md](SUPERCRITICAL_QUALIFICATION_REVIEW.md): S0's measurements M1..M7, the
  recommended pressure ranges and the sizes of the open decisions.
- Earlier: [../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md](../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md)
  (architecture assessment), [../2026-11-13-codebase-review/SOLVER_DECOUPLING_PLAN.md](../2026-11-13-codebase-review/SOLVER_DECOUPLING_PLAN.md)
  section 7 (the room made in 0.31.0: `PhaseTraits`).

## Code areas (planned)

`science/thermo/phase/` (band, traits), `science/fluid/thermo/` and `science/fluid/network/` (slot mapping, laws on
traits, "no saturation"), `science/fluid/transport/MixtureViscosity`, `science/machine/`, `science/flowsheet/`,
dataset records under `data/icheme/materials/properties/`, the device screens' phase rows.

## Open items

- Owner decisions O1 to O10 of the plan. S0 (measurement) needs only O1.
- Not checked: compressed liquids above 2 MPa of the datasets O4 would raise; the effect of O9's pairs on mixtures; the
  error of a cut's estimated constants (qualification review, "Not measured"). The campaigns do not cover the dense
  window (limits review, last section).
- O8's options (data review P5) assume zero pairs; the network derives E-PPR78 pairs for gases with Henry data.
- S0's brief is written (`tmp/supercritical-briefs/BRIEFS.md` in the worktree, not tracked; the hook refuses the main
  checkout's `research/`); the sub-thread waits for the owner's approval.
