# Junction heat and composition qualification

## Final saved-plant result, 2026-11-19

At `e4321d66b` the charge circuit advanced past the stalls at 93318 and 94004. It reached committed tick 96957 at online tick 96977. All 279 distinct feed commits observed in the 6,000-tick run were positive. The last delivered 21.8289 kg/s. The column finished RUNNING at full throughput, both burners had relit after each hot-inlet closure and no island was held at the endpoint.

Across 296 matched windows the maximum absolute coil-to-feed temperature difference was 0.012320 K, against the 0.5 K requirement. Among 174 full-firing endpoints the largest difference was 0.007339 K. Four charge-island round deadlines occurred during the observation, at ticks 91697, 91987, 96297 and 96327. It advanced after each one. A later deadline at 97037 was outside the observation window. The shutdown checkpoint still had that committed tick at online tick 97703, with the next retry due at 97723. The headless server passed all 30 required GameTests. The final solver passed all 196 targeted JUnit methods. The affected lane and watch campaign below were run before the last drain and workspace changes.

A disposable copy resumed that shutdown checkpoint at its saved retry tick. It advanced first to 97057 at a commanded zero feed, then delivered a fresh 19.7877 kg/s at 97437 and passed the saved retry tick in committed time at 97737. The first positive booking preceded relight. Both burners subsequently relit, and all 35 traced worker attempts during this recovery committed. The tested source save remained unchanged.

A bounded worker-timing capture reproduced an early charge deadline at 91997. The worker exhausted its three-second computation budget after 1,695,219 checkpoints. Its result was delivered after the round deadline and a ten-tick retry advanced to 92007. This establishes the cause for that captured request. The four warnings in the uninstrumented run lack the worker timestamps required for the same attribution. The diagnostic capture did not retain the sampled heat curves for three pumparound streams. It cannot establish which nonlinear equation consumed the work in an exact offline replay.

## Qualification follow-up, 2026-11-19

A flowing fitting now keeps a backed moving parcel between substeps. A drop in flow can reduce that parcel against the measured inlet composition range and the available delivery window. The D46 ledger retains the whole stock. A frozen rate probe keeps the committed reserve bit for bit, including after a canceled attempt. As before, a topology replacement that mints a new fitting starts a new D46 buffer lifetime. Exact checkpoint restoration keeps the existing lifetime. Replay revision 4 also compares constituent mass in each flow direction before certifying an interval.

| Check | Result or correction |
| --- | --- |
| First hot feed and cold blend | At 83 kg/s the single fitting delivered 636.89531 K against 636.89572 K, with three accepted steps and zero rejections. An isothermal switch from 83 to 1 kg/s missed 0.0213% of the imposed largest-cut change against a 0.1% allowance. |
| Water coil and mixed gas | The water outlet reached 448.15 K at the original 1,500 s check. Across twelve gas networks the five-second test used 395 Newton solves, with 38 in the case bounded at 40. |
| Cryogenic and slurry paths | Scale the fluid flash before attaching its solids, and release proportional retained fluid when the owned solids require carrier. Trace liquid-junction components retain a resolvable Jacobian perturbation. |
| Pressure-valve discharge | Both outlet modes are checked against their head and capacity. The transient inlet–outlet difference must match measured drum inventory change within 10⁻⁷ kg/s. The same inlet–outlet bound applies to two consecutive settled intervals. On the original pressure-drop fixture, a saturated valve can remain fully open after the drum settles. Its pressure must exceed the setpoint, its added loss must be zero and its flow must match the computed velocity cap. A regulating valve must satisfy its pressure setpoint and remain below capacity. |
| Blanket, pump and finite outlet | A newly primed pump starts from the reopened hydraulic balance. A failed trial can identify a vanishing phase, while the next pass retains the preceding seed. When the leading phase runs out at a vessel outlet, its receiving junction can retry with the finite mixed draw. |
| Saved stopped charge, tick 93318 | The feed commanded zero but its accepted wide-open mode overrode that command. Three consecutive four-second requests returned FULL in the production worker using the reconstructed saved relay inputs. Feed remained exactly zero. The first used three accepted substeps and two rejections. |
| Column and runtime | Lagging inlet islands keep their causal control history. The condenser comparison measures ten equal per-shell obligations at matched water-side commit fences. Cross-chunk recovery, lost-drum cleanup and terminal-budget fixtures observe their own islands and the available completion slots. |
| Earlier-save diagnostic soak | An intermediate candidate resumed the saved running column for 6,000 ticks. All 300 samples had positive feed. Across 297 matched windows the largest temperature gap was 0.498096 K. The first fully firing window booked 637.690217 kg and differed by 0.000346 K. Six deadline warnings recovered. The column ended running at full throughput, with 182.5578 kg/s feed and 15 ticks of charge-island lag. |
| Restored plant at `16f572dce` | The charge island advanced past tick 93318 and then stopped at tick 94004 with positive feed demand of 136.11292 kg/s. It was held in 28 of 60 samples. The largest temperature difference across 150 matched windows was 0.058393 K. At the final online tick 97645 the column reported a feed shortage and zero throughput. |

