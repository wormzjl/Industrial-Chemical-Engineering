# 2026-11-12 fluid conversion

Status: In progress since 2026-11-12. W1 (rules) and W2 (`Converters`, fluid facts schema 24) are built on branch `claude/fluid-conversion-build-656f98`; W3 to W7 not started. Formats: fluid facts schema 24 (was 23); the state format is unchanged (47).

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
- 2026-11-12: W1, the rules: `science/fluid/conversion/` (`ConversionRule` with typed refusals, `ConversionRules` by priority then id with the 2 % hysteresis on the drawn phase, `ConversionLaws` for V = m / rho and the forward litre at the flash's liquid density, `ConversionRuntime`), `material/ConversionReloadListener` registered after the material listener; `JsonFields.object` and `revision` made public. A rule with `to_forge` must name one fluid, not a tag (refusal TAG_TO_FORGE). Ran `ConversionRuleTest` only: 5 of 5 pass.
- 2026-11-12: W2, `runtime/fluid/Converters` (an `IslandCoordinator.Ports`, joined to the world's host after the ejectors with `Ports.both`; `FluidWorldAuthority.converters()`), `ConverterUnit` (the facts), `WorldTopologyLedger` converters, fluid facts schema 24 with the corpus image `compat/fluid/schema-24/`. Decisions in the build: a port node has one kind, so a converter has two hidden ports, a drain void and a fill generator, each behind its own valve and nozzle pipe (two devices that are not pipes never join); the drain leaves a heel of 1 % of the vessel volume (at least 1 L), because a regulated draw that asks for more than the drawn phase holds held the island (singular Jacobian, seen in `ConvertersTest`); a level threshold of none is written -1 (facts numbers are finite); a load returns every account to its Forge tank in whole mB; a reservation is replaced by the next plan and lapses 200 ticks after its slice. Ran `ConvertersTest` (5 of 5), and the classes of the changed facts, ledger and codec: `FluidFactsCompatTest` 20, `FluidCheckpointCodecTest` 13, `WorldTopologyLedgerTest` 14, `MixedSetMergeTest` 4, all pass. Conservation over 100 slices each way (time factor 1, open 1 m3 tank, water rule): forward 131.4508 kg, 131.84 L at 997.05 kg/m3, Forge 1368 mB + 0.16 L account + 131.84 L = 1500 L, the tank's water 131.4508 kg; reverse 67.7989 kg = 68.0000 L, the Forge tank 1368 to 1436 mB, remainder 5.7e-7 L; port mass and energy equal the island's boundary bookings. `FluidWorldAuthority` wiring is checked by compile only (JUnit does not build the authority).

## Documents

- [FLUID_CONVERSION_PLAN.md](FLUID_CONVERSION_PLAN.md): the decisions, the physics, the design (equipment, the `Converters` port host, rules, control, saves, GUI), what is ruled out, pitfalls and work packages W1 to W7.

- [FLUID_CONVERSION_PLAN_REVIEW.md](FLUID_CONVERSION_PLAN_REVIEW.md): the gaps in the plan (physics and conservation, engine schedule, rule semantics, balance mode, equipment and integration, saves and verification) and the decisions for the owner.

## Code areas

- `src/main/java/com/wormzjl/icheme/science/fluid/conversion/`: rules, matching, volume laws.
- `src/main/java/com/wormzjl/icheme/material/ConversionReloadListener.java`: `data/<namespace>/icheme/fluid_conversion/*.json`.
- Fixture rules: `src/test/resources/fluid_conversion/fixture/`.
- `src/main/java/com/wormzjl/icheme/runtime/fluid/Converters.java`, `ConverterUnit.java`; converters in `WorldTopologyLedger`, `FluidFacts` (schema 24), `FluidCheckpointCodec.sameWorldBody`, `FluidWorldAuthority`.
- Tests: `ConversionRuleTest`, `ConvertersTest`; `FluidFactsCompatTest` writes and reads the schema-24 converter.

## Open items

- The Forge fluid survey (review Q5, plan W6) and the bundled rule list, for the owner's check.
- The build, W3 to W7; not started.
- W3: place the two hidden ports, valves and nozzle pipes; call `Converters.register` and `remove`; call `Converters.release` when the island restates or holds; grow an island whose forward rule names a compound it does not carry (now NO_RULE), as `ColumnOperation.grow` does; give `Converters.tags` the fluid registry's tags.
- W4: settings, thresholds and alarms as queued engine events (`Converters.settings` is the apply); the balance measure of the gas converter (now the condensed volume fraction).
- The drain heel (1 % of the vessel volume): a game-level choice, open to the owner's veto.
- `FluidCheckpointCodec.sameWorldBody` compares no shaft units, stores or pump health (before this batch); a change of only those may not rewrite the topology unit. Not changed here.
- The simulator catalog class that AGENTS.md names `FlowsheetCatalog` was not found by that name in `src/main/java` at `ccde84295`; locate it in W3.
