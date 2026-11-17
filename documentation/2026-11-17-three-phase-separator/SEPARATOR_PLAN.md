# Three-phase separator: plan (2026-11-17)

Class: PHYSICS (owner's option, with numbers). Lanes: fluid laws (`PhaseDraw`), fluid step (`PassiveStepSolver`'s port
streams), runtime (the slice plan), plant (the column's held receiver), flowsheet (the sizer's rows), client (status
words). Nothing is coded. It answers option O6 of [COLUMN_HYDRAULICS_PLAN.md](../2026-11-17-column-internal-hydraulics/COLUMN_HYDRAULICS_PLAN.md).

## 1. The request

Owner, 2026-11-17: "Time to implement a 3 phase separator model", and "the drum should only outputs well separated water
and oil when properly sized, otherwise you will find some oil in water and some water in oil".

## 2. What exists

- A vessel of the network is one mixed inventory at equilibrium. Its ports draw by phase with no limit of rate
  (`PhaseDraw`, fluid-laws.md): `VAPOR` the gas, `BOOT_HC` the hydrocarbon (water only above the D/3 weir),
  `BOOT_WATER` the water, `LIQUID` both. A drum of any size gives clean gas, clean oil and clean water at any flow.
- The simulator rates a drum (`DrumSizing.rate`): the gas's speed through half the section against Souders and Brown's
  (`DRUM_GAS_*`, K 0.107 m/s halved, 1.25 times for a lying drum), 5 min of hold half full (`DRUM_HOLD`), 5 min in the boot
  (`DRUM_BOOT_HOLD`). Advice only.
- A running column holds its receiver. The off-gas, the distillate and the sour water are split by the table, clean.
- Batch `2026-11-16-solute-reaction-system` (in progress) plans an electric desalter (its WP6) whose plan says: "Not
  modelled: water carried over in the oil (the phase draw takes clean oil)". This plan is that part. Its salt law is
  another matter: salt has nothing to do with phase carry-over (owner, 2026-11-17), and this plan neither reads nor
  changes it.

## 3. Physics

A liquid enters as drops in the other liquid. A drop settles at `v_s = g d² Δρ / (18 μ (1 + 0.15 Re^0.687))`, Stokes
with Schiller and Naumann's drag correction, `μ` the viscosity of the liquid it crosses. A drop leaves its layer if it
crosses it before the layer's liquid leaves: an ideal settler passes every drop of speed `v_s` where `N = v_s A / Q`
is 1 or more (`A` the layer's plan area, `Q` the layer's own outflow), and the share `1 − N` of them stays where `N`
is under 1. Gas leaves clean while its speed through the vapour space stays under Souders and Brown's
`K sqrt((ρ_l − ρ_g) / ρ_g)`.

The review plant's receiver (the probe's streams: gas 0.034 m3/s at 5.2 kg/m3; oil 15.1 m3/h, 666 kg/m3, 0.30 mPa s;
water 1.35 m3/h, 984 kg/m3, 0.47 mPa s). The drops (owner, 2026-11-17, option B of the sizer's moved result): a water
drop of 0.5 mm in the oil settles at 43.7 mm/s, an oil drop of 0.2 mm in the water rises at 10.4 mm/s. Before that
decision both were 0.1 mm (5.0 and 3.3 mm/s), and a drum of 3 m or more sized on its hold did not settle its water.
The 0.5 mm is the oil pad's usual design drop where no laboratory data exists; the 0.2 mm is the upper end of design
practice's 0.15 to 0.2 mm (secondary sources; Arnold and Stewart's book itself was not read).

