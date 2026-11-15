# Fluid interfaces and pipe accessories

Batch started 2026-11-15 on `claude/fluid-interfaces-a51f3a`, cut from `claude/equipment-visual-design-d4e22c` at `9c777e86e` (plan D12). Status: In progress since 2026-11-15.

Class: PHYSICS (new devices; no existing reference may move). Lanes touched: thermo (data only), fluid laws (`FlowControl`), runtime (`FluidWorldAuthority`, `FluidFacts`, `Converters`), flowsheet and control (`runtime/instrument`, `runtime/control`), equipment (`world/**`, `registry`, new geometry files), client.

Purpose: an orifice plate, an automated shutoff valve, a liquid hopper, a gas intake with the rare gases, a placeholder crude oil Forge fluid and JEI pages for the fluid converter.

## The owner's request (2026-11-15)

1. Add an orifice plate.
2. Add an automated shutoff valve as an accessory.
3. Add a liquid hopper: a simple conversion interface; the player puts a Forge fluid in (right click with a filled bucket, for example) and it converts to IChemE fluid at a small rate ("100mb/s?").
4. Add a gas intake that converts air into IChemE fluid (N2 + O2 + water + rare gases); it must work well with a compressor. Add the rare gases to the compound database.
5. Add a placeholder crude oil Forge fluid "with the same id as most mods" when no other mod adds one.
6. Add JEI support for the fluid converter.

The owner accepted the plan's decisions D1 to D12 on 2026-11-15: "Liquid hopper 4000mb, others go" (D5's Forge tank is 4 000 mB).

## Decisions (plan section 3)

- D1: the orifice plate is a restriction orifice (a fixed pressure loss set by its bore ratio), not a flow meter.
- D2: bore ratio 0.20 to 0.80 in steps of 0.05, default 0.50, set on the item.
- D3: the automated shutoff valve is on/off, by hand or by a local controller or DCS loop; no FE, fail position or redstone.
- D4: an on/off loop uses the controller's existing settings: `OUTLET` opens above the target and closes below it, `INLET` the reverse, a band of 2 % of the target each side; no format change.
- D5: the liquid hopper is one block, a 4 000 mB Forge tank over a boundary at ambient pressure plus 0.5 m of head; no vessel, no settings.
- D6: hopper rate 0.1 L per process second; liquid forward rules only.
- D7: intake air: dry air N2, O2, Ar, Ne, He, Kr, Xe, wetted to 50 % relative humidity at 25 °C, at 25 °C and 101 325 Pa; no CO2.
- D8: the intake is one boundary block: flow enters only; loss coefficient K = 3.
- D9: the crude oil Forge fluid `icheme:crude_oil`, always registered, in `c:crude_oil`, hidden when another mod's fluid is in the tag; no world generation.
- D10: JEI: one category "Fluid conversion", one recipe per rule; the rules reach the client in a new packet.
- D11: no new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` or `TopologyCompiler.Kind` constant.
- D12: branch base `claude/equipment-visual-design-d4e22c` at `9c777e86e`.

## History

- 2026-11-15: batch opened; the plan copied in ([FLUID_INTERFACES_PLAN.md](FLUID_INTERFACES_PLAN.md)). Documents only, no test run.

## Documents

- [FLUID_INTERFACES_PLAN.md](FLUID_INTERFACES_PLAN.md): decisions D1 to D12, the physics with numbers, work packages W1 to W8, what is ruled out.

## Code areas

(filled in per package)

## Open items

- W1 to W8 to build.
