# Water steam: second virial from IF97 Region 2 (option B, owner 2026-10-28)

Branch `claude/water-steam-virial` from `claude/heat-exchanger-design-cf05d1` (1a339b18). Identity `separate-free-water-v3`.

**Expected.** Steam at 10 bar is a real gas: IF97 Region 2 gives Z = 0.929 at saturation (453 K), so ideal steam is 7.6 % too large in volume and 64 kJ/kg too high in enthalpy. The second virial B(T) carries most of that at network pressures. **Before:** the free water's steam was ideal (v = RT/p, h = h_ig), qualified to 0.125-2 MPa, so the fired heater's 10 bar water raised the notice. **Now** (`SteamVirial`, five I = 1 terms of Region 2): v = RT/p + B, h = h_ig + p(B - T B'), s and g likewise; p_w = n_w RT/(V - n_w B) in the hydrocarbon vapour's volume (Dalton). Condensed water and ice carry the steam's fugacity at their vapour pressure (ln phi + B p_sat/RT), so the melting line and the stable phase stay where they were. Below 273.15 K B continues linearly (C1). The network (`FluidThermodynamics.state`, velocity stiffness p_w V/(V - n_w B)) and the engine (`FluidTpEquilibrium`: wet coexistence, boiling, triangle corners, gas phase) share one implementation.

**Error against full IF97 Region 2** (molar volume; virial / ideal), with h_res in kJ/kg (IF97 / virial; ideal 0):

| State | Volume error | h_res |
|---|---|---|
| 1 bar sat (372.8 K) | +0.08 % / +1.6 % | -13.2 / -12.4 |
| 10 bar sat (453.0 K) | +1.05 % / +7.6 % | -64.0 / -52.9 |
| 10 bar, 600 K / 800 K | +0.05 % / +2.1 % ; +0.003 % / +0.67 % | -21.9 / -21.3 (600 K) |
| 49 bar, 584 K | +2.0 % / +14.6 % | |
| 100 bar sat (584.2 K) | +16.1 % / +49.5 % | -373.6 / -229.7 |
| 100 bar, 700 K / 800 K | +1.45 % / +14.2 % ; +0.36 % / +7.5 % | -158.4 / -139.5 (700 K) |
| 100 bar / 500 bar, 1073 K | -0.004 % / +1.9 % ; -0.83 % / +9.2 % | |

Isothermal compressibility at 10 bar sat: -2.3 % (ideal -8.2 %); at 100 bar sat -31 % (ideal -46 %).

**Limit table** (`waterVaporPressureLimit`, computed, not taken from the brief). Each band's limit is the pressure where the virial volume first leaves 2 % of Region 2 at the band's lower edge, rounded down to two digits (`SteamVirialTest` sweeps 275-1073 K and checks safety and tightness per band). Below 500 K: 1.8 MPa. From 500 K: 2.2; 525: 2.8; 550: 3.6; 575: 4.5; 600: 5.5; 625: 6.7; 650: 8.1; 675: 9.6; 700: 11; 725: 13; 750: 15; 775: 18; 800: 22; 825: 27; 850: 34 MPa. From 875 K: 65 MPa (Region 2's least crossing up to 1073 K, 65.5 MPa at 1000 K). The virial holds 2 % up to saturation as far as 480.9 K (18.2 bar), not the 12 bar the brief estimated; at 584 K the crossing is 4.9 MPa, as the brief said. The ideal table was 0.125 to 2 MPa. Water at 10 bar no longer raises the notice.

**Tests that moved** (only what the new physics moves):
- `LegacyNetworkPathPinTest`: revision literal v2 to v3. Digest 37c1b702... to 02fec125..., because the pinned path carries the steam's volume and energy.
- `FluidPropertyCoverageTest`: all 13 NIST steam points now lie inside the limit. Before, one was rejected and the compression check accepted 12. The virial volume and 1/(pZ) match NIST within 0.5 %; the ideal gas's errors there reach 2.0 %. The JSON references are unchanged. The coverage beyond the limit moved to `SteamVirialTest`, against Region 2.
- `NetworkPhaseEngineTest` hot gases: the worst p_w/(y_w P) ratio is 0.0507. N2 at 600 K and 10 MPa has Z 1.036, and the steam adds B p_w/RT = -1.8 %. The bound goes from 4 % to 6 %.
- `FluidTpEquilibriumP3Test`: the state beyond the limit is now 500 K at 4 MPa (p_sat 2.64 MPa against 2.2 MPa); 450 K at 2 MPa now lies inside. The expected steam volume is now the virial's.
- `FluidTpEquilibriumPhUvTest` boiling and `FreeWaterIceTest` (balance, triple corners): their expectations are rebuilt from the virial h, u and v. Ideal-based expectations missed the steam by 0.5 % at 1 bar (0.37701 mol against 0.375; h_res is -224 J/mol against a 40.7 kJ/mol latent heat) and by 2e-5 at the triple point. The evidence text "ideal steam's" became "steam's".
- `PhaseTestSupport:208`: reads the table, no edit. New: `SteamVirialTest` (4) with the test helper `If97Region2` (all 43 terms, Region 4, B23).
- `HeatSideNetworkTest.aJunctionTheHeatBoils...`: re-recorded at 22 m2 and 540 K (was 20 m2 at 520 K); see finding 1.
- chain-100 re-captured (4521b65c). Each unit holds 0.2 mol water at 350 K and 1.5 bar, where B p_w/RT is about -0.9 %. Against the v2 capture: moles 2.7e-6 relative, free-water volume fraction 2.1e-6 (gate 1e-6), 5.7e-5 K.

**Saves.** No material revision and no facts change. Corpus image `compat/fluid/steam-v2` was written under v2: 1 m3, N2 with 145.5 mol steam, 661 K, 10 bar. It loads by route FACTS. Moles, wall volume, T and P are kept. p_w 7.98 bar, contents' volume 0.9911 m3. U changes by -25.1 kJ = sum of -n_w p_w T B' (within 1e-3), and the ledger books exactly that. A reload is EXACT.

**Findings for the owner / heat batch.**
1. The step's start flows ignore the arriving-phase seed. `startPoint` takes them from the graph's liquid states (13.4 kg/s), while the two-phase root is at 2.4 kg/s. The Newton then crawls along the vapour's bound: each step is cut to 1 % of the distance to zero. Whether it escapes is chance. A sweep of 16-24 m2 x 500-540 K: the ideal steam boils correctly in 10 of 15 cases (3 fail, 2 end superheated liquid), the virial in 11 of 15 (3 fail, 1 liquid: the old 20 m2 / 520 K point). Where both boil they agree: 452.9 K at 9.97 bar, 2.41 against 2.47 kg/s. Proposal: start a seeded junction's lines from their law on the seeds. This changes the step solver and the chain digests, so it is the owner's call.
2. Restatement is a TP flash at the saved P (`FluidFacts.rebuild`). The denser virial contents fill 0.89 % less than the wall until the first solve, which then moves T a little. A UV/TV restatement would keep T and V exactly. This predates this change.

**Gates.** All on code commit 3a527f1e: `test` 1709 tests in 298 classes and `slowGates` 188 in 18 classes, 0 failures, 0 skipped (one `test slowGates` run, 4 min 58 s); fluid GameTests 35/35 on the fresh run id `steamvirial1`. chain-100 (`fluidSolverRegression`) is exact against its re-capture (4521b65c). Not run: the campaign, which is not in this brief. Code guide: `equilibrium-engine.md` and `fluid-science.md` (snapshot 3a527f1e), README file count. Tool folders: none. Scratch stays in `tmp/steam-virial/` (VirialTable sweep, probes); `If97Region2` is a test helper that the gates run.