| Drum (lying, with boot) | Oil layer's area, `N` (water out of oil) | Boot's area, `N` (oil out of water) | Gas speed of its limit | Result |
|---|---|---|---|---|
| 2.0 x 5 m (sized), boot 0.67 m | 9.8 m2, 102 | 0.35 m2, 9.7 | 3 % | clean |
| 1.0 x 6 m, boot 0.4 m | 5.5 m2, 57 | 0.13 m2, 3.5 | 12 % | clean |
| 0.5 x 9 m, boot 0.25 m | 4.0 m2, 41 | 0.05 m2, 1.4 | 46 % | clean |
| 1.0 x 6 m at 4 times the flow | 5.5 m2, 14 | 0.13 m2, 0.87 | 48 % | clean oil; 13 % of the water outlet's drops stay: oil in the water |
| 2.0 x 5 m at 16 times the flow | 9.8 m2, 6.4 | 0.35 m2, 0.61 | 47 % | clean oil; 39 % oil in the water |
| 2.0 x 5 m at 128 times the flow | 9.8 m2, 0.80 | 0.35 m2, 0.08 | 372 % | 20 % of the water stays in the oil (1.8 % by volume); 92 % oil in the water; the gas outlet draws 73 % bulk |

In a light oil the boot is what a small drum loses first: the oil layer settles its water until far past the gas
limit.

Gas from liquid, in a standing and a lying drum (owner, 2026-11-17: the gas law works with a vertical drum as well). The
review plant's whole overhead vapour, 0.648 m3/s at 5.1 kg/m3 over a liquid of 666 kg/m3; the limit is 0.61 m/s standing
(K 0.0535 m/s) and 0.76 m/s lying (1.25 times):

| Drum | Vapour section | Gas speed | Of its limit | The gas outlet draws |
|---|---|---|---|---|
| Standing 0.5 m | 0.20 m2 (the whole section) | 3.3 m/s | 540 % | 18 % gas, 82 % the vessel's bulk |
| Standing 1.0 m | 0.79 m2 | 0.83 m/s | 136 % | 74 % gas, 26 % bulk |
| Standing 1.5 m | 1.77 m2 | 0.37 m/s | 60 % | clean gas |
| Lying 1.0 m, half full | 0.39 m2 (the segment above the level) | 1.65 m/s | 217 % | 46 % gas, 54 % bulk |
| Lying 1.5 m, half full | 0.88 m2 | 0.73 m/s | 96 % | clean gas |

A standing drum's section does not change with its level; a lying drum's vapour space shrinks as it fills, so a high
level carries liquid sooner.

A heavier service has more teeth: an oil of 900 kg/m3 and 10 mPa s settles 0.5 mm drops at 1.2 mm/s, so 26.6 m3/h
of it needs 6.2 m2 to leave dry; a mix valve's emulsion of 0.1 mm settles at 0.05 mm/s and needs 150 m2. That is the
desalter's case, which the solute batch answers with its field (1.3 mm, 7.3 mm/s).

## 4. Laws

