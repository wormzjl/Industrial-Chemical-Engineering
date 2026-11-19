# Junction CPU and allocation follow-up

The original charge reopening advanced under budget on the final candidate `282fb135f`. The first full plant trial nevertheless logged a different late deadline at 96,197 and used 93.1 CPU-seconds, compared with 50.3 before the junction task. The candidate was rejected for the branch.

## What the profile showed

The profile of the first optimization, `8b5ab228c`, used the same plant and observer as the earlier investigation. Of the sampled Java stacks on ticks 1,040–3,000, 1,190 were on a fluid-island solve and 678 on a column solve. Of the fluid samples, 386 were establishing starting phases. The sparse linear solve and block Jacobian were also substantial. The column repeatedly evaluated unchanged tray properties in finite-difference probes and scanned growing bands for a pivot-growth diagnostic. Repeated world-axis projections accounted for 1.85 GB of sampled allocation. JFR allocation weights are statistical estimates. Only process counters are used for the benchmark totals.

The column ran eight distinct feed-enthalpy jobs in each original junction run and one in the pre-task run. Most followed the existing minimum solve spacing. During the six-thousand-tick trials the faster junction response produced genuine changes in fuel command and column input. Every benchmark used the existing solver deadlines, certificate rules, and controller thresholds.

## Late charge command

A dedicated copy running the first optimization captured charge island 1403 at committed tick 96,397. Its accepted feed-valve flow was exactly zero, its two heater branches carried opposite flows of about 0.21 kg/s, and the new feed request was 132.1885643 kg/s. The four-second request and two-second retry both missed their three-second worker deadlines. The one-second retry advanced. The four-second request made ten rejections and advanced zero seconds before cancellation. Replaying the recorded four-second request, including six exchanger reads, on the first follow-up candidate still returned a wall deadline. The original four-second job used 2.97 worker CPU seconds and 1,520,157 callbacks before cancellation. Both short retries had the same 132.1885643 kg/s demand. This capture is separate from the timed runs.

The balance used to initialize the reopened line had failed to converge. Its pump supplied zero while its proposed valve flow was 454.24 kg/s. Its largest junction mass-rate mismatch was 395.29 kg/s. At 10% speed the pump could not overcome the discharge head. The corrected candidate retained the accepted pump and circulating branches when the preliminary balance failed, and initialized the newly opened valve with zero throttling head. The identical four-second dispatch completed in one accepted step with zero rejections, 144,676 checkpoints, zero pump flow, negligible feed flow and about 0.054 kg/s of internal branch circulation.

A separate methane-and-water furnace fixture exposed an older accounting defect. A 10.045 MJ fuel booking with 5.659 MJ radiant entitlement allowed four later accepted 3.5 MJ heat transfers. The same 14.0 MJ was transferred on both pre-junction and first-optimized builds. The saved plant also reused one 32-tick fuel cap across 40 charge ticks, which establishes repeated availability but does not quantify the saved plant’s actual delivered heat. Correcting that cross-island booking would change furnace operation and is an open defect. The benchmark preserves the existing furnace behavior.

## Work removed

| Path | Change |
| --- | --- |
| Column Newton solver | Keep thermodynamic workspaces per tray for an attempt, reuse exact unchanged composition logs and avoid rebuilding the base residual during final verification. Reduce the maximum pivot growth across independent accumulators while retaining double multiplication and subtraction in elimination. |
| Fluid Newton solver | Decode only the component that a probe changed, with exact temperature and pressure from the base for unchanged coordinates. Reuse sparse arrays after the last referencing workspace is evicted and sort the permuted row indices before scattering their values. |
| Thermodynamic properties | Select the Gibbs root with the original cubic evaluation and compute composition derivatives at the chosen root. Invalidate a temperature preparation before it can fail partway. Precompute logarithms of the immutable viscosity-table knots. |
| Presentation | Reuse projections of the same graph and interval by identity within a server tick. A same-tick commit or a certified preview can replace either independently. Each read keeps its own clock, status, fences and certificate. |

A separate 64-entry interval-job cache reuses exact heat-curve samples for the same model, composition, pressure and temperature span, then applies the requested films. The diagnostic capture repeated 353 of 395 samples and those repeats consumed 559,909 checkpoint callbacks. A successful sample is the only path that inserts a curve.

The intermediate plant build exposed a sparse factorization defect: the DFS visited flags and the search cursors share one array, and their boundary depends on matrix dimension. A larger factorization reusing a smaller matrix’s storage could read a cursor as a visited flag and skip a pivot. That build produced 40 warning events and used 167.5 CPU seconds on the saved plant. The corrected candidate clears the visited flags on a change of dimension. A 3×3 → 2×2 → 3×3 regression matches fresh EJML factorizations and solutions bit for bit.

## Fixed-input measurements

Each circuit result is an average over two JVMs with six warm-ups and twelve measured sets per JVM. The column benchmark holds feed, hardware, pressure and steam fixed and raises the inlet temperature by 2 K from the converged seed. It uses two JVMs per build, two warm-ups, then four measured solves per JVM. The first three columns use the earlier control measurements and the last uses fresh runs.

| Per full circuit set or warm column solve | Pre-task | Unoptimized junction | First optimization | Follow-up |
| --- | ---: | ---: | ---: | ---: |
| Four liquid circuits, thread CPU (ms) | 19.53 | 24.74 | 26.04 | 21.48 |
| Four liquid circuits, allocated (MiB) | 14.87 | 16.88 | 14.53 | 14.17 |
| Twelve gas circuits, thread CPU (ms) | 196.61 | 268.23 | 264.97 | 193.36 |
| Twelve gas circuits, allocated (MiB) | 130.96 | 172.45 | 142.84 | 133.86 |
| Warm column, thread CPU (s) | — | 3.551 | 2.863 | 2.150 |
| Warm column, allocated (MiB) | — | 2,579.4 | 1,261.5 | 1,265.0 |

The liquid set is short against the 15.625 ms Windows thread-CPU clock quantum. All 64 serialized fluid checkpoint packs and the accepted and rejected substep counts matched the unoptimized junction build. Each of the eight follow-up warm column solves matched the earlier digest over liquid and vapor amounts, temperatures, free water and wet-tray flags.

Raw results are under `tmp/junction-lag/perf-prepost/` in the named worktree.