### Holds in the operating charge train

With both burners lit, the radiant duty must stay between zero and the fuel-limited available power. The local convection coil receives the flue gas left by that same radiant duty. Previously the solver kept the starting firing fraction and flue capacity as the oil redistributed between parallel branches. One Newton trial asked one radiant coil for 110.05 MW from a 46.84 MW allowance. This was a rejected trial. At the saved tick 94004 the uncorrected request exhausted 600,001 diagnostic checkpoints without an accepted substep.

The corrected residual, sparse Jacobian and conservative reconstruction use the trial flow and inlet enthalpy on each coil. A convection coil in a different island reads committed radiant heat as of the slice start. Five consecutive four-second jobs with the saved operating inputs then advanced in the normal worker budget, with one accepted substep and zero rejections each. The first delivered 129.1550 kg/s, split 72.6900 and 56.4650 kg/s. The radiant duties were 29.4286 and 28.7081 MW against respective caps of 29.4286 and 46.8399 MW.

A separate startup path shut both fuel valves on hot crude. The oil subsequently cooled below the setpoint with the fan stores full. A pending closed-valve solve could certify the stale zero-flow response after demand had returned. A dependency change now preserves the valid admitted interval and clears its eligibility as evidence for future replay. A deterministic regression covers cooling during an outstanding shut-valve interval and requires the new demand to produce actual fuel flow.

A cross-island convection coil can commit a slice that read the preceding radiant rate after its producer has already committed a different rate. The accepted slice remains causal. Qualification checks the producer records against the admitted read, limits replay to any known change tick and checks settled exchanger debt separately from heat booked ahead of the reader. A checkpoint taken before that tick keeps the materialized inventory. On reopen the worker recreates replay evidence for the new inputs.

State format 57 saves the uncapped endpoint duty needed at each radiant coil. The next fuel plan and certified replays use that reading even when the last slice delivered no heat. Format 56 remains readable. Replay revision 5 checks changes in this reading before issuing a certificate.

Before the final exchanger-wake and drain changes, the paired-furnace candidate ran for 6,000 ticks at the configured production worker budget with no logged holds and no held samples in any of its 14 islands. The charge island advanced to 96957 at online tick 96975. All 298 observed new feed commits were positive, including the final 72.2878 kg/s. Both fuel valves reopened twice following hot-inlet shutdowns. The column finished RUNNING at full throughput while it refreshed its enthalpy table. Across 294 matched windows the maximum absolute coil-to-feed gap was 0.008021 K. The first reported full-firing endpoint, ticks 91097–91117, booked 637.690217 kg at both ends and differed by 0.000551 K. The headless run passed all 30 required GameTests. The untouched source world was format 53 and the shutdown copy was format 57. Both decoded through the exact checkpoint route.

