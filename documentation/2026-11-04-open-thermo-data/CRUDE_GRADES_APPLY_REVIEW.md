# O3 review: the four crude grades applied (2026-11-05)

Work package O3 of [OPEN_DATA_PLAN.md](OPEN_DATA_PLAN.md), decision E1. Commits `f12f8f15d` (data, libraries, tests, fixtures), `7b141f79c` (sample sheet, sizing gates), `ed9208252` (tools).

## 1. Expected behaviour

- The game reaches only the generated grades `icheme:light_crude`, `medium_crude`, `heavy_crude`, `oil_sand_dilbit` (tools/crude-grades `generated-r1`): fluid presets, the network default, the default column preset, the client libraries, the example sheet. No ExxonMobil value stays in `src/`. Tia Juana stays a test-only fixture.
- The default column preset converges on the classical initializer, and each side draw takes at most 80 % of its tray's liquid.
- A saved world loses nothing: no saved format may name a removed preset.

## 2. What changed

- `src/main`: four assays and `fluid_2..5` (the grades); network fluid presets water, nitrogen and the grades; default column preset `medium_crude` (the generator's draft, unchanged); `crude_unit.json` on the medium grade. Removed: five ExxonMobil assays, `fluid_3/4`, 12 client files, 14 en_us keys (10 added). Other lang files wait for the translation commit.
- Tia Juana (tjl20 and tjl19 assays, tjl19 package, `column_literature`, `column_tia_juana`, `column_tia_juana_nitrogen`, its fluid preset) moved to the overlay `src/test/resources/materials/tia-juana/`, read by `TiaJuanaTestCatalog` and `TiaJuanaExtension` (added to the columnGates and equipmentGates helper lists). TJL physics pins kept Tia Juana with no re-pin; tests of the bundled defaults moved to the grades.
- Saves (search of every codec): no saved format stores a fluid or column preset id. Package and assay ids in design tags and simulator doors are labels never looked up. No alias.
- Fixtures rebuilt: crude-assay-conversion, column-preset-inputs, column-draw-volume-targets, regrouped-authored-feature-recovery, `compat/presets` images.

## 3. Measurements

- Default preset, classical start, before (Tia Juana) / after (medium): control polls 219,616 / 316,325; products kg/s overhead 1.64 / 2.68, distillate 13.78 / 9.45, draws 15.92, 23.63, 9.64 / 18.21, 22.75, 10.73, bottoms 94.35 / 95.68, free water 5.97 / 5.89. Draw share of tray liquid 0.14, 0.15, 0.06 / 0.18, 0.15, 0.07; flood 0.68 on tray 34. Controls as drafted (638.15 K feed, 250 kPa top, reflux 4.17, condenser 332.15 K, steam 330.7 mol/s at 533.15 K).
- Other grades: light 549,497 polls, heavy naphtha 241.7 mol/s against 998 mol/s of tray liquid (0.19), flood 0.90; heavy 551,509; dilbit 1,325,207 (coupled ramp). The 80 % draw rule holds everywhere; no draw lowered.
- Feed-temperature grid, classical start, 622 to 644 K (10 points): Tia Juana 10 of 10; medium 5 of 10. It fails at 631.28 (the sample's valve flash), 634, 636, 640, 644 K: stage continuation stalls at 30 stages, before any draw is added (draws x0.8 fail at the same points with the same polls). Feed tray +-1 and steam x1.5 move the failures but do not remove them.
- Hold-fuzz: the harnesses load the same Tia Juana crude through `TiaJuanaTestCatalog` and no preset, so no 200-seed quick check. Smoke runs at 4 threads: bundled random seeds 1-20 equal O4's stored counters except seed 6 (O4's 8-thread wall-deadline hold, clean here); cold random 1-10 equal.
- chain-100 exact: 0 deviation on every quantity.
- Tests: every class that loads the changed data (test 1436, columnGates 432, holdGates 130, equipmentGates 3 classes), then the failed classes after their fixes, the 4 sizing gates, `PumpRateWarmStartTest` and the 18 other `SheetFixture` users (177 tests): all pass but `FluidTpEquilibriumPhUvTest.freeWaterRoundTripsAndBoiling` (P1, the same 499999.99346 Pa). Not run: fluid GameTests (3 edited), `LanguageFilesTest` and `UnitTextTest` (they wait for the translation commit).
- Sample sheet sizing moved: condenser 5 to 4 shells (522 m2), E-101 tubes 5 to 6 m, H-101 cabin 12.0 to 11.0 m (80.3 MW bare), naphtha sphere 17 to 14 m, CWS/CWR 0.7 to 0.6 m, KERO 0.2 to 0.25 m, AGO 0.15 to 0.2 m, overhead/receiver/pump member lines one bore less; P-102 green to yellow (69 % of rated, PUMP_RATED); deciding limits OFFGAS speed to drop, NAPHTHA drop to speed. Column 8 m and every other size stand.

## 4. For the owner

1. The classical start is fragile on the medium grade (section 3). The in-game sample closes at its authored conditions, but the coupling gates `FlowsheetColumnTest` (8 of 10 failed) and `UtilityRegulationTest` (a 10 % crude cut failed) now run on Tia Juana: the test column, and the sample as it stood (test resource). Two of them also read a block energy gap of 59 and 65 kW, 2.1e-3 to 2.2e-3 of the condenser duty, over their 2e-3 rule. Options: (a) a solver task on stage continuation for the medium grade, and a look at the simulator-to-V3 enthalpy gap; (b) wait for O8's learned seed; (c) another default grade or feed temperature. Proposal: (a).
2. Least-change choices: the pot template kept (no crude data); `PumpRateWarmStartTest` on the heavy grade, first setpoint 1.0 not 1.5 (the light grade boils at 1 atm, the heavy one meets the 5 bar head limit at 1.5); `FluidThermodynamicsTest` leaves the dilbit out (no free water at 350 K); the regrouped recovery fixture on the heavy grade at the holdout flow; compat image package `icheme:medium_crude_tjl20`; mixture names "Medium crude + 2 % nitrogen" and "Medium crude residue".
3. Left for O5: evidence texts naming TJL19 and "global Dalia transport" in `packages/tjl20.json` and `refinery.json`, and code comments naming TJL19.

## 5. Open items

- The neural model does not bind the medium default (O8). Code guide: changes due at merge (material catalog, presets, the simulator sample).
