# Revised cooling tower plan

Owner revisions, 2026-10-30. This supersedes the initial four-nozzle/float/drift proposal. The latest revision keeps the inlet at the top of the water section and places the outlet at the basin bottom, removing the earlier pickup riser. Add the drum-style level-gauge mount; control linking is deferred at the owner's request.

## Structures

Induced-draft cells are 4, 6 or 8 m wide with 2–6 m fill in 1 m increments, default 6 m and 2 m. `TowerBank(columns, rows)` is 1–6 columns and 1–4 rows, default one and maximum 24 cells. Each cell repeats its own top fan, fill, distributor and eliminator. Common headers and a shared metal basin serve the bank on a steel frame, with no concrete support. Every cell receives the same share of return water in parallel. Larger duties require additional player-built banks; equipment never expands automatically. Existing dry cooler series/parallel semantics stay unchanged.

Natural towers have one size input: diameter 40–80 m in 10 m increments. Height is automatically twice diameter, default 60 × 120 m. Their hyperbolic shell remains sparse and hollow. About 2 m circumferential chords and 4 m vertical bands reduce geometry cost. A cached GPU mesh draws the chimney in one call; upper wall blocks retain collision and placement occupancy but have no duplicate chunk quads. The construction preview uses whole facets rather than block-cut polygons.

Both towers expose one water inlet at distributor elevation (fan fill depth + 5.5 m, natural 13.5 m) and one water outlet at the basin bottom, with its centre 0.5 m above the base. There is no internal outlet riser. Basin hydrostatic head and external piping determine outlet flow. Return and makeup share the same inlet. There is no separate makeup nozzle, internal float valve or external overflow nozzle. Existing open-vessel spill accounting handles water above freeboard. Basin operating depth is 2 m, with 1 m freeboard, initially empty. A bottom level-gauge mount uses the existing drum sensor path; valve linking remains later work.

## Physics and control

Ambient is fixed at 25 °C, 50% relative humidity and 101325 Pa. Eight steady fill segments couple sensible transfer and evaporation/condensation against local humid air, with ideal humid-air relations, a unity Lewis relation and the network's water properties and enthalpy references. The shared coefficient is a **game calibration**: 2 m fill, both water and dry-air loading 2 kg/s/m², cooling 35 °C water to 25 °C under that ambient.

Signed airflow balances quadratic fan pressure, resistance and humid-air buoyancy. Stopped fans permit passive flow; cold water can reverse draft. Fan power applies the fan laws at the declared full-speed air loading: for a fixed tower it is proportional to speed³ and independent of the solved air or water state. Wet basin surface sensible exchange remains with no spray, but the basin itself does not evaporate or condense. Fill transfer requires sprayed water, is the only evaporative loss and retains no fill inventory. Droplet drift is removed entirely.

Only pure liquid water that stays liquid at atmospheric discharge is supported, including 80 °C natural-draft feed. Unsupported material closes the incoming path and preserves upstream contents, with no freezing-point cutoff. Water exchange, sensible transfer and carried vapor enthalpy enter Newton, conservative reconstruction, accepted audits and certificate replay. Evaporative duty is diagnostic, never a second latent sink.

The fan choices are **Fans control mode** (manual speed, full speed by default) and **Target temperature mode**. The latter controls water leaving the fill before basin mixing, independent of basin holdup. It saturates and reports an unreachable target without holding supported operation. Without spray it stops the fans; the wet basin still exchanges sensible heat with ambient. Natural draft stays passive. There is no self-recirculation.

## Presentation and persistence

Readings start with inlet temperature, basin temperature and bottom pressure. One cooling duty combines sensible and evaporative duty. The compact page retains inlet flow, fill evaporation and fan power/speed where applicable. It omits airflow, ambient top pressure, mass, net outflow, separate makeup and cold-water supply rates, drift and the phase-composition group. Cached scheduled open-basin displays drive visible water height and fan animation; GUI opening does not solve contact.

Persist geometry, bank, controls, basin contents and accepted ledger totals as facts. Calculated transfer can be rebuilt. Append the two equipment kinds and named water roles and keep historical compatibility images. State format 30, facts schema 6, shape fields 4, protocols fluid-17/equipment-15 and certificate graph identity 9 cover this unreleased batch.

## Qualification

Check startup, circulation, starvation, final drawdown, spill, shutdown, save/load and replay; per-cell scaling through 24 cells and oversize rejection; allowed dimensions, nozzles, hollow collision, placement, restoration and geometry cost; condensation, flashing rejection, supercooled liquid, stopped fans, zero return and reverse draft. Run quick/slow gates, exact chain-100, fresh fluid GameTests and the three established serial 1000-seed campaigns. Use one native Minecraft MCP client session for GUI, water/fan rendering and natural-tower performance checks.

Excluded: treatment chemistry, automatic blowdown, weather, plume transport, droplet drift, retained fill inventory and gauge-to-valve linking.
