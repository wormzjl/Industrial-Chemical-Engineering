# Supercritical fluids

Status: **In progress** since 2026-11-15 (S0, S1, S2, S3, S4, S4b and S5 done). Branch `claude/supercritical-fluid-support-d0ffa4`, base `e9edc974b` (main 0.31.0).

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
- [S5_PRESENTATION_REVIEW.md](S5_PRESENTATION_REVIEW.md): S5, what the player reads on one dense fluid: the survey,
  the rule and its client evaluation, the pump's head share, the before and after table, the renders, the owner items.
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

- Decided (plan 2b). Open for the owner: the two findings there; S2's density threshold (an option S2's review will give);
  hydrogen's zero pair with a cut (only if hydrotreating at pressure matters).
- For the owner: S3 edited `examples/material-override/.../tjl20_methane.json`, outside its brief's scope; `MaterialPresetsTest`
  requires that file to mirror the bundled record.
- Not checked: liquids M8 cannot reach (ethane at Tr 0.96-1 inside its domain, any liquid between Tr 0.95 and Tc); the
  effect of O9's pairs on mixtures; the error of a cut's estimated constants (qualification review, "Not measured").
  The campaigns do not cover the dense window (limits review, last section).
- The briefs are in `tmp/supercritical-briefs/BRIEFS.md` in the worktree, not tracked (the hook refuses the main
  checkout's `research/`): to be moved there. Its M4 line states the viscosity coefficients ten times too large; the
  test and the qualification review use the published ones.
