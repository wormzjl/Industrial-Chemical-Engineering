# Supercritical fluids

Status: **In progress** since 2026-11-15 (S0, S1, S2, S3, S4, S4b, S5 and S8 done; main 0.33.0 merged in 2026-11-16). Branch `claude/supercritical-fluid-support-d0ffa4`, base `e9edc974b` (main 0.31.0), main merged at `66a87f9d5` (0.33.0).

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
  the scientific revision of the three packages, every `fluidRevision`, the viscosity revision; the three simulator
  examples re-stamped on the new identity (read, stamp, write; no stored result). Targeted tests and the sample gates green.
  [S3_DATA_REVIEW.md](S3_DATA_REVIEW.md).
- 2026-11-15: the main thread checked S1 and S3 (diffs, Gradle logs, stored results). S1: 2 of 429 digest lines moved, both pure
  nitrogen in the old box 4, amounts and counts unchanged; the threshold is the kernel's ratio 5.87712 (plan corrected).
  S3: found that the three simulator examples held the old data identity and that `FlowsheetExamplesTest` had not run; the
  sub-thread wrote them again (`acd894c93`: only `basis.revision` differs, checked by a JSON comparison) and ran the example
  readers and the four sample gates green. The compat corpus and the neural files are untouched.

- 2026-11-15: S2 split by the main thread after reading the network code (plan, "S2 as carried out"): S2a structure,
  S2b the stream laws, S2c the rest. Brief S2a/S2b written (`tmp/supercritical-briefs/BRIEFS.md`).
- 2026-11-15: S2a and S2b done (sub-thread): a phase carries its root's traits, a state names its one fluid
  (`6dd9cd57c`); one slot viscosity with the dense residual from rho_r 0.25, the film by liquid-like weight, a dense
  liquid slot's acoustic limit and heat capacity ratio, `DenseFluidGatesTest` (`ca321ef23`..`e55bdf53a`). S3 fix: identity
  bound 21000 and the NBT check (`1b0fc7770`). The slot rule not edited (11 nitrogen states inside the old ranges
  disagree); owner: "Keep O2 A", carried out in S2c. Affected classes green. [S2_NETWORK_REVIEW.md](S2_NETWORK_REVIEW.md).

- 2026-11-15: the main thread checked S2a and S2b (diffs, stored results: every affected class green in run 3 but
  `ColumnSolveTest`, green in run 4 after the identity bound). Decisions recorded in the plan's section 2b, points 3 and
  4. S2b's intermediate commits were not compiled one by one; the final tree is the tested one.

- 2026-11-15: brief S2c written (`tmp/supercritical-briefs/BRIEFS.md`; plan, "S2c as briefed").
- 2026-11-15: S2c (sub-thread, O2 A for mixtures too): the slot by reduced density for one dense fluid (`ac1808ee2`); the dense pump suction test and the ejector law above water's Tc (`af2590e6a`); three GameTests (`e5b554968`), run once (`s2c-gt1`): 98 existing passed, the 3 new failed on test premises and a layout (no hold, ledgers closed), rebuilt (`22be39e01`) and run once more (`s2c-gt2`): the two-nozzle test passed, the letdown and the loop failed on two block placements in the tests, the existing blanketed-drum test failed (99.57 % against 99 %, passed in run 1 on the same production code); then made runnable alone (`-PfluidGameTestNamespaces=icheme_dense_test`), fixed and made to remove what they place on every exit: `s2c-dense2` passed all 3 (the loop crosses Tc and reduced density 1 each circuit). [S2C_NETWORK_REVIEW.md](S2C_NETWORK_REVIEW.md).

- 2026-11-15: the CoolProp parity and reference tables rewritten one record per line at the owner's word ("consolidate";
  `79f902b22`): 46,361 lines to 2,734, the parsed documents and every number's text identical; both generators write the
  layout (`tools/coolprop-parity/compact_json.py`). Their four test classes green.