Evaluated per vessel at dispatch from its committed state and what entered over the last slice, carried to the step as
a slice input (as `PortPlan` and an exchanger side's read are): the step's rows keep their form and the solver is not
coupled.

1. **Water out of oil.** `N_w = v_s(oil) A_oil / Q_oil`. The oil outlet draws its oil and, per volume of oil,
   `r = max(0, 1 − N_w) x min(1, water in / oil in)` of water (the volumes that entered over the last slice).
2. **Oil out of water.** `N_o = v_s(water) A_water / Q_water`, `A_water` the boot's section, or the interface's in a
   vessel without a boot. The water outlet draws its water and `max(0, 1 − N_o) x min(1, oil in / water in)` of oil.
3. **Liquid out of gas**, in every closed vessel with a `VAPOR` port, standing or lying. The gas's speed is its outflow
   over the vapour section: a standing vessel's whole section, `π D² / 4`; a lying vessel's segment above its level
   (`VesselGeometry`). The limit is `K sqrt((ρ_l − ρ_g) / ρ_g)`, `K` the simulator's: 0.0535 m/s standing, 1.25 times
   that lying. The gas outlet draws gas up to the limit; the share of its volume above it, `1 − v_max / v`, it draws as
   the vessel's bulk.

   Laws 1 and 2 act where a vessel has an outlet for each liquid: a lying drum with a boot (`BOOT_HC`, `BOOT_WATER`)
   and the desalter. A standing drum and a lying drum without a boot have one liquid outlet (`LIQUID`), which draws both
   liquids as today: there is nothing to keep apart.
4. Clean at `N` of 1 or more and at a gas speed under its limit: a properly sized drum is as today, bit for bit.
5. Mass, components and energy stay exact: a draw's composition changes, nothing else. A vessel with nothing entering
   settles: `r` is 0.
6. **A column's held receiver.** The same three numbers from the receiver's shape and the table's overhead streams.
   The carried water moves from the sour water product to the distillate, the carried oil the other way, the liquid
   the gas carries to the off-gas. Simplification, to approve: the reflux's share of the carried water returns to the
   top tray and comes back overhead, and the table is not re-solved. Corrected 2026-11-17 by the trial
   ([REFLUX_WATER_REVIEW.md](REFLUX_WATER_REVIEW.md)): returned water does not cost condenser duty. With the reboiler
   duty fixed, the duty moves by at most 20 kW of 110.9 MW and 0.9 kW of 2127 kW. The cost is lost distillate and a
   cooler tray one, because the water's latent heat takes the place of naphtha condensation:
   - medium crude wet column, 8 % and 26 % of its decanted water returned: distillate −0.55 % and −1.8 %, tray one
     1.3 K and 4.4 K cooler;
   - review plant: −1.9 % and −2.3 %, tray one 1.05 K and 1.10 K cooler. There, tray one turns wet and carries 0.94
     and 4.74 mol/s back to the drum (E31, kept by the owner: "Keep the carry-over").

   The solver now takes the returned water as an input (`V3ColumnInput#refluxFreeWaterMolPerSecond`); the cycle does
   not set it yet.
7. **The simulator.** `DrumSizing.rate` gains the two `N` rows (Must: 1; Auto sizes for 1.2, the 20 % spare) beside
   the hold rows, so "properly sized" on the sheet is "clean" in the world. A drum's page shows `N` and what its
   outlets carry.

### 4.1 The desalter (owner, 2026-11-17: "based on this, a bit different because it has higher coalescence rate")

The desalter's phase separation is this separator with one parameter changed: the drop size a vessel coalesces to.
`Separation` takes the drop sizes from the vessel (`Separation.Drops`: a water drop in the oil, an oil drop in the
water): 0.5 mm and 0.2 mm in a drum; in a desalter the water drop is 1.3 mm with its field on and 0.1 mm without power
(its mix valve's emulsion, which a drum's feed is not). The desalter kind sets both. Laws 1 and 2 act on its oil and water outlets, law 3 on its gas.

On a desalter of 54 m2 at 0.184 m3/s of oil (the solute plan's example and its viscosities):

| Crude, temperature | `N` | Water in the oil |
|---|---|---|
| Medium, 60 C | 3.9 | none |
| Medium, 120 C | 9.1 | none |
| Heavy, 120 C | 1.3 | none |
| Medium, 120 C, no field | 0.11 | 89 % of it stays |

With the field on this desalter gives dry oil; a heavier or colder crude, or a desalter of a quarter the area, brings
`N` under 1 and water into the oil. The salt is not part of this: the solute batch's salt law stands as that batch
wrote it.

## 5. Owner decisions (all taken 2026-11-17)

- S1 A: the ideal settler, clean at `N` of 1 or more.
- S2 A: under `N` of 1 the share `1 − N` of the other liquid is carried, at most volume for volume (laws 1 and 2).
- S3 A: the gas law (law 3), for standing and lying vessels.
- S4: its own batch, built at once in the session that planned it (owner, 2026-11-17: "Do the separator job here"),
  ahead of the solute batch's WP2 and its desalter (WP6), which takes its phase separation from this model. Law 6
  joins the hydraulics batch's cycle package.
- Salt has nothing to do with phase carry-over: the solute batch's salt law is not read or changed.

Open: law 6's simplification (the carried water's return with the reflux is not re-solved and its heat is left out).
The owner asked whether the column solver takes a reflux with water (2026-11-17). It does not take free water:
- A basis without Water (the review plant's): water is a vapour profile the steam feeds author, not an unknown of the
  trays (`V3SteamFeedSpec`); a steam feed must be 5 K over saturation (`V3ColumnProblemResolver`); the condenser
  decants free water and the reflux is the organic phase. There is no liquid water feed to tray 1.
- A basis that names Water: water is a compound of the cubic, so the reflux carries the water the oil dissolves, and
  no more.
So the simplification stands until the solver has a free water reflux input, which changes the column solver and its
Rust twin: its own batch, the owner's call.

Update 2026-11-17: the Rust library is archived (batch `2026-11-17-rust-solver-archive`). The solver has the input on a
basis without Water (`V3ColumnInput#refluxFreeWaterMolPerSecond`, [REFLUX_WATER_REVIEW.md](REFLUX_WATER_REVIEW.md)),
and the top tray keeps the carry-over (owner: "Keep the carry-over"). Law 6's note above is corrected. Still open:
the cycle does not set the input.

## 5.1 As built (packages 1 and 2)

- The slice input is `Separation.Carried` on `PassiveNetwork.Inventory` (`carried`, never saved), set at dispatch by
  `Separation.plan` through a `Ports` hook (`runtime/fluid/Separators`); `IslandCoordinator` is unchanged. The plan
  reads the island's last accepted slice: each phase's outflow and inflow are the phase volumes its connections moved
  (`PipeTransfer`), over the slice's seconds. A restored island plans from its own saved last result.
- `StepEquations.phaseRates` applies it where an end draws its own phase alone (`BOOT_HC`, `BOOT_WATER`, `VAPOR`):
  the carried phase takes its mass share of the flow, drawn at the step's start state as a pinned phase is, up to the
  capacity a pinned phase has (what the vessel held at the start, over its drawing ends). An end already drawing by
  priority (a phase run out) carries nothing more. A graph none of whose vessels carries anything takes none of these
  branches.
- Scope, narrower than law 3's "every closed vessel": a vessel with a pool (a kettle, a still's pot) or a cooling
  tower's basin, and an open vessel, take no law. A kettle and a pot are sized as exchangers and boilers, not by
  Souders and Brown; whether they entrain is the owner's call.
- Not in the inflow: a scheduled injection (a unit's product placed in a vessel). The readers of a vanishing flow
  (`PortStreams`, `InletPhase`, a mover's suction estimate) read the outlet's own phase clean; the step's own stream
  carries the shares.
- The desalter's drop size (4.1) waits for the solute batch's desalter kind: `Separation.plan` gives every vessel
  `Separation.DROPS`.

## 6. Work packages

1. `science/fluid/network/Separation`: `settling(d, Δρ, μ, ρ)`, `passes(N)`, `gasLimit(standing)`; the drop size a
   vessel gives (`DROPS`: 0.5 mm of water in oil, 0.2 mm of oil in water; the desalter's kind sets its own); the areas from `VesselGeometry` (a standing
   vessel's section; a lying drum's chord at the interface, its boot's section, its vapour segment above the level).
   `SeparationTest` on section 3's numbers, both tables.
2. The plan: `SeparationPlan(vessel, waterInOil, oilInWater, liquidInGas)`, built at dispatch from the committed slice;
   `PhaseDraw` reads it for `BOOT_HC`, `BOOT_WATER`, `VAPOR` and the desalter's ports. A plan of zeros is today's draw.
   Tests: `PhaseDrawTest` (zeros are exact; the shares at hand numbers), a network test of a small drum (oil in the
   water, water in the oil, the ledger exact), the hold-fuzz campaign on a world with an undersized drum (200 seeds,
   refused 0).
3. The held receiver: `ColumnUnit.Lines` names the receiver; `ColumnCycle.step` moves the carried amounts between the
   three products (`Condition.CARRY_OVER`, a NOTICE with the shares). `ColumnCycleTest`.
4. `DrumSizing`'s rows, `Limit.DRUM_SETTLE_WATER` and `DRUM_SETTLE_OIL`; the drum page's readings; `en_us.json`.

Risks: `PhaseDraw` and the port streams are where the hold classes live (the mixed-liquid draw, seed 236). The plan
changes a stream's shares between slices, never inside one. A drum near `N` of 1 flips between clean and carrying:
`r` is continuous there (0 at 1), so the stream moves without a step.
