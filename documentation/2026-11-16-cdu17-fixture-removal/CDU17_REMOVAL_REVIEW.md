# Removing the cdu17 test package: review

Plan: [CDU17_REMOVAL_PLAN.md](CDU17_REMOVAL_PLAN.md). Base `ee13d1034` (0.34.1).

## Step 0: the medium crude is one liquid when cold

Probe: `tools/supercritical-probes/p3/.../P3MediumCrudeProbe.java`, `bash tools/supercritical-probes/p3/run.sh medium
tmp/<name>`. Bundled catalog, package `icheme:tjl20_methane`, assay `icheme:medium_crude`, dry. Per state: the production
flash (`V3FeedFlash.resolve`), then `TangentPlaneStability` on its liquid (the flash's liquid, or the feed when it is one
liquid) on the column's own kernel, exhaustive, at four times the default trial budget, with every vapour-root trial
excluded. A negative distance there is a second liquid.

Expected: one liquid everywhere (worst cut pair 4 to 6 RT, diluted by the intermediate cuts).

Result: **one liquid at all 24 states**. No liquid trial lowers the Gibbs energy (distance 0 at every state). The two
crudes split off vapour only at 0.1 MPa (the dissolved light ends).

| Mixture | T (K) | P (MPa) | Flash | Vapour fraction | Liquid test | tm, liquid trials |
|---|---|---|---|---|---|---|
| medium crude | 280 | 0.10 / 0.25 / 1.00 | two phase / liquid / liquid | 0.000107 / 0 / 0 | stable | 0 |
| medium crude | 300 | 0.10 / 0.25 / 1.00 | two phase / liquid / liquid | 0.003095 / 0 / 0 | stable | 0 |
| medium crude | 320 | 0.10 / 0.25 / 1.00 | two phase / liquid / liquid | 0.009359 / 0 / 0 | stable | 0 |
| medium crude | 350 | 0.10 / 0.25 / 1.00 | two phase / liquid / liquid | 0.034955 / 0 / 0 | stable | 0 |
| 565 C+ / naphtha 50/50 mass | 280, 300, 320, 350 | 0.10 / 0.25 / 1.00 | liquid | 0 | stable | 0 |

The 565 C+ end is `crude_pc10..12` in the assay's proportions, the naphtha `crude_pc01..03`; the blend holds 11.3 mol %
of the heavy end.

Control (scratch, not committed): the same probe on the cdu17 fixture before its removal (`cdu17_pc` ids, assay
`icheme:tia_juana_light_kl1976`) finds a second liquid at all 24 states: tm -5.7 (280 K) to -0.39 (350 K) on the whole
crude, -3.6 to -0.09 on the blend, the trial liquid 45 to 95 mol % `pc10..12`. The probe detects the defect the plan
names, and the medium crude does not have it.

The "cold Tia Juana second liquid" open item of batch 2026-11-16-supercritical-followups closes with this batch: it was
a property of the fixture, and no data change is needed.