- 2026-11-15: the main thread checked S2c (the slot-rule and ejector diffs, the build change, run `s2c-dense2`'s log: 3 of
  3). Decisions in the plan's section 2b, points 5 to 7.

- 2026-11-15: brief S4 written (`tmp/supercritical-briefs/BRIEFS.md`; plan, "S4 as briefed").
- 2026-11-16: main merged twice, at the owner's "Approve, merge first":
  - 0.31.1, the fluid step cost batch (`8bac3e6e7`). Two document conflicts; the affected classes passed (1,546 + 136
    + chain-100 exact); dense GameTests `s2c-merge1` 3 of 3.
  - `b41f7f9c7`, the tools large files batch and the `icheme-2` reservation (`10c9578b2`): documents and tools only,
    no test run.
- 2026-11-16: S4 (sub-thread). A dense fluid's pump vapour share and compressor condensed share follow its liquid-like
  weight, and so does the pump's E. Commits: the weight moved to the model (`1b4396c25`); the shares and the suction
  limit, with `dense-movers-v1` (`505832d82`); the simulator readers (`0b3d6a1d9`); the tests (`22589367d`). Results:
  the affected classes passed, and dense GameTests `s4-dense1` 3 of 3. The compressor law was measured, not changed.
  [S4_MACHINES_REVIEW.md](S4_MACHINES_REVIEW.md).

- 2026-11-16: the main thread checked the merge of main (`8bac3e6e7`, then `10c9578b2`) and S4 (the diff, the stored
  results, the review). The standing merge instruction, main's moves and S4's open form are in the plan's section 2b,
  points 8 to 10.
- 2026-11-16: S4b (sub-thread; owner: "ok but use a new thread", then "Use 0.5 to 1.0"). One dense fluid has no second
  phase at a machine. Its pump's E stands only at a real flash, from the bubble side below Pc: CO2 at 320 K and 15 MPa
  keeps 6.5198 MPa, and the lock band (δ 0.5 to 1.1) is gone. It has no p_v and no droplet wear. On the sheet it has no
  edge, no bubble warning and a cold suction, and the compressor's discharge exponent is blended to the solved streams'.
  One seam is left, on the critical isenthalp (δ 1.067, 1.181, 1.311 at 310, 320, 330 K). A pump's own weight runs from
  δ 0.5 to 1.0 (option 1 B of [DENSE_PUMP_RESEARCH_REVIEW.md](DENSE_PUMP_RESEARCH_REVIEW.md)), so it keeps its full head
  from the critical density. Commits `588f7da31`, `6e6f928aa`, `c69ff5303`, then the documents. Results: 0 non-dense
  values moved; the affected classes passed twice (190 classes and 1,347 tests, then 235 and 1,533; holdGates 136
  each); chain-100 deviation 0; dense GameTests `s4b-dense1` 3 of 3. [S4_MACHINES_REVIEW.md](S4_MACHINES_REVIEW.md)
  (corrected sections 1, 3, 4, 6, 8, 10).

- 2026-11-16: the main thread checked S4b (the diff, the stored results, the review); one stale number in a comment of
  `MoverSuction.zoneEdge` corrected (the seam at 310 K is at reduced density 1.07). Decisions in the plan's section 2b,
  points 11 and 12.
- 2026-11-16: S5 (sub-thread; owner: "Accept all"; main thread: label source A, pump page form ii). One dense fluid above
  reduced density 0.5 reads "Dense fluid" in either slot, "Vapor" at or below; no word changes at δ 1. The client evaluates
  the shown contents' one root on its own catalog, once per published view (`DenseLabel`), and keeps today's label where
  it cannot; no wire field. A pump on one dense fluid has no bubbles (gas ratio NaN, no churn, no `GAS`); its page reads the
  head share. The simulator's pump sheet reads `PUMP_DENSITY` against 0.86. Results: `test` 468 classes, 2,874 tests,
  6 failed, none from S5 (S3's ranges against 4 thermo pins, 2 `FORMATS.md` rows of the pollution batch, the
  translations); columnGates 379, equipmentGates 244, holdGates 136 green; chain-100 deviation 0; dense GameTests
  `s5-dense1` 3 of 3. [S5_PRESENTATION_REVIEW.md](S5_PRESENTATION_REVIEW.md).
