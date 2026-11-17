# Reflux with free water: the trial (2026-11-17)

The owner (2026-11-17): "try the reflux with water". Law 6 of [SEPARATOR_PLAN.md](SEPARATOR_PLAN.md) lets an undersized
reflux drum send some free water back with the reflux, and its simplification left that water's return and heat out
because the V3 column solver took no free water in its reflux. This trial gives the solver that input on a basis
without Water (the review plant's). Branch `claude/rust-archive-reflux-water-762f04`.

**Result: the trial works.** Both columns converge at 0, 8 % and 26 % of the decanted water returned and pass the
audit. Water closes exactly and the net water product does not move. The condenser duty does **not** rise by the
returned water's latent heat, as the brief expected; it stays within 0.03 % of the base. The physics with these
specifications gives that answer (section 2). The water's latent heat takes the place of the same heat of naphtha
condensation, so the distillate falls.

## 1. The model

`R_w` (mol/s) is free water the drum returns with the reflux, liquid at the drum's temperature. It is an authored
input of the solve, `V3ColumnInput#refluxFreeWaterMolPerSecond`, default 0.

- **Water.** `R_w` is a liquid water feed to tray one. The telescoped identity of `V3WetTraySet` gains one term on
  tray one only. The authored profile at tray one is `(all steam) + R_w`, so `W_1 = steam + R_w` on a dry tray one and
  `W_1 = steam + R_w - F_1` on a carry-over tray one. Trays two and below are unchanged, so the band does not widen.
  The drum receives `steam + R_w` and its regime split is unchanged. `WaterCondenserSplit` gains a third part, the
  returned water, and the decanted product is `arriving - slip - R_w`. The column's net water out is its steam.
- **Energy.** Tray one's energy row gains `R_w h_l(T_drum, P_drum)`, and the condenser duty books the same stream
  leaving the drum. The stream is internal, so the whole-column boundary is unchanged. The drum's temperature is
  not an unknown, so the term has no derivative and the Jacobian is unchanged.
- **Regime.** `R_w > 0` needs steam (`V3ColumnInput` refuses it otherwise) and a drum that decants: a problem whose
  regime is not `FREE_WATER` refuses it. Tray one may then cross its water dew point and join the wet set, and the
  carry-over form (E31) applies unchanged. A trial in which tray one carries over all its water is a rejected trial,
  as before (`trayWaterVaporFlow` throws). A drum that would return more than it decants publishes a negative
  product, which `FREE_WATER_SPLIT` refuses.
- **Audit.** It recomputes everything from the input on its own: the profile (`WATER_PROFILE`), the node, drum
  and boundary water closures (`WATER_BALANCE`), the drum's split (the independent split subtracts `R_w`), the
  drum's energy (`CONDENSER_ENERGY_BALANCE`, with `R_w h_l` leaving), and the global closure (the duty includes the
  stream). One advisory line and the `FREE_WATER_SPLIT` detail give the returned water and the decanted product.
- **Zero is exact.** Every new term is behind `R_w > 0`. The shorter constructors give 0, a −0.0 is stored as 0.0,
  `hashCode`, `toString` and the input digest add nothing at zero. No saved or wire field: `ColumnWire` does not
  carry it, so a client-built input has 0. The input is a record, and a new component was the only form that the
  continuation's rebuilds (`V3ContinuationLadder`, `V3FeatureRamp`, `V3NeuralRegistry`) carry. The feature ramp
  scales `R_w` with the steam it is part of, so a rung without steam has none.

## 2. Expected behaviour, in numbers

The brief expected the duty to rise by `R_w (h_v(T_1) - h_l(T_drum))`, about 45 kJ per mole returned. That
expectation holds when the hydrocarbon overhead stays the same. Here the specifications are the drum temperature,
the reflux ratio and the reboiler duty. The feed, steam and reboiler heat are fixed, and the water loop (drum to
tray one to drum) is internal, so the global balance gives

`Q_cond = H_products - H_feed - Q_reboiler - H_steam`