All 16 JUnit failures and all five GameTest failures from the original full lane passed targeted verification. The six focused Gradle classes for the furnace and acoustic changes passed all 69 tests. They cover furnace coupling, fuel control, checkpoint state, certificates and acoustic bounds. A two-phase nitrogen and water regression holds the hydraulic velocity bound at 61.0388 m/s when its inventory is scaled by either 10⁻¹⁷⁰ or 10¹⁷⁰. Seven original cold-campaign holds shared the acoustic-bound error message. The synthetic regression does not resolve their individual campaign status.

On the later blanket-drain request, the adaptive controller repeatedly doubled back to the size it had just rejected. Keeping the successful size for another step cut the isolated tick-454 request from 321 to 218 rejections. The candidate also changed the pressure-valve transient and introduced holds in two previously passing cold networks. This controller change was discarded.

The final drain change acts after a Newton failure with a finite phase draw. It forecasts the donor’s remaining material and energy with the incoming feed included, and weights the receiver mixture with the corresponding transported rates. The next pass still starts and books from the original physical inventory. On the captured 20 s request at tick 454, the stock-controller diagnostic went from 2,523,241 to 2,097,181 checkpoints and from 321 to 268 rejected steps. At tick 358 it used 584,944 checkpoints, up from 574,768. The final committed sources passed 196 targeted Gradle tests spanning the blanket, pressure, cold, phase, junction, column and replay cases. The Newton workspace key also distinguishes the optional solids equations and the boot-weir equation. Both layout transitions have regressions using the same solver before and after the change.

### Affected lane at `311471725`

The follow-up ran 4,070 selected JUnit methods, with 4,067 passing and three failures. All 140 required Minecraft GameTests passed, and chain-100 matched exactly for amounts, temperature, phase and flow. A formatting correction made the format-table check pass. The level-loop startup also passed alone. The wide-open drain's solo run passed its earlier failed tick and reached a second worker deadline at committed tick 454.

| Campaign | Clean on the earlier full lane, same watch worlds | Clean on this lane | Previously held, now clean | Previously clean, now held |
| --- | ---: | ---: | ---: | ---: |
| Cold random | 8 / 27 | 20 / 27 | 14 | 2 |
| Bundled random | 4 / 35 | 10 / 35 | 8 | 2 |
| Bundled defaults | 0 / 3 | 0 / 3 | 0 | 0 |

All seven cold acoustic-bound failures cleared. The two new bundled deadlines, seeds 12 and 59, passed the required solo replay. Cold 181 and 919 ended with numerical holds and had also held in historical factor-four campaigns. For 181 the last failure was a singular Jacobian at active-set pass 4. For 919 it was a stalled line search at pass 0. Those archived reports record neither the failed equation nor its iterate. The two campaign harnesses now stop on a first hold during placement as well as during the running period. Future first-hold reports retain the published state, the backed inventory, the endpoint flows and the modes at that failure. Neither seed was rerun to infer a cause.

## Initial implementation and original full lane

The 0.6 m single-junction fixture delivers real Tia Juana crude to a booked column feed at 83 kg/s over 20 process seconds. At feed pressure, coil delivery was equivalent to 636.89572 K and the booked feed to 636.85733 K. Three substeps committed with zero rejections. A hot enriched crude, cold original crude, and an isothermal blend switch were also exercised. The test requires each first-slice outlet concentration change to reach within 0.1% of the imposed change in the largest cut.

The fresh two-furnace charge-train observer recorded 2,286.18736 kg over committed ticks 1877–1967 on the candidate that passed the cold gates. The inferred radiant discharge was 394,543.689 J/kg and the feed account received 394,029.389 J/kg. Both were flashed at the feed pressure, giving 565.918591 K and 565.764818 K, a difference of 0.153773 K. Both coils showed full firing at the endpoint. This 18 process-second interval used three accepted steps and zero rejections. The measured temperature follows actual heat delivery. The setpoint was 638.15 K.

The charge island subsequently reported round-deadline holds. This run establishes the first measured transfer and does not establish continuous charge-train operation. An earlier candidate undertook a different full-firing-endpoint transfer at 587.970487 K with a 0.170527 K difference and later reported a column condenser-duty trip.

