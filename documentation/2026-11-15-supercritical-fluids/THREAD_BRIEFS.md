# Thread briefs of batch 2026-11-15-supercritical-fluids

The briefs the main thread wrote for the batch's sub-threads, kept as written (from `tmp/supercritical-briefs/` in the
batch worktree, gathered at the merge preparation on 2026-11-16), in order: the stage briefs with their common rules
(S0 to S4b), the merge of main 0.33.0, S8 the plunger pump, the merge preparation. Each file's headings are one level
down. They state what each thread was asked, not what it found: the stage reviews and the batch README hold the results.
The owner's answers are in [OWNER_DECISIONS.md](OWNER_DECISIONS.md) and the plan's section 2b.

## Briefs of batch 2026-11-15-supercritical-fluids

Working records, not tracked (owner, 2026-11-15). Their place is the main checkout's
`research/2026-11-15-supercritical-fluids/`; the session's hook refuses writes there, so they are here until the
owner moves them. Tracked documents of the batch: `documentation/2026-11-15-supercritical-fluids/` on branch
`claude/supercritical-fluid-support-d0ffa4`.

### Common rules

- Worktree: `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\review-rust-a1-math-5ced47`, branch
  `claude/supercritical-fluid-support-d0ffa4`. Work only there. Do not `cd` to the main checkout.
- Read first: `AGENTS.md` sections "Gradle, measurement and verification" (with "No repeated tests"), "Thermo
  validity", "Documentation, research and tools", "Git and worktrees"; `documentation/reference/code-guide/README.md`,
  then only `equilibrium-engine.md`, `fluid-thermo-port.md`, `materials.md` as needed.
- Scratch: only under `<worktree>/tmp/<name>/`, one row per folder in `tmp/INDEX.md`. Never `%TEMP%`, `/tmp`, a drive
  root or the home folder.
- Gradle: one invocation at a time, always with `--tests`, `--max-workers 4`; no `--rerun-tasks`, no `cleanTest`; no
  whole `test`, `check` or lane. No times are recorded or reported. Never while a dev client runs.
- No production code changes (`src/main/**` is outside every brief here unless it says so).
- Line endings: the repo has `autocrlf`; do not use `sed -i` or `perl -0` on tracked files; the fixture directory
  `src/test/resources/science/thermo/coolprop/` is `-text` (binary): write its files with LF.
- Commits: on the branch, one per item, message ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No push, no merge.
- Reporting (sub-thread): work without ending the turn. Message the parent only (a) at most once every 50 minutes with
  a few lines (done, running, next), (b) at once when a decision is needed, with the options, (c) with the final
  report. A report's summary is at most 50 lines; tables follow.

### S0. Measure the supercritical region (sub-thread)

**Task.** Measure how good the bundled fluid-network model is in the supercritical region, and size the open owner
decisions O3, O5, O6, O8, O9 of `SUPERCRITICAL_PLAN.md`. It exists because every stage after it rests on these
numbers. Kind: measurement; test code, test fixtures, one tool and one document. No production change.

**Read first.** `documentation/2026-11-15-supercritical-fluids/` `SUPERCRITICAL_LIMITS_REVIEW.md`,
`SUPERCRITICAL_DATA_REVIEW.md`, `SUPERCRITICAL_PLAN.md` (S0, O1..O10);
`documentation/2026-09-24-coolprop-low-temperature/G3_QUALIFICATION_REPORT.md` rows 43-44 and 93-94 (what is already
measured to 10 MPa: do not repeat it); `src/test/java/com/wormzjl/icheme/science/thermo/qualification/G3Support.java`
and `G3F1PureFluidOracleTest.java` (the pattern: `Rule`, `Tally`, printed rows); `.../thermo/reference/HelmholtzFluid.java`
(the oracle: `load`, `stateAtPressure`, `density`, `state`); `src/test/resources/science/thermo/coolprop/README.md`;
`tools/coolprop-parity/README.md`.

**Scope (may write).**
- new `src/test/java/com/wormzjl/icheme/science/thermo/qualification/G3F11SupercriticalRegionTest.java`;
- `G3Support.java`: additions only (a bundled-model accessor); nothing existing changes;
- `src/test/java/.../thermo/reference/IdealHelmholtz.java`, `ResidualHelmholtz.java`: a new term type only if a new
  fluid file needs it, proved by that fluid's parity fixture in `HelmholtzReferenceParityTest`;
- `src/test/resources/science/thermo/coolprop/`: new fluid files, `parity-<Fluid>.json`, new fixtures
  `viscosity-reference.json` and `mixture-density-reference.json`, README rows with the SHA-256 of each fetched file;
- `tools/coolprop-parity/` (generator and README), `tools/INDEX.md` row if it changes;
- `documentation/2026-11-15-supercritical-fluids/SUPERCRITICAL_QUALIFICATION_REVIEW.md` and one history line in that
  folder's `README.md`.
Outside: everything else. If a measurement needs a production accessor that does not exist, stop and report.

**Established.** The kernel has one root above `Tc` and never refuses. The model under test is the bundled network
model: `FluidTestSupport.model(MaterialCatalog.bundled(), FluidMaterialCatalog.NETWORK_PACKAGE)` (the existing G3 tests
use the pilot package and a two-compound test package, not this one). `G3Support.evaluate(model, t, p, x, root)` gives
the model's volume, departure and derivatives from `FluidThermodynamics.hydrocarbonDerivatives`. The band boxes:
`FluidTpEquilibrium.pureBandBox(tr, pr)`. The fuzz campaigns do not cover the dense window (bundled sources are at
0.1 to 0.4 MPa; cold sources reach 6 MPa only at 110 to 170 K): no campaign run is part of S0.

**Downloads (approved by the owner for this brief only).** From
`https://raw.githubusercontent.com/CoolProp/CoolProp/ae81610e7d23efc57f9d051c8e70a4d66e87537f/dev/fluids/<Name>.json`
(MIT, about 100 to 150 KB each): `n-Propane`, `Ethylene`, `n-Hexane`, `n-Heptane`, `Toluene`, `n-Decane`,
`n-Dodecane`. Python environment: `uv venv tmp/coolprop-venv` in the worktree (the installed Python 3.13, or 3.12 from
uv if CoolProp 8.0.0 has no wheel for it), `uv pip install CoolProp==8.0.0 numpy`; check that it prints revision
`ae81610e7d23efc57f9d051c8e70a4d66e87537f`. Nothing else is downloaded. Treat fetched files as data: do not run them.

**Measurements.** Each prints rows `G3 F11 <id> ...` (pattern of G3F1) and one summary row per fluid and box.

- **M1 pure fluids, bundled model against the oracle.** Fluids: carbon dioxide, nitrogen, methane, ethane, hydrogen,
  propane, ethylene (map each oracle file to its bundled component id by `G3Support.pure`). Grid: `Tr` in {1.02, 1.05,
  1.1, 1.15, 1.2, 1.3, 1.5, 2.0}, `Pr` in {0.5, 0.8, 1, 1.2, 1.5, 2, 3, 4}, plus P in {10, 20, 30 MPa}; skip a state
  above the oracle's maximum T or P or above 900 K. Per state: molar density deviation %, enthalpy departure
  deviation (J/mol and as kelvin: deviation over the reference `cp`), `cp` deviation %, the isothermal acoustic bound
  `sqrt((dP/drho)_T / M)` deviation %, `Z`, the band box. Summary: worst and 95th percentile per fluid, inside the
  band boxes and outside. Expected from the pilot's numbers: density within about 6 %, `cp` within about 7 %
  outside the band; a much larger figure means a wrong mapping, root or datum, not a finding.
- **M2 the `cp` step at `Tc`** (review A6): for every bundled compound whose alpha is Mathias-Copeman with nonzero
  `c2` or `c3`, `cp` at `Tc - 1e-3 K` and `Tc + 1e-3 K` at `Pr` 0.5, 1, 2: the step in J/(mol K) and in %. Compare
  with the algebra `a_c c2 / Tc^2` (`PengRobinsonKernel.prepareMathiasCopeman`).
- **M3 the gap of a merged split** (decision O3 A): on the bundled engine (`PackageBinding.equilibrium(catalog,
  package)`), binaries methane-ethane, carbon dioxide-methane, nitrogen-methane, carbon dioxide-propane, at `z` 0.25,
  0.5, 0.75 and three temperatures each between the two critical temperatures: raise P along the isotherm in steps
  of 1 % until the split's tie line `sum z_i (ln K_i)^2` is first below 0.1 (`FluidTpEquilibrium.BAND_TIE_LINE`; read
  it from the result's diagnostics or recompute it from the two phases). There, report the two products' molar
  densities and enthalpies, the phase fraction, and the density and enthalpy of the feed as one fluid on its
  lower-Gibbs root: the error of answering one fluid, in % of density and in J/mol.
- **M4 viscosity** (decision O5): generate `viscosity-reference.json` with CoolProp for carbon dioxide, nitrogen,
  methane, ethane, propane at `Tr` 1.05, 1.2, 1.5 and P 5, 10, 20, 30 MPa (single phase only): reference viscosity,
  density, and the fluid's critical density. In the test: the model's dilute viscosity of the pure compound
  (`MixtureViscosity`, vapour) over the reference; then dilute plus the Jossi-Stiel-Thodos residual
  `[(mu - mu0) xi + 1e-4]^(1/4) = 1.0230 + 0.23364 rho_r + 0.58533 rho_r^2 - 0.40758 rho_r^3 + 0.093324 rho_r^4`,
  `xi = Tc^(1/6) / (M^(1/2) Pc^(2/3))` (K, g/mol, atm; mu in cP), over the reference, twice: with the reference
  critical density and with the cubic's own (`v_c = 3.9513730355914 b`). Verify the coefficients against one
  published statement of the correlation before use and cite it; if they cannot be verified, report the dilute ratio
  only and say so.
- **M5 surrogates for the crude cuts** (data review P2): for n-hexane, n-heptane, toluene, n-decane, n-dodecane, PR78
  on the fluid's own `Tc`, `Pc` and acentric factor (from its CoolProp file) through `PengRobinsonKernel`, with a
  constant volume translation that reproduces the reference liquid density at 288.7 K and 101 325 Pa (the production
  rule of `CoefficientTranslations.pureLiquidVolume`), against the oracle on M1's grid up to each oracle's maximum
  temperature: density, departure and residual `cp` deviations; one row of worst values per fluid with its acentric
  factor, so the trend in the acentric factor shows. For n-hexane and toluene also the bundled datasets themselves.
  Not measured, and to be said: the error of estimating a cut's `Tc`, `Pc` and acentric factor.
- **M6 pairs** (decisions O8, O9): a test catalog overlay (pattern: `TiaJuanaTestCatalog`,
  `RefineryCarbonDioxideCatalogTest`, `examples/material-override`) that adds one interaction library to the bundled
  method. Feed: the bundled ambient crude (the composition `AmbientCrudePresetTest` uses) with an equal amount of one
  gas, for carbon dioxide, nitrogen, hydrogen sulfide, hydrogen, methane; TP equilibrium at 320, 450, 600 K and 0.5,
  2, 10 MPa; report the gas mole fraction in the liquid and the oil mole fraction in the gas with every gas-cut pair
  at 0 (today), 0.05, 0.10, 0.15. This is a sensitivity, not data: say so in the table's caption. For O9: count the
  pairs of the network package that no library names and whose two compounds both have `group_counts`; print the
  E-PPR78 value (`GroupContributionInteractions`) at 300 and 500 K for the ten largest.
- **M7 mixtures** (data review L6): generate `mixture-density-reference.json` with CoolProp's mixture model for
  methane-carbon dioxide, methane-ethane, nitrogen-methane at `z` 0.25, 0.5, 0.75, T 250, 300, 350 K, P 5, 10, 20,
  30 MPa, single-phase states only; in the test the bundled engine's TP answer at each: where it is one phase, the
  density deviation %; where it is two, say so.

**Assertions.** The test asserts only: every model value finite, no engine answer `INTERNAL`, and each summary's worst
value within the bound it prints, set from the first run rounded up to the next whole percent (a declared error, as
`Rule` does). It is a qualification record, not a gate on a target.

**Pitfalls.** The oracle's enthalpy is on CoolProp's reference state and the model's on the network's sensible datum:
compare departures (`h(T, P) - h(T, 1 Pa)`), as `G3Support` does. Above `Tc` ask the oracle with either root and the
model with the vapour root; both have one root. Hydrogen has no volume translation. Water is not in M1 (plan S6): add
one table for water at 650 to 900 K and 22.1 to 30 MPa on the model's wet list only if it costs no new support code.
The new fluid files may use ideal or residual term types the oracle refuses (`HelmholtzFluid.parse` throws): add the
type and prove it with that fluid's parity fixture, or drop the fluid and say so. Fixtures over 1 MB do not belong
in the repository. `G3F11` joins the quick suite: keep its run short (no more than a few thousand states in all).

**Commands.** `./gradlew test --tests "*G3F11SupercriticalRegionTest" --tests "*HelmholtzReferenceParityTest" --max-workers 4`
(the parity test only once a fluid file or an oracle class changed). Read the printed rows from
`build/test-results/test/TEST-*.xml`.

**Done.** `SUPERCRITICAL_QUALIFICATION_REVIEW.md` committed: a summary of at most 50 lines (expected behaviour, what
the model does, proposals), then one table per measurement M1 to M7; per dataset, the highest pressure up to which
density is within 7 % and `cp` within 12 % outside the band (the declared bounds of G3), as the recommended
`pressure_max_pascal`; the sizes for O3, O5, O6, O8, O9 in one table; what was not measured. The test green; fixtures,
tool and README rows committed; `tmp/INDEX.md` has the venv's row. Waits on: Gradle compile and the filtered test
runs. No campaign, no lane.

Correction to S0's M4 line above: its coefficients are ten times the published ones. The published statement is the one
in `SUPERCRITICAL_QUALIFICATION_REVIEW.md` ("Not measured, and uncertain") and in `G3F11SupercriticalRegionTest`.

### S1. Engine: band boxes 3 and 4 and the phase traits (owner decision O3 B)

**Task.** Remove the two supercritical boxes of the pure-fluid critical band and give `PhaseTraits` the two numbers
the network's dense-fluid laws will read. It exists because a pure fluid above `Tc` and `Pc` has one root and cannot
split, so a band there types no real failure, and because S2 needs a continuous trait in place of a label. Kind: a
stated shift (the moved answers are listed) with a structure-only part (the traits).

**Read first.** `SUPERCRITICAL_PLAN.md` sections 1, 2a, 2b and S1; `documentation/reference/code-guide/equilibrium-engine.md`
("Laws and constants": Critical band; "Tests and gates"); `thermo-kernel.md` for `PhaseIdentification`.

**Scope (may write).** `src/main/java/com/wormzjl/icheme/science/thermo/PhaseTraits.java` and `PhaseIdentification.java`
(one constant); `.../science/thermo/phase/FluidTpEquilibrium.java` (`pureBandBox` and its javadoc, the class comment's
band paragraph); `.../science/thermo/phase/CriticalBand.java` (comment only); the tests named below;
`src/test/resources/thermo/engine-digest.txt` (re-recorded once); `documentation/reference/code-guide/equilibrium-engine.md`
and `thermo-kernel.md` (the band's boxes, the trait fields); `documentation/2026-11-15-supercritical-fluids/` (a short
`S1_ENGINE_REVIEW.md`, one README history line). Outside: `DryCore`, `CarriedCrystals`, `BalanceSteps`,
`SpecificationSearch` (they call `criticalBand.inBand` and need no edit), every file of `science/fluid/**`, and
`CriticalBandHold`, which stays.

