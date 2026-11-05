# Neural retrain: basis, data generation and cleaning (2026-11-05)

Companion to [NEURAL_RETRAIN_PLAN.md](NEURAL_RETRAIN_PLAN.md), work packages N1 to N2 and N5. The owner asked: "list the basis components (materials that can come out of crude) and more details on data generation & cleaning method". The rules come from the [training guide](../2026-09-10-v4-neural-initializer/V4_TRANSFORMER_TRAINING_GUIDE.md) sections 4 to 7 and 9.2, and from `tools/neural/prepare_transformer_data.py` (`strict`). Facts checked at `546ae8027`.

## 1. Basis

- The composition axis is the column package `icheme:tjl20_methane`. It has 19 compounds, which are global features x17 to x35, in this order: Methane, Ethane, Propane, Isobutane, N-butane, Isopentane, N-pentane, then the cuts `crude_pc01` to `crude_pc12`. The axis is unchanged from the current model, so the encoding and the parameter count (88,852) stay.
- Water is not on the axis. It enters as the steam inputs and appears only in the free-water targets (interior trays, plus the condenser through the TWO_PHASE split).
- The four grades (`materials/assays/`, mass basis):

| grade | compounds held | C1 to C5, mass % |
|---|---|---|
| light_crude | 19 | 4.0 |
| medium_crude | 19 | 2.5 |
| heavy_crude | 19 | 1.0 |
| oil_sand_dilbit | 18 (no methane) | 6.0 |

- None of the other 25 world-list compounds is in any grade: N2, and the 24 of Tier 1, H2S and both mercaptans among them. CO2 (O11) is in no grade either. A feed that holds any of them is outside the axis, so the model declines it and the classical start runs.
- **A sour grade, or any compound added to `tjl20_methane`, changes the axis.** The model then needs new labels and the "widen, then continue" route (guide section 8.1). That choice must be made before N2. Today the plan assumes the 19-compound axis.

## 2. Data generation (N1, N2)

- Compositions (guide 4.3):
  - Draw blend weights over the four grades, w ~ Dirichlet(0.5). This gives pure grades, pairs and many-way blends.
  - Mix: z = sum of w_f z_f. Then z_i *= lognormal(0, 0.25) and renormalise. The perturbation leaves the blend plane on purpose. On the regrouped basis, the off-ring holdout gave neural-only 33 with ring-only training, 107 with global training, and 109 for classical (guide 13.2).
  - With a small probability, zero a light-end group exactly (the C1-C2 pair). A compound absent from every contributing grade stays exactly zero, as methane does in dilbit.
  - About one third of the requests sit at the pure grades and their pairwise blends, the commonest game inputs. Each request records its weights and zero mask.
- Operating factors: feed temperature, top pressure, reflux, condenser temperature, steam rate and temperature, PA duties and trays, draw trays and rates, the stage counts and feed trays of the constructor's templates, and the diameter (flow-dependent pressure drop on). The ranges are those the column-operation controls and the simulator's column doors allow, plus a usage neighbourhood around each grade's preset (guide 4.2).
- Screens at generation, never by a solve (guide 4.4):
  - `requestOnlyAdmission(input, 0.30)`, the liquid-supply screen;
  - the energy-limited draw screen sigma >= 0.66 (worst sigma re-measured, since the preset set changed);
  - the reader's envelope (`supported`);
  - the structurally degenerate family: no steam, zero reboiler duty and trays below the feed (guide 6.1);
  - disjointness by `generalized_design.digest` from every earlier request file.
- Labelling: `CURRENT_ONLY` on the current V3 code. Every input is journalled, failures included. A network prediction is never a label.
- Yield and size: the measured strict classical yield is 30 to 35 % (guide 4.5). The packaged model got 306 strict labels from 1,152 requests (27 %); the full-cell holdout got 106 of 312 (34 %). The earlier 65 % in the plan had no measured source and is withdrawn. For about 2,000 labels (at least 250 per grade) N2 needs about 6,000 TRAIN requests. The 5 % pilot (about 300) decides the final count.

## 3. Cleaning (N2 to N3)

