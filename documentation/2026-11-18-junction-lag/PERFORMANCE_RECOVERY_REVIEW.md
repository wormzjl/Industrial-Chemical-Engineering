# Junction performance recovery

On the saved plant the optimization changed whole-process CPU by -24.1% and the allocation rate by -42.3% against the junction implementation. This recovered 36.5% of its excess CPU and 60.2% of its excess allocation in the matched repeat.

The original comparison measured 2.89× pre-task CPU and 3.56× allocation. The fresh controls measured 2.94× and 3.36×. After optimization the plant used 2.23× pre-task CPU and 1.94× allocation. Mean resident RAM increased 3.6% against the junction build. The two optimized trials reduced CPU per committed charge tick by 20.3% on average.

## Attribution

The saved plant has two distinct workloads. The quicker thermal response varied the fired-coil outlet over 482–643 K in the original junction runs, compared with 636–639 K before the task. The two fuel circuits showed steady status on only 1–4 of 51 samples, down from 41–43. On loading the save, the pre-task build restored five island certificates and the junction implementation requalified all 14 islands. The column registered eight distinct feed-enthalpy re-solve episodes per measurement window after the task and one before it. The sampled episodes follow the existing 1 K-equivalent drift check.

Flight Recorder sampled ticks 1,040–3,000 from one matched trial per revision. In the junction build the column worker appeared in 1,463 sampled Java stacks and accounted for an estimated 12.2 GB of the 24.3 GB of sampled allocation. Fluid-worker stacks accounted for 6.14 GB and view construction for 3.26 GB. In the pre-task profile the fluid worker accounted for 2.13 GB and view construction for 3.49 GB. That shorter baseline window contained no column-worker sample. These are weighted allocation estimates, and the saved-plant observer also requests views.

## Changes

- **Column calculations.** Probe a coordinate on the exact decoded state, recalculate the affected liquid or vapor property only, retain unchanged tray properties, and refill a pair of residual vectors for the Jacobian. Steam and free-water balances continue to use the full trial. Restrict band elimination to the pivot row’s occupied extent, including through row swaps. Keep the signed-zero fallback and numerical acceptance checks.
- **Thermodynamics.** Retain exact-temperature pure-component enthalpy and viscosity work, reuse component-support storage and avoid copying fugacity coefficients twice. The bounded fdlibm cosine for cubic-root angles avoided 32 allocated bytes per call and matched the reference bits in 3,020,282 test inputs.
- **Fluid solves and presentation.** Revisit initial mixtures when their own seed or an upstream donor changes. Each node accumulates the balances it owns. The junction and presentation paths rebuild fewer inventories and transfer arrays, sharing immutable data where the committed result and the snapshot already share it.

## Saved plant

Two fresh headless server JVMs per version, in mirrored pre-task / junction / optimized / optimized / junction / pre-task order. All six used the same 6,000-tick observer and a verified copy of the same save with 14 islands, 1,362 devices and 75 forced chunks. CPU and allocation were measured between ticks 1,000 and 6,000, about 250 seconds per trial. Run conditions match the [original performance comparison](JUNCTION_PERFORMANCE_REVIEW.md), including the JDK, G1 collector, 512 MiB initial heap and 4 GiB heap cap. All timed workloads ran serially.

| Metric | Pre-task `e9feeffde` | Junction `e9b43f0ef` | Optimized `8b5ab228c` | Change vs junction | Excess recovered |
| --- | ---: | ---: | ---: | ---: | ---: |
| Whole-process CPU (seconds) | 50.3 | 147.8 | 112.2 | -24.1% | 36.5% |
| Average logical cores | 0.201 | 0.591 | 0.449 | -24.1% | 36.5% |
| Process CPU per committed charge tick (ms) | 10.05 | 29.50 | 23.50 | -20.3% | 30.9% |
| Solve-worker CPU (seconds) | 20.0 | 79.6 | 57.7 | -27.5% | 36.7% |
| Allocation (MiB/s) | 69.1 | 232.4 | 134.1 | -42.3% | 60.2% |
| Allocated over window (GiB) | 16.9 | 56.7 | 32.7 | -42.3% | 60.2% |
| Mean resident RAM (GiB) | 2.685 | 2.790 | 2.892 | +3.6% | -97.1% |
| Largest observed working set (GiB) | 2.745 | 3.143 | 3.509 | +11.6% | -91.8% |
| Mean used Java heap (GiB) | 1.439 | 1.500 | 1.575 | +5.0% | -120.9% |
| Mean committed Java heap (GiB) | 2.180 | 2.279 | 2.366 | +3.8% | -86.8% |
| Collections per window | 26.5 | 97.0 | 51.0 | -47.4% | 65.2% |

Recovery is (junction − optimized) / (junction − pre-task). Means use the two repeat runs. Peak RAM is the largest sampled working set across both. The original pre-task and junction means were 48.6 and 140.7 CPU-seconds, 68.9 and 245.2 MiB/s allocation. CPU time includes all process threads. The solve-pool breakdown uses the sampled CPU of the shared workers.