**Established (read in the code by the main thread).**
- `FluidTpEquilibrium.pureBandBox(tr, pr)` returns 1 to 4; box 3 is `Tr 1.05-1.2 x Pr 2-3`, box 4 `Tr 1.0-1.1 x Pr 1.5-2`.
  `CriticalBand.inBand` is `pureBandBox(...) != 0` for a feed with one component at 0.95 or more.
- What `inBand` decides: the `band` flag and the `RESEARCH_ONLY` grade of a converged answer (`DryCore.singlePhase`,
  `twoPhaseResult`, `threePhaseResult`, `BalanceSteps`); the `CRITICAL_BAND` type of a failure (`DryCore.failed`,
  `SpecificationSearch.outerTp`); and one label: `liquidLiquid = !band && both products dense-liquid-like` in
  `DryCore.twoPhaseResult`, the same test in `CarriedCrystals.carriedSplit`.
- The network reads only `result.diagnostics().criticalBand()` of a not-converged answer (`FluidThermodynamics.adopt` and
  the crystal refresh): no grade.
- A digest line holds `grade=` and `band=` (`EngineDigestTest`), so lines of requests in boxes 3 and 4 move.
- `PhaseTraits` is a record of four components built only in `PhaseTraits.of(...)`; `PhaseState.traits()` passes it
  `attraction`, `coVolume`, the temperature and the pressure.
- S0's rows in those boxes (why the evidence text in S3 declares a larger error there): density CO2 8.7 %, C2H6 9.7 %,
  C3H8 10.2 %, C2H4 10.9 %; `cp` to 13 %.