and the duty can move only as much as the product enthalpies move. On tray one the returned water takes its latent
heat from the hydrocarbon vapour, which condenses there instead of in the drum. The drum condenses `R_w` more water
and about `R_w λ_w / λ_hc` less naphtha, so its duty stays nearly the same. With the reflux ratio fixed, the
distillate falls, tray one cools by a few kelvin, and the lighter material leaves lower down.

Where tray one is close to its water dew point (the review plant: tray one at 97.6 C, 250 kPa), it cannot
evaporate much more water. It turns wet and carries the excess back over to the drum (E31), so most of `R_w` only
circulates as liquid between the drum and tray one.

## 3. What the solver did

**Medium crude wet column** (`V3RefluxFreeWaterTest`, the wet baseline of `V3PumparoundSteamCalculatorTest`: 725 mol/s
of feed, 30 trays, 333 mol/s of sump steam, 250 kPa, a 59 C drum, reflux ratio 4.17, 8 MW reboiler). Each case is a
cold solve.

| Returned | `R_w` mol/s | Condenser MW | Change kW | `R_w` × latent kW | Tray 1 K | Tray 1 | Distillate mol/s | Decanted mol/s | Newton | Polls |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0 | −110.920 | | | 447.05 | dry | 325.68 | 332.38 | 4 | 124 414 |
| 8 % | 26.59 | −110.916 | +3.2 | 1 229 | 445.72 | dry | 323.89 | 332.37 | 4 | 125 411 |
| 26 % | 86.42 | −110.900 | +19.7 | 3 985 | 442.67 | dry | 319.82 | 332.34 | 4 | 125 408 |

**Review plant** (`tools/column-hydraulics/RefluxWaterProbe.java`: the network's default column at 1/16, 44.4 mol/s
of feed, 40 trays, 20.67 mol/s of steam, 250 kPa, solved directly by the calculator, not through the flowsheet. The
brief quotes 1938 kW from the flowsheet run of `ReviewPlantProbe`; this direct solve gives 2127 kW at zero).

| Returned | `R_w` mol/s | Condenser kW | Change kW | `R_w` × latent kW | Tray 1 K | Tray 1 carries mol/s | Distillate mol/s | Decanted mol/s (m3/h) | Newton | Polls |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0 | −2126.8 | | | 370.72 | dry | 7.000 | 20.437 (1.346) | 3 | 216 648 |
| 8 % | 1.635 | −2127.6 | −0.8 | 72 | 369.67 | wet, 0.937 | 6.865 | 20.433 (1.345) | 7 | 266 406 |
| 26 % | 5.314 | −2127.7 | −0.9 | 233 | 369.62 | wet, 4.741 | 6.838 | 20.433 (1.345) | 3 | 224 236 |

`R_w` × latent uses `h_v(T_1) - h_l(T_drum)`: 46.2 kJ/mol on the medium crude (from the solved tray-one and drum
temperatures); about 44 kJ/mol on the review plant, estimated at 370 K and 332 K (the probe does not print it).

In plain words:

- Every case converges on the same cold route, with the same solve path as its zero case, and passes the audit:
  `WATER_PROFILE`, `WATER_BALANCE`, `FREE_WATER_SPLIT`, `CONDENSER_ENERGY_BALANCE`, `GLOBAL_ENERGY_BALANCE`. The
  duty ledger closes on the published products to 1e-6.
- Water: the drum returns exactly `R_w`, vapour slip plus product equals the steam to 1e-12, and the published
  `free_water` stream is the decanted product. The product moves by at most 0.04 mol/s, because the slip follows the
  overhead hydrocarbon vapour.
- Duty: it moves by at most 0.5 % of `R_w` × latent on the medium crude (the test bound is 2 %) and 1.1 % on the
  review plant. It does not rise.
- Tray one cools by 1.3 K and 4.4 K on the medium crude and stays dry, because at 446 K it is far above its water
  dew point. On the review plant it cools by 1.1 K and turns wet: it carries 57 % (8 %) and 89 % (26 %) of the
  returned water straight back to the drum. That is why its distillate barely falls between 8 % and 26 %.
