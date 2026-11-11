# Cooling tower: one atmospheric contact

Batch 2026-11-11-aqueous-solvents, W3b. Owner, 2026-11-11: "Remove cooling tower composition requirement, allow evaporation of non-water compounds"; "make cooling water saturated with air as well"; "The cooling tower is performing heat and mass exchange with air, and any gas phase in the inlet stream gets removed, it should be coherent in design"; "you need to work out the design details a bit more". This plan replaces W3B_NETWORK_PLAN.md sections 20 and 27. Numbers marked (hand) are hand estimates, not engine output.

## 1. Expected behaviour

A cooling tower sprays a stream into air at atmospheric pressure. Three things happen, in this order along the stream's path:

1. At the nozzle the stream comes to atmospheric pressure. Gas it carries joins the air. A liquid that is above its bubble point there (hot water under pressure, a liquid holding more dissolved gas than one atmosphere keeps in it) flashes, and that vapour joins the air too.
2. In the fill the liquid and the air exchange heat and every compound, each towards equilibrium with the other: volatile compounds leave the liquid in proportion to their partial pressure over it, their latent heat cools it, and the air's nitrogen and oxygen dissolve into it.
3. The liquid that is left, and any solids it carries, falls into the basin.

Steady numbers (hand): 2 kg/s of water from 35 to 25 C against 2 kg/s of dry air gives up 83.7 kW and evaporates about 0.03 kg/s (1.5 %). Water with 10 mol % ethanol at 35 C stands at about 4.6 kPa of ethanol and 5.2 kPa of water, so of about 1.6 mol/s evaporated 0.77 mol/s is ethanol (35 g/s, 8 % of the ethanol the stream brings) and 0.86 mol/s water (15 g/s). Water arriving at 380 K flashes 1.3 % at the nozzle and enters the fill at 373 K. Water saturated with methane at 10 bar (2.5e-4 by moles) loses 90 % of it at the nozzle. Water leaves the fill with about 8.8e-6 of nitrogen and 4.7e-6 of oxygen by moles at 25 C.

## 2. What the code does

On main: a spray inlet takes a stream only if its liquid is water alone (`TowerExchange.liquidWater`); anything else closes the inlet (`WATER_ONLY`). The fill (`TowerTransfer.contact`) is Merkel's rating on water's saturation curve with a constant heat capacity; one loss, water. The inlet is rebuilt from the donor's bulk state, not from the stream the transport carries.

In the sub-thread's uncommitted draft (built to section 20): no refusal; `TowerTransfer.Liquid` reads the engine at the inlet and at a second temperature and keeps `ln(phi_i P) = a_i - b_i/T`; the fill's potential holds every compound below its critical temperature; compounds above it leave in equilibrium with the ambient air; losses are one rate per component; the leaving liquid's temperature comes from partial molar enthalpies (`vapour - R b_i`). Still open in it: the stream is still the donor's bulk; arriving gas goes to the basin; a boiling liquid has no contact (`BOILING`); the critical-temperature test puts a jump in the tower's performance at 304 K for water holding carbon dioxide (below it the dissolved gas inflates every compound's saturated content by the factor `1/(1 - r)`, above it not); each exchanging component costs two extra kernel evaluations for its vapour enthalpy.

## 3. Design

### 3.1 The stream (per spray edge)

The tower acts on the stream the transport delivers to the basin on that edge, not on the donor's bulk: per component, the gas `g_i` and the liquid `l_i` in up to two liquid slots (mol/s), with the gas phase's enthalpy as transported and the donor's temperature. At a bulk end these are the donor's slots in the proportion of the flow; at a phase port the phases the port's segment draws. What the tower then removes can never exceed what arrived, component by component, so the basin's balances cannot go negative. Several spray edges add up; their liquid's temperature is mass-weighted, as now.

### 3.2 The nozzle: let-down to the atmosphere

