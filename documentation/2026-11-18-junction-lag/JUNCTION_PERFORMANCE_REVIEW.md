# CPU and RAM before and after the junction implementation

2026-11-19. The baseline is `e9feeffde`, the last plan-only commit before junction implementation. The comparison head is `e9b43f0ef`, including the subsequent furnace, replay, valve, solver and shutdown fixes. This is a branch-level comparison.

The saved plant needed **2.89× the process CPU**, **3.56× the allocation rate** and **242 MiB more mean resident RAM (+9.2%)**. The solve pool accounted for about 68% of the extra process CPU. Both current trials cost more CPU than either baseline trial. Average sampled charge feed fell from 158.8 to 125.4 kg/s, so the plant was doing different physical work. One current run ended upset on condenser duty and the other ended running without a hold warning.

## Saved plant

Four fresh headless JVMs ran the same 6,000-tick plant in before–after–after–before order. Means below cover ticks 1,000–6,000 in each trial, about 250 seconds at the observer’s 20 ticks/s pace. Each peak row gives the highest sampled value across both trials of that version.

| Metric | Before | After | Change |
| --- | ---: | ---: | ---: |
| Whole-server process CPU (s) | 48.6 | 140.7 | +189.4% |
| Average logical cores used | 0.194 | 0.563 | +189.4% |
| CPU per server tick (ms, all threads) | 9.72 | 28.14 | +189.4% |
| IChemE shared solve pool CPU (s, sampled threads) | 17.1 | 79.5 | +364.5% |
| 95th-percentile CPU demand (cores over one second) | 0.59 | 2.31 | +289.5% |
| Mean Windows working set (GiB) | 2.572 | 2.808 | +9.2% |
| Highest sampled Windows working set (GiB) | 2.671 | 2.995 | +12.1% |
| Mean Windows private committed memory (GiB) | 2.609 | 2.849 | +9.2% |
| Mean used Java heap (GiB) | 1.418 | 1.528 | +7.8% |
| Highest sampled used Java heap (GiB) | 1.985 | 2.285 | +15.1% |
| Java allocation rate (MiB/s) | 68.9 | 245.2 | +255.9% |
| Total Java allocation (GiB) | 16.8 | 59.9 | +255.9% |
| Garbage collections per window | 29.5 | 96.5 | +227.1% |
| GC bean collection time per window (ms) | 99 | 286 | +188.9% |

| Trial | Process CPU (s) | Solve CPU (s) | Mean / peak resident (GiB) | Charge ticks advanced in window | Mean sampled feed (kg/s) | Column running / limited / upset samples | Column at end | Hold warnings in window / full observation |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |
| before-1 | 47.0 | 13.4 | 2.598 / 2.671 | 5000 | 158.8 | 251 / 0 / 0 | RUNNING | 0 / 0 |
| after-1 | 147.8 | 80.4 | 2.823 / 2.987 | 4991 | 124.2 | 167 / 65 / 19 | UPSET | 2 / 3 |
| after-2 | 133.6 | 78.6 | 2.792 / 2.995 | 5000 | 126.6 | 171 / 62 / 18 | RUNNING | 0 / 0 |
| before-2 | 50.2 | 20.9 | 2.545 / 2.582 | 5000 | 158.8 | 251 / 0 / 0 | RUNNING | 0 / 0 |

Both builds loaded the same original save at online tick 90,745 with 14 islands, 1,362 devices and 75 forced chunks. The baseline restored five replay certificates. The current version restored zero and requalified on its schedule. The first 1,000 ticks, server startup and shutdown were excluded from the resource window. Feed and column state were sampled every 20 ticks. Hold warnings are from the server log while the observer was active.

## Fixed junction circuits

Both revisions also ran the same four water-mixing circuits and twelve methane/nitrogen circuits in independent JVMs. The water circuits advanced four five-second intervals each. The gas circuits advanced 5, 5, 3 and 3 seconds, including a fixed methane injection. Every interval had to commit its entire requested duration. Each JVM warmed up for six sets and measured twelve more. Results are the mean per complete circuit set across two JVMs per revision. CPU and allocation here belong to the benchmark thread and include the circuit setup.

| Circuit set | CPU before → after (ms) | CPU change | Allocated before → after (MiB) | Allocation change | Accepted / rejected substeps before → after |
| --- | ---: | ---: | ---: | ---: | --- |
| 4 liquid circuits | 15.0 → 21.5 | +43.5% | 14.88 → 16.89 | +13.5% | 34 / 2 → 34 / 2 |
| 12 gas circuits | 150.4 → 201.2 | +33.8% | 131.04 → 172.52 | +31.6% | 194 / 18 → 200 / 24 |

The equal-work circuit measurements show a solver cost increase under identical inputs. The liquid set used 43.5% more thread CPU and 13.5% more allocation with the same substep counts. The mixed-gas set used 33.8% more CPU and 31.6% more allocation, including six additional rejected substeps. The liquid set is short relative to the host CPU timer’s 15.625 ms quantum. The table aggregates 24 measured sets per revision.

## Measurement setup

Windows 11 on a Ryzen 7 9700X with 16 logical processors, JDK 21.0.11, G1, 512 MiB initial heap and 4 GiB maximum heap. Both source snapshots were compiled against the same resolved project classpath. The same pre-task headless observer and resources were used for both. The full source save was verified against its content manifest before and after each trial.

CPU comes from the JVM process CPU timer. One core means one CPU-second per elapsed second. Solve-pool CPU sums the sampled CPU of live `icheme-cpu-solve-*` threads. Java allocation is the total allocated-byte counter, including short-lived objects. Used heap was sampled every 20 ticks. The Windows process working set and private committed bytes were sampled every 0.5 s. All memory figures use binary units.

The plant comparison includes the changed thermal response, control actions and replay behavior. The fixed-circuit comparison holds the input schedule and simulated work constant. Neither experiment measures client rendering or identifies an individual allocation site.

The local evidence is under `tmp/junction-lag/perf-prepost/`. Each trial contains `resources.csv`, `resident.csv`, the soak outputs and the server log. The build, samplers and aggregation scripts are alongside the trial directories.
