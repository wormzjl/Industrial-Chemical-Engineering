# Unused data applied (O5)

Batch [2026-11-04-open-thermo-data](README.md), plan O5, inventory [UNUSED_DATA_REVIEW.md](UNUSED_DATA_REVIEW.md) (rules R-main, R-test, R-XOM, R-fact). Owner: E3 "Remove unused data"; E25 the pilot to the test overlay. Date 2026-11-05. Code guide: no change now; at merge `thermo-materials.md` (bundled packages, record kinds) and `README.md` line 183 must say the pilot is a test overlay.

## 1. Inventory at HEAD f5f871c44

28 items from section 7, README item 4 and NIST section 5: 13 done already (O3 f12f8f15d, O4 34573f70e/7972a2a9a, O11 19a544b82), 11 done here, 5 wait, 1 kept. List: session worktree `tmp/o5-removals/inventory.md`. A git grep of the five old assays' mass amounts and the sidecar's composition edges finds ExxonMobil values only in `data/icheme/neural/` (E26) and in what this task removes.

## 2. Removed or moved, by rule

- R-XOM (`2f59caa62`): `tools/wp5-legacy-digest/src/src/` (320 files, also the NIST nitrogen); the README keeps the `git archive 959ea0d src/main` recipe. The two WTI world dumps went to the main checkout's `research/2026-11-04-open-thermo-data/xom-copies/`; their tools read them there. `CRUDE_REGROUPING_PLAN_REVIEW.md`: the Dalia MW and density, the PC13 shares, the heavy-cut viscosities, the four coefficients and the two ratio tables were removed. The reasoning stays, with a note.
- NIST (`2f59caa62`, `4e2a77b9f`):
  - The WebBook fetchers `examples/Build-NitrogenFluidProperties.py` and `Fetch-FluidSteamReferences.py` went to `research/.../nist-copies/examples/`. No build step, test or tool read them.
  - cdu17 `water.json` takes the bundled `water-iapws95-shomate-r3` (it carried the NIST Shomate).
  - `tjl-reference-resources.json` `test:tjl_methane`: its Cp fit is now the bundled `tjl20-methane-spine-r3` (it was `nist-r1`).
  - The wet-boundary fixture was re-made by its teacher, because it names that identity: condenser outlet 333.85331 to 333.85278 K, seed flows within 8.4e-5, stage temperatures within 1.6e-6 relative.
- R-test, E25 (`bc471a90c`): 12 records moved to `src/test/resources/materials/pilot-cryogenic/` with `git mv`: the package, the method, the interactions, the E-PPR78 matrix, 4 `pilot_*` datasets and 4 spines.
  - The game's `components/carbon_dioxide.json` now names `chemsep_carbon_dioxide`. The overlay has its own copy, which names `pilot_carbon_dioxide`, so the pilot package record is unchanged (no revision bump, same identity).
  - `materials-index.json` lists 119 files.
  - `PilotCryogenicTestCatalog.withPilot()` (bundled + pilot, the game's network) and `over(base)` are new. About 45 test classes read the pilot through them.
  - What stays in `src/main`: the CO2-I crystal and `chemsep_carbon_dioxide` (`icheme:refinery` declares them). A reachability check found that no spine or E-PPR78 record is reached without a spine package.
- Other (`b89cb024e`):
  - Evidence texts of refinery, tjl20 and tjl20_nitrogen: "frozen TJL19 reconstruction" now names the DWSIM Tia Juana characterisation, and "global Dalia transport" now names Twu + ECCC. Evidence is in no identity (MaterialCatalogTest option B).
  - Comments naming TJL19 now name the test fixture.
  - The Tia Juana `crude_unit.json` copy lists CarbonDioxide. `UtilityRegulationTest` reads it, so it stays.

## 3. Waiting or kept

- E26 (the retrain owns `data/icheme/neural/` and `tools/neural/`): the sidecar's ExxonMobil compositions, the payload, `regrouped-model-provenance.json`, `regrouped-predictions.json`, and the manifests that hash the removed `tools/neural/fit_methane.py` (`transformer-accuracy-cache-manifest.json`).
- Translation commit: the other lang files still hold the five crude preset and template keys.
- Owner items: git history, the local PDFs, the size of `tools/`.
- Kept: `tools/spine-join-measurement/build-spine-records.mjs` (3 JANAF S values). It is the generator that the 4 spine records' `source` names, and the MethaneSpineTest and ReferenceSpineTest comments cite it.

## 4. Save check (R-fact)

Writers searched: FluidSavedData and FluidCheckpointCodec, FluidFacts, the accessory and instrument facts, AssemblyStore, ColumnDesignTags and the block entities, RecoveredSolidsItem.
- None stores a moved record id: no property, method, interaction, spine or matrix id.
- Island facts store the package id. A load runs every island on the current network package, whatever id it names (`FluidWorldAuthority.model`).
- Compound ids and the crystal id are unchanged.
- A save could name `icheme:pilot_cryogenic` only through a third-party datapack override of the network. No alias is needed.

## 5. Tests

- g1 (35 classes, 248 tests: the readers of the cdu17 water and the TJL reference): all pass but the wet-boundary test, whose fixture its teacher then re-made.
- g2 (68 classes, 392 tests in `test`, `columnGates` and `holdGates`; everything but the last fix): 6 failures, all "Unknown material package: icheme:pilot_cryogenic" in MethaneSpineTest, PilotVolumeAnchorTest and G3F5CriticalRegionPathTest, which read the pilot through `bundled()`. MaterialCatalogTest, the catalog gates and the holdGates samples pass.
- After the fix (`withPilot()`), those 3 classes alone, run by the main thread: 8/8. Nothing else changed since g2. All runs used lane 2 at 4 workers, beside the retrain's N2 campaign; none measured time.

## 6. Open

- The code guide chapters at merge (above). Lane: fluid GameTests and whole suites.