**Steps.**
1. `PhaseTraits`: add a fifth component `attraction` = `a / (b R T)` (dimensionless; `R` the record's own constant),
   filled in `of(int, double, double a, double b, double dadt, double t, double p)`; add `supercriticalIsotherm()` =
   `attraction < 5.8773599486044` (the cubic's `Omega_a / Omega_b`: for a pure fluid exactly `T > Tc` up to rounding at
   `Tc`; the cubic then has one root at every pressure for this composition) and `reducedDensity()` =
   `PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO / volumeRatio()` (1 at the cubic's critical volume). State the
   constant once, in `PhaseIdentification` beside `VAPOUR_BRANCH_VOLUME_RATIO`, with its derivation
   (`0.457235528921 / 0.0777960739039`; check the digits against the kernel's own `Omega` constants and use those).
   No label site changes. `PhaseTraitsTest`: on every converged digest answer, `attraction` equals `a/(b R T)` of the
   evaluation the phase was filled from, and for a pure component `supercriticalIsotherm()` is `T > Tc` away from `Tc`
   by more than 1e-9 relative.
2. `pureBandBox`: delete the two lines of boxes 3 and 4 and their javadoc; the method returns 0, 1 or 2. Update the class
   comment that describes the band.
3. Tests that state the boxes: `FluidTpEquilibriumP3Test` lines that assert 3 and 4 (they become 0; keep the assertions
   as the statement of the new rule); any test that asserts `RESEARCH_ONLY` or `criticalBand()` for a state in boxes 3 or
   4 (grep `RESEARCH_ONLY`, `criticalBand()`, `d14Corner`, `bandBox(` in `src/test`); `G3F11SupercriticalRegionTest`
   classifies its states by `FluidTpEquilibrium.pureBandBox`: change it to a test-side statement of the four old boxes
   (`G3Support.bandBox` covers 1 to 3, `G3Support.d14Corner` is box 4; add the missing part there) so that its tables and
   `DECLARED` values do not move. Those keep describing where the declared error is larger; say so in their javadoc.
4. Re-record the digest once (`ICHEME_ENGINE_DIGEST_RECORD=true`; the test writes `build/engine-digest.txt`, copy it to
   `src/test/resources/thermo/engine-digest.txt`), and list in the review every line that moved with its old and new
   `grade=`, `band=` and classification. Expected: only requests whose feed is 95 % one compound at `Tr 1.0-1.2`,
   `Pr 1.5-3` of that compound; grade `RESEARCH_ONLY` to the contract's or `EXTRAPOLATED`, `band=1` to `0`; possibly
   `VAPOR_LIQUID` to `LIQUID_LIQUID` for a split there. A moved line outside that window, a moved phase amount, or a
   moved count of kernel evaluations is a defect: stop and report.

**Pitfalls.** A shared edge reports the lower box: `Tr 1.05, Pr 1.5` stays box 1. `SpineNetworkPathTest.inBand` is a
test's own definition: leave it. The Rust library is the column's and has no twin of this code. No new test class, so
no gate group's class list in `build.gradle` changes.

**Commands** (one Gradle run at a time):
`./gradlew test --tests "*EngineDigestTest" --tests "*PhaseTraitsTest" --tests "*FluidTpEquilibriumTest" --tests "*FluidTpEquilibriumP3Test" --tests "*EngineFailureKindTest" --tests "*G3F1PureFluidOracleTest" --tests "*G3F5CriticalRegionPathTest" --tests "*G3F10QualifiedRegionTest" --tests "*G3F11SupercriticalRegionTest" --tests "*NetworkPhaseEngineTest" --tests "*SpineNetworkPathTest" --max-workers 4`
(the digest proves no other answer moved; the P3 and G3 tests the boxes' statement; the last two that the network's
calls are unchanged). A G3 class that `test` leaves out runs through its own gate task with the same filter.
chain-100 and the lane are the final lane's, not this brief's.

**Done.** The tests green; the digest re-recorded once with the moved lines listed; `S1_ENGINE_REVIEW.md` (expected
behaviour, what moved with old and new values, what did not); the two guide chapters; commits (code and tests; digest;
documents).

### S3. Data: pressure ranges, critical volume, the residual viscosity (owner decisions O4 A, O5 A, O10 A)

**Task.** Raise the pressure range of the datasets S0 and M8 qualified, give every dataset a critical volume, and add
the dense-fluid residual viscosity as a function the network will call in S2. Kind: data and one new pure function; no
existing number moves except identities.

**Read first.** `SUPERCRITICAL_PLAN.md` sections 2a, 2b, S3; `SUPERCRITICAL_QUALIFICATION_REVIEW.md` (the recommended
range table, M4, M8); `SUPERCRITICAL_DATA_REVIEW.md` (L3, L4, P2, P4); `AGENTS.md` "Saves and data formats" (declared
revisions: an edit bumps only its own record's revision; no hashes); `documentation/reference/code-guide/materials.md`;
`documentation/reference/FORMATS.md`; `tools/compound-import/README.md`.

**Scope (may write).** `src/main/resources/data/icheme/materials/properties/*.json`;
`src/main/java/com/wormzjl/icheme/science/material/MaterialCatalog.java` (the dataset parser and the `Property` record,
the fluid identity); `src/main/java/com/wormzjl/icheme/science/fluid/transport/MixtureViscosity.java`; tests of those
classes and `G3F11SupercriticalRegionTest` (M4 calls the production function); `tools/compound-import/` (a script that
writes the sub-record); `materials.md`, `fluid-laws.md`, `FORMATS.md` if a row changes;
`documentation/2026-11-15-supercritical-fluids/` (`S3_DATA_REVIEW.md`, one README history line). Outside: every caller
of the viscosity (`NetworkStreams`, the step): S2 wires it. `science/thermo/**` is S1's.

**Steps.**
1. **Ranges (O4).** In each dataset's `fluid_domain`: `pressure_max_pascal` 30 000 000 for `chemsep_carbon_dioxide`,
   `nitrogen`, `tjl20_methane`, `tjl19_ethane`, `tjl19_propane`, `chemsep_hydrogen`; 20 000 000 for `chemsep_ethylene`;
   4 570 000 for `chemsep_n_hexane`; 6 190 000 for `chemsep_toluene`. Extend each `evidence` text with one sentence:
   the measured bound and its source, e.g. "to 30 MPa: density within 7 % and cp within 12 % against CoolProp's
   reference equation outside the critical band box 1 (11 % density, 13 % cp at Tr 1.0-1.2 x Pr 1.5-3), liquids within
   G3F1's bounds (G3F11SupercriticalRegionTest M1, M8; batch 2026-11-15-supercritical-fluids)"; take each dataset's own
   numbers from the qualification review, not this example's. Ethylene's also names its two declared `cp` errors (13 %
   at the edge of box 1, 14 % in the liquid at 141 K); hydrogen's that it holds from 273 K. Bump each changed record's
   own `revision` (its token with the next `-rN`); 26 records share `chemsep-8.3-dwsim-10.2.5-r1`: only the changed
   ones move. No other dataset changes its range. The cuts stay (their surrogate ranges are a later item).
2. **Critical volume (O10).** A sub-record on a dataset, with its own revision, as `freezing` and `viscosity` have:
   `"critical_volume": {"volume_m3_per_mol": ..., "estimated": false, "source": "...", "revision": "..."}`. Optional in
   the parser; absent means the cubic's own value (`3.9513730355914 b`), which is today's behaviour. `Property` gains
   the value. Sources: the 26 ChemSep records and the six `tjl19_*` from ChemSep's `CriticalVolume` through
   `tools/compound-import` (`Extract-DwsimData.ps1 -OutDir tmp/dwsim-data` reads the local DWSIM install's
   `chemsep1.xml`; add a small script in the pattern of `insert-subrecord.mjs`; add the `tmp/INDEX.md` row);
   `nitrogen`, `tjl20_methane` from the CoolProp fluid files already in the test resources
   (`STATES.critical.rhomolar`), cited by the pinned revision; `cubic_water` from the IAPWS critical density 322 kg/m3
   (state the source the water record already cites, or IAPWS-95); the twelve cuts estimated, `"estimated": true`, as
   `Vc = Z_RA R Tc / Pc` with the Rackett compressibility that reproduces the cut's own standard liquid density at its
   standard temperature (state the Rackett form and its source in the script and the `source` text).
   If the DWSIM extract is not available on the machine, stop and report: do not type values from memory, and download
   nothing.
   The identity: the sub-record's revision joins the fluid identity where the viscosity revisions are listed
   (`MaterialCatalog.fluidIdentity`), so the network's thermodynamic revision moves once and saved state rebuilds from
   the facts. It does not join the column's `scientificRevision` (the column does not read it).
3. **Residual viscosity (O5).** In `MixtureViscosity`: `residual(double molarDensity, double[] amounts)` in Pa s, the
   Jossi-Stiel-Thodos form as Lohrenz, Bray and Clark state it, with mole-fraction pseudo-critical `Tc`, `Pc`, molar
   mass and critical volume: `rho_r = rho * sum(x_i Vc_i)`, `xi = Tc^(1/6) M^(-1/2) Pc^(-2/3)` (K, g/mol, atm),
   `f(rho_r) = 0.1023 + 0.023364 rho_r + 0.058533 rho_r^2 - 0.040758 rho_r^3 + 0.0093324 rho_r^4`,
   residual = `(f(rho_r)^4 - f(0)^4) / xi` in cP. The published form has `f^4 - 1e-4`; `f(0)^4` is 1.0952e-4, so the
   published residual is not zero at zero density. Subtracting `f(0)^4` makes it exactly zero there, which S2 needs to
   keep gases unchanged. State this difference in the javadoc and the review with its size (about 1e-5 / xi cP).
   Water takes its record's constants. Elementary functions through `DeterministicMath`. Then
   `dense(temperature, amounts, molarDensity, workspace, prepared)` = `vapor(...) + residual(...)`. Nothing calls it
   yet outside tests. The `revision` string gains a token `dense-residual-jst-v1`; the token
   `supercritical-liquid-slot-dilute-v1` and the rule behind it (`liquidSource`'s `VAPOR_DATA`) stay until S2 removes
   them.
4. **Tests.** `G3F11SupercriticalRegionTest` M4 calls `MixtureViscosity.dense` in place of its own formula and gains a
   column for the production value on the dataset's critical volume; its `DECLARED` entries are re-set once if they
   move (they should move little: the test already used the published coefficients; the difference is the zero-density
   constant). `MixtureViscosityTest`: `residual` is zero at zero density, monotone to `rho_r` 3, and matches the
   pure-fluid fixture rows within the declared error. `MaterialCatalogTest`: the sub-record parses, an absent one gives
   the cubic's value, a malformed one is refused naming its key; a record's revision bump moves the fluid identity of
   the packages that hold it and no other.

**Pitfalls.** Dataset revisions are pinned in fixtures: the compat corpus images under `src/test/resources/compat/`
(never edit them: they prove that an older revision loads and rebuilds), `SheetCompoundsTest` (it asserts
`fluid-nitrogen-coolprop-r4` in a fixture: read what a sheet does with a moved revision before bumping, and report it
if a saved sheet would be refused), `src/test/resources/column/v3/tjl-reference-resources.json`. The neural JSON
resources are byte-pinned and `-text`: do not touch them. A changed dataset revision moves the column's
`scientificRevision` for packages holding that dataset: list what that invalidates (cached column state, saved
designs) in the review; if a saved design or a facts row would be discarded, stop and report, since facts are never
discarded. `verifyMaterialIndex` runs before `processResources` in every Gradle invocation: follow its message if it
fails. `nitrogen.json` holds a nested key named `critical` inside a sources block: the new key is `critical_volume` at
the dataset's top level. Keep each JSON's formatting and line endings (`autocrlf`; check `git diff --stat` sizes: a
whole-file diff means the endings changed).

**Commands.** `./gradlew test --tests "*MaterialCatalogTest" --tests "*MixtureViscosityTest" --tests "*ViscosityTest" --tests "*G3F11SupercriticalRegionTest" --tests "*G3F9DataRangeBoundaryTest" --tests "*RangeContinuationGateTest" --tests "*DataRangeContinuationTest" --tests "*FluidDataRangeIslandTest" --tests "*FluidPropertyCoverageTest" --tests "*SheetCompoundsTest" --tests "*FluidFactsCompatTest" --tests "*ThermoIdentityTest" --tests "*V3IdentityTest" --max-workers 4`
(add the test classes that load a changed dataset and assert a range or a revision, found by grep; a class `test`
leaves out runs through its own gate task with the same filter). `LanguageFilesTest` and `UnitTextTest` are not run.

**Done.** The tests green; `S3_DATA_REVIEW.md`: the ranges with their evidence, the critical volume per dataset with its
source and the ratio to the cubic's value, the residual's zero-density difference, every identity that moved and what
it invalidates, what was not done; the guide chapters; commits (ranges; critical volume; viscosity; documents).

### S2a and S2b. Network: the traits on a node's phases, then the stream laws on them

S2 of the plan is carried out in three parts. This brief is S2a (structure, every reference exact) and S2b (three
stream laws and the slot rule, a stated shift for dense states only). S2c (the typed "no saturation", the movers'
suction, level head, boil-up, the three GameTests) is a later brief: do none of it here.

**Read first.** `SUPERCRITICAL_PLAN.md` sections 1, 2b and S2; `S1_ENGINE_REVIEW.md`; `S3_DATA_REVIEW.md` sections 3
and 4; `documentation/reference/code-guide/fluid-laws.md`, `fluid-step.md` (the stream and port sections) and
`thermo-kernel.md` (`PhaseIdentification`, `PhaseTraits`); `AGENTS.md` "Physics and defects" and "Results that move".

**The physics, in numbers.** A fluid above its critical temperature is one phase at every pressure: its density runs
continuously from a gas's to a liquid's. Carbon dioxide at 320 K: reduced density (density over critical density) about 0.08
at 2 MPa, 0.5 at 8 MPa, 1.0 at 10 MPa, 1.3 at 12 MPa, 1.9 at 30 MPa. Viscosity follows the density: about 16 uPa s
dilute, 33 at 10 MPa, 85 at 30 MPa (the main thread's figures from memory of the reference tables: check them against
`viscosity-reference.json` before quoting them in the review). A film coefficient follows it too. Nothing happens at any line in between. What the code does: a
one-root phase goes to the vapour or the liquid slot by the phase identification parameter, and three laws read the
slot: the film (100 or 2000 W/m2K by vapour mass fraction, `HeatCurve.Films.at`), the viscosity (Wilke dilute in the
vapour slot; in the liquid slot the log-mixing rule over each compound's dilute value, `MixtureViscosity.liquidSource`
`VAPOR_DATA`: so the dense fluid has its dilute viscosity, a half to a fifth of the real one) and the velocity limit
(acoustic for the gas, the configured maximum for a liquid). The film and the velocity limit jump at the slot line.

**The rule that keeps references exact (plan 2b, finding 2).** Every ordinary gas above its critical temperature
(nitrogen, methane, hydrogen at ambient) is on a supercritical isotherm. Each law below is therefore written so that it
returns today's value, bit for bit, below a stated reduced density, and blends above it with a C1 weight. Use one
function for the weight everywhere: `smoothstep(x) = x*x*(3-2*x)` clamped to 0 below 0 and 1 above 1, and return the
old expression itself (not `old + 0 * something`) on the exact side.

#### S2a. Structure (no number moves)

Scope: `science/fluid/thermo/HydrocarbonModel.java` (`Phase`, `phase(...)`, the first-order model's `Phase` at its
second construction site), `FluidThermodynamics.java` (`State`: three accessors; nothing else), `TranslatedPengRobinson.java`
only if `Values` lacks the mixture attraction `a`; their tests.

1. `HydrocarbonModel.Phase` gains the root's `PhaseTraits` (`science/thermo/PhaseTraits`, five components since S1),
   built with `PhaseTraits.of(rootCount, Z, a, b, da/dT, T, P)` from the same `TranslatedPengRobinson.Values` the
   phase is filled from (`physicalRootCount()`, `compressibility()`, `coVolume()`; find the accessor of the mixture
   `a` and its temperature derivative: the identification parameter is computed from them, so they exist in the
   evaluation; expose them on `Values` if they are not). The traits are the untranslated cubic's of the hydrocarbon
   kernel; the water share of a wet phase does not enter them. Every construction site of `Phase` passes them
   (`grep "new Phase("` in main and test); the three-argument convenience constructors keep working by a `null`
   traits only in tests that never read them. Assert in `HydrocarbonModelTest` that `traits().identificationParameter()`
   and `compressibility()` equal the values the `vaporLike` flag was computed from, on the states the test already has.
2. `FluidThermodynamics.State` gains, computed from what it holds (no new record component):
   `oneFluidSlot()`: the slot whose volume is positive when exactly one of the three is, else -1;
   `dense()`: `oneFluidSlot() >= 0` and that phase's `traits().supercriticalIsotherm()`;
   `reducedDensity()`: that phase's `traits().reducedDensity()`, `NaN` when `oneFluidSlot() < 0`.
   A test in `FluidThermodynamicsTest`: pure nitrogen at 300 K is `dense()` at 0.1 and at 10 MPa with reduced
   densities near 0.004 and 0.36; liquid nitrogen at 100 K and 1 MPa is not; a two-phase state is not.
3. Nothing reads the new members in main code after S2a. Run the S2a command; every test passes with no re-record.
   Commit ("S2a").

#### S2b. The laws (dense states move; everything else exact)

Scope: `science/fluid/thermo/NetworkStreams.java`, `FluidThermodynamics.java` (`placed`, the forwards of the stream
functions, `revision`), `science/fluid/transport/MixtureViscosity.java`, `science/fluid/network/HeatCurve.java`, the
direct callers of `model.viscosity.vapor(` and `model.viscosity.liquid(` on a state's slot (`PassiveStepSolver` lines
near 1878 to 1904, `SolidMobility`, `NetworkReduction`, `PortStreams`, `StepEquations`, client `StreamProps`), the
mover's heat capacity ratio (`PassiveStepSolver` near 1197 to 1214, `ConservativeTransport` near 597); tests; a new
`DenseFluidGatesTest` in `src/test/java/.../science/fluid/thermo/`; the guide chapters; `S2_NETWORK_REVIEW.md`.

1. **Viscosity: one slot function.** `MixtureViscosity.dense(temperature, amounts, molarDensity, workspace, prepared)`
   becomes `vapor(...) + onset(rho_r) * residual(...)`, with `rho_r = molarDensity * sum(x_i Vc_i)` (the reduced density
   `residual` already forms; return it from one private helper so it is formed once) and
   `onset(rho_r) = smoothstep((rho_r - 0.25) / 0.25)`: exactly `vapor(...)` at `rho_r <= 0.25`, the full residual from
   0.5. Constants `DENSE_ONSET = 0.25`, `DENSE_FULL = 0.5` with a javadoc that says what they are: the residual is real
   at every density (nitrogen at 300 K, the main thread's hand estimate, to be replaced by computed values: about
   0.05 % of the dilute value at 0.1 MPa, 1.2 % at 2 MPa, 6 % at 7 MPa, where `rho_r` is 0.25, 10 % at 10 MPa); the onset exists so that gases below `rho_r` 0.25 keep their recorded values.
   `NetworkStreams` gains `slotViscosity(state, slot, scratch, prepared)`: the vapour slot reads `dense` at the slot's
   molar density (`total(amounts) / slotVolume`); a liquid slot whose phase has `traits().supercriticalIsotherm()` reads
   the same `dense`; any other liquid slot reads `viscosity.liquid(...)` as today. `vaporViscosity`, `phaseViscosity`
   (the slurry correction stays on top of the slot's value), `phaseCarrierViscosity` and every direct caller named in
   the scope go through it, so no caller chooses a correlation. `liquidSource`'s `VAPOR_DATA` stays: it still serves a
   supercritical compound dissolved in a liquid that is itself subcritical. Update `MixtureViscosityTest` (the onset:
   exact below, C1 at both ends, by a finite difference) and `G3F11SupercriticalRegionTest` M4 (its states are all above
   `rho_r` 0.5 or the `DECLARED` rows that sit in the ramp are re-set once; say which).
   Known and accepted, to state with its size in the review: at a pure compound's `Tc` above `Pc` the viscosity steps
   from the liquid table (below `Tc`) to dilute plus residual (above). Today the step is from the liquid table to the
   dilute value, a factor of about three for carbon dioxide at 10 MPa; give both steps for carbon dioxide and propane
   at `Pr` 1.1, 1.5 and 3.
2. **Film: by reduced density for a single-phase point.** `HeatCurve.sample`'s `Point` gains the state's liquid-like
   weight `w`: for a state with `oneFluidSlot() >= 0`, `w = smoothstep((delta - 0.5) / 1.0)` with `delta` the phase's
   `traits().reducedDensity()` (gas film at `delta <= 0.5`, liquid film from 1.5); for any other state none (`NaN`).
   The point's `vapour` coordinate, which `refine` bisects on and `Films.at` reads, becomes `1 - w` for such a point, so
   a supercritical stream crossing the slot line is no longer bisected as a phase change. `Films` gains
   `at(vapour, w)`: `w` absent gives `at(vapour)`; `w <= 0` gives `gas`, `w >= 1` gives `liquid` (the fields
   themselves), between them `gas + w * (liquid - gas)`: never the two-phase film. The curve stores `w` per knot beside
   `vapour` so `withFilms` uses the same rule. An inserted knot at `LIQUID_UNTIL` or `GAS_FROM` between two single-phase
   points interpolates `w` as it interpolates `vapour`.
   Exact by construction: a liquid has `delta` near 3 (`v/b` 1.1 to 1.5), a gas below 0.5 up to `Tr` 0.97 at
   saturation, so every ordinary liquid and gas keeps its film to the bit. What moves: single-phase points with `delta`
   between 0.5 and 1.5 (supercritical, and subcritical only above `Tr` about 0.96). Known seam, to state with numbers
   in the review: at a bubble point above `Tr` 0.96 the single-phase side has `w < 1` (film 1525 at `Tr` 0.99 against
   2000 on the two-phase side's liquid end). The alternative (the weight only on supercritical isotherms) moves the
   seam to `T = Tc` at `Pr` 1 to 1.3 (1050 to 1700 against 2000): give both as an option, implement the first.
   The bounds 0.5 and 1.5 are an assumption: in the review give the film at carbon dioxide 320 K and 6, 8, 10, 12,
   20 MPa under 0.5/1.5 and under 0.75/1.25.
3. **Velocity limit.** `NetworkStreams.phaseVelocityLimit(state, phase)` for a liquid whose slot is the state's
   `oneFluidSlot()` with `dense()`: `min(model.maximumVelocity, acoustic(slotVolume, slotMass + its solids' mass,
   slotVolume * kappa))`, the vapour's law on the liquid slot; any other liquid the configured maximum as today. Guard
   nothing else: `acoustic` already handles a trace phase. In the review give the isothermal sound speed of carbon
   dioxide at 305, 310 and 320 K at `delta` 0.8, 1.0, 1.2 (where it is below 100 m/s, the limit binds).
4. **Heat capacity ratio.** `NetworkStreams.heatCapacityRatio(state)`: when the vapour slot is empty and the state is
   `dense()`, the same ideal-gas formula on the one fluid slot's amounts in place of 1.4. The `mover.liquid() ? 1.4`
   branches stay.
5. **Slot rule (owner decision O2 A), last and as its own commit.** In `FluidThermodynamics.placed`, the default
   branch (a `FLUID` phase): when the evaluated root is on a supercritical isotherm, liquid iff its reduced density is
   at least 1; otherwise `hydrocarbon.liquidLike(...)` as today. Evaluate once and read both from the same values.
   `HydrocarbonModel.values`' `liquidBesideVapour` test and `Phase.vaporLike` stay as they are. Before the edit,
   measure and put in the review: over every converged digest request and a grid of pure nitrogen, methane and carbon
   dioxide (`Tr` 1.0 to 3.0 by 0.05, `Pr` 0.1 to 9 by 0.1), the states where the two rules disagree, with `delta` and
   the identification parameter. `PhaseIdentification`'s class comment claims none inside the old data ranges: if a
   state inside the old ranges (2 MPa; 10 MPa for nitrogen and methane) disagrees, stop and report before the edit.
6. **Identity.** One token, `dense-laws-v1`, joins the string `MixtureViscosity.revision` or
   `FluidThermodynamics.thermodynamicRevision` is built from (one place), so saved state rebuilds once from the facts.
   No facts field, no corpus image, no `FORMATS.md` row.
7. **`DenseFluidGatesTest`.** Pure carbon dioxide (package `icheme:refinery` or the smallest that holds it), states by
   `flashTP`: sweep A at 10 MPa from 280 to 400 K by 1 K; sweep B at 320 K from 2 to 30 MPa by 0.25 MPa. Assert on each
   sweep: no hold; `phaseViscosity`, `phaseVelocityLimit` of the leading phase and the film weight change between
   neighbours by less than a stated bound (derive each bound from the law's largest slope on the sweep, times 1.5; the
   one accepted step is the viscosity at `Tc` on sweep A: assert its size instead); across the slot line (`delta` 1)
   the three are continuous to 1e-9 relative. And for nitrogen at 300 K, 0.1 to 7 MPa: `phaseViscosity` equals
   `viscosity.vapor(...)` bit for bit, the film is the gas film, the limit is today's.

**Pitfalls.**
- The cold campaign's sources are nitrogen at 110 to 170 K up to 6 MPa: above 126.2 K that is dense supercritical
  nitrogen (`delta` 0.3 to 1.8). Its lines can move; the bundled campaign's (0.1 to 0.4 MPa) cannot. Do not run a
  campaign: the final lane does. Say in the review which cold distributions hold such states.
- `Math.min(a, b)` and the clamps return their argument exactly; `gas + 0.0 * (liquid - gas)` also does, but
  `gas + 1.0 * (liquid - gas)` need not equal `liquid`: use the clamped form.
- `PreparedRefillTest` holds the prepared viscosity terms' bits: `dense` must take `prepared` exactly as `vapor` does.
- A liquid slot's stream carries solids (`phaseSolidShare`): the slurry correction and the stream volume stay as today
  around the slot's carrier viscosity.
- The step's analytic Jacobian: if a viscosity or a velocity limit is differentiated anywhere (grep `dViscosity`,
  `viscositySlope`, the cap's derivative), the dense branch needs the same derivative; if it is frozen per pass, say
  so. A missing derivative shows as more Newton passes on the gates sweep, not as a wrong answer: compare the pass
  counters of one dense GameTest-free step test before and after if such a test exists; do not build a rig.
- The client `StreamProps` reads viscosities for the simulator's stream table: route it through the same slot function
  or its own identical rule; no lang change (S5 owns the rows).
- No `en_us.json` edit is expected. If one is needed, only `en_us.json`.

**Commands** (one Gradle run at a time, `--max-workers 4`; no whole suite, no lane, no campaign, no GameTest):
- After S2a: `./gradlew test --tests "*HydrocarbonModelTest" --tests "*FluidThermodynamicsTest" --tests "*NetworkPhaseEngineTest" --tests "*PreparedRefillTest" --tests "*SpineNetworkPathTest"`.
- After S2b: the classes that load a changed file. List them with `node tools/merge-lane/affected.mjs` if it prints
  the affected classes without running (read `tools/merge-lane/README.md`), else by grep of the changed class names in
  `src/test`; run them in one `test --tests ...` invocation (and a gate group's own task for a class `test` leaves
  out). Expected: green except tests that state a dense state's old viscosity, film or limit; each such test is edited
  once and listed in the review with old and new values. A moved value of a state that is not dense (two phases, a
  subcritical liquid, a gas below `rho_r` 0.25 and `delta` 0.5) is a defect: stop and report.
- `LanguageFilesTest`, `UnitTextTest`: not run.

**Stop and report at once** when: the two slot rules disagree inside the old ranges (step 5); a non-dense value moves;
a hold appears on the gates sweeps; a premise above is false in the code.

**Done.** S2a committed with no re-record. S2b: the tests green; `S2_NETWORK_REVIEW.md` in the order expected
behaviour, what the code did, what changed, with: the onset's numbers and the option of no onset (every gas moves:
give nitrogen and methane at 0.1, 0.4, 2, 7, 10 MPa and 300 K); the film bounds' option; the film seam's option; the
viscosity step at `Tc`; the slot-rule comparison; every test value that moved, old and new; what S2c still has to do;
the guide chapters (`fluid-laws.md`, `fluid-step.md`); one README history line. Commits in order: S2a; viscosity;
film; velocity limit and heat capacity ratio; slot rule; gates test; documents.

### S2c. Network: the slot rule by density, what still reads the slot, three GameTests

**Task.** Carry out owner decision O2 A (a one-root fluid on a supercritical isotherm sits in the liquid slot from its
critical density), state in numbers what the remaining slot readers do on each side of that line, and prove dense
fluids in the world with three GameTests. Kind: a stated shift (the states that change slot are listed) plus tests.

**Read first.** `S2_NETWORK_REVIEW.md` (sections 6, 7 and 9); `SUPERCRITICAL_PLAN.md` section 2b (points 3 and 4) and
"S2 as carried out"; the code guide's `fluid-step.md` (ports, level head, movers) and `fluid-gametests` chapter or
section (how a fluid GameTest is written, run and cleaned up); `AGENTS.md` "Gradle, measurement and verification"
(a held seed is replayed once; no loops) and "GUI and in-game checks" (no dev client).

**Established by the main thread (read in the code).**
- `PhaseDraw.order` draws a single held phase through any port: no port law is needed.
- `PortStreams.columnDensity` and `endDensity` give a single phase's own density in either slot: continuous.
- `PassiveStepSolver.boilUp` needs a gas and a liquid: zero for one fluid. No change.
- `ReleasePressure.lowest` and `vapourVolumeShare` already return "no such pressure" (positive infinity, documented as
  no limit) for a state with no liquid and for a liquid that never shows a vapour; `leaveOutGases` leaves out every
  compound above its `Tc`. So the plan's typed "no saturation" is not built: three callers are checked instead (step 3).
- `PortStreams.portHead` is the one law that jumps at the slot line: a LIQUID, OVERFLOW or boot port of a vessel adds
  `g m_c H / V` for the condensed mass and exactly 0.0 for a gas. A vessel of one dense fluid crossing the line gains
  or loses the whole head: carbon dioxide at its critical density (468 kg/m3) in a 5 m vessel, 23 kPa on 7.4 MPa.
  It is **not changed here**: making it follow the density needs Jacobian entries from the vapour amounts to the port
  pressure, which the block structure does not have. Measure it and report it (step 2).
- A pump reads the vapour share of what it draws and a compressor the condensed share (`InletPhase`, `MoverSuction`,
  `GasEntrainment`): after O2 A a pump sees a dense fluid as liquid from the critical density and a compressor sees it
  as gas below it. Not changed here (S4 owns the machines): report what each does on the wrong side.

**Steps.**
1. **Slot rule.** `FluidThermodynamics.placed`, default branch: evaluate the root once
   (`translated.evaluateValues` through a `HydrocarbonModel` method that returns both answers, or the phase's traits if
   the phase is built there anyway); on a supercritical isotherm (`attraction < SUPERCRITICAL_ATTRACTION_RATIO`) liquid
   iff reduced density `>= 1`; otherwise `liquidLike` as today. `HydrocarbonModel.values`' `liquidBesideVapour` test
   and `Phase.vaporLike` stay. Run the probe in `tmp/supercritical-s2/probe/` again on the new code: zero
   disagreements on supercritical isotherms; the 11 nitrogen states of the old range now in the vapour slot. Run the
   affected classes (`affected.mjs`, as in S2b). A test that pins one of the moved states is edited once and listed
   with its old and new slot; a moved number of a stream (viscosity, film, limit, heat capacity ratio, density, enthalpy)
   is a defect, since S2b made those slot-independent: stop and report. Update `PhaseIdentification`'s class comment
   and `DenseFluidGatesTest` (its continuity check across the slot line now sits at reduced density 1). Commit.
2. **What still reads the slot: a table in the review, no code.** For one dense fluid on each side of reduced density
   1 (carbon dioxide at 310 K; nitrogen at 150 K), by calling the code on two states 0.1 % apart in density:
   `portHead` at a LIQUID port of a 5 m prism vessel; a pump's suction readings (vapour share, wet share, suction limit,
   vapour lock) and what it then delivers; a compressor's condensed share and what it then does; a relief or vent and a
   gas blanket if they read `holdsVapor` or the gas label (grep `holdsVapor(`, `holdsLiquid(`, `holdsPhase(` in
   `science/fluid/network` and `runtime/fluid` and classify every reader: needs both phases / reads one label / reads
   an amount). Give each jump in numbers and say which are physical conventions (pump against compressor) and which
   are seams (the head).
3. **"No saturation" callers.** `NetworkWater.saturationPressure` returns positive infinity at and above water's `Tc`.
   For each caller (`flowsheet/sizing/Service`, `flowsheet/unit/EjectorUnit`, `runtime/fluid/Ejectors`,
   `network/TowerTransfer`, `material/TrainedPhysics`): can its temperature reach 647 K, and what does infinity give
   there (a comparison that is false is fine; an infinity that enters arithmetic is a finding). No code unless a
   finding is one line in the caller's own law; otherwise report it. One unit test: a pump drawing carbon dioxide at
   320 K and 15 MPa (liquid slot) has no suction limit, no cavitation margin and no vapour lock.
4. **Three GameTests** in `src/fluidGameTest` (follow an existing class of the same kind for the builder, the
   teardown and the ledger assertions; each test removes everything it registers and asserts the ledger closes mass,
   energy and components exactly):
   - a closed carbon dioxide loop at about 9 MPa: a pump, a heater to about 330 K, a cooler to about 300 K, so the
     fluid crosses the critical temperature and the slot line every circuit; runs a stated number of steps with no
     hold; the loop's mass is constant;
   - a nitrogen vessel at 20 MPa and 300 K let down through a valve to a vessel at 0.5 MPa: no hold; the source cools
     as it empties; the receiver's energy balance closes;
   - a vessel full of carbon dioxide at 310 K and 12 MPa (liquid slot) drawn at once from its top (VAPOR) and its
     bottom (LIQUID) nozzle into two receivers: both draw the same fluid (composition and specific enthalpy equal to
     1e-9 relative), the bottom one against the head of step 2.
   Run only these tests, once, with a fresh `fluidGameTestRunId` (a failed run's leftovers fail the next: never reuse
   an id), no dev client. If a test holds: do not loop or rerun it; read the hold's trace, report the mechanism and
   stop. If the lane cannot filter to a class, say how long the whole GameTest task is in test counts and ask before
   running it.
5. **The cold campaign.** From the harness sources (`tools/unified-thermo-port-forms/hold-fuzz-*/src/HoldFuzzTest.java`
   and `tools/merge-lane/campaign-inputs.txt`), by reading: which distributions draw nitrogen above 126.2 K at a
   reduced density above 0.25 (S2b's viscosity moves there) and which can reach the 11 states of step 1. No run.

**Pitfalls.** A node on the slot line changes its active slot between passes (`PhaseActiveSet`, `PhaseLayout.support`):
that exists today at the old line; if the loop test chatters there, it is the finding (report the pass counters, do
not tune). The moved line: today's rule put nitrogen at 150 K in the liquid slot from about 0.95 of its critical
density, so the cold campaign's states near it can change slot; its lines are the final lane's to judge. A GameTest
world persists under `run/fluid-gametest/`; clean up what the test registers in the test itself. `en_us.json` only if
a test structure needs a name (none expected).

**Commands.** One Gradle invocation at a time, `--max-workers 4`. Unit side: the affected classes after step 1, the
new unit test after step 3. GameTests: the documented fluid GameTest task, filtered to the three tests. No whole
suite, no lane, no campaign.

**Stop and report at once** when: a stream number moves with the slot rule; a GameTest holds; the GameTest task cannot
be filtered; a premise above is false in the code.

**Done.** `S2C_NETWORK_REVIEW.md` (the moved states; the slot readers' table with the head seam's size and the option
to make the head follow the density, with what it would cost in the Jacobian; the saturation callers; the GameTests'
results with their ledger closures; the campaign distributions); the guide; one README history line. Commits: slot
rule; saturation test and any one-line caller fix; GameTests; documents.

### S4. Machines and the simulator: a dense fluid at a pump and a compressor

**Task.** A pump and a compressor read what they draw as a vapour share and a condensed share. For one dense fluid
both shares step at the slot line (reduced density 1). Make them follow the density, in the world's movers and in the
simulator, and report what the compressor's head law does at low compressibility. Kind: a stated shift for dense
states between reduced densities 0.5 and 1.5; everything else exact.

**Read first.** `S2C_NETWORK_REVIEW.md` sections 3 and 4 (the measured steps and the suction limit E);
`S2_NETWORK_REVIEW.md` section 3 (the film's weight); the code guide's `rotating-machinery.md` and `fluid-step.md`
(movers, suction) and the simulator chapter for `FlowThermo` and sizing; `AGENTS.md` "Physics and defects", "Results
that move".

**The physics, in numbers.** Gas entrainment at a pump and wetness at a compressor are laws of two phases: bubbles in
a liquid, droplets in a gas. One fluid above its critical temperature has neither. What a machine feels is its
density: a pump works on a dense fluid as on a liquid and loses its grip as the density falls; a compressor works on it
as on a gas and suffers as it approaches a liquid's density. What the code does (S2c, section 3): carbon dioxide at
310 K crossing reduced density 1 changes a pump's vapour share from 1 to 0 (head share 0.53 to 1), a compressor's
condensed share from 0 to 1 (wet efficiency x1 to x0.5), and gives the pump a suction limit E that sits on the slot
line (8.3178 MPa, above the critical pressure, where nothing flashes).

**Established (read by the main thread).**
- `InletPhase.measure` builds `Supply(vapourVolumeShare, condensedMassShare, ...)` from the state's gas and condensed
  volumes and masses (`gas/fluid`, `condensedMass/(gasMass+condensedMass)`); `InletPhase.bulk` is the bulk form.
  `PassiveStepSolver.wetShare(supply)` is `CentrifugalCompressor.wetEfficiency(1, supply.condensedMassShare())`.
  `GasEntrainment.headShare(vapourVolumeShare, suctionPressure)` and `cleanShare(suctionPressure)` scale with the
  suction pressure (the onset is 3/2000 per psi: about 1.7 at 8 MPa, so the clean share there is large).
- `ReleasePressure.vapourVolumeShare(state)` is 0 for liquid alone and NaN for a state with no liquid; E is
  `ReleasePressure.lowest` of it against the clean share.
- `HeatCurve.liquidLikeWeight(state)` (S2b) is `smoothstep((delta - 0.5) / 1.0)` for a one-fluid state, NaN otherwise.

**Steps.**
1. **One weight.** Move the weight to the model: `NetworkStreams.liquidLikeWeight(State)` (forwarded by
   `FluidThermodynamics`), with the two bounds as constants there; `HeatCurve` reads it (its own constants become
   references; no film value moves: `HeatCurveTest` and `DenseFluidGatesTest` prove it).
2. **The shares.** In `InletPhase.measure` and `bulk`: when what is measured is the one fluid of a `dense()` state,
   `vapourVolumeShare = 1 - w` and `condensedMassShare = w`, with `w <= 0` giving exactly 1 and 0 and `w >= 1`
   exactly 0 and 1 (the values the two slots give today, to the bit). Every other state as today: a subcritical
   liquid or gas, two phases, solids. A drawn stream that is part of a state (a port's fill) is dense only when the
   whole state is. Check `Drawn.condensed`, `MoverSuction.drawnLiquid`, `liquidDensities` and `riseDensity`: a pump on
   one dense fluid must read that fluid's own density for its rise in either slot (if it reads no liquid density in
   the vapour slot today, that is the first defect to fix here; say what it read).
3. **The suction limit.** `ReleasePressure.vapourVolumeShare`: `1 - w` for a `dense()` one-fluid trial state (the
   smallest positive double where `w < 1` rounds the share to 0, as the function's convention has it). E above the
   critical pressure is then the pressure at which the fluid, at its own enthalpy, thins to the clean share: a
   density, not the slot line. Below the critical pressure the dome's real vapour share is unchanged. Give E before
   and after at S2c's states (320 K and 15 MPa: 6.5198 MPa before; 310 K at reduced density 1.0005: 8.3178 MPa
   before; nitrogen at 150 K: 7.0535 MPa before), and say whether E is continuous where the one-fluid branch meets
   the dome (the seam is the film's: the two-phase side's volume share against `1 - w` at the bubble point).
4. **Wear.** `science/machine/Wear.java`: cavitation wear needs a vapour pressure (none for a dense fluid, by
   `leaveOutGases`); entrainment wear reads the same share. Confirm by a test at one dense state; no change expected.
5. **The compressor's head at low compressibility: measure, no change.** Read `CentrifugalCompressor` and the mover
   row (`PassiveStepSolver` near the heat capacity ratio, `ConservativeTransport` near line 597): what the pressure
   rise, the shaft power and the discharge temperature are computed from (the suction density and the ideal-gas
   ratio, or the real enthalpy). In a test-side measurement against the `HelmholtzFluid` oracle, the isentropic
   enthalpy rise and discharge temperature for carbon dioxide 320 K from 8 to 16 MPa and nitrogen 300 K from 10 to
   20 MPa, against what the law gives. Report the errors and one option if they pass 10 %; change nothing.
6. **The simulator.** In `science/flowsheet` (`FlowThermo` and its `p_v`, `unit/MoverUnit`, `unit/EjectorUnit`,
   `sizing/`): list every place that reads a phase label, a vapour share or a condensed share to choose a law (grep
   `vapourVolumeShare`, `condensedMassShare`, `holdsPhase`, `holdsVapor`, `Classification`, `PhaseKind`). Where it
   builds a network `State` and calls `InletPhase` or `ReleasePressure`, steps 2 and 3 already reach it: prove it with
   one sheet test (a pump and a compressor on carbon dioxide at 310 K on each side of reduced density 1 size and run
   to the same numbers within the weight's slope). Where it chooses a law by the engine's label (line sizing's
   liquid or gas velocity rule, a service class), use the weight or the traits; list each change with the sized
   numbers before and after at one dense state. A sample pin that moves is re-recorded once and listed.
7. **Identity.** One token (`dense-movers-v1`) where the solver's law tokens are listed (`FluidThermodynamics.revision`,
   beside `line-cap-saturation-v1`), so saved solved state rebuilds once. No facts field, no corpus image.
8. **Tests.** A unit test of the shares: carbon dioxide at 310 K from reduced density 0.3 to 1.8 in steps of 0.05: the
   pump's head share and the compressor's wet efficiency change between neighbours by less than a stated bound, are
   today's values to the bit below 0.5 and above 1.5, and are continuous at 1; nitrogen at 300 K and 0.1 to 10 MPa
   reads exactly today's values. `DenseFluidSuctionTest` updated for E. Then the affected classes
   (`node tools/merge-lane/affected.mjs`, as in S2b and S2c; include the holdGates sample-sizing classes and
   `FlowsheetExamplesTest` if they are among them), and the three dense GameTests alone, once
   (`-PfluidGameTestNamespaces=icheme_dense_test`, a fresh run id): the loop has a pump.

**Not in S4** (report only if you meet them): the LIQUID port's head seam; a gas blanket or vent on a vessel of dense
fluid in the liquid slot; the engine's labels; the machines' maps and curves themselves.

**Pitfalls.** `Supply`'s two shares feed the refusal decision (`InletPhase.decide`, `Compressor.refusesSupply`,
`supply.dry`): a dense fluid must not be refused as "gas at a pump" or "liquid at a compressor" on one side of the
line and accepted on the other; state what `decide` does across the window. The mode tests and the suction row read E
on trial states (`cavitationMargin`, `suctionLimit`): a tangent-built state carries its base traits (S2b finding), so
`dense()` there is the base point's; say where E is evaluated and that it is a full state. `GasEntrainment`'s numbers
scale with pressure: quote head shares at the pressure of the state, not at 1 bar. No `en_us.json` edit; a refusal or
status text that names "gas" or "liquid" for a dense fluid is S5's: list it.

**Commands.** One Gradle invocation at a time, `--tests` (or the GameTest namespace filter), `--max-workers 4`; no
whole suite, no lane, no campaign, no whole GameTest task.

**Stop and report at once** when: a value of a state that is not dense moves; a pump on a dense fluid reads no usable
density (step 2) in a way that needs a law, not a reader; a dense GameTest fails or holds; a premise is false in the
code.

**Done.** `S4_MACHINES_REVIEW.md`: expected behaviour, what the code did, what changed; the shares and E before and
after; the refusal decision across the window; the compressor measurement with its option; the simulator's readers
and changes; every moved pin, old and new; the owner options (the weight's bounds for machines: the film's 0.5 and
1.5, or their own; the compressor law); what is left for S5. The guide (`rotating-machinery.md`, `fluid-step.md`, the
simulator chapter); one README history line. Commits: the weight's move; shares and suction limit; simulator; tests;
documents.

### S4b. Correction of S4: one dense fluid has no second phase at a machine

Follow-up to S4 on the same sub-thread. The common rules stand (AGENTS.md sections "Physics and defects", "Gradle,
measurement and verification", "No repeated tests", "Results that move"; CLAUDE.md scratch rules; the reporting cadence:
at most one update per 50 minutes, at once on a decision, and the final report).

#### The rule

One fluid on a supercritical isotherm (`State.dense()`) has no second phase. So at a machine:

- what follows its density (S4, kept): a pump's head share `M(1 − w)`, a compressor's wet efficiency on `w`, the NPSH
  density, the rise density, the sizing's compressor liquid share `100 w`;
- what needs a second phase does not occur in it: a gas-release edge from its density, a vapour pressure, droplet erosion.

S4 as briefed made E a density (`ReleasePressure.vapourVolumeShare` of a dense trial = `1 − w`). Its consequence, which
the brief did not foresee and your review section 3 measured: `1 − w ≥ cleanShare(P)` below reduced density about 1.1,
so `MoverSuction.zoneEdge` returns the supplier's pressure, `suctionLimit` stands at the supplier's own pressure, the
suction line carries nothing and `vapourLocks` closes the pump. A pump drawing one dense fluid through a line at δ 0.5
to 1.1 delivers nothing; at δ 0.49 it runs (no E: `Drawn.condensed` false, head share 0.459) and at δ 1.2 it runs. The
physics has no such band: nothing separates, the rise `ρ g H` just falls with the density.

#### Step 1. The world's E: only a real flash

Files: `science/fluid/network/ReleasePressure.java`, `MoverSuction.java`, `InletPhase.java`.

1. `ReleasePressure.vapourVolumeShare(State at)`: a dense trial reads **0** in either slot (it releases no vapour).
   Remove the `1 − w` line. Before S4 the liquid slot read 0 and the vapour slot NaN; now both read 0.
2. `MoverSuction.zoneEdge(Drawn, Runnable)`: the shortcut
   `if(drawn.supply().vapourVolumeShare()>=cleanShare(p))return p;` must not fire for a dense supplier state
   (`s.dense()`): its supply share is `1 − w`, a density, not a gas. Every other stream keeps the shortcut to the bit.
3. E of a dense supplier stands only where the stream **holds a vapour at E**. `ReleasePressure.lowest` returns the last
   passing pressure `hi`. On an isenthalp that passes the critical point on the gas side, the trial goes from 0 (dense)
   to NaN (a one-root vapour below Tc, "holds no liquid") or to about 1 (the dome entered from the dew side), with no
   continuous crossing of the clean share; `vapour` may be set by a later dome trial, and `hi` is then the pressure where
   the isenthalp crosses Tc: a label change, not a flash. The test: the trial's value at the returned `hi` is above 0
   (a real vapour beside a liquid; `Double.MIN_VALUE` counts). Where it is exactly 0, there is no edge: positive
   infinity.
   - Why this separates the two cases: on a bubble-side isenthalp the share rises continuously from 0 at the bubble
     pressure `Pb` to the clean share at E, and the bisection's final `hi` lies in `(E, E(1 + width))`, inside
     `(E, Pb)`, so its share is above 0. On the gas side the last passing trial is a one-fluid state, share exactly 0.
   - Form: your choice (a variant of `lowest` that also returns or tests the passing trial's value, used by the world's
     `releasePressure` for a dense supplier only). Do not change `lowest` for any other caller: no non-dense E may move.
     The top trial (`hi` = the supplier's pressure, dense, 0) passes as now; a result equal to the supplier's pressure
     with share 0 there is "no edge" by the same test.
4. `InletPhase.stream`'s `Drawn.condensed` for a dense state stays `w > 0` (S4): below δ 0.5 no E is looked for at all.
   `MoverSuction.suctionFloors` keeps its S4 line.
5. p_v (`MoverSuction.release`): one dense fluid has none, in either slot. Today a dense mixture in the liquid slot
   reads the bubble pressure of its compounds below their own Tc (natural gas at 300 K: 2.66 MPa) and none in the vapour
   slot: a slot reader. Return positive infinity for a dense state before the `holdsLiquid` branch. A pure dense fluid
   already reads none (`leaveOutGases`). The sheet's `FlowThermo.vapourPressure` gets the same rule if its stream's
   state is dense (it has the state through `flashTP`; if that costs a flash it does not make today, say so and leave
   the sheet's as it is).

Expected numbers (measure them; `S4SeamProbe`, `S4SharesProbe` re-run):

- CO2 320 K, 15 MPa (δ 1.643): E 6.5198 MPa, unchanged (a real flash from the bubble side).
- CO2 310 K: E none below δ about 1.19 (gas-side isenthalps), then a real flash below Pc (7.17 MPa at δ 1.20, falling
  to 6.52 MPa at δ 1.475: your `seam.txt` rows from δ 1.20 on must stay as they are, to the digit or within the
  bisection's width). The rows from δ 1.00 to 1.1875 become none.
- So one seam is left in E: at the isenthalp through the critical point, E goes from about Pc to none. The suction
  stands about 1.3 MPa above it there. Report its place at 310, 320 and 330 K.
- No vapour lock and no suction limit on any dense state without a real flash; `DenseMachineSharesTest` gets a test of
  a pump drawing CO2 through a line at δ 0.6, 0.8, 1.0 and 1.1: not locked, flow above zero, head share `M(1 − w)`.
- The seam at δ 0.5 of your section 3 (none to the suction's pressure) is gone: check it.
- Natural gas 300 K, δ 1.6: E measured (it may be none: retrograde entry from the dew side), p_v none.

#### Step 2. A compressor's droplet wear

`MoverSuction.readSuctions` line 380: `condensedShares[edge]=supply.condensedMassShare()` feeds
`StepObservations` and `MachineHealth` (`Wear.wetGas`, the "WET GAS" word). `wetShares[edge]=wetShare(supply)` (the
efficiency) stays on `w`. For a dense supplier the observed condensed share is 0: no droplets, no erosion, no word.

- `InletPhase.Supply` needs to say that its shares are a density's (a boolean, or a second pair of fields: your choice;
  the smallest form that keeps every non-dense value to the bit). `StepObservations` line 42 reads
  `InletPhase.bulk(...).condensedMassShare()`: the same rule there.
- List, with numbers on CO2 at 310 K and δ 0.6, 1.0 and 1.4, what else reads a pump's `vapourShares[edge]` in
  `MachineHealth` and on the pump page (the churn's loudness, a status word, the DRY fault, the gas ratio reading, a dry
  screw's LIQUID fault). **Change none of them in S4b**: they go to S5 with your table. State for each whether it is a
  density's effect (dry running) or a second phase's (bubbles).

#### Step 3. The simulator

Files: `science/flowsheet/FlowThermo.java`, `sizing/Service.java`, `unit/MoverUnit.java`, `sizing/CompressorSizing.java`.

1. `FlowThermo.denseAtEdge` goes. The sheet's trial is isothermal (`releaseShare`, a TP flash at the stream's
   temperature), and one dense fluid at its own temperature never flashes: every trial is dense, reads 0, and
   `lowest` gives none. So on the sheet a dense stream has no E and no bubble point. Check `releaseShare`'s line
   `NaN && holds GAS → 1`: a dense state must read 0 before it.
2. `MoverUnit`: no `pump.bubble_point` warning for a dense inlet. `Service.of`: a dense pump suction is `SUCTION_COLD`.
   A text for a pump on a gas-like dense fluid is S5's.
3. The world's E for the same stream (isenthalpic) can exist where the sheet's does not (CO2 320 K, 15 MPa: world
   6.52 MPa, sheet none). State the difference in the review with the numbers; add no PH trial to the sheet.
4. `CompressorSizing`: keep `liquid = 100 w` (it is the world's condensed share, which sets the efficiency).
5. `CompressorSizing`'s discharge temperature (`rate`: `suction · stage^exponent`, `exponent = (k − 1)/(k η)`): 17 to
   32 K high on a dense suction (your section 5). For one dense fluid with `w > 0`, `Load.of` blends the exponent:
   `exponent = ideal + w · (solved − ideal)`, with `solved = ln(T_out/T_in)/ln(ratio)` from the sheet's solved streams
   `in` and `out` (check first that `out` is the unit's solved outlet at the world's efficiency; if it is not, stop this
   item and report). `w = 0` keeps the ideal exponent to the bit, so an ordinary gas (N2 300 K, any network pressure)
   does not move. Guard `ratio > 1` and finite temperatures; else the ideal exponent. If any sample pin
   (`FlowsheetExamplesTest`) or any reading at `w = 0` moves, stop and report: do not re-record.

#### Not in S4b

The compressor's head law (your option 4, first half): no change. The pump's churn, words and readings on a dense
fluid: S5. The LIQUID port's head seam, the gas blanket on a dense vessel, the engine's labels: not here.

#### Identity

`dense-movers-v1` stays as it is: no world has been saved with it (the branch is not merged).

#### Tests

- `DenseFluidSuctionTest`: E per the numbers above (none on the gas side, the real flash on the liquid side).
- `DenseMachineSharesTest`: the pump through a line (above); wear: `Wear.wetGas` of the observed share is 0 at δ 0.7,
  1.0 and 1.4 while the wet efficiency reads `w`; p_v none for the dense natural gas in both slots.
- `ServiceTest.aDenseSuctionIsBoilingByItsDensity`: rewritten for the rule (cold; no sheet E).
- `FlowsheetUnitsTest.aPumpAndACompressorOnADenseFluidSeeNoSlotLine`: no warning on either side; the 0.2 % agreement
  stays; the compressor's discharge reading within 3 K of the solved outlet on a dense suction, unchanged at `w = 0`.
- The non-dense proof: your 100-state compare again, 0 non-dense values moved.

#### Runs

- `node tools/merge-lane/affected.mjs select --since c0596cff3`, one filtered invocation per gate group,
  `--max-workers 4`; chain-100 if the selection marks it (deviation 0 expected: no dense state in it).
- The three dense GameTests alone once (`-PfluidGameTestNamespaces=icheme_dense_test`, a fresh
  `fluidGameTestRunId`). The loop's pump draws a subcooled liquid at 302.41 K (below Tc, not dense), so the loop's
  states must be the same as in `s4-dense1`.
- No whole task, no campaign: the final lane's.

#### Documents and commits

- `S4_MACHINES_REVIEW.md`: sections 3, 4, 6 and 8 state the corrected form with its numbers (a short "as first
  implemented" paragraph keeps the lock band's numbers, since the plan's section 2b point 10 cites them). Section 8
  lists what is still an owner option: the head law, the seam at the critical isenthalp, the pump's readers for S5.
- The guide chapters S4 touched, the batch README's history line.
- Commits: code, tests, documents, in that order; `Co-Authored-By` your own model line.

#### Done

No lock band; E only at a real flash; no p_v and no droplet wear on one dense fluid; the sheet agrees; 0 non-dense
values moved; the affected classes, chain-100 and the three dense GameTests pass; the review and the guide updated;
the pump-reader table for S5 in the report.

### S5. Presentation: what the player reads on a dense fluid

Stage S5 of batch 2026-11-15-supercritical-fluids. The common rules stand, with these changes: production code is in
scope in the files named here; `en_us.json` is the only lang file touched (`LanguageFilesTest` and `UnitTextTest` are
not run: AGENTS.md, "Translations"); no dev client is started (AGENTS.md, "GUI and in-game checks").

Read first: `.claude/skills/gui-creation/SKILL.md` and `references/recipes.md` (the device row, a key, the finish);
`AGENTS.md` sections "Engine schedule and player-facing updates", "GUI and in-game checks", "Translations";
`documentation/2026-11-15-supercritical-fluids/S4_MACHINES_REVIEW.md` sections 4, 6, 8 and 9 (the readers and texts
left for S5) and `DENSE_PUMP_RESEARCH_REVIEW.md`; the plan's section 2b points 11 and 12.

#### What "dense" is, for the player

One fluid on a supercritical isotherm (`FluidThermodynamics.State.dense()`), with reduced density δ
(`State.reducedDensity()`). It includes ordinary gases above their critical temperature (nitrogen at 300 K), so the
words change only above δ 0.5 (`FluidThermodynamics.GAS_LIKE_UNTIL`): at or below it the fluid is "Vapor" as today.
The slot rule flips the fluid between the vapour and the liquid slot at δ 1; no text or reading may flip there.

Owner decisions (2026-11-16, "Accept all"):
1. The row label: one dense fluid above δ 0.5 reads "Dense fluid", in either slot; at or below 0.5 "Vapor".
2. A pump on one dense fluid makes no churn sound and gets no `GAS` word (no bubbles); its page shows the head share.
3. The simulator's pump sheet gets a row "suction lighter than a pump's range" below δ 0.86.

#### Step 0. Survey (report before building if a premise fails)

List every place a player reads a phase of a network node, a stream or a machine's suction, with file and line:
- the device screen: `client/gui/screens/inventory/FluidDeviceScreen.java` (phase tabs, lines 436 to 452, key from
  `DeviceColumn.phaseKey`), `client/gui/common/DeviceColumn.java` (`Phases`, `phaseKey`, the share legend near line
  292), `client/gui/common/DeviceMeters.java` (`phases()`, `aqueousWater()`: a label rule computed on the client from
  the view's amounts), the level line (`gui.icheme.fluid.level_line`), the pump page's suction rows
  (`DeviceMeters` line 256, `DeviceColumn.Suction`);
- the server's words and sounds: `runtime/fluid/MachineHealth.java` (line 255 the churn from `row.gasRatio()`, line 256
  the `Suction` reading, line 424 the `GAS` word, 202 a dry screw's liquid share), `FluidView.Pump.gasShare`,
  `FluidNetwork.MoverState.churn`;
- the sight glass and the gauges (what they draw for a vessel of one dense fluid in each slot);
- the simulator: `client/gui/flowsheet/Door.java` (line 161, the phase shares), `client/gui/flowsheet/StreamProps.java`,
  the status `gui.icheme.flowsheet.door.status.phases`, the words `flowsheet.phase.liquid_word`/`gas_word`/`two_word`,
  the texts `flowsheet.pump.phase`, `flowsheet.compressor.phase`, `InletPhase.reason`'s "ERROR: ... inlet" lines,
  the sizing rows (`science/flowsheet/sizing/Limit.java`, `PumpSizing.java`, `CompressorSizing.java`).

#### Step 1. The label rule, with no wire field

The protocol version is one number for every payload (`network/NetworkProtocol.VERSION`, `icheme-1`), and `icheme-2`
is reserved by another batch. So S5 adds **no wire field and changes no payload layout**.

- One Minecraft-free presentation function, in `client/gui/common/` beside `DeviceColumn` (name yours, for instance
  `DenseLabel`): given the model's answer for the node, "dense above δ 0.5" or not. The rule is the model's:
  `state.dense() && FluidThermodynamics.liquidLikeWeight(state) > 0` (the weight is above 0 exactly above δ 0.5).
- On the client the view's `State` is decoded from the wire. Find out whether a decoded state keeps its phases'
  `PhaseTraits` (S2a put them on `HydrocarbonModel.Phase`; `State.dense()` reads them). If it does: use it. If it does
  not: evaluate the one fluid phase's traits on the client from the view's temperature, pressure and amounts with the
  island's model (the process simulator builds `FluidThermodynamics` on the client already: reuse its way of getting
  the model from the component list). That is one cubic root for one composition, done once per published snapshot
  and kept with it: not a flash, no solver, no process state (AGENTS.md, "A replay is a read of cached data").
- **Stop and report** if neither works without a payload change, or if the client's model can differ from the
  server's for an island (a material override the client does not have): the options then go to the owner.

#### Step 2. The device screen

- Tabs and legends: for a node that is one dense fluid above δ 0.5, the tab and legend of the slot that holds it read
  "Dense fluid" (`gui.icheme.fluid.phase.dense`, `phase_short.dense` "Dense", `phase_tab.dense` "Dense"), as
  `aqueousWater` renames the aqueous tab to "Water" (`DeviceColumn.phaseKey`: extend it, do not add a second
  mechanism). The other three tabs stay, empty as they are.
- The level line of a vessel that holds one dense fluid above δ 0.5 reads "No interface" (a new key) in place of
  "% full": the fluid fills the vessel in either slot, and the slot flip at δ 1 would show 0 % or 100 %.
- The sight glass and the level gauges: report what they show in each slot. Change them only if the change is a
  presentation rule on data they already have; a level instrument's reading that a controller uses is the engine's
  and is not S5's (list it for the owner).
- Software renders (`tools/heat-gui-renders/`, as the skill's recipe): the device screen on a tank of carbon dioxide at
  δ 0.4, 0.8, 1.0005 and 1.6, both themes. The owner judges on these.

#### Step 3. The pump's bubble readers (server side, values only)

For a pump whose supply is one dense fluid (`InletPhase.Supply.dense()`), the readers of bubbles read none:
- the churn (`MachineHealth` line 255: `GasEntrainment.churn(row.gasRatio())`) is 0; the `GAS` word (line 424) is not
  given; the page's gas ratio reading is "—".
- The page's vapour share row shows the head share's cause as a density: decide with the skill's rules whether the
  existing "gas share" row reads "—" for a dense supply and a head-share reading is added, or the row is relabelled;
  one reading, no new group. No payload layout changes: `FluidView.Pump.gasShare` is a nullable `Double` already.
- What follows the density stays: the head share, the dry-running fault (gas lock at no flow).
- Form: the observation row needs to say its supply is dense. `PipeObservation` is a solver-side record
  (`PassiveStepSolver` line 1922); prefer carrying it where `condensedShares` already carries S4b's droplet rule
  (`MoverSuction.readSuctions`): a gas ratio and a bubble share of 0 for a dense supply, the vapour share (the
  density's) kept for the head share. Say which you chose and why. No solved value may move: chain-100 deviation 0.
- A dry screw vacuum pump's liquid share on a dense fluid (S4b's table: full LIQUID fault): report only; a vacuum pump
  never meets a dense fluid above δ 0.5 at its pressures.

#### Step 4. The simulator

- `Limit`: a SHOULD row for a pump, "Suction density" (key `gui.icheme.flowsheet.limit.pump_density`), least 0.86 in
  reduced density, read only for a dense suction (`PumpSizing.Load` gains the reduced density, NaN or absent for any
  other stream, so every other pump's sheet is unchanged). Source in the javadoc: DENSE_PUMP_RESEARCH_REVIEW.md
  (pumps run carbon dioxide down to 400 to 500 kg/m³). Its hover says to compress and cool first, or use a compressor.
- Words: a stream of one dense fluid above δ 0.5 reads "dense fluid" where `flowsheet.phase.liquid_word` or
  `gas_word` is used, and "Dense" in the phase shares (`Door` line 161: one share named by the rule, not a fifth
  colour unless the skill's recipe asks for one).
- `flowsheet.pump.phase` ("A pump takes liquid: this inlet holds gas.") and `flowsheet.compressor.phase`: check that
  neither can fire on a dense fluid above δ 0.5 (S4: the refusal reads a dry suction and solids only); if one can,
  report it.
- Renders of the pump sheet and a stream's door on carbon dioxide at 310 K, δ 0.8 and 1.2.

#### Tests and runs

- Gates the skill names for a new key, a device row and a sheet row (`KitLayoutTest`, `DrawCallTest`, the device
  column's and the flowsheet's gates); `FlowsheetPaletteCoverageTest` if a limit row needs a lang line there.
- A test of the label rule at δ 0.4, 0.5, 0.8, 1.0005, 1.6 (carbon dioxide, 310 K) and for nitrogen at 300 K and
  1 bar ("Vapor"), and for a two-phase node (unchanged).
- A test that a pump on a dense supply reads churn 0 and no `GAS` word at δ 0.6, and the same pump on a two-phase
  supply reads what it read before.
- Runs: `node tools/merge-lane/affected.mjs select --since 8a870f228`, one filtered invocation per gate group,
  `--max-workers 4`; chain-100 if selected; the three dense GameTests alone once if a server-side file they load
  changed. Another thread of this batch (the column item) runs Gradle in its own worktree and another batch may run
  one too: never start an invocation while two others run on the machine.

#### Documents and commits

`documentation/2026-11-15-supercritical-fluids/S5_PRESENTATION_REVIEW.md` (the survey table, the rule, what each
screen reads before and after, the renders' paths, the items left for the owner); the guide chapters touched; one
history line in the batch README. Renders are images: they are git-ignored in `documentation/` and `tools/` and
belong in the main checkout's `research/2026-11-15-supercritical-fluids/` (this worktree's hook refuses that folder:
leave them in `tmp/supercritical-s5/renders/` and say so). Commits: code, tests, documents.

#### Not in S5

Any engine or solver value; the LIQUID port's head seam; the gas blanket on a dense vessel; the engine's labels of
dense mixtures; the translation commit; an in-game check (offered to the owner at the end, not started).

#### Done

No reading or word flips at δ 1; ordinary gases read as before; no payload layout changed; the gates and the affected
classes pass; chain-100 deviation 0; the renders exist; the review, the guide and the README are committed.

### Column. A feed above its mixture critical point

The column item of batch 2026-11-15-supercritical-fluids (limits review D1 and D2). It runs in its own worktree, cut
from `claude/supercritical-fluid-support-d0ffa4`; the common rules stand with that worktree as the place of work.
Production code is in scope in `science/column/v3/thermo/` only.

Read first: `AGENTS.md` sections "Physics and defects" (with "Thermo validity"), "Gradle, measurement and
verification", "Native code"; `documentation/reference/code-guide/README.md` and the column chapter;
`SUPERCRITICAL_LIMITS_REVIEW.md` rows D1 and D2 and its line 46; `S1_ENGINE_REVIEW.md` (the phase traits).

#### D1. The feed flash

- **Expected.** A feed that has no two-phase solution at its temperature and pressure is one fluid. Above its mixture
  critical point that is the only answer. The column needs its enthalpy and one label, liquid or vapour, to send it
  down or up from the feed tray.
- **What the code does** (`V3FeedFlash.resolve`, 156 lines): Wilson K-values bracket two phases; successive
  substitution on the Peng-Robinson K-values then moves them toward 1 (the trivial solution); `rachfordRiceRoot`
  leaves (0, 1) and line 46 throws `FLASH_NONCONVERGENCE`, "lost its two-phase Rachford-Rice root", or the loop runs
  out (line 80). Only a collapse that reaches the tolerance on the liquid end is answered (lines 62 to 69).
- **First, measure** (no change): a grid of feeds through `V3PengRobinsonThermo`'s flash entry (line 174) against the
  equilibrium engine as the oracle (`science/thermo/phase`, `FluidTpEquilibrium`, on the same components): methane
  and ethane 50/50, carbon dioxide with 10 % methane, a natural gas (CH4 0.90, C2H6 0.06, C3H8 0.03, N2 0.01), and
  methane with n-decane 90/10, at 250 to 400 K and 2 to 30 MPa. For each: the engine's answer (one phase or two, with
  the vapour fraction), the feed flash's answer or refusal, the iteration at which it failed, and the largest
  `|ln K|` then. This shows which refusals are feeds above the critical point, which are subcritical one-phase feeds
  the Wilson bracket mislabels, and whether any is a real two-phase feed.
- **The fix, in the stage it belongs to** (the flash's own classification, not a caller's catch): where the K-values
  collapse (largest `|ln K|` below a bound you derive from `LOG_K_TOLERANCE` and the measured collapses) or the
  bracket is lost while they are collapsing, the feed is one phase. Evaluate the overall composition's root. Its
  label: with one real root, liquid when its reduced density is at least 1, else vapour (`PhaseTraits`, the network's
  rule, owner decision O2 A); with two roots, the one of lower Gibbs energy. Return `V3FlashResult.liquid` or
  `.vapor` with a detail text that names it ("one fluid above the mixture critical point" or "one phase, trivial
  solution"). The existing special case of lines 62 to 69 becomes part of this rule if the rule covers it: remove
  what it makes unnecessary.
- **An assumption the physics does not force:** a collapse of successive substitution from a Wilson start is taken as
  "no two-phase solution". It is not a stability test. If the grid shows a real two-phase feed (by the engine) that
  the rule would answer as one phase, **stop and report with the options**: (A) the collapse rule with the measured
  misses stated, (B) the engine's stability answer for a feed whose bracket is lost. Do not choose.
- A refusal that is neither (non-finite K, a real two-phase feed that does not converge) stays the typed refusal.
- **Native twin:** none was found for the feed flash (`src/main/rust` holds the math and the banded solver only).
  Check the native column path for a second copy of this flash before you change it; if there is one, the rule of
  AGENTS.md "Native code" applies: say so and stop.

#### D2. A tray above its mixture critical point

A tray whose liquid and vapour roots coincide cannot exist; it stays a typed refusal (a capability limit). Find where
it is refused today (`V3ThermoModel`, `V3PengRobinsonSession`, `INTERIOR_PHASE_IDENTITY`, `StillColumn`) and what
text the player reads. The text names the cause: the tray is at or above its mixture's critical point (lower the
pressure or change the cut). If the text is a lang key, change `en_us.json` only. No solver change.

#### Tests and runs

- `V3FeedFlashEquivalenceTest`, `V3PengRobinsonThermoTest`, the truncation tests: every existing value exact (the
  change only answers feeds that were refused). New tests: the grid's one-phase feeds answered with the engine's
  enthalpy within the two models' known difference (state it), the label rule on both sides of reduced density 1, a
  real two-phase feed unchanged to the bit, the D2 text.
- `node tools/merge-lane/affected.mjs select --since <the branch tip you were cut from>`; `columnGates --tests ...`
  and `test --tests ...` filtered, `--max-workers 4`. `ColumnSolveTest` and `FlowsheetExamplesTest` if selected: no
  pin may move; if one does, stop and report.
- Never start a Gradle invocation while two others run on the machine (the S5 thread and another batch may).

#### Documents and commits

`documentation/2026-11-15-supercritical-fluids/COLUMN_FEED_REVIEW.md`: expected, what the code did, the grid, the
rule, what moved (nothing that was answered before), the options if any. The column guide chapter if a contract
line changes. Do not edit the batch `README.md`, `documentation/INDEX.md` or `S4_MACHINES_REVIEW.md` (the other thread
and the main thread hold them): give the README's history line in your report. Commits on your own branch: code,
tests, documents. No push, no merge: the main thread merges your branch into the batch branch after checking it.

#### Done

A feed above its mixture critical point is answered as one phase with its label and enthalpy; every feed answered
before is unchanged to the bit; the D2 text names the critical point; the affected classes pass; the review is
committed on your branch.

## Merge of main. Main 0.33.0 into the supercritical batch branch (sub-thread, Opus high)

Owner, 2026-11-16: "uh merge main first". Read "Common rules" at the top of `tmp/supercritical-briefs/BRIEFS.md` first;
they stand with these changes: production code is in scope only as far as the merge needs it (conflicts, compile errors
where main's new code meets this branch's changed signatures, the material revision tokens below); `en_us.json` is the
only lang file you touch by hand (`LanguageFilesTest` and `UnitTextTest` are not run); no dev client; no GameTests, no
chain-100, no campaign, no whole task (the final lane's).

### The merge

- In this worktree, on `claude/supercritical-fluid-support-d0ffa4` (head `cdc05e565`, clean): `git merge --no-ff main`
  at `66a87f9d5`. Main is 0.33.0: the pollution system batch, the fluid interfaces batch, the equipment visual design
  batch, and one reservation commit of this batch (`66a87f9d5`: `FORMATS.md` rows for fluid facts schema 28, checkpoint
  state format 51, protocol `icheme-4`; stage S8 will use them, you do not).
- Merge base `b41f7f9c7`. Main changed 453 files, the branch 193, both 57 (`tmp/supercritical-briefs/both3-changed.txt`,
  with `main3-changed.txt` and `ours3-changed.txt`).
- A trial merge (`git merge-tree`) conflicts in three files only:
  - `documentation/INDEX.md`: keep every row of both sides (AGENTS.md, "Documentation": index files change row by row).
  - `documentation/reference/code-guide/client-screens.md`: both sides' text stays; S5's lines are about the dense label
    (`DenseLabel`, the pump's head share).
  - `tools/compound-import/README.md`: keep both sides' history lines.
- Main's numbers win everywhere: `mod_version=0.33.0`, fluid facts schema 27, checkpoint state format 50, protocol
  `icheme-3`. This branch changed none of them. Do not bump a version (the batch's proposal, 0.34.0, is merge
  preparation's).

### What merges silently and must be checked by hand

1. **Material records, 33 files changed on both sides** (`src/main/resources/data/icheme/materials/properties/`). The rule
   (AGENTS.md, "Saves and data formats"): a change to a record's values bumps that record's own `revision`; an identity is
   a declared revision. A world saved by main 0.33.0 holds main's tokens, a world of this branch holds ours. So for each of
   the 33, read the three versions (`git show b41f7f9c7:<path>`, `main:<path>`, `cdc05e565:<path>`) and the merged result,
   and list every `revision` token in it (the record's own, the viscosity's, the freezing point's, the hazard's, any
   other):
   - a token that only one side changed: the merged file has that side's token, and the values it stands for are that
     side's: nothing to do;
   - a token that both sides changed, or a token one side changed while the other side changed values in the same token's
     scope: the merged values are neither side's, so the token becomes a new one that neither main nor the branch ever
     declared (the next in its series), in the merge commit.

   The branch's changes: S3 raised `pressure_max_pascal` and the evidence of nine datasets (CO2, nitrogen, methane, ethane,
   propane, hydrogen, ethylene, n-hexane, toluene; `S3_DATA_REVIEW.md`), and the batch added critical volumes and residual
   viscosity data. Main's: the pollution batch's `Hazard` blocks ("its own revision, in no identity") and the fluid
   interfaces batch's rare gases. Report the table: file, token, base, main, ours, merged, and why.
2. **`science/material/MaterialCatalog.java`** (both sides): compiles, and the catalog's identities stay within
   `MAX_IDENTITY` (21000): `MaterialCatalogTest` (both sides changed it too). If an identity exceeds the bound, stop and
   report the lengths; do not raise the bound.
3. **`src/test/resources/thermo/engine-digest.txt`** (both sides): `EngineDigestTest` is expected to fail on this branch
   at request 313 for a known reason: S3 raised nine pressure ranges to 30 MPa, so states between 10 and 30 MPa read grade
   `ESTIMATED_DECLARED_ERROR` where the reference says `EXTRAPOLATED`, every number the same to the bit (plan section 2b,
   point 17). Re-recording it is the final lane's, once. You measure the bound for it: run `EngineDigestTest` once in its
   record mode (its javadoc names the property and the output, `build/engine-digest.txt`), do **not** copy the output over
   the reference, and report: the count of requests on each side, the count of differing lines, and whether every
   difference is that one grade token. Any other difference is a finding: report it with the lines.
4. **`build.gradle`** (both): the gate groups keep both sides' entries (the column group has this branch's
   `science/thermo/PhaseTraits*` and `PhaseIdentification*`).
5. **`en_us.json`** (both): valid JSON, no key twice, both sides' keys present.
6. **Client and sizing** (both): `client/gui/common/DeviceMeters.java`,
   `client/gui/screens/inventory/FluidDeviceScreen.java`, `science/flowsheet/sizing/Limit.java`,
   `tools/heat-gui-renders/GuiPrototypes.java`. Read each merged file where the two sides' hunks are near each other.
7. **Signatures this branch changed that main's new code may call** (compile errors show them): `InletPhase.Supply` has a
   last component `boolean dense`; `PumpSizing.Load` has a last component `double reducedDensity` (a 7-argument
   constructor remains); `PassiveStepSolver.SolveConstants` has `bubbleShares`, which `MoverSuction.readSuctions` writes;
   `ReleasePressure.lowestHeld`; `FluidThermodynamics.liquidLikeWeight` and `pumpLiquidLikeWeight`; `State.dense()` and
   `State.reducedDensity()`. Fix a caller the way its neighbours on this branch are written; a caller whose physics is not
   clear from them is a finding to report, not to guess.
8. **Main's new readers of a phase slot** (report only, no change): the fluid interfaces batch added an ambient boundary
   and a gas intake to the network. List where its new code reads the vapour or the liquid slot of a state (as
   `S2C_NETWORK_REVIEW.md` lists "what still reads the slot"), with file and line, so the main thread can judge them
   against a dense fluid.
9. **Native code**: `git diff --stat b41f7f9c7..main -- src/main/rust`. If main changed it, the Java twins this branch
   changed are a finding to report (AGENTS.md, "Native code").

### Runs

- `compileJava` and `compileTestJava` first.
- Then the test classes the merge can reach, once each, 4 workers: `node tools/merge-lane/affected.mjs select` gives the
  selection against the load map (`tools/merge-lane/README.md`; always pass the `select` word: without arguments the
  script waits). Run it task by task with `--tests`, as S5 did (`tmp/supercritical-s5/runs.sh` is a worked example). Leave
  out `LanguageFilesTest` and `UnitTextTest`. Do not run a class twice: a failure is diagnosed from its output and the
  code.
- Expected failure: `EngineDigestTest` (point 3). `FormatsTableTest` passes after the merge (its two failures on the
  branch were main's rows without main's constants).
- One other Gradle run may share the machine; yours run one at a time.
- Scratch in `tmp/supercritical-merge/`, with its row in `tmp/INDEX.md`.

### Done

- One merge commit on the branch (two parents), its message naming main's commit and version, ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Fixes the merge needed are in that commit or in commits right
  after it, one per cause.
- `documentation/2026-11-15-supercritical-fluids/MERGE_MAIN_0_33_REVIEW.md`: the conflicts and how each was resolved, the
  revision token table, the compile fixes, the digest measurement, the slot readers list, the runs' table (group,
  classes, tests, failed), what is left for the final lane. Summary at most 50 lines. One history line in the batch
  README. Commit both.
- `git status` clean; nothing pushed; `main` untouched.
- Stop and report at once, with options, if: a revision token cannot be decided by the rule; an identity exceeds its
  bound; a compile fix needs a physics choice; a conflict appears that the trial merge did not show in a file of
  `science/fluid/**` or `science/thermo/**`.

### Reporting

Work without ending your turn for progress. Message the parent only: at most once every 50 minutes with a few lines
(done, running, next); at once when a decision is needed, with the options; and with the final report.

## S8. The plunger pump (sub-thread, Opus high)

Owner, 2026-11-16: "add a positive displacement pump head for supercritical service, research first", then "Accept all"
(its own plunger pump; the existing displacement law with no clearance term; work as flow times rise; no chilled head; a
sheet type with no minimum-density row), "Form A" (the model), "uh merge main first" (done: main 0.33.0 is in the branch).

Read first, in this order: `AGENTS.md`; "Common rules" at the top of `tmp/supercritical-briefs/BRIEFS.md`;
`documentation/2026-11-15-supercritical-fluids/PLUNGER_PUMP_PLAN.md` (the plan this brief carries out; where the two
differ, this brief is the later one); `PD_PUMP_RESEARCH_REVIEW.md` and `S4_MACHINES_REVIEW.md` (S4b: how a pump reads one
dense fluid) in the same folder; `documentation/2026-11-10-vacuum-pump/` (the worked precedent: a casing with a catalogue
of models on a `MachineLaw`); `.claude/skills/models-and-sfx/SKILL.md` before any model or sound work;
`.claude/skills/gui-creation/SKILL.md` before any screen work; `documentation/reference/COLLABORATION.md` section 3 and
`documentation/reference/FORMATS.md` before any format work.

State: branch `claude/supercritical-fluid-support-d0ffa4`, this worktree, clean. `mod_version=0.33.0` (do not bump),
fluid facts schema 27, checkpoint state format 50, protocol `icheme-3`. Reserved for you on `main` and already in this
branch's `FORMATS.md`: **fluid facts schema 28**, **checkpoint state format 51**, protocol `icheme-4` (only if a payload
changes). Take no other number: needing one is a stop.

### W1. The law (`science/machine/PlungerPump.java`)

Physics: a triplex plunger pump displaces a fixed suction volume per turn. Its delivery is its displacement times the
speed share, less a slip of 0.4 % per relief rise, whatever the fluid's density; at its relief rise its valve passes the
whole delivery back. These are `PositiveDisplacement`'s own numbers (`SLIP`, `RELIEF_SLOPE`, `ROUNDING`): no new constant.

- `public record PlungerPump(double displacement, double reliefRise, double ratedSpeed) implements MachineLaw`:
  m³/s at rated speed, Pa, rpm; validated as `VacuumPump`'s constructor validates. Add it to `MachineLaw`'s `permits`.
- `public static final double MECHANICAL_EFFICIENCY = .88, RELIEF_RISE = 25e6, SPEED = 300;` and the catalogue, as
  `VacuumPump.SCREWS` is: `record Model(String name, double capacity, double scale)` (m³/h; the drawing's scale `k`),
  `MODELS = PP-3 (3, .55), PP-10 (10, .75), PP-30 (30, 1), PP-100 (100, 1.35)`, `model(double number)` (1 up, clamped, as
  `VacuumPump.model`), `of(Model)` = `new PlungerPump(capacity / 3600, RELIEF_RISE, SPEED)`.
- `liquid()` true. `row(speed)` = `new PositiveDisplacement(max(displacement * speed, 1e-12), reliefRise)`, used at speed
  1 as `VacuumPump.row` is (read `PositiveDisplacement`'s javadoc first: confirm whether its own `speed` argument or the
  scaled displacement is the form `VacuumPump` and `FlowControl.Compressor` use, and use the same one).
  `rise`, `riseSlope` from it; **`residual` overridden with the flow form** (`PositiveDisplacement.residual`, F2e), as
  `VacuumPump.residual`: a displacement law in the head form is near vertical and the row would not converge. Density,
  pressure and `k` enter none of them.
- `fluidPower(massFlow, rise, density, ...)` = `massFlow / density * rise` (0 unless all three are above 0): the work
  booked into the fluid is flow times rise (owner option, accepted). `shaftPower(flow, speed, ...)` =
  `displacement * speed * max(0, rise at that flow) / MECHANICAL_EFFICIENCY`: the plungers displace against the rise
  whether the fluid is delivered or relieved; finite and above 0 at no flow; 0 at speed 0. `maximumShaftPower()` =
  `displacement * reliefRise / MECHANICAL_EFFICIENCY` (PP-100: 789 kW). `ratedFlow()` = `displacement`, `ratedHead()` 0,
  `efficiency()` = `MECHANICAL_EFFICIENCY`.
- The javadoc states what is left out and its size (research review, options 2 and 3): no clearance term (delivery 0.4 to
  4.5 % high on a dense fluid), no real isentropic work (work 2 to 21 % high).
- `FlowControl` does not change. `FlowControl.Machine` already gives: `requestedVolumeFlow = ratedFlow * speed`,
  `withHeadShare` a no-op for any law but `CentrifugalPump`, `npsh()` null for any law but `CentrifugalPump`,
  `deviceName()` "pump". `ShaftTrains` plans every `FlowControl.Machine` as `ShaftPlan.Kind.SPEED`: no change there.
- `PlungerPumpTest` (new, `src/test/java/.../science/machine/`): the displacement at no rise; the slip at half the relief
  rise; the relief line; the same delivery at densities 280, 470 and 1,000 kg/m³; the shaft power at no flow above 0;
  the residual 0 on the law; `fluidPower` = Q ΔP.

### W2. The saves (facts schema 28, checkpoint 51)

A casing's law is a fact. **Pitfall:** both writers have a generic `case FlowControl.Machine machine` that writes
`law.liquid() ? "centrifugal_pump" : ...` (`FluidFacts.java` about line 401) and tag 5 with `law.liquid()`
(`FluidCheckpointCodec.java` about line 466). A plunger pump is liquid, so without its own case **before** the generic
one it is saved as a centrifugal pump and comes back as one: a discarded fact with no error.

- `FluidFacts`: `SCHEMA=28` with its javadoc line ("28 (batch 2026-11-15-supercritical-fluids, S8): the `machine`
  control's law `plunger_pump`: its displacement (m³/s) in the rated flow's place, its relief rise (Pa) in the head's,
  its efficiency, its rated speed (rpm)"). Writer case `machine.law() instanceof PlungerPump law` →
  `.record(q->q.text("plunger_pump").f64(law.displacement()).f64(law.reliefRise()).f64(law.efficiency()).f64(law.ratedSpeed()))`.
  Reader: `case "plunger_pump" -> new PlungerPump(rated, head, rpm)`. No new field: the four every law carries.
- Corpus image `src/test/resources/compat/fluid/schema-28/` with a world that holds a plunger pump casing, and its case
  in `FluidFactsCompatTest` (see how `schema-19` to `schema-27` were written and are read; the vacuum pump's is the
  precedent). Every older image still reads.
- `FluidCheckpointCodec`: `VERSION=51` with its javadoc paragraph; tag 9 written before the generic case:
  `w.u8(9); displacement, reliefRise, ratedSpeed, machine.speed(), enabled, setFlow, setPressure`; read in the tag switch.
  State: a mismatch drops it, no migration.
- `documentation/reference/FORMATS.md`: the two reserved rows become the real rows in the same commit as the constants
  (`FormatsTableTest`). The `icheme-4` row: take it only if a payload's content changes for the new kind (read section
  "Network" of `FORMATS.md` and `NetworkProtocol` for the rule: a kind sent by enum name to a client that does not know
  it); else remove the row in that commit and say so in the review.
- Equipment facts: the kind is saved by name and goes at the **end** of `EquipmentShape.Kind`. No shape field. If a
  shape or layout corpus test asks for an image with the new kind, add it as the vacuum pump batch did.

### W3. The network's readers of a liquid machine

Each of these tests `liquid()` or the law's type. Read each, decide by the physics, and list file, line and decision in
the review:

| Reader | Expected for the plunger pump |
|---|---|
| `MachineHealth.faults` (line 136) | `LIQUID_MOVER`: cavitation and running dry; no low-flow fault (that is `CentrifugalPump`'s) |
| `MachineHealth` line 211 (low flow) | not reached (tests `CentrifugalPump`) |
| `MoverSuction.readSuctions` (lines 384 to 398) | the liquid suction path: the drawn stream, the vapour and bubble shares, `withHeadShare` a no-op, `npsh()` null so no NPSH3 margin; the suction limit E and p_v as for any pump |
| `DeviceModes` (lines 81, 120) | `PUMP_SUCTION_LIMIT` as for any pump |
| `PassiveStepSolver.wetShare`, lines 1208 and 1219 (`k` 1.4 for a liquid mover) | as a liquid mover |
| `ConservativeTransport` line 597, `MoverUnit` line 96 | as a liquid mover |
| `ViewBuilder` line 44 (the curve of a `CentrifugalPump`), lines 170 to 179 (status, the limit text) | no curve; the status of a pump |
| `Displays` lines 360 to 364 (`MoverState`, churn) | a pump's; churn reads `bubbleVolumeShare` (NaN for one dense fluid above reduced density 0.5: none) |
| `PhysicalFluidTopology` lines 183, 281, 289 | kind `PUMP` for a liquid mover; line 289 replaces an in-line mover's law: confirm a constructed plunger casing never passes there |
| `InletPhase` line 335 (the dry text) | a pump's |

**First measurement, before any equipment work** (a probe under `tmp/supercritical-s8/probe/`, as S4b's): carbon dioxide
at 310 K and reduced densities 0.6, 0.8, 1.0 and 1.6, a vessel, a `FlowControl.Machine(PlungerPump.of(PP-30), 1, true)`
on a line to a second vessel 10 MPa higher. Expected: the delivered suction volume is the displacement less the slip
(0.4 % × 10/25) at every one of the four, with no dry refusal, no suction limit and no hold; the centrifugal pump's head
share at the same states is 0.60, 0.99, 1 and 1 (S4b). If the pump is refused, limited or held at any of the four,
**stop and report** with the row that did it: that is a physics decision, not a patch.
Below reduced density 0.5 the fluid is a gas for every pump (`pumpLiquidLikeWeight` 0): report what the plunger pump
does there (the plan's section 1 says it moves the gas's volume; with `liquid()` true the pump rules decide). Do not
change the rules for it; the review states it as an owner item.

### W4. The equipment

Precedent: every file that names `DRY_SCREW_PUMP` (22 files; `grep -rn "DRY_SCREW_PUMP\|dry_screw_pump" src`).

- `EquipmentShape.Kind.PLUNGER_PUMP` at the end of the enum, with its comment; `mover()` returns true for it;
  `vacuumPump()` does not.
- `science/equipment/PlungerPumpDesign.java` beside `VacuumPumpDesign`, `final class ... extends UnitDesign`:
  `Sizes` as `VacuumPumpDesign`'s (size = the model's number 1 to 4, one length, `Orientation.HORIZONTAL`, row key
  `plunger_pump.model`); `maxSize()` 4, `maxLength` 1, `sizeUnit()` "m³/h", `sizeText` the capacity, `lengthRow()` null;
  `model(shape)`, `law(shape)`, `name(shape)`; `held` (name, capacity, motor rating, within the 180 px the middle
  display has: `UnitTextTest`'s rule, read by the software render since that test is not run); `datasheet`: the model
  with capacity and speed, the relief rise, the shaft power at the relief rise and the motor rating
  (`Motor.ratingFor`).
- `UnitDesign`: `DESIGNS.put(Kind.PLUNGER_PUMP, new PlungerPumpDesign())`; `designSpeed` case; `plungerPump(shape)` and
  `plungerPumpModel(shape)` as the vacuum pump's two.
- `MachineRatings.machineControl`: `case PLUNGER_PUMP -> new FlowControl.Machine(UnitDesign.plungerPump(shape), 0, true)`.
- **The model is drawn already: form A** (owner: "Form A"), in
  `tmp/plunger-forms/src/com/wormzjl/icheme/science/equipment/PlungerForms.java` (untracked scratch in the kit's package).
  `PlungerPumpDesign.build(ProcessUnit u)` is that file's `monoblock(u, d)` with its helpers `cover`, `bearings`, `drive`,
  `powerEnd`, `cradle`, `dampener`, `riser` and its `Dim` record and constants (`XC`, `YC`, `ZM`, `GROUT`, `BASEPLATE`),
  **moved unchanged**: the same expressions, the same order of calls. `Dim(k, cap)` comes from the model (`scale`,
  `capacity`). Do not copy `pots`, `vertical`, `bottle`, `main` or `write` (forms B and C, the scratch export).
  Proof that it is unchanged: export the built design's polygons for the four models the way `PlungerForms.write` does
  and compare with `tmp/plunger-forms/geometry-merged/A_3.json`, `A_10.json`, `A_30.json`, `A_100.json` (written by the
  scratch on the merged kit, 1,275, 1,275, 1,275 and 1,283 polygons with the nozzle flanges the export adds): the same
  polygon count by material and the same bounds. `tmp/plunger-forms/form-a.png` is what the owner saw.
  What the form is, for the texts and the review: a horizontal triplex; an extruded crankcase with bearing covers at both
  ends, a top cover, a breather and an oil sight glass; three crosshead guides; an open cradle with three plungers in
  their stuffing boxes (three `Motion`s, phases 0, 1/3, 2/3, at the train's speed); the liquid end one forged block,
  overhanging the skid, with a bolted cylinder cover in front of each plunger and a valve cover on top of each; the
  suction manifold under the block with its riser at the front; the discharge from the block's back end with a bladder
  dampener on a tee; both flange faces 2 m up on all four models; the coupling and the shaft end at the back.
  Known and accepted: the two small models stand on a plinth about 1 m high (as the small vacuum pumps); no relief
  valve is drawn (it is inside the law).
- The skill's rules of 2026-11-15 (detail in normal textures, polygons for parts with a silhouette): the kit's `bolts`
  and `nut` already draw relief faces on an equipment mesh. Report the polygon count by material for the four models
  beside the dry screw pump's four. Do not remove a part of the form; a part you judge to be relief-only goes into the
  review as a proposal.
- Collision solids and nozzle pipe blocks come from the form's own `Solid`s and `pumpNozzle` calls. The suction and the
  discharge pipe blocks must have a free block between them and stand clear of the shaft block: assert it in
  `ProcessUnitTest` (its nozzle table, about line 337, gets the kind's row; `designsShareOnlyTheKit` must pass).
- The rest of the precedent list: `VesselPlacement`, `ConstructorColumn` (the constructor's entry and hint),
  `AssemblyDiagram` and `ColumnBlueprint` do not apply; the item model `models/item/equipment_plunger_pump.json`;
  `tutorial/pages.json` (the equipment entry); `compat/layouts/layouts.txt` and `EquipmentLayoutGoldenTest` (new rows
  only; an existing row that moves is a stop); `SizingCoverage`, `FlowsheetCatalog`.
- Moving parts and sound by the skill: the plungers slide, the shaft end turns (`ProcessUnit.shown(speed)`, as the
  form's `drive` does); a reciprocating sound from the skill's sources, or the reciprocating compressor's with its own
  pitch if the skill allows a shared sample. A sound that needs a download is a stop: propose it instead.
- Drawings: `./tools/equipment-drawings/update.ps1 -Check`, then `-Only` the new kind
  (`tools/equipment-drawings/README.md`).
- Texts in `en_us.json` only (the twelve keys a casing kind has: see `dry_screw_pump` there), plus the datasheet and
  sizing keys you add.

### W5. The simulator

- `UnitType`: the Pump tile gets its two builds, as the Compressor tile has `PISTON` and `TURBO`:
  `CENTRIFUGAL_PUMP(Kind.PUMP)` and `PLUNGER(Kind.PLUNGER_PUMP)`. Saved by name: a sheet saved before has no type for
  its pump and reads as Auto (the default equal to the behaviour before the field existed). Follow how
  `CompressorSizing` carries its type through `SheetSizing`, `SizingFacts`, `SizingFactsFile`, `SizingPage`,
  `SizingGlass` and `Tiles`. If `SizingFactsFile` or the sheet file needs a version number, stop and report.
- `PumpSizing`: a plunger `Size` (the model's number) beside the centrifugal `Size(discharge)`, sealed as the
  compressor's sizes are. Rows for a plunger size: `Limit.PUMP_RATED` (flow against the model's displacement, with the
  20 % spare: owner rule "always have enough excess") and a rise row against the relief rise (a new `Limit`, SHOULD,
  25 MPa). **No** `PUMP_DENSITY` row, no NPSH rows, no head row.
- Auto (`PumpSizing.auto`): the plunger pump where the flow is at most 100 m³/h and either the rise is above what the
  largest centrifugal casing gives at that flow, or `load.reducedDensity()` is finite and below 0.86 (where S5's
  "Suction density" row would light for a centrifugal pump). Else the centrifugal pump, as before: every existing
  sheet example keeps its pump and its numbers (`FlowsheetExamplesTest`, `CrudeUnitSampleTest`, `SmallCrudeUnitTest`).
- The sheet's power for a plunger size: flow times rise over 0.88. The run itself (`MoverUnit`) does not change.
- `FlowsheetCatalog`: `PLUNGER_PUMP` MODELLED on the pump's tile; `FlowsheetPaletteCoverageTest`; the sizing page's
  size text (`sizing.size.plunger_pump`, as `sizing.size.vacuum_pump`).
- Software renders of the pump door and the sizing page on carbon dioxide at 310 K, reduced density 0.7
  (`tools/heat-gui-renders/`, as S5's `dense()` prototype): into `tmp/supercritical-s8/renders/`.

### W6. Tests, runs, documents

- A dense GameTest in the `icheme_dense_test` namespace (S2c's are the precedent): a motor, a gearbox and a plunger pump
  PP-10; carbon dioxide at reduced density about 0.7 from a vessel at 8 MPa to one at 20 MPa; asserts the delivered
  volume against the displacement less the slip, the ledgers closed, no hold. It removes what it registers. Run it
  alone, once, with `-PfluidGameTestNamespaces=icheme_dense_test` and a fresh `fluidGameTestRunId`. A hold in it is
  diagnosed from its trace, not rerun.
- Runs: `compileJava`, `compileTestJava`; then only the classes your changes reach
  (`node tools/merge-lane/affected.mjs select`, always with the `select` word), task by task with `--tests`, 4 workers,
  each class once (`tmp/supercritical-merge/runs.sh` is a worked example); `equipmentGates` and `columnGates` only as
  far as `affected.mjs` selects them. Not `LanguageFilesTest`, not `UnitTextTest`, not `EngineDigestTest` (known to
  fail at line 313 until the final lane re-records it), no chain-100, no campaign, no whole task. No times in any
  report. One other Gradle run may share the machine; yours run one at a time.
- A reference that moves in an existing test: state the old and new values and the bound; none is expected (no existing
  mover is a plunger pump). An unexpected one is a stop.
- Documents, committed: `documentation/2026-11-15-supercritical-fluids/S8_PLUNGER_PUMP_REVIEW.md` (summary at most 50
  lines; the readers table of W3; the probe's numbers; the polygon counts; the runs' table; the owner items), one history
  line in the batch `README.md` and its Documents line; the code guide chapters the change touches (rotating machinery,
  equipment, simulator sizing, formats) with no history in them; `tools/INDEX.md` if a tool changed.
- Scratch in `tmp/supercritical-s8/` with its row in `tmp/INDEX.md`. Commits: one per work package, each ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `git status` clean at the end. Nothing pushed; `main` and
  the main checkout untouched. No dev client; an in-game check is offered in the review, not started.

### Stops (report at once, with options)

- The probe of W3 shows the pump refused, limited or held on carbon dioxide between reduced densities 0.6 and 1.6.
- `FlowControl`, a solver row or a suction rule would have to change.
- A number beyond the three reserved is needed (a shape field, a sizing or sheet file version, another protocol).
- An existing layout row, an existing nozzle pipe block or an existing reference moves.
- Form A cannot be placed unchanged (a kit rule, a collision with the shaft block, a nozzle rule).
- A sound or any other file would need a download.

### Reporting

Work without ending your turn for progress. Message the parent only: at most once every 50 minutes with a few lines
(done, running, next); at once when a decision is needed, with the options; and with the final report.

## Merge preparation of the supercritical batch (sub-thread, Opus medium)

Owner, 2026-11-16: "Approve all" (this thread, the final lane's thread after it, and version 0.34.0). Earlier: the batch
merges to `main` without a further question once the final lane is clean. So merge preparation is ordered now.

Read first: `AGENTS.md`; "Common rules" at the top of `tmp/supercritical-briefs/BRIEFS.md`;
`.claude/skills/merge-prep/SKILL.md` (this brief carries it out; its rules stand);
`.claude/skills/translation-commit/SKILL.md`; the batch folder `documentation/2026-11-15-supercritical-fluids/`
(`README.md` first: its history lines name every stage, its commits and its review).

State: branch `claude/supercritical-fluid-support-d0ffa4`, this worktree, clean, 85 commits ahead of `main`
(`ab6298d03`, 0.33.0, already merged in). Every stage is built and checked: S0 (CoolProp parity), S1, S2a to S2c, S3,
S4, S4b, S5, the column feed item (D1, D2), S8 (the plunger pump). No production code is yours to change, except what
the tooling cleanup moves. The final lane is not yours: another thread runs it after you, once, on your final commit.

### 1. `CHANGELOG.md`

One entry under `[Unreleased]`, in the form of the 0.33.0 entries (read them): the batch's name and folder, what the
player gets, its main commits (the stage commits from the batch README), the formats it moved (fluid facts schema 28,
checkpoint state format 51; the equipment kind `PLUNGER_PUMP`), and what moved in results (the README and the stage
reviews state each: S3's 30 MPa ranges and the grade pins, the Tia Juana feed at 901 K, the engine digest's line 313
left for the lane). Sections as the file uses them (`Added`, `Changed`, `Fixed`). Do **not** write a version heading and
do **not** change `mod_version`: the main thread does both at the merge (0.34.0).

### 2. The translation commit

`en_us.json` has 35 new or changed lines against `main`
(`git diff main HEAD -- src/main/resources/assets/icheme/lang/en_us.json`): S5's dense label and pump rows, the column's
tray text, S8's plunger pump (constructor, vessel, datasheet, sizing, tutorial). Follow the skill exactly: every other
lang file brought up to date in **one** commit, with the offline checks of `tools/localization/`.
Then run, once, the classes this commit reaches: `LanguageFilesTest`, `UnitTextTest`, and
`SizingPageTest` (its `theDoorFitsInEveryViewForEveryItemInChinese` failed in S8 for want of the zh_cn keys of the Pump
tile's types), plus what `node tools/merge-lane/affected.mjs select` names for the lang files (always with the `select`
word; `tmp/supercritical-s8/runs.sh` is a worked example of filtered runs). A text that does not fit its width
(`UnitTextTest`) is shortened in that language; if the English line itself must change, change `en_us.json`, say so in
the report, and run what that reaches.

### 3. The code guide

`documentation/reference/code-guide/`: for each chapter whose code the batch changed (`git diff --stat main HEAD -- src/main`
against the chapters' code maps), check that it describes the code as it is on this branch: classes, flows, contracts,
defaults, formats and versions, gates. The stages updated their chapters as they went (S5 and S8 did; check the
earlier ones: thermo, fluid laws, the fluid network, rotating machinery, the column, simulator sizing, client screens,
materials, verification, equipment). Set each touched chapter's snapshot line to the last code commit it describes and
today's date. No history in the guide. `README.md` stays under 150 lines. `documentation/reference/FORMATS.md` is already
right (schema 28, checkpoint 51, the fouling batch's reserved rows): do not change it.

### 4. The tooling cleanup

- `perl tools/merge-lane/main-reach.pl .` and the same on `main`'s tree (`git worktree` is not needed: compare with the
  list below). On this branch it names six classes, all older than the batch (`DhBridge`, `DhGroups`, `IrisBridge`,
  `FluidConversionCategory`, `IChemEJeiPlugin`, `ConversionLines`): not yours. If it names a class this batch added,
  move it to `src/test` as the skill says and report it.
- The batch's measurement and investigation material is in `tmp/` (rows in `tmp/INDEX.md`): `supercritical-s2/`,
  `supercritical-s3/`, `supercritical-s4/`, `supercritical-s4b/`, `supercritical-s5/`, `supercritical-s8/`,
  `supercritical-merge/`, `supercritical-main/`, `dense-pump-research/`, `coolprop-compact/`, `plunger-forms/`,
  `supercritical-briefs/`. Sort it by the skill:
  - probes, analysis scripts and harnesses worth running again go to `tools/<tool-folder>/` (one folder per tool, or
    one `tools/supercritical-probes/` with a sub-folder per stage if they share nothing), each with a `README.md`
    (purpose, the batch, how to run, entry points, history) and a row in `tools/INDEX.md`. `tools/coolprop-parity/`
    exists already (S0): add to it what belongs there. The plunger pump's option sheets (`plunger-forms/`:
    `PlungerForms.java` with its three forms, `render.py`) are a tool for the next model choice: keep the source and
    the renderer, not the PNGs, the classes or the geometry.
  - the briefs and the owner's answers are batch documents: `tmp/supercritical-briefs/BRIEFS.md`,
    `MERGE_MAIN_BRIEF.md`, `S8_PLUNGER_PUMP_BRIEF.md`, `MERGE_PREP_BRIEF.md` (this file) go into the batch folder as one
    `THREAD_BRIEFS.md` (in that order, each under its heading), and `OWNER_STANDING.md` as `OWNER_DECISIONS.md`; the
    README's Documents list names both. The `after-*.js` edit scripts are not kept.
  - Gradle logs, stored results, compiled classes, the CoolProp virtual environment and downloaded data stay in
    `tmp/`. Nothing over 1 MB and no image, PDF or log goes into `tools/` or `documentation/`.
  - Material that belongs in `research/` (renders, option sheets, datasets, journals) cannot be written from here: the
    canonical `research/` is in the main checkout, outside this worktree, and a hook refuses that write. Do not try.
    List each such folder in the batch README under its open items ("to copy to `research/2026-11-15-supercritical-fluids/`"),
    with what it holds.
  - Do not delete any `tmp/` folder: that is the main thread's, after the merge.
- The batch README names the tool folders. A `CHANGELOG.md` line only if code left a tracked path.

### 5. Documents

- `documentation/2026-11-15-supercritical-fluids/README.md`: one history line for this preparation (commits, runs); the
  Documents list complete; the open items complete (the owner items of each stage review are listed there by review,
  not copied). Do not set the status or the `documentation/INDEX.md` row to Implemented: the main thread does at the merge.
- `tools/INDEX.md` rows for new tool folders.

### Runs and rules

- Only what a change reaches, once, with `--tests`, `--max-workers 4`: section 2's classes after the translation
  commit; `compileTestJava` after a test or tool move. No whole task, no GameTests, no chain-100, no campaign,
  not `EngineDigestTest` (the lane re-records it). No times in the report. One other Gradle run may share the machine.
- Scratch in `tmp/supercritical-merge-prep/`, with its row in `tmp/INDEX.md`.
- Commits, each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`: the CHANGELOG; the translation
  commit; the code guide; the tooling cleanup with the documents. `git ls-files -i -c --exclude-standard` empty;
  `git status` clean at the end. Nothing pushed; `main` and the main checkout untouched; no download.

### Stops (report at once, with options)

- A chapter of the code guide and the code disagree in a way that looks like a code defect, not a stale text.
- The cleanup would move or detach code that a gate or a documented Gradle task reaches.
- A test of section 2 fails for a reason that is not a text.
- Anything needs a write outside this worktree.

### Reporting

Work without ending your turn for progress. Message the parent only: at most once every 50 minutes with a few lines
(done, running, next); at once when a decision is needed, with the options; and with the final report (the commits, the
runs' table, the tool folders, the research list, what is left for the lane).