- A strict label passes `prepare_transformer_data.strict`:
  - success with `DRY_EQUILIBRIUM` or `WET_EQUILIBRIUM`; `DRY_SUPERSATURATED` and advisory successes are not labels;
  - the seed's canonical input equals the request;
  - every acceptance-audit check passed;
  - a real final Newton step at closure 1e-8, linear backward error at most 1e-12, largest log-flow change at most 1e-8, temperature-step ratio at most 1.
- Deduplicate by canonical input hash. TRAIN is disjoint from validation, the usage panel and every holdout (asserted).
- One profile per input, by fixed priority. Material root disagreements go to quarantine and are never averaged.
- Never drop a row by residual, iterations, time, solver path, model error or any holdout outcome. Redundancy is measured with `tools/training-curation/curate.py` before anything moves to reserve.
- Never delete a never-solved TRAIN input. It stays in the journal as an acquisition target.
- Report the failures by class (guide 6.3): A structurally degenerate, B path-dependent heat gate, C request-only typed infeasible, D open. Headline rates use B + D. Liquid-depleted side draws are their own class, outside the latency denominators.
- Freeze (guide 7):
  - normalisation statistics from strict TRAIN labels only, column-balanced (1 / nodes);
  - anchors from the production `V3NativeAnchor` for every legal branch;
  - validation is the validation fold of the same design;
  - the usage panel is the four presets plus about a dozen neighbours each;
  - the holdout is a new seed of the full generator, drawn after the candidate is frozen and never solved before; a consumed holdout is not blind again.
- Open-data check: no Tia Juana or ExxonMobil composition in the requests, the labels or the sidecar. Every request is compared with the Tia Juana overlay (`src/test/resources/materials/tia-juana/`) and with the five ExxonMobil compositions of the current sidecar's `compositionEdges`, read from git before N6 deletes them. Both checks must find nothing; a hit fails the set.

## 4. Envelope and the E7 check (N5, D5)

- The envelope must not exceed the labels (guide 9.2). `globalMin`/`globalMax` come from the solvable families' requests and are checked against each factor's labelled range.
- E7: mean and 3-PC basis of the strict TRAIN compositions, stored in sidecar schema 5 with those compositions. The thresholds come from the trained spread:
  - in-plane: the feed's projection lies inside the hull of the four grades in PC space, widened by the 99th percentile of the in-plane shift that the lognormal perturbation gave the TRAIN compositions;
  - the out-of-plane limit is the 99th percentile of the TRAIN out-of-plane residuals.
  - Both are measured in N5. O8's 0.3 RMS radius is a ceiling, not the value.
- The per-compound box (feature x17 to x35, 10 % span margin) stays as the second gate.

## 5. Up to 5 % unknowns (revision 2; D6)

Owner: "The neuro model need to tolerate up to ~5% unknowns, research about the model structure that can accommodate this purpose."

