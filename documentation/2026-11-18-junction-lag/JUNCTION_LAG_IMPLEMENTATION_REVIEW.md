# Junction heat and composition qualification

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
