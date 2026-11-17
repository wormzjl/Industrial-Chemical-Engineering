# Three-phase separator: plan (2026-11-17)

Class: PHYSICS (owner's option, with numbers). Lanes: fluid laws (`PhaseDraw`), fluid step (`PassiveStepSolver`'s port
streams), runtime (the slice plan), plant (the column's held receiver), flowsheet (the sizer's rows), client (status
words). Nothing is coded. It answers option O6 of [COLUMN_HYDRAULICS_PLAN.md](COLUMN_HYDRAULICS_PLAN.md).

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
- Batch `2026-11-16-solute-reaction-system` (in progress) gives every vessel a settling law for its salt tag (its plan,
  8.1): `v_s = g d² Δρ / (18 μ (1 + 0.15 Re^0.687))`, `d` 0.1 mm in any vessel but a desalter with its field on,
  `N = v_s A / Q`, evaluated at the cadence and carried to the step as a plan, so the solver is not coupled. It says:
  "Not modelled: water carried over in the oil (the phase draw takes clean oil)". This plan is that part.

## 3. Physics

A liquid enters as drops in the other liquid. A drop leaves its layer if it crosses it before the layer's liquid leaves:
an ideal settler passes every drop of speed `v_s` where `N = v_s A / Q` is 1 or more (`A` the layer's plan area, `Q` the
layer's own outflow), and the share `1 − N` of them stays where `N` is under 1. Gas leaves clean while its speed through
the vapour space stays under Souders and Brown's `K sqrt((ρ_l − ρ_g) / ρ_g)`.

The review plant's receiver (the probe's streams: gas 0.034 m3/s at 5.2 kg/m3; oil 15.1 m3/h, 666 kg/m3, 0.30 mPa s;
water 1.35 m3/h, 984 kg/m3, 0.47 mPa s; drops of 0.1 mm settle at 5.0 mm/s in the oil and rise at 3.3 mm/s in the water):

| Drum (lying, with boot) | Oil layer's area, `N` (water out of oil) | Boot's area, `N` (oil out of water) | Gas speed of its limit | Result |
|---|---|---|---|---|
| 2.0 x 5 m (sized), boot 0.67 m | 10 m2, 11.9 | 0.35 m2, 3.1 | 3 % | clean |
| 1.0 x 6 m, boot 0.4 m | 6 m2, 7.1 | 0.13 m2, 1.1 | 12 % | clean |
| 0.5 x 9 m, boot 0.25 m | 4.5 m2, 5.4 | 0.05 m2, 0.43 | 46 % | clean oil; 57 % of the water outlet's drops stay: oil in the water |
| 2.0 x 5 m at 16 times the flow | 10 m2, 0.74 | 0.35 m2, 0.19 | 47 % | 26 % of the water stays in the oil; oil in the water |

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

A heavier service has more teeth: an oil of 900 kg/m3 and 10 mPa s settles 0.1 mm drops at 0.05 mm/s, so 26.6 m3/h
of it needs 150 m2 to leave dry. That is the desalter's case, which the solute batch answers with its field (1.3 mm).

## 4. Laws

Evaluated per vessel at the cadence from its committed state and what entered over the last slice, carried to the step
as a plan (the solute batch's `TagPlan` pattern): the step's rows keep their form and the solver is not coupled.

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
   top tray and comes back overhead; the heat to boil it again (55 kW of the condenser's 1938 kW where 8 % of the
   water is carried, 180 kW at 26 %) is left out, and the table is not re-solved.
7. **The simulator.** `DrumSizing.rate` gains the two `N` rows (Must: 1; Auto sizes for 1.2, the 20 % spare) beside
   the hold rows, so "properly sized" on the sheet is "clean" in the world. A drum's page shows `N` and what its
   outlets carry.

### 4.1 The desalter (owner, 2026-11-17: "based on this, a bit different because it has higher coalescence rate")

The desalter is this separator with one parameter changed: the drop size a vessel coalesces to. `Separation` takes the
drop size from the vessel: 0.1 mm in a drum, 1.3 mm in a desalter with its field on (the solute plan's values, 8.1 and
section 10), 0.1 mm in a desalter without power. Nothing else of the desalter's phases is its own: laws 1 and 2 on its oil
and water outlets, law 3 on its gas (its status `GASSING`).

On the solute plan's desalter (54 m2, 0.184 m3/s; its own `N`, which is this plan's):

| Crude, temperature | `N` | Water in the oil, this model | "Water settled", solute plan 8.1 (stirred) |
|---|---|---|---|
| Medium, 60 C | 3.9 | none | 79 % |
| Medium, 120 C | 9.1 | none | 90 % |
| Heavy, 120 C | 1.3 | none | 56 % |
| Medium, 120 C, no field | 0.11 | 89 % of it stays | 10 % |

So with the field on, this desalter gives dry oil at every listed temperature; temperature shows only where `N` falls
under 1 (a heavier crude, a colder one, a desalter of a quarter the area). The solute plan's salt law (8.1) is another
quantity, the salt on the oil, and it is that batch's to state. Option S5 says how the two meet.

For the solute batch (its branch, not edited from here): its plan's 8.1, D9 and section 10 rest on this plan's
`Separation` (the settling speed, `N`, the drop size by vessel); its "Not modelled: water carried over in the oil" ends
with this plan; its Sizing row `N` of 9 or more follows S5.

## 5. Owner options

- S1, the liquids' form. A (recommended, the owner's sentence): the ideal settler, clean at `N` of 1 or more.
  B: the salt law's stirred form for the phases too: one form, but no drum is ever clean (8 % of the water stays in
  the review plant's sized receiver).
- S2, how much is carried under `N` of 1. A (recommended): the share `1 − N` of the other liquid, at most volume for
  volume (laws 1 and 2). B: the same, capped at 10 % by volume: milder, one more constant.
- S3, the gas. A (recommended): law 3. B: no gas law in this batch.
- S4, order (changed by the owner's word on the desalter). The separator comes before the solute batch's desalter
  (WP6), which is built on it. A (recommended): its own batch after the solute batch's WP2 (which holds
  `IslandCoordinator` and the fluid laws lane) and before its WP6; law 6 joins the hydraulics batch's cycle package.
  B: now, beside the solute batch's stage A, with the hot files shared by the owner's word.
- S5, the desalter's salt against its water. A (recommended): the salt law keeps its form (8.1: the coalesced share `E`
  and the stirred `N / (1 + N)`), so wash water and temperature act on the salt as that plan's table shows, while the
  water follows this plan (dry oil at `N` of 1 or more). The two share `v_s`, `N` and the drop size. B: the salt follows
  the water exactly, `E x min(1, N)`: one form, but with the field on the salt removed no longer moves with
  temperature above `N` of 1 (the 54 m2 desalter: 92 % at 5 % wash from 60 to 150 C).

## 6. Work packages (under S4 A)

1. `science/fluid/network/Separation`: `settling(d, Δρ, μ, ρ)`, `passes(N)`, `gasLimit(standing)`; the drop size a
   vessel gives (`dropSize`: 0.1 mm; the desalter's kind sets its own); the areas from `VesselGeometry` (a standing
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