| Trial | CPU (s) | Worker CPU (s) | Allocation (MiB/s) | Mean / peak resident (GiB) | Charge ticks advanced | Mean sampled feed (kg/s) | Column running / limited / upset samples | Final column | Charge or other hold warnings, window / observation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |
| before-r1 | 52.9 | 23.7 | 69.4 | 2.713 / 2.745 | 5000 | 158.8 | 251 / 0 / 0 | RUNNING | 0 / 0 |
| after-r1 | 165.3 | 87.7 | 251.3 | 2.649 / 2.793 | 5010 | 124.5 | 172 / 60 / 19 | RUNNING | 1 / 1 |
| optimized-1 | 125.9 | 65.5 | 150.1 | 2.555 / 2.864 | 4611 | 125.9 | 171 / 69 / 11 | RUNNING | 5 / 5 |
| optimized-2 | 98.5 | 49.9 | 118.1 | 3.229 / 3.509 | 5000 | 125.6 | 171 / 70 / 10 | RUNNING | 0 / 0 |
| after-r2 | 130.3 | 71.5 | 213.5 | 2.931 / 3.143 | 5010 | 125.7 | 159 / 92 / 0 | LIMITED | 1 / 1 |
| before-r2 | 47.6 | 16.4 | 68.7 | 2.658 / 2.716 | 5000 | 158.8 | 251 / 0 / 0 | RUNNING | 0 / 0 |

Both optimized trials averaged about 126 kg/s of sampled column feed, comparable with the two junction controls. One optimized trial completed 5,000 charge ticks without a hold. The other advanced 4,611 ticks with five deadline warnings, including four at the same committed tick. A similar late charge deadline occurred in the original junction comparison. These six free-running trials establish resource use on this plant. They do not establish a change in the probability of a hold.

## Fixed-input column

The literature Tía Juana column received a 2 K increase in inlet temperature from its converged base. The feed composition, flows, pressure, steam and specifications stayed fixed. Each of four fresh JVMs ran two warm-ups and four measured solves, in junction / optimized / optimized / junction order. All 16 measured solves converged and gave the same SHA-256 digest over the raw bits of the returned liquid, vapor, tray temperature, free-water and wet-tray seed data.

| Per warm solve, calling thread | Junction | Optimized | Reduction |
| --- | ---: | ---: | ---: |
| CPU (s) | 3.551 | 2.863 | 19.4% |
| Allocated (MiB) | 2,579.4 | 1,261.5 | 51.1% |

## Fixed circuits

The unchanged circuit harness runs four liquid and twelve mixed-gas junctions. Each process warmed up for six sets and measured twelve. Each reported value is per full set, averaged across two JVMs. CPU on the short liquid set is quantized by the Windows thread CPU clock.

| Circuit set | Metric | Pre-task | Junction | Optimized | Change vs junction | Excess recovered |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Liquid | Thread CPU (ms) | 19.53 | 24.74 | 26.04 | +5.3% | -25.0% |
| Liquid | Allocated (MiB) | 14.87 | 16.88 | 14.53 | -13.9% | 116.5% |
| Gas | Thread CPU (ms) | 196.61 | 268.23 | 264.97 | -1.2% | 4.5% |
| Gas | Allocated (MiB) | 130.96 | 172.45 | 142.84 | -17.2% | 71.4% |

All 64 committed fixed-circuit intervals had byte-for-byte identical saved checkpoint packs and identical accepted and rejected substep counts between the junction build and optimized code. The reference plant physics, conservation tolerances, certification gates and solve budgets remain in force. The plant trials are free-running and can follow different physical trajectories.

## Correctness checks

All 274 focused tests passed on the final production sources. The final affected lane passed 4,034 of 4,036 tests in parallel. The 100-node exact regression had zero deviation in state, moles, temperature, phase fraction and flow. All 140 required GameTests passed. The six junction tests exercised first-slice coil delivery, passive hot and cold fronts, an isothermal composition switch, splits and joins, and the D46 lifetime ledger. The 24 island certificate tests covered replay of committed junction inventory, rejection while buffered heat is still changing, and certification horizons.

The two parallel failures, `BlanketDrainWorldTest` and `LevelLoopBlanketStartTest`, both reported wall deadlines. They were also the two failures in the previous affected lane. Both passed in a single-worker repeat with their original deadlines.

The 65-world watch campaign recorded 33 first holds, compared with 34 in the most recent campaign at `6d000cdde`, which predates subsequent junction fixes. Cold random repeated all seven prior holds among 27 worlds. Bundled random went from 24 held to 23 among 35, with 645 and 855 clearing and 803 newly reaching its deadline. Bundled defaults again held all three worlds. The one new deadline case, 803, ran alone at the same time factor through all 120 slices with zero holds or refusals. Existing numerical and budget holds remain open.

Against that older campaign lane all 33 junction solver-counter lines matched, including 395 mixed-gas Newton solves. With floating-point output included, 20 of the 33 lines matched. Across its 24 mixed-gas rows the maximum relative mole discrepancy changed from 1.31e-15 to 1.45e-15, and the maximum relative energy discrepancy from 2.97e-15 to 2.67e-15. The direct regression-build comparison above covers all 64 serialized fixed-circuit checkpoints.

Raw plant, circuit, and flight-recorder evidence is under `tmp/junction-lag/perf-prepost/`. The deterministic checkpoint comparisons are in `tmp/junction-lag/perf-bookkeeping/`.