- 2026-11-16: the column's feed (D1, D2; sub-thread on `claude/supercritical-column-feed-b49e05`; owner: "Approve
  both"). `V3FeedFlash` answers a feed with no two-phase split as one phase (2,412 of the review grid's 10,602 states,
  no real split among them); the tray audit's text names the critical point. Column gates 62 classes, `test` 41 classes,
  none failed. [COLUMN_FEED_REVIEW.md](COLUMN_FEED_REVIEW.md). Merged into the batch branch by the main thread after its
  check (`30498aaf1`).
- 2026-11-16: the main thread checked S5 (the diff, the stored results, the renders). The plunger pump (stage S8)
  researched and planned at the owner's request; three forms of its model rendered (`tmp/plunger-forms/`), the owner
  chose form A. S3's four moved grade pins: three test cases moved to 36 MPa, the engine digest left to the final lane.
  Decisions in the plan's section 2b, points 13 to 17.
- 2026-11-16: main 0.33.0 (`66a87f9d5`) merged into the branch (sub-thread; owner: "uh merge main first"): merge commit
  `dfc9d9e55`, three document conflicts; no revision token needed a new value. The merge left main's five rare gases
  without S3's `critical_volume`: added with S3's tool (`0f4230144`). `test` 373 classes (1 failed, that check), column 75,
  equipment 3, hold 10 classes green; after the fix 30 classes green. Digest: line 313 only, the grade token. Finding: the
  burst-pipe release splits by slot. [MERGE_MAIN_0_33_REVIEW.md](MERGE_MAIN_0_33_REVIEW.md).