The fixed lifetime stock, conservative component and energy projection, two-inlet/two-outlet fixture and alternating slurry ledger are covered by the targeted tests. The one-time D46 water-wash bound passes. Format 56 keeps the retained parcel and the separate flowing solid populations. Formats 53 and 55 remain readable. Replay revision 3 rejects earlier certificates and bounds extrapolation against the circulating inventory. An exact reopen retains the published endpoint rates.

All 126 selected solver, reconstruction, phase-change, certificate, checkpoint and D46 tests passed, including every cold-start gate. The tank fixture exposed an absent liquid phase referenced by a residual trial draw of 1.55 × 10⁻¹¹² kg/s on a line with zero booked flow. A zero-flow line carries neither constituents nor enthalpy.

The 6,000-tick restored two-heater save recorded 69 matched discharge/feed windows, 20 ending at full firing. The first such window delivered 637.690217 kg at 636.917646 K equivalent and the feed received it at 636.911865 K. The largest absolute gap among all 69 windows was 0.150135 K. The charge island later held at a round deadline in 34 samples and the column ended at zero feed. Earlier fresh and saved soak reports had no sampled holds. On the captured stopped-feed request, the feed valve demanded 0 kg/s and the two parallel heater legs had last circulated 0.017610 kg/s in opposite directions.

The blocked runtime command was replayed independently from the restored graph with a bounded diagnostic worker. It admitted zero substeps in 600,001 solver checkpoints. The nonfinite derivative occurred in the recirculating leg at node 21: its accepted temperature was 626.960 K, while a Newton iterate reached 187.916 K. Substituting an exact finite-difference stencil removed the nonfinite matrix exception but left the step unable to converge. Extending the deadline does not establish a fix. A cyclic inlet predictor initialized all ten circulating junctions near 625–626 K but also failed to advance that job. The diagnostic changes were removed.

## Full lane

The owner-requested `lane.sh head --all` finished at `93a5c848d`. This candidate is not merge-ready. The test stage ran 4,457 methods with 16 failures and no skips. The chain-100 result matched its reference exactly for amounts, temperature, phases and flow. Five required fluid GameTests failed. Campaigns completed all 3,000 seeds. Local reports are in `tmp/junction-lag/full-lane/head/`.

| Campaign | Clean / 1,000 | Previous archived full run | Newly observed budget-class seeds replayed alone | Holds reproduced |
| --- | ---: | ---: | ---: | ---: |
| Cold random | 981 | 992 | 6 | 5 |
| Bundled random | 969 | 989 | 13 | 12 |
| Bundled defaults | 997 | 1,000 | 2 | 2 |

The original lane's 16 failures include these findings.

| Group | Finding |
| --- | --- |
| Two gas-mixing formula assertions | Both expected the entire lifetime stock to enter the flowing blend. Commit `d2a1fc434` checks the actual first-slice fractions and independently closes mass and enthalpy against the booked inventory. The class passed its targeted rerun. |
| Flow and phase behavior | Three `HoldClassWorldsTest` cases held. The steady fired-water outlet was 448.02515 K against 448.15 K with a 0.1 K steady tolerance. The pressure-valve fixture's bottom valve regulated where its reference expects it open. One mixed-gas case used 42 Newton solves against its bound of 40. |
| Deadline-sensitive fixtures | A one-worker rerun reproduced the blanket drain, blanket level-loop start and cooling-tower holds. The vent-header fixture passed its isolated rerun. |
| Column operations | Cold-to-hot feed tripped the condenser-duty closure. The two-shell condenser's water-duty share missed its reference. |
| Source rules | The Curios respirator imports a client class from common integration code. Sight-glass geometry calls `Math.hypot`. Both source lines predate the junction batch. |
| GameTests | Two blanket level loops timed out, the lost-drum recovery waited for isolation, the cross-chunk test waited for cleanup, and the 65-terminal test missed its same-tick completion-budget condition. |

The three campaign lists now contain 65 watch entries. Six failing methods were removed from the lane's exclusion list under its existing upkeep rule. The full lane was run once; the two corrected gas assertions and isolated budget replays are reported separately.
