# 2026-11-12 fluid conversion

Status: In progress since 2026-11-12. The plan is reviewed and decided; the model is built here, W1 and W2 run on branch `fluid-conversion-build`. Branch `claude/fluid-conversion-plan-review-786fbc`, cut from `main` at `ccde84295` (0.27.1).

Purpose: a converter between standard Forge (NeoForge) fluids and IChemE fluids.

## The owner's plan (2026-11-12)

Add a fluid conversion system between standard forge fluid and IChemE fluid:

- A converter equipment, which has a tank for forge fluid and another tank for IChemE fluid, and a controller in between. It is scalable with increasing tank size and max conversion rate.
- It can convert in both directions, set in the GUI; it can also operate in a "balance mode" which tries to balance the amount of fluid in both tanks. Max conversion rate can be set in the GUI as well.
- Has an internal level meter connected to DCS and can set a level alarm. Operating parameters can be set by DCS as well.
- Forge fluid converts to IChemE fluid based on a specified rule, with defined composition, pressure, temperature. For example, crude oil converts to medium crude with ambient P, T. Forge fluid is considered volumetric and 1 mB = 1 L. Look for other fluids on the market that can join the pre-defined rule.
- IChemE fluid converting to forge fluid needs a well specified composition range and P, T range. For example, a fluid needs composition A > 40 %, B > 40 %, and C < 5 %, P range 1.0-1.5 bar, and T range 10-40 C.
- Conversion rule defined via data pack, customizable via KubeJS.

## History

- 2026-11-12: batch opened. The plan reviewed for what it lacks (FLUID_CONVERSION_PLAN_REVIEW.md): 25 gaps in six groups, seven of them owner decisions. Documents only, no test run.
- 2026-11-12: the owner decided the seven points (plan section 1): open tank; forward rules few and limited, most use IChemE to Forge; liquids plus gas rules, gas on a closed variant only; rate in process L/s; balance by fill fraction with a deadband; a constructor kind without FE; datapack JSON only for KubeJS. FLUID_CONVERSION_PLAN.md written. Documents only, no test run.
- 2026-11-12: the owner started the build (W1 and W2 in a sub-thread, branch `fluid-conversion-build`), asked for a bigger maximum, for the model to be built in this session and for a pressure safety valve on the gas variant (plan D9, D10).
- 2026-11-12, the model (part of W3): kinds `FLUID_CONVERTER` and `GAS_CONVERTER`, `ConverterDesign` (tanks 1 to 20 m across, shells 2 to 24 m, 7,540 m3 and 7,540 L/s at most), constructor forms under Vessels, the simulator catalog (drawn only, on the storage tank's tile), `en_us.json` keys, drawing scenes, six new layout rows. Drawing only: nothing registers in the fluid network yet. Ran `equipmentGates` on `EquipmentLayoutGoldenTest`, `ProcessUnitTest`, `ShapeRasterizerTest`, `EquipmentShapeCompatTest`, `EquipmentServicesTest` (92 tests pass) and `test` on `FlowsheetPaletteCoverageTest`, `SizingCoverageTest` (8 pass). First views sent to the owner; the look is open (large sizes bare, no ladders, a fixed 3 m bay).
- 2026-11-12, the model, second pass (owner: "smallest one looks weird"; the liquid gauge as an optional accessory, plan D11): the cabinet scales with the tanks (0.55 to 1.7), the bay runs 2 to 6 m, the Forge port's face sits on the nearest block face, the vent, hatch and top stubs scale, the fixed level boards left and `ConverterDesign.gauge` reserves the accessory's place. The six converter layout rows re-recorded; no other row moved. Ran `equipmentGates` on the same five classes (92 pass). The gauge accessory itself is W3 work: it reads a registered vessel's level.
- 2026-11-12, the model, third pass (owner: the pipe connection "in the middle"; "forge drum and IChemE drum needs to clearly distinguish from each other"): the Forge tank is a square sectional panel tank (bare panels on painted seams, corner posts, a flat roof with a hatch), the process tank stays a round painted vessel; the transfer line runs straight through the middle of the bay with the meter half way, the cabinet in the bay in front of it. The six converter layout rows re-recorded; no other row moved. Ran `equipmentGates` on the same five classes (92 pass). W1 and W2 came back built on `refs/heads/claude/fluid-conversion-build-656f98` (`a94aaba41`, `28a6a9464`); not merged here.

## Documents

- [FLUID_CONVERSION_PLAN.md](FLUID_CONVERSION_PLAN.md): the decisions, the physics, the design (equipment, the `Converters` port host, rules, control, saves, GUI), what is ruled out, pitfalls and work packages W1 to W7.

- [FLUID_CONVERSION_PLAN_REVIEW.md](FLUID_CONVERSION_PLAN_REVIEW.md): the gaps in the plan (physics and conservation, engine schedule, rule semantics, balance mode, equipment and integration, saves and verification) and the decisions for the owner.

## Code areas

None yet.

## Open items

- The Forge fluid survey (review Q5, plan W6) and the bundled rule list, for the owner's check.
- The build, W1 to W7; not started.
- The simulator catalog class that AGENTS.md names `FlowsheetCatalog` was not found by that name in `src/main/java` at `ccde84295`; locate it in W3.