- 2026-11-16: S8, the plunger pump (sub-thread; owner: "Form A", then "Keep both"). `PlungerPump` on `PositiveDisplacement`
  (PP-3 to PP-100 m³/h at 300 rpm, relief 25 MPa, η 0.88); fluid facts schema 28, state format 51, corpus `schema-28`;
  `icheme-4` not needed. Kind `PLUNGER_PUMP` (form A with the main thread's shaft fix and 3 cm longer necks; the nozzles on
  the column's bore rule); the Pump tile's `CENTRIFUGAL_PUMP` and `PLUNGER` types, Auto's rule, `PLUNGER_RISE`. Probe: on
  its law from δ 0.3 to 1.6, share 0.99838. Dense GameTest: 9.986 m³/h against 9.981 displaced less slip, no hold. Runs:
  `test` 121 + 85 classes, equipment 24 + 21 + 3, hold 6 + 9, all S8 failures fixed but the Chinese sizing door
  (translation commit). [S8_PLUNGER_PUMP_REVIEW.md](S8_PLUNGER_PUMP_REVIEW.md).
- 2026-11-16: the main thread checked the merge of main 0.33.0 and S8 (the diffs, the stored results, the placed form
  against the scratch). Form A changed for three equipment gates (owner: "Keep both"); the pump page's head share only
  on a centrifugal curve (`9195b5161`); main's reservation commit `ab6298d03` merged. Decisions in the plan's section
  2b, points 19 to 21.
- 2026-11-16: merge preparation (sub-thread; owner: "Approve all"), by `merge-prep`. CHANGELOG entry under `[Unreleased]`
  (`daa117a40`); the translation commit, 35 keys in zh_cn, fr_fr, es_es, ja_jp (`7b98b9567`); the plunger pump's relief
  line, in en_us too: its bare `% p` broke `String.format` and it was 534 px against the datasheet's 411, both found by
  `UnitTextTest`, which S8 had left for this commit (`7ae07f423`, `3307722ab`); the code guide at `9195b5161`, 23
  chapters' snapshot lines, line counts, `ReleasePressure.lowestHeld` and a mover's device kind by its law (`55f338e3f`;
  checked, no text change: `effects.md`, `runtime-engine.md`, `tutorial.md`); the tooling cleanup with
  [THREAD_BRIEFS.md](THREAD_BRIEFS.md) and [OWNER_DECISIONS.md](OWNER_DECISIONS.md) (the next commit).
  `main-reach.pl`: the six older classes, none of this batch. Runs, the classes `affected.mjs select` names for the lang
  files, on the final text: `test` 75 classes, 516 tests; `equipmentGates` `UnitTextTest`; `holdGates` 8 suites, 57
  tests; `columnGates` 2 classes (up to date after run 1); none failed (`LanguageFilesTest`, `SizingPageTest` among
  them). Not run: `EngineDigestTest` (the lane re-records line 313), chain-100, the GameTests, the campaign.

## Documents

- [SUPERCRITICAL_LIMITS_REVIEW.md](SUPERCRITICAL_LIMITS_REVIEW.md): expected behaviour, what the code does, the
  findings A1..D4.
- [SUPERCRITICAL_DATA_REVIEW.md](SUPERCRITICAL_DATA_REVIEW.md): the data per need, what lacks (L1..L8), the crude
  cuts (P1..P6), the rule for a lack.
- [SUPERCRITICAL_PLAN.md](SUPERCRITICAL_PLAN.md): the idea, owner decisions O1..O10, stages S0..S7, order,
  verification, proposed delegation.
- [S3_DATA_REVIEW.md](S3_DATA_REVIEW.md): S3's ranges with their evidence, the critical volumes, the residual viscosity,
  the identities that moved, the identity bound.
- [S2_NETWORK_REVIEW.md](S2_NETWORK_REVIEW.md): S2a and S2b, the dense-fluid stream laws, the slot-rule comparison, the
  owner options (onset, film bounds, film seam).
- [S4_MACHINES_REVIEW.md](S4_MACHINES_REVIEW.md): S4, a dense fluid at a pump and a compressor: the shares and E
  before and after, the seams, the compressor law against the reference, the simulator's readers, the owner options.
- [DENSE_PUMP_RESEARCH_REVIEW.md](DENSE_PUMP_RESEARCH_REVIEW.md): how a real centrifugal pump behaves on a supercritical
  fluid (sources, the rise on the suction's density, options); option 1 B carried out in S4b.
- [S2C_NETWORK_REVIEW.md](S2C_NETWORK_REVIEW.md): S2c, the slot rule by density and its moved states, what still reads the slot, the dense pump's suction edge, the "no saturation" callers, the GameTests, the campaign draws.
- [COLUMN_FEED_REVIEW.md](COLUMN_FEED_REVIEW.md): the column's feed flash on a feed with no two-phase split (D1) and the
  tray text (D2): the grids against the equilibrium engine, the enthalpy against the engine, the owner options O1 and O2.
- [PD_PUMP_RESEARCH_REVIEW.md](PD_PUMP_RESEARCH_REVIEW.md): how a reciprocating pump behaves on a compressible fluid
  (sources, the clearance loss in numbers, the mod against the sources, options).
- [PLUNGER_PUMP_PLAN.md](PLUNGER_PUMP_PLAN.md): stage S8, the plunger pump: the law, the equipment and its form, the
  simulator, the tests.
- [S5_PRESENTATION_REVIEW.md](S5_PRESENTATION_REVIEW.md): S5, what the player reads on one dense fluid: the survey,
  the rule and its client evaluation, the pump's head share, the before and after table, the renders, the owner items.
- [S8_PLUNGER_PUMP_REVIEW.md](S8_PLUNGER_PUMP_REVIEW.md): S8, the plunger pump: the law, the saves, the probe on carbon dioxide, the
  readers of a liquid machine, the equipment and its polygons, the simulator, the dense GameTest, the runs, the owner items.
- [MERGE_MAIN_0_33_REVIEW.md](MERGE_MAIN_0_33_REVIEW.md): the merge of main 0.33.0: conflicts, the revision tokens of the 33
  records both sides changed, the rare gases' critical volumes, the digest measurement, main's slot readers, the runs.
- [S1_ENGINE_REVIEW.md](S1_ENGINE_REVIEW.md): S1, the band boxes 3 and 4 removed, the phase traits, the digest lines that moved.
- [THREAD_BRIEFS.md](THREAD_BRIEFS.md): the briefs of every sub-thread (common rules, S0 to S4b, the merge of main 0.33.0,
  S8, the merge preparation), as written.
- [OWNER_DECISIONS.md](OWNER_DECISIONS.md): the main thread's note of the owner's standing instructions and answers.
- [SUPERCRITICAL_QUALIFICATION_REVIEW.md](SUPERCRITICAL_QUALIFICATION_REVIEW.md): S0's measurements M1..M7 and the follow-up M8, the
  recommended pressure ranges and the sizes of the open decisions.
- Earlier: [../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md](../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md)
  (architecture assessment), [../2026-11-13-codebase-review/SOLVER_DECOUPLING_PLAN.md](../2026-11-13-codebase-review/SOLVER_DECOUPLING_PLAN.md)
  section 7 (the room made in 0.31.0: `PhaseTraits`).

## Code areas (planned)

`science/thermo/phase/` (band, traits), `science/fluid/thermo/` and `science/fluid/network/` (slot mapping, laws on
traits, "no saturation"), `science/fluid/transport/MixtureViscosity`, `science/machine/`, `science/flowsheet/`,
dataset records under `data/icheme/materials/properties/`, the device screens' phase rows.

Tool folders: [`tools/supercritical-probes/`](../../tools/supercritical-probes/README.md) (the stages' probes and number
scripts), [`tools/plunger-forms/`](../../tools/plunger-forms/README.md) (the form option sheets of S8),
[`tools/coolprop-parity/`](../../tools/coolprop-parity/README.md) (S0's generators; its scratch scripts in `supercritical-s0/`),
[`tools/compound-import/`](../../tools/compound-import/README.md) (`critical-volume.mjs`, S3).

## Open items

- For the owner (S2b, [S2_NETWORK_REVIEW.md](S2_NETWORK_REVIEW.md) sections 2 and 3): the viscosity onset (rho_r 0.25 to
  0.5, or none), the film bounds (0.5/1.5, or 0.75/1.25), the film seam (weight on every one-fluid state, or on
  supercritical isotherms only). Implemented: the first of each.
- For the owner (S2c, [S2C_NETWORK_REVIEW.md](S2C_NETWORK_REVIEW.md) section 8): the engine's PIP labels of one-root mixtures (option B, not done); the LIQUID port's head seam; the gas blanket in the liquid slot; the pump and compressor conventions and E for S4; the blanketed drum's failure in run `s2c-gt2` (section 6). The S2 review's cold-campaign line is corrected there (section 7).
- For the owner (S4, [S4_MACHINES_REVIEW.md](S4_MACHINES_REVIEW.md) section 8):
  - the compressor's head law (−14 % head on a liquid-like suction);
  - the seam at the critical isenthalp (E none → about Pc), left as it is;
  - the pump's readers of `1 − w_pump` (churn, `GAS`, gas ratio, a dry screw's LIQUID), for S5;
  - the sizing's discharge between w 0 and 1 (15.5 K high at the slot line);
  - the sheet's p_v of a dense mixture.
  S4b settled the bounds (the pump on 0.5 to 1.0) and the wet gas wear (none).
  For S5, section 9 lists the texts that name a phase.
- For the owner (S3 fix, [S3_DATA_REVIEW.md](S3_DATA_REVIEW.md) section 4): the thermodynamic revision repeats the fluid
  identity about three times (once would lift the NBT wall to about 290 compounds); the simulator menu's opening data is
  18,281 characters against a limit near 32,600 bytes, not confirmed (about 110 compounds).

- For the owner (S8, [S8_PLUNGER_PUMP_REVIEW.md](S8_PLUNGER_PUMP_REVIEW.md) section 7): the plunger pump on a gas (no
  clearance term), Auto's rule, the sound, the bolts as relief, the run door's efficiency, the Model entry's unit (shared
  with the vacuum pump and prime mover), the dense pump page's head share on a plunger, an in-game check.

- Decided (plan 2b). Open for the owner: the two findings there; S2's density threshold (an option S2's review will give);
  hydrogen's zero pair with a cut (only if hydrotreating at pressure matters).