The table of the pooled liquid: `p_i(T) = f_i exp(a_i - b_i/T)` (`f_i` the mole fraction in the slot richest in `i`), latent heat `R b_i`, heat capacity `c_p` from the two readings. With `z_i` the pooled mole fractions and `K_i(T) = p_i(T) / (z_i P)`:

- No flash where `sum_i z_i K_i(T_in) <= 1`.
- Otherwise a flash at constant enthalpy on the table: the vapour share `psi` and the temperature `T'` solve Rachford-Rice, `sum_i z_i (K_i(T') - 1) / (1 + psi (K_i(T') - 1)) = 0`, and the heat balance, `c_p (T_in - T') = psi sum_i y_i R b_i` per mole of liquid, `y_i = z_i K_i / (1 + psi (K_i - 1))`. Two nested one-variable roots, no engine call. `psi = 1` (nothing stays liquid) sends the whole stream to the air.

The arriving gas `g_i` and the flash vapour `psi y_i` leave with the air. The flash starts at zero, so the rates are continuous where the stream crosses its bubble point; the slope has a corner there.

### 3.3 The fill: exchange with the air

On the liquid after the nozzle (`l'_i`, `T'`), with dry air flow `G` and the fill's approach from Merkel's integral as now.

- **Every compound except the air's nitrogen and oxygen.** Saturated air at the liquid holds, per kg of dry air, `w_i = (M_i / M_air) (p_i / P) F(r)`, `F = 1 / (1 - r)` continued with value and slope above `r = 0.9`. The content is limited by supply: the air cannot hold more of a compound than the stream brings, `B_i = l'_i M_i / G + w_i,ambient`. So `p_i/P` enters as the smooth lesser of itself and `B_i M_air / M_i` (the 4-norm lesser, which leaves a bulk compound unchanged to 1e-6), and `r` is the sum of those. A trace of dissolved gas then adds its own small amount and no longer inflates the others. No critical-temperature test, so no jump.
- The air moves straight towards that state at the liquid's mean temperature (Lewis number one). A compound's loss is `G approach (w_i - w_i,ambient)`, at most what the liquid holds; a gain (the air's water into a liquid that holds little) at most what brings the leaving liquid to equilibrium with the ambient air.
- **Nitrogen and oxygen** (0.7808 and 0.2095 of the dry air, less the humidity; only those the package holds). The liquid leaves saturated at its leaving temperature, slot by slot: `n_i,out = sum_slots N_slot p_i,air / (phi_i,slot P)`. The rate is what arrived less that: a gain for air-free water, a loss for a liquid that arrived with more.
- The leaving temperature follows from the energy that left, each component at its partial molar enthalpy in the liquid, as in the draft.

A dissolved gas the air does not hold therefore leaves in two parts: what one atmosphere does not keep, at the nozzle; of the rest, the fill's approach share (typically 0.5 to 0.8). Methane-saturated water from 10 bar: 90 % at the nozzle, 95 to 98 % in all (hand).

### 3.4 Rates and energy

One signed rate per component: `g_i` + nozzle vapour + fill loss (kg/s; a gain negative). One energy rate: the arriving gas at the enthalpy it was transported with (so the basin's row nets to zero for it exactly), the nozzle vapour at its vapour enthalpy at `T'`, the fill's exchange as in the draft, a gain at the ambient air's temperature. The cooling duty shown is the heat the liquid gave up, nozzle included. The basin's temperature comes from its own energy balance, so conservation does not depend on the table's approximations.

### 3.5 The basin

Receives the liquid and the solids. Water saturated with the air's nitrogen and oxygen stands just under its bubble pressure at one atmosphere (the air's 1 % of other gases is the margin), so the spray puts no gas phase into the basin. The surface exchanges sensible heat only, as now.

## 4. Interfaces

