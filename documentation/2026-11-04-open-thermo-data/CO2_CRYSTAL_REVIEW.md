# O11 review: CO2 and its dry-ice crystal in the world list (E25)

Batch [2026-11-04-open-thermo-data](README.md), work package O11, 2026-11-05. Status: stopped before the package change; the owner chooses an option (section 3). Probe: `tools/open-thermo-data/o11/`; outputs in the session worktree's `tmp/open-thermo-o11/`.

## 1. Expected behaviour

- CO2 in `icheme:refinery` (crude_pr78: PR78 with fitted ideal gas). At 1 atm, pure CO2 deposits dry ice below 194.69 K; the triple point is 216.592 K, 0.51795 MPa (Span and Wagner 1996). A CO2-bearing gas cooled through the solid region deposits CO2-I. A view without CO2 keeps its identity and numbers. The column refuses a CO2 island with `CrystalPhasesUnavailable`.

## 2. What the code does

- The dataset can be made. `tools/compound-import` (`selection-co2.json`, new `from_crystal` entry) writes `chemsep_carbon_dioxide` from ChemSep index 909 (chemsep1.xml):
  - Tc 304.21 K, Pc 7.383 MPa, omega 0.223621; Cp eq. 16 fitted over 216.58..900 K (worst 0.046 %); viscosity tables: liquid 216.58..289 K, vapour 216.58..900 K; standard density 816.4 kg/m3 (the saturated liquid at 60 F); freezing data from the crystal anchor (`crystal-anchor-r1`);
  - the Tr = 0.7 volume point (212.95 K, 440.4 kPa) lies 3.6 K below the start of the eq. 105/101 data; PR78 has a liquid root there;
  - the D3 pairs with the 8 original pure compounds and the Tr = 0.7 point, each in a record of its own.
- The anchor represents this family. The probe anchors the crystal on the coefficient (fit) ideal gas. Its pilot run reproduces the gate's numbers (2.84 % at 150 K, -0.060 % enthalpy).

| Check | pilot (spine) | crude_pr78 | reference |
|---|---|---|---|
| PR78 p_sat(216.592 K) | 516056 Pa (-0.37 %) | 515519 Pa (-0.47 %) | 517950 Pa |
| model triple point | 216.5916 K, -0.37 % p | 216.5915 K, -0.47 % p | 216.592 K |
| sublimation T at 101.325 kPa | 194.628 K (-0.058 K) | 194.636 K (-0.050 K) | 194.6855 K |
| worst p_sub error, 150 K..T_t | 2.84 % (150 K) | 3.84 % (150 K, 0.27 K) | SW eq. 3.12 |
| sublimation enthalpy at 195 K | -0.060 % | -0.104 % | 25.2 kJ/mol |

- The engine does not accept the result: crystals compete only on spine packages. With the crystal in `refinery.json`, the catalog refuses the package: "crystals: package icheme:refinery declares crystals without a reference spine, which anchors them" (`MaterialCatalog.build`, line 947).
  - The same rule stands in four more places: `FluidThermodynamics` (line 119) admits crystals only with a spine; `NetworkCrystals.of` (line 88) takes the spine's formation offset; `CrystalPhaseEvaluator.forPackage` and `CubicPhaseEvaluator.forPackage` require a spine (the network builds the fit evaluator itself, `NetworkPhaseEngine` lines 63-71).
  - E25's "already implemented" holds for the pilot family only.
- Identity, measured with CO2 joined without the crystal: the scientific and fluid identities stay the same for a 19-compound crude view and for a CH4, C2H6, H2S and benzene view.

## 3. Proposal (owner)

- **A (recommended): one crystal rule for both ideal-gas sources, then O11 as planned.**
  - The crystal anchors to the family's own liquid on either source. On the fit path the formation offset is 0, as for the free water's ice, which already sits on the network's own datum.
  - Move `CoefficientIdealGas` out of `NetworkPhaseEngine`, so that the evaluator, the contract and the network share it. This deletes the spine-only special case in five places.
  - Risk: a new energy-offset path; the fluid GameTests; one restate for CO2 islands. The pilot's spine path does not change.
- **B:** CO2 joins now, fluid-only: below 194.7 K at 1 atm it stays a supercooled vapour or liquid until A lands (wrong physics, temporary). **C:** CO2 stays out until A.

## 4. Pairs and data choices (owner)

- The 8 dormant CO2 pairs come alive as they are written, with no file change: H2S 0.0967, ethylene 0.0541, propylene 0.0933, 1-butene 0.0593, n-hexane 0.11, cyclohexane 0.1052, benzene 0.0774, toluene 0.1056. A check run of the import reproduces all 8 bit for bit.
- D3 also gives 8 new pairs: CH4 0.0885, C2H6 0.13, C3H8 0.0978 (DECHEMA), iC4 0.13, nC4 0.1352, iC5 0.1219, nC5 0.1252, N2 0.1652. Least change: their own library, `icheme:dwsim_refinery_pr78_co2`, after the others in crude_pr78's list, so D7 moves no view without CO2.
- **N2/CO2: 0.1652 is a transcription error in kij_pr.dat.** After the diagonal, the N2 row is shifted one column:
  - its H2O cell holds -0.0193 (DECHEMA's N2/CO2, p310); its CO2 cell holds 0.1652 (DECHEMA's N2/H2S, p318); its H2S cell holds 0.1641 (DECHEMA's N2/benzene, p343);
  - all six DECHEMA N2/CO2 fits lie between -0.010 and -0.022. Options: (i) an exception at -0.0193, as for CH4/H2S; (ii) D3's DECHEMA rule, -0.0122 (p312, 233..293 K); (iii) keep 0.1652.
- **The live N2/H2S pair (W6) is 0.1641, the N2/benzene value.** DECHEMA gives 0.1652 (256..344 K), only 0.7 % more. Correcting it moves the identity of every view that holds both N2 and H2S (state only).
- Least-change choices recorded, all by the Tier 1 rules: the extrapolated Tr = 0.7 anchor; the standard density at 60 F; Cp held below 216.58 K (part of the 3.84 % at 150 K); `refinery.json` names the dataset explicitly, so the pilot keeps the component's default until O5.

## 5. Not done, and why

- Not done: `refinery.json`, `materials-index.json`, the `fluid_6` preset, the tests, the quick check and chain-100. They depend on section 3, so no product file changed. The dataset stays in `tmp/`; the tool reproduces it. Not run: `ParseCheck`. It would load the import's `components/carbon_dioxide.json`, and the pilot's spine method would refuse a fit dataset. The probe parses the same records and builds `HydrocarbonModel`, which checks the liquid root at the volume anchor. Gradle: lane 2, three filtered runs of the probe only. Code guide: no change.