- For the owner: S3 edited `examples/material-override/.../tjl20_methane.json`, outside its brief's scope; `MaterialPresetsTest`
  requires that file to mirror the bundled record.
- Not checked: liquids M8 cannot reach (ethane at Tr 0.96-1 inside its domain, any liquid between Tr 0.95 and Tc); the
  effect of O9's pairs on mixtures; the error of a cut's estimated constants (qualification review, "Not measured").
  The campaigns do not cover the dense window (limits review, last section).
- The briefs are tracked since the merge preparation ([THREAD_BRIEFS.md](THREAD_BRIEFS.md)). Its M4 line states the viscosity coefficients ten times too large; the
  test and the qualification review use the published ones.

- For the owner (S5, [S5_PRESENTATION_REVIEW.md](S5_PRESENTATION_REVIEW.md) section 7): items 1 to 8 (the client's catalog
  on a datapack server, the sight glass, the level transmitter, the line service, a two-phase head share, the density
  row's hover, the share's colour, a dense aqueous tank).
- For the owner (the column's feed, [COLUMN_FEED_REVIEW.md](COLUMN_FEED_REVIEW.md), "Options for the owner"): O1 (the feeds
  still refused as one phase) and O2 (the 181 one-phase answers by the Wilson endpoint test). Implemented: O1 A.