| Place | Change |
|---|---|
| `TowerExchange.at` | Takes each spray edge's transported stream from its caller (one record: gas, two liquids, gas enthalpy, temperature). No `liquidWater` gate; statuses `RUNNING`, `NO_SPRAY`, `TARGET_LIMIT` only |
| Newton residual (`PassiveStepSolver`, the basin's rows) | Passes the edge's drawn stream; the rate vector touches every component row (the pattern already sees the donor) |
| `ConservativeTransport` (4 sites) | Passes its `draws`; books one `BoundaryTransfer` of kind `ATMOSPHERE` with per-component amounts and the energy (the record already carries both) |
| Presentation (`readings`, `atRest`) | At rest nothing arrives: no rates |
| `PassiveStepSolver.refusedSpray`, `InletPhase.priming` | The water test goes; what is left of priming is decided from what still uses it |
| Checkpoint (`FluidCheckpointCodec`) | `Result` carries the loss vector: state, format bump with step 6 |
| Device screen | "Evaporation" shows water's loss; a second row, "Other losses to air", only when it is not zero (`en_us.json` only) |
| Plume (`FluidNetwork`, `FluidWorldAuthority`) | Reads water's loss and humidity, not the total |
| Process simulator | No tower unit calls `TowerTransfer` today; nothing to change |

## 5. Edge cases

- No spray flow, or flow leaving through the spray end: no rates, the draught alone.
- A package without nitrogen or oxygen: that gas is skipped. A package without water: the air's water passes as an inert vapour (draft).
- A liquid colder than the air's wet bulb (a cold solvent): the fall is negative, the liquid warms and still loses its volatile compounds; Merkel's integral already takes both signs.
- An oil with no water: light ends leave by their partial pressures, heavy ends stay; the air's water enters only up to equilibrium.
- The whole stream is gas, or flashes whole: everything leaves with the air; the basin receives nothing.
- No air flow: the fill exchanges nothing (the draught search never evaluates exactly zero air); the nozzle still acts.

## 6. Cost

Per basin and residual: two liquid readings per liquid slot. The draft's two extra kernel evaluations per exchanging component for the vapour enthalpy are replaced by the model's ideal-gas enthalpy of the component (a closed form in T). The nozzle flash and every search inside the fill read the table only. To report: kernel evaluations per `at` call on the tower tests and the step time of `CoolingTowerRuntimeTest` against today's.

## 7. Tests

Each with the per-component and energy ledgers closed at 1e-12; expected values derived from the partial pressures and heats, never pinned to output.

1. Water alone: the calibration point (35 to 25 C at L/G 1) and how far today's references move (expected about 0.15 %, the cubic's water against the record's).
2. Water with 10 mol % ethanol at 35 C: both leave, in the ratio of their partial pressures; the liquid cools more than water alone at the same duty in the air.
3. Air-free water: leaves with nitrogen and oxygen at the model's own Henry values; then no gas phase in the basin at one atmosphere.
4. A two-phase stream (water, free nitrogen, a little methane): all its gas in the rates, none reaches the basin; through a liquid port the same donor delivers no gas and none is removed.
5. Water at 380 K under pressure: the nozzle vapour by the heat balance (about 1.3 %), then the fill; and at 373.0 and 373.3 K the rates within a fraction of a per cent of each other.
6. Water saturated with methane at 10 bar: the nozzle share and the total.
7. Water holding carbon dioxide at 303 K and 305 K: the cooling duty within a fraction of a per cent (the jump of the critical-temperature test is gone).
8. A dry oil: its water gain stops at equilibrium with the ambient air.
9. `CoolingTowerRuntimeTest` and the pumped loop as now.

## 8. Choices the physics does not force (orchestrator's defaults, open to the owner)

- A dissolved gas the air does not hold strips by the fill's approach share after the nozzle, not whole. Effect: 2 to 5 % of it reaches the basin (hand); it removes a class of compounds and a jump.
- Gas and vapour released at the nozzle do not change the draught (they join the air above the fill). Effect: none on the fill; the stack's buoyancy is slightly off for a stream that is mostly gas.
- The basin's surface exchanges no mass, as on main.
- Two meters on the device screen instead of one list per compound.