- Physics first. Unknowns are not inert. A grade feed is 527 (heavy) to 912 (light) mol/s; medium is 710. So 5 mol % of a non-condensable (H2, N2, CO) is about 36 mol/s, against about 27 mol/s of off-gas today. It more than doubles the vapour leaving the condenser, and it strips more C3 to C5 into that gas. Heavy unknowns (xylenes, about 412 K) stay in the naphtha and kerosene trays. The seed's top therefore depends on the unknowns' volatility, and a structure that ignores them is wrong at the top. The water-soluble ones (NH3, H2S, SO2) also go into the condenser water in a real plant. The column's free water is pure water, so the model need not carry that.
- What "unknown" means here: a compound on the column's feed that is not on the model's 19-compound axis. The candidates are the world list's 25 compounds outside the axis: N2 and the 24 of `icheme:refinery`. CO2 is out while E25 refuses it. U0, answered from the code (2026-11-05): `icheme:refinery` extends `icheme:tjl20_methane_nitrogen`, which extends `icheme:tjl20_methane`, so a column on it carries all 44 compounds (the simulator's `ColumnDoor` takes the package's whole list). `V3NeuralRegistry.bind` already projects a request onto the model's axis when every active compound is on it, and projects the seed back. U1 widens that test to "off-axis share at most 5 %", lumping before the projection and delumping after it. Whether V3 solves such a feed classically is measured by the pilot's injected rows. Oxygen is not injected: no crude column carries free oxygen.
- Forms (at most three):
  - **U1, volatility lumping before inference.** Each off-axis compound is mapped onto the axis compound with the nearest normal boiling point:
    - lighter than methane (H2 20 K, N2 77 K, CO 82 K, O2 90 K) -> methane;
    - ethylene -> ethane;
    - H2S, propylene, COS -> propane;
    - SO2, isobutene -> isobutane;
    - the butenes, butadiene, methyl mercaptan -> n-butane;
    - ethyl mercaptan -> n-pentane;
    - C6 and the aromatics -> the nearest crude cut.
    The fixed 19-slot model predicts. Each unknown is then delumped with its lump's split: the same recovery fraction per node, scaled by its share of the lump. Methane is a fair lump for the gases: at 332 K and 250 kPa every one of them leaves overhead, so the top's total gas and its stripping effect come out right to first order. No architecture change; the lump map is data in the sidecar. Newton corrects the residual difference (heat capacity, exact K).
  - **U2, U1 plus off-axis channels.** Three extra global features give the off-axis mole share in three volatility bands: normal boiling point below 150 K, 150 to 320 K, above 320 K. The model learns how the top responds beyond the lump. This changes the input width only (global 73 -> 76): a feature revision and a retrain, both already planned.
  - **U3, property-descriptor tokens.** Each compound is a token built from its constants (Tc, Pc, omega, MW, normal boiling point, density, Cp coefficients). A set encoder (Deep Sets, Zaheer et al. 2017; Set Transformer, Lee et al. 2019) pools the tokens, weighted by mole fraction. It covers any compound, but it needs a new encoder and anchor encoding, and labels with many different compounds before it generalises. Estimated at about 2 days more than U1 or U2, with the most risk.
  - Recommendation (D6 A): build U1 and U2, compare them in N3, keep the better; do not build U3. This follows the owner's preference for lightweight game-level rules; petroleum practice already characterises unknown fractions by boiling-point lumping.
- Training injection: about a third of the CDU rows get an injection. 1 to 3 compounds are drawn from the world list (CO2 excluded) with a total of U(0, 5) mol % of the feed, and the hydrocarbon part is renormalised. N3 reports validation errors with and without injection.
- E7 with unknowns: the off-axis share must be at most 5 %. The known part, renormalised, must pass its family check (D5).

## 6. VDU set (revision 2; D8 placeholder)

Owner: "Data generation needs to include a set of VDU specific data (" (the text breaks off; the base session has asked the owner what it held).

- Since 0.18.0 the V3 column solves a VDU on CDU bottoms (batch [2026-10-28-vacuum-column](../2026-10-28-vacuum-column/README.md)). The accepted case: 10 interior stages, feed stage 8, top 2 kPa(a), diameter 15 m, inlet 355.4 C, zero reflux with dry upper stages, no reboiler, bottom steam, cuts on stages 3 and 6, pumparound cooling of 10 and 15 MW.
- Broad VDU convergence is open: 228 of 1,000 cold cases passed all checks. That 23 % sets the expected classical yield, and it is the class where re-iteration (section 8) should help most.
- Owner rule: literature before VDU experiments. The design follows case A ([2026-08-31-v3-vdu](../2026-08-31-v3-vdu/README.md), Ji and Bagajewicz 2002, adopted 2026-09-08): top 1.3 to 8 kPa, flash 3.3 to 6.7 kPa, 8 to 15 stages, no condenser reflux and no reboiler, the heat removed by pumparounds.
- Feeds: the bottoms of the strict CDU labels, per grade and blend, exported exactly as in `tools/vacuum-column-test/`. The grades are never blended directly, because a residue lies far off the grade plane. The bottoms therefore form a second composition family (D5).
- Open check V0, before the design is frozen:
  - the new sidecar's node-pressure floor comes from its labels (guide 9.2), so the old 50 kPa floor does not bind;
  - zero reflux admits the VAPOR_ONLY branch in `bestBranch`;
  - the dry-layout formulation revisions (`-phase-r1`) must be listed by the model;
  - whether `NativeAnchor` (the MATERIAL_CLOSED initializer) builds on a dry zero-reflux top at 1 to 8 kPa is unmeasured. The pilot measures it.