- Counters: the medium crude solves take the same 4 Newton steps and 1 % more polls. The review plant at 8 % needs
  7 steps against 3 and 23 % more polls, because tray one turns wet there. The neural seed and the warm starts know
  nothing of `R_w`: every case here is a cold solve.

## 4. Proposal for the owner

1. Keep the input as built. The water and energy closures are exact, zero is the column as it was, and the audit
   checks every new term on its own.
2. Correct law 6's simplification in the plan. Its "heat to boil it again (55 kW of 1938 kW at 8 %, 180 kW at 26 %)
   is left out" assumed the drum's duty grows by that heat. With the reboiler duty fixed, the duty stays the same and
   the loss is distillate: about 0.5 % and 1.8 % on the medium crude, 1.9 % and 2.3 % on the review plant. So when
   the cycle feeds the returned water to the solve, it changes the products, not the condenser.
3. A decision for the owner (options). On a wet tray one, E31 sends the returned water that tray one cannot
   evaporate straight back to the drum as entrained liquid. In a real column most of it falls to tray two and
   evaporates there, so tray two also condenses some naphtha. The heat that the model does not cycle is
   `F_1 λ_w`: 41 kW at 8 % and 208 kW at 26 % on the review plant.
   - A: keep E31 as it is. A loop of liquid water between the drum and tray one costs no heat, and the
     `CARRY_OVER` check still reports the entrainment limit.
   - B: let the returned water fall: tray one's surplus that came from `R_w` goes to tray two, the steam's excess
     stays carry-over. This changes `V3WetTraySet`'s identity on trays one and two, so it is its own package.

## 5. What a production form still needs

- **The cycle's input.** `ColumnCycle` (or the held receiver of law 6) sets `R_w` from the drum's last state: the
  carried water times the reflux's share of the drum's liquid. Not built: no change to the running column here.
- **The ratio form.** `R_w` as water carried per unit of reflux couples the water profile to the state. The band
  stays the same, but tray one's water then depends on the condenser's liquid unknowns, and the Jacobian needs that
  derivative. It is the next step if the owner wants the solve, not the cycle, to close the loop.
- **The mode with Water on the basis.** There, water is a compound of the cubic, and a returned free water phase is a
  second liquid on tray one: a three-phase tray. Out of scope.
- **Sizing and other rebuilds.** `ColumnSizing.at` and the client's `V3EditorDraft` rebuild inputs with the shorter
  constructor and drop `R_w`. `V3TrayHydraulics` counts no `R_w` in tray one's liquid load. The neural seed and the
  warm-start digests know nothing of it (the digest separates the two cases, so no state is reused across them).
- **The wire and the GUI.** No field. The column calculator never shows it.

## 6. Tests

- `V3RefluxFreeWaterTest` (new, `columnGates`, 5 tests). Zero is the input, its hash and its digest as they were,
  and a −0.0 stays zero. The input is refused without steam, and the problem is refused with a drum that does not
  decant. The water profile and the drum split close for carried water from 0 to 4 mol/s. Tray one's energy row and
  the duty change by exactly the returned water's liquid enthalpy and latent heat, the column's energy rows sum to
  the boundary, and the drum's own closure refuses a duty that forgot the water. The medium crude column solves at
  0, 8 % and 26 % (section 3).
- `affected.mjs select --since c6f60e2aa..HEAD`, run as the lane's test step builds it (`-PlaneTests`,
  `left-out.txt` honoured, `--max-workers=4`): `test` 153 classes (994 tests), `holdGates` 6 (59), `columnGates` 69
  (381), `equipmentGates` 7 (64). One failure, `MaterialPresetsTest.allFourInputsRemainTheQualificationInputs`: it
  compares the record's fields, through Gson, with the captured fixture `materials/column-preset-inputs.json`, which
  lacked the new component. The fixture gains `"refluxFreeWaterMolPerSecond": 0.0` on its four inputs (no value
  moves; only this test reads it), and the class then passes (6 tests). No production code serialises the record.
  No other reference moved: every column gate that loads the changed classes passes as recorded.
- Not run: the GameTest step (`affected.mjs` marks it due, the final lane's), the solver regression and the campaign
  (it skips them).