- For the owner: S1's open line ([S1_ENGINE_REVIEW.md](S1_ENGINE_REVIEW.md), "Open"); S3's not-done list
  ([S3_DATA_REVIEW.md](S3_DATA_REVIEW.md) section 6).
- For the final lane ([MERGE_MAIN_0_33_REVIEW.md](MERGE_MAIN_0_33_REVIEW.md) section 9): re-record the engine digest once
  (line 313, the grade token); chain-100 and the fluid GameTests are due. For the main thread there: the burst-pipe
  release reads the slot (section 6).
- To copy to `research/2026-11-15-supercritical-fluids/` in the main checkout (the session hook refuses that write from
  the batch worktree). From the worktree's `tmp/`:
  - `supercritical-s5/renders/` (S5's device and sizing renders) and `supercritical-s8/renders/` (S8's plunger pump page
    and sizing renders);
  - `plunger-forms/`: the option sheets (`forms-30.png`, `forms-sizes.png`, `form-a.png`, `form-a-before.png`) and the
    geometry folders (`geometry*/`); the source and the renderer are in `tools/plunger-forms/`;
  - `equipment-drawings/20261116-043157-cdb869/` (the plunger pump scenes) and `tutorial-renders/` (the plunger pump icon);
  - the probes' outputs: `supercritical-s2/probe/*.txt`, `supercritical-s4/probe/*.txt`, `supercritical-s4b/before/`,
    `after/` and `after-pump/`, `supercritical-s8/probe/plunger-co2-310K.txt`, `supercritical-s8/geom/out/`,
    `dense-pump-research/*.txt`, `supercritical-merge/tokens.txt` and `engine-digest-recorded.txt`;
  - S0's tables and M8 rows (`supercritical-s0/tables/`, `m8/`) and S3's `cv_table.md`;
  - the GameTest logs (`supercritical-s2/dense*-logs/`, `gametest-s2c-gt*/`, the later stages' `runs/gametest.log`):
    compressed, as `tools/<folder>/logs/<name>-logs-<date>.tar.gz` in the main checkout (AGENTS.md, "Documentation").
  The CoolProp environment (`coolprop-venv/`, its cache) and the DWSIM extract (`dwsim-data/`) stay as downloaded tools
  and data; the edit scripts, Gradle logs, stored results and compiled classes stay in `tmp/` until the main thread
  cleans it after the merge.