- Thermo caveats, noted only (no basis change without the owner):
  - in the 12-cut basis, the HVGO cut point (538 to 566 C) falls in the gap between pc10 (558) and pc11 (661);
  - PR78 cuts sit 4 to 13 K below Maxwell-Bonnell at 1 to 13 kPa.

## 7. "Does this condition make sense" (revision 2)

Owner: "Data cleaning should include a 'does this condition makes sense' step to prevent too much garbage data."

Every criterion is a physical property of the operating point, never solver difficulty (residual, iterations, time, path). That keeps the guide's rule "never drop by residual, iterations, time, solver path". Counts are reported per criterion.

- At generation, on the inputs, with no solve. Each window is cited in N1 to its source (Watkins 1979, *Petroleum Refinery Distillation*; Kister 1992, *Distillation Design*; or the game's GUI limits). A preset that fails a window flags the window, not the preset.
  - Heater outlet below cracking: CDU at most about 370 C (643 K), VDU about 400 to 420 C. The revision-1 design reached 660 K, so it narrows to at most 643 K.
  - Top pressure by column type: CDU about 150 to 350 kPa, VDU 1.3 to 8 kPa.
  - Stage count by column type: CDU from the constructor's templates, VDU 8 to 15.
  - Reflux, steam-to-feed, draw and pumparound-duty ranges. Steam never above the feed rate: 10 of the 203 partial-pilot rows broke this, all heavy grade with steam x1.4. The heavy preset itself runs 468 mol/s of steam on 527 mol/s of feed.
- After labelling, on the converged state:
  - no tray above the cracking limit;
  - products in boiling-range order (overhead < naphtha < kerosene < diesel < bottoms, by each product's 50 % point);
  - positive internal reflux in every wet section;
  - overflash inside a set range (about 2 to 5 vol % of the feed in practice, cited in N1);
  - no vanishing product rate (each draw and product at least 0.5 % of the feed);
  - a VDU residue above the VDU's heater limit flags the case.
- A row that fails a check is kept in the journal with its criterion, and is not a label. It is not a solver failure, and it stays out of the A to D classes.

## 8. Re-iteration, two rounds (revision 2)

Owner: "Add a re-iteration step to use the first neuro model to generate more converged data to increase coverage, repeat 2 times."

- v1 is trained on the classical labels (N3).
- Round 1:
  - run v1 as LNN_FIRST, at D2's poll budget, on every TRAIN input that classical did not solve (stalls, heat gate, VDU), plus about 1,000 fresh requests in the design's thinnest cells;
  - keep strict native results that pass section 7 only; a network output is never a label;
  - root disagreements with existing labels go to quarantine;
  - retrain from scratch on the union to get v2.
- Round 2: the same with v2, giving v3.
- Each promotion (v1, v2, v3) is judged on a fresh holdout; a consumed holdout is not blind again. After each round, report coverage per cell and per class (heat gate, VDU, stall band) and the round's cost in polls and hours.
- Bias guard: inputs that v1 solves are the inputs it already covers. The fresh thin-cell requests and the per-class coverage report show whether a round widened coverage or only deepened it.

## 9. Freezing out of scope; E30 (2026-11-05)

- The owner: "freezing isn't part of this work (it's for cryo separation of other compounds), you will not be training CDU/VDU models based on freezing conditions". So there is no frozen-tray check and no freezing condition in the design or the labels. CO2 stays out of the injection list while the column refuses islands that hold a crystal (E25, E29).
- E30, the owner's words: "Land both". F1b and fix (a), one water model, land on the base branch after O11's Gradle work. Fix (a) bumps the wet formulation revisions, so N2 waits for that commit and a sync.
