# Solute and reaction system: design (revision 2)

Revision 2 (owner, 2026-11-16: "I mean model the HCl as an components"): hydrogen chloride is a compound of the network package. A tag becomes the compound, and the compound a tag on water, at holdups (sections 4, 5.2, 7.4, 8.2, 8.3, 9, 12, 13, 15, 16, 17).

Batch `2026-11-16-solute-reaction-system`, branch `claude/solute-reaction-system-01ef6c`, cut from `main` at `adb413b4d` (0.34.0). Status: design, no code. It builds on batch `2026-11-16-fouling-corrosion` (`science/integrity`, the walls, the column's wear), which is on `claude/fouling-corrosion-final-lane-f2943c` and not on `main` yet: no work package of this plan starts before that batch merges.

The kinetic models, their sources and the inputs they lack are in [REACTION_MODELS_REVIEW.md](REACTION_MODELS_REVIEW.md). This plan designs the solute system, the transformations that need no reactor, the desalter and the per-part corrosion. The catalytic units get their own design later; section 14 fixes only the frame they will stand in.

## 1. The request (owner, 2026-11-16)

- A solute system that is not a full physical model: phase affinity, no binary interaction parameters.
- Besides ordinary solutes, it tags the pseudocomponents with their salt, sulfur, nitrogen, metal, resin and asphaltene content, for the later reaction modules (coking, hydrotreating, hydrocracking, FCC, steam cracking, SMR, isomerization and others).
- A crude desalting model, mainly the influence of temperature, with an electric desalter as new equipment.
- A solute transforms under prescribed conditions and equipment. Chloride salt in hot crude becomes HCl, which goes to the water at the outlet where water is present and corrodes the column's top where free water stands. Heavy fractions coke: part deposits on the equipment (fouling), part is carried away as particles.
- Corrosion is evaluated for each component of a multi-structure on its own.
- Each reaction has a chosen kinetic model and a condition range outside which nothing is calculated. Reaction enthalpy counts where it is a major part of the enthalpy change (coking). Some lag is accepted, to keep the solver uncoupled.
- Find which inputs each kinetic model lacks.
- A reaction in equipment without holdup uses a justified residence time.
- Nothing happens in pipes.
- Design for performance.
- Research and design first. For the catalytic reactions: the model and its requirements only.

During the work (owner, 2026-11-16):

- "solute 'tags' the pseudocomponent and water, and they retain their physical properties"
- "compounds with different 'tag' can do mixing as they would"
- On the first design's HCl as a tag on water: "I mean model the HCl as an components"

## 2. What exists today

| Piece | Where | What it does | Gap |
|---|---|---|---|
| Compounds | `MaterialCatalog`, package `icheme:refinery` (51 compounds and water) | The thermodynamic axis; an island runs on at most 64 compounds and water | Every compound costs flash time; a ppm species has no place there |
| Crude cuts | `crude_pc01..12`, shared by the four assays | Fixed properties per cut | A cut has one sulfur value for every crude (fouling-corrosion D9: the dilbit misses its sulfur by 43 %) |
| Per-cut numbers | `corrosion` sub-record (fouling-corrosion WP1): sulfur, acid number, fouling | Static inputs of the corrosion digest | A hydrotreater could not lower them; no nitrogen, metals, asphaltenes, salt |
| Formation enthalpy | `thermochemistry` sub-record on every dataset (cuts: estimated CHx, Cragoe 1929) | Heating values by Hess's law | No entropy or Gibbs energy; cut values too rough for cracking heats |
| Solids | `SolidInventory`, `SlurryTransport`, `ConservativeTransport` | Inert particles with mass, settling, deposition, filters | Too heavy a mechanism for ppm species |
| Transport history | `PipeTransfer.Stream.phaseMoles[phase][compound]` per pipe and interval | What every pipe moved, by phase and compound | Nothing reads it after the commit |
| Walls | `science/integrity`, `runtime/fluid/Integrity` (fouling-corrosion) | Corrosion M1 to M11, erosion, a Kern–Seaton fouling placeholder, at a 5 s cadence, at most 256 records a pass | "Later, with their species: HCl overhead corrosion" (plan 6.3); fouling waits for "the reaction module" (O1) |
| Column wear | `ColumnWear`, `ColumnOperation` (WP8) | One wall record per column, on its bottoms liquid | The top, the condenser and the reboiler are not evaluated |
| Units on accounts | `ColumnOperation`, `Stills`, `Converters` on the `Ports` hook | Plan at dispatch, book at commit, one model solve per operating point | The pattern a reactor needs; no reactor uses it |
| Reactor kinds | `EquipmentShape.Kind`: `STIRRED_REACTOR`, `TRICKLE_BED_REACTOR`, `EBULLATED_BED_REACTOR`, `FCC_CONVERTER`, `DELAYED_COKER`, `ADSORBER`, `CYCLONE` | Drawing only | No model; no desalter kind |

## 3. Physics, in numbers

A crude unit, 100 kg/s of medium crude with 50 PTB of salt (150 mg/kg), of which one fifth is magnesium and calcium chloride.

1. **Desalter.** Wash water (5 %) takes up the brine. Water drops settle by Stokes' law, `v = g d² Δρ / (18 μ)`. Heat lowers the oil's viscosity: at 120 °C about 95 % of the drops settle, at 60 °C about 80 %. Without the electric field the drops stay small and about 10 % settle. One stage removes about 87 % of the salt at 120 °C.
2. **Preheat and furnace.** Above 120 °C magnesium and calcium chloride hydrolyse to HCl: 2 % in a minute at 120 °C, 54 % at 270 °C, 90 % at 340 °C. Sodium chloride is stable to 500 °C.
3. **Column top.** HCl leaves with the steam. Dry, it harms nothing. Where the first water condenses it takes all the HCl: 3 mg of HCl per kg of crude in 3 % of water is pH 2.6 and 1.7 mm/y on carbon steel (a 3 mm wall lasts 35 process hours); the first condensate is ten times more acid. Ammonia or caustic neutralises it; a better desalter removes its cause.
4. **Hot surfaces.** Asphaltenes coke on a hot wall. At a film temperature of 350 °C and 1 m/s a tube reaches a deposit of 0.0009 m²K/W in 10 process hours; at 2 m/s it stays almost clean, because the flow strips what forms and carries it on as fines.
5. **Hot holdups.** Above 340 °C the bulk liquid cokes: 0.3 % of its asphaltenes per hour at 340 °C, 1.9 % at 370 °C, 10 % at 400 °C. This is why a vacuum column's bottom is kept cool.

None of these changes a stream's density, enthalpy or phase split by a part in 10 000. They are bookkeeping on top of the flow, and are designed as that.

## 4. The design in short

| # | Decision |
|---|---|
| D1 | A solute is a **tag**: an amount (kg) carried by one carrier compound, a pseudocomponent or water. It has no mass, volume, heat capacity or pressure of its own. The carrier keeps its physical properties, so no flash, no step and no identity sees a tag. |
| D2 | A stock holds one amount per (tag, carrier). Two stocks of a compound with different tags mix as one compound; the amounts add. A tag leaves a stock with its carrier, in proportion to the carrier's moles that leave. |
| D3 | Phase affinity is three classes, not parameters: BOUND (with the carrier in every phase), DISSOLVED (with the carrier's liquid only), ACID GAS (with the carrier's liquid where it has one, else with its vapour). |
| D4 | Tags move in a pass of their own after each solved interval, from the pipes' recorded transfers. The step's Newton, its booking and the certificates are not changed. Nothing happens in a pipe. |
| D5 | A transformation is a data record: participants, a rate law, a condition window, the equipment classes it acts in. It is evaluated at the walls' 5 s cadence on the last committed state and applied by the tag pass. One cadence of lag. |
| D6 | A transformation of this batch moves tags, and parts per million of one compound (7.4), so it books no heat of reaction (each is below 1e-4 of its stream's enthalpy change, section 8.6). Heat belongs to the reactor units (section 14). |
| D7 | Hydrogen chloride is a compound, `HydrogenChloride`, in `icheme:refinery` (owner, revision 2). In a holdup with free water it becomes the tag `acid_chloride` on that water; in a holdup without, the tag becomes the compound. The cubic is not asked to model hydrochloric acid: no Henry record, no electrolyte term. |
| D8 | A stock without tag data has its datasets' default contents. Only a stock that differs stores amounts, so a world without salted crude pays nothing and an old save needs no conversion. |
| D9 | The electric desalter is a horizontal vessel of the fluid network with an electric store. Its law is the settling law every vessel has; the field only makes the drops larger. |
| D10 | Corrosion is evaluated per part: each product zone of a column's shell and the process side of each of its exchangers has its own wall record. |
| D11 | A reactor is a unit on accounts with a yield table from one model solve, as a running column is with its split table. Detailed design: the next task. |

## 5. Tags

### 5.1 Records

A new material kind, `solutes` (the 16th): `data/<ns>/materials/solutes/<id>.json`.

| Field | Meaning |
|---|---|
| `id`, `revision`, `source` | As every record. In no identity: a tag changes no physics |
| `carriers` | `pseudocomponents`, `water`, or both |
| `class` | `bound`, `dissolved`, `acid_gas` (D3) |
| `stranded` | Where a `dissolved` amount goes when its carrier's liquid is gone: `heaviest_cut`, `water`, or `stay` |
| `molar_mass_kg_per_mol` | For the stoichiometry of a transformation |
| `particulate` | Counts as solid share in the erosion and tray fouling laws |
| `hazard` | Optional, as a compound's: the channel and reference for a release to the air |

Default contents: a `content` sub-record on a pseudocomponent's dataset, kg of tag per kg of the cut, with its own revision, in no identity. It replaces the `corrosion` sub-record of the fouling-corrosion batch: `sulfur_mass_percent` and `acid_number` become the defaults of the tags `sulfur` and `acid`; `fouling` is replaced by the asphaltene content (section 8.4). The old field names are read as aliases.

An assay gains an optional `content` block: whole-crude targets (sulfur wt%, nitrogen and metals in mg/kg, asphaltenes and resins wt%, acid number, salt in PTB, the hydrolysable share of the salt). `MaterialCatalog` scales the cuts' default profile by one factor per tag so that the whole crude meets its target. Every assay then has its own sulfur, which closes decision D9 of the fouling-corrosion batch. The salt of an assay tags its heaviest cut (brine and crystals dispersed in the oil); assays stay dry.

### 5.2 The tags of this batch

| Tag | Carriers | Class | Unit | Used by |
|---|---|---|---|---|
| `sulfur` | cuts | bound | kg S | Sulfidation (M1); hydrotreating, FCC, coking |
| `nitrogen` | cuts | bound | kg N | Hydrotreating, hydrocracking, FCC |
| `acid` | cuts | bound | kg KOH equivalent | Naphthenic acid corrosion (M2) |
| `metals` | cuts | dissolved (stay) | kg Ni + V | Hydrotreating catalyst life, FCC |
| `asphaltenes` | cuts | dissolved (stay) | kg | Coking (8.4, 8.5), coker and FCC coke yield |
| `resins` | cuts | dissolved (stay) | kg | Coke yield; asphaltene stability later |
| `salt` | cuts, water | dissolved (heaviest cut) | kg Cl in stable chloride | Desalting; fouling later |
| `hydrolysable_chloride` | cuts, water | dissolved (heaviest cut) | kg Cl in Mg and Ca chloride | Hydrolysis (8.2) |
| `acid_chloride` | water, cuts | acid gas | kg Cl as HCl | Hydrochloric acid in water (M12); the hydrolysis product on its way to the next holdup (7.4) |
| `caustic` | water, cuts | dissolved (heaviest cut) | kg NaOH | Converts hydrolysable chloride; neutralises acid |
| `coke_fines` | cuts | dissolved (stay), particulate | kg | Erosion, tray fouling |

Sulfur in H2S, mercaptans, COS and SO2 stays in their formulas, as today. Ammonia is a compound: its dissolved part neutralises acid chloride.

Ordinary solutes are the same thing: a brine, a caustic or amine solution is water with a `dissolved` tag. The water keeps water's density and boiling point at any content (the owner's rule); a solubility limit is not in this batch.

### 5.3 Where the amounts live

- `PassiveNetwork.Inventory.tags`: a `TagSet` (a `double[tags][carriers]` over the island's pseudocomponents and water), or `null` for default contents (D8). A fact of the vessel, like its moles.
- Junction buffers hold tags as state, as they hold composition.
- A generator's spec (`FluidDeviceSpec`) may name contents (from an assay); else it supplies default contents.
- A column's, still's and converter's accounts hold a `TagSet` beside their amounts.
- The ledger keeps one total per tag: stocks, accounts, deposits, what entered and left at boundaries, what each transformation made and used. `TagLedgerTest` closes it exactly.

### 5.4 Mixing, separation, stranding

- **Mixing**: amounts add per (tag, carrier). A stock with `null` tags is expanded to its defaults the first time it meets explicit ones.
- **Flash and phase draw**: a `bound` tag follows its carrier's moles into each phase; a `dissolved` tag its carrier's liquid moles; an `acid_gas` tag the liquid moles where the donor has liquid of the carrier, else the vapour moles.
- **Column**: product `p` takes `recovery[c][p]` of each (tag, carrier `c`) from the split table. A `dissolved` tag takes the liquid's way: to the bottoms, as inert particles go.
- **Stranding**: a `dissolved` tag whose carrier has no liquid left in a stock moves to its `stranded` carrier (salt of evaporated water becomes crystals on the heaviest cut). With no liquid at all it stays in a vessel as dry residue until its carrier returns; in a junction it rides the total flow.
- **Leaving the game's axis**: a Forge fluid conversion out of the network, a vent, a spill and a flare take their tags with them; the ledger books them as left.

## 6. Transport

`science/solute/TagTransport` (pure, no Minecraft type), run on the island's worker after `PassiveIntervalSolver.solve` returns, on the interval's result:

1. For each carrier `c` that holds a tag on the island: `N_i` = its moles in node `i` at the interval's end, `O_ij` = its moles moved from `i` to `j` (`PipeTransfer`), both in the scope of the tag's class.
2. Implicit donor: of what node `i` had and received, the share `f_i = Σ_j O_ij / (N_i + Σ_j O_ij)` left, split among its pipes by `O_ij`. One sparse system per carrier and scope, solved for all its tags with one factorization (`SparseLuSolver`).
3. Positive, conservative and stable at any interval length. A junction passes what it receives.
4. Then the transformations (section 7) act on each site with the coefficients its plan carries.

Cost and bounds:

- No tags on the island: the pass is skipped by one test.
- An island without a phase draw: every carrier has the same `f_i`, so one factorization serves all.
- Otherwise one factorization of the island's node count per carrier and class that holds a tag (at most 38 with these records, about 15 for a salted crude), and one back-substitution per tag held. The cost is judged by these counts in the campaign's counters.
- Per interval, not per step.

Certified islands: a replay advances the flows linearly; at a materialisation the tag pass takes the whole replayed span as one implicit step, which lands on the steady tag field. Tags are not part of the certificate's stationarity test, so a tag front does not keep a steady island solving.

## 7. Transformations

### 7.1 Record

A new material kind, `reactions`: `id`, `revision`, `source`; `uses` and `makes` (tags, with mass ratios); `law` (one of a closed list, below) with its parameters; `window` (temperature range, and which of oil, free water, gas must be present); `sites` (equipment classes); `enthalpy` (`none`, a stated J/kg, or `hess` for real compounds: section 14).

| Law | Form | Used by |
|---|---|---|
| `first_order` | `k = A exp(−E/RT)` on a tag | Hydrolysis, bulk coking |
| `transfer` | A tag moves between carriers at a rate the site's law gives | Desalting, caustic |
| `threshold_deposit` | Formation minus removal on a heat side's wall | Surface coking |

### 7.2 Sites and residence time

| Site | Holdup | Time used |
|---|---|---|
| Vessel of constructed equipment | Real | Its holdup: the closed form below |
| Heat side passage (exchanger, kettle tubes, air cooler, fired coil) | None | `τ = V / Q̇`: tube count × length × bore area over the volume flow of its last slice; the rate constant as the Simpson mean over inlet, middle and outlet temperature |
| Column zone (section 9) | Accounts | Bottom: sump volume over the bottoms' volume flow. Other zones: no transformation in this batch |
| Reactor units (later) | None or real | Bed volume × void fraction, riser or coil volume, over the volume flow at reactor conditions |
| Pipe, fitting, valve, machine, junction, one-block tank | | Nothing happens |

A vessel with a first-order transformation: over an interval `Δt`, with `n0` held, `f` arriving per second and turnover time `τ`, `dn/dt = f − n/τ − k n` has the solution `n(t) = n∞ + (n0 − n∞) e^(−λt)`, `λ = k + 1/τ`, `n∞ = f/λ`. The amount transformed is `k [n∞ Δt + (n0 − n∞)(1 − e^(−λΔt))/λ]`. It is exact for a steady vessel, never exceeds what is there, and needs no step limit.

A passage: `X = 1 − exp(−k τ)` of what passes.

### 7.3 Evaluation

`runtime/fluid/Reactions`, beside `Integrity`, at the same cadence and with the same bound (256 sites a pass, round-robin):

1. A site is evaluated only if its island holds the transformation's tags (a bit per island and tag) and its last committed state is inside the window. One comparison rejects a cold site before any `exp`.
2. It stores the site's coefficient (`k`, or `X`, or a transfer rate) as state.
3. The island's next command carries the coefficients (`TagPlan`, immutable); the tag pass applies them.

So a coefficient lags its site's state by at most one cadence, and the solver is not coupled.

### 7.4 A tag that becomes a compound, and back

Only at holdups, and in this batch only for hydrogen chloride (a reaction record names the compound in `makes` or `uses`):

- **Release**: in a vessel without free water, the `acid_chloride` it holds becomes `HydrogenChloride`.
- **Absorption**: in a vessel with free water, all its `HydrogenChloride`, in every phase, becomes `acid_chloride` on the water. Accurate: over acid of pH 2.6 its pressure is 4e-12 atm at 25 °C (review 4.2).
- **Neutralisation**: in a vessel with free water, `Ammonia` up to the acid chloride present becomes, with it, the tag `ammonium_chloride` on the water (dissolved; a twelfth tag). The cubic's Henry pair alone would leave the ammonia in the gas, and reading it without removing it would let the same ammonia neutralise in every vessel.

How it is booked:

1. `Reactions` plans, per vessel, the moles to release (the tag it held at the last commit) or the order to absorb. The island's command carries the plan.
2. `PassiveIntervalSolver.solve` applies it once, at the interval's start, where the crystals' conversions run: the vessel's amounts change and the vessel is restated at its temperature and pressure (one TP flash, as `flushTraces` restates a node). The moles and the energy difference are a `BoundaryTransfer` of a new kind, `SOLUTE`.
3. The tag pass reads the interval's `SOLUTE` transfers and books the same chlorine on the tag side. Chlorine closes exactly across compound and tags (`TagLedgerTest`).

- Absorption takes what the vessel holds at the interval's start; what arrives within the interval is taken at the next one.
- A unit on accounts does both on its accounts, with no solver part. A column gives all the `acid_chloride` and `HydrogenChloride` of its feed to its top product, as the compound (section 8.3).
- An island that receives the compound and does not list it grows its list, as it does for a column outlet (`IslandSets`).
- A junction, a pipe and a heat side exchange nothing. Between a heater and the next holdup the hydrolysis product travels as the tag.

This is the one place where the interval changes: one conversion at its start, one enum constant in `ConservativeTransport`, and `SOLUTE` among the kinds a certificate replays as a rate (`IslandCertificate.rate`). The step's Newton and its booking do not change.

## 8. The transformations of this batch

### 8.1 Desalting (`transfer`, in every vessel that holds oil and free water)

Salt on the oil moves to the water at the rate `k = E · v_s / H`:

- `v_s = g d² (ρ_w − ρ_o) / (18 μ_o)`: Stokes, with the oil's viscosity and both densities from the vessel's state.
- `d`: 1.3 mm in a desalter with its field on, 0.1 mm in any other vessel and in a desalter without power.
- `H`: the oil layer's height.
- `E = 1 − exp(−W / 0.02)`: the contact with wash water, `W` the water to oil mass ratio of what entered over the last slice (of the holdup when nothing entered).
- `hydrolysable_chloride` moves at half the rate (a single stage removes about 90 % of sodium chloride and 40 to 50 % of the others: OGJ, review section 2).

In a through-flow vessel this gives a removal of `E · N/(1 + N)`, `N = v_s A / Q` (`A` the plan area, `Q` the oil's volume flow). A desalter of 54 m² at 0.184 m³/s:

| Crude, temperature | Oil viscosity (typical) | `N` | Water settled | Salt removed at 5 % wash |
|---|---|---|---|---|
| Medium, 60 °C | 8 mPa s | 4.7 | 82 % | 76 % |
| Medium, 90 °C | 4 mPa s | 9.5 | 90 % | 83 % |
| Medium, 120 °C | 2 mPa s | 18.9 | 95 % | 87 % |
| Medium, 150 °C | 1.2 mPa s | 30.8 | 97 % | 89 % |
| Medium, 120 °C, no field | 2 mPa s | 0.11 | 10 % | 9 % |
| Heavy, 120 °C | 12 mPa s | 1.4 | 59 % | 54 % |
| Heavy, 150 °C | 6 mPa s | 2.6 | 72 % | 66 % |
| Heavy, 150 °C, half the flow | 6 mPa s | 5.1 | 84 % | 77 % |

Wash water: 1 % gives `E` = 0.39, 3 % 0.78, 5 % 0.92, 8 % 0.98. Two stages in series reach 98 %.

Temperature acts through the viscosity, as the owner asked. Its upper limit needs no rule: where the water boils the vessel holds no free water and nothing transfers. A storage tank settles its brine too, in days (`k` = 5e-6 1/s at 25 °C, a time constant of 54 process hours).

Not modelled: the mix valve's pressure drop, demulsifier, water carried over in the oil (the phase draw takes clean oil).

### 8.2 Chloride hydrolysis (`first_order`, heat sides, vessels, a column's bottom)

`hydrolysable_chloride` → `acid_chloride`, mole for mole of chlorine. `k = 180 s⁻¹ · exp(−5190 K / T)`, window 393 K and above, oil present. A fit to two anchors: 90 % in 60 s at 340 °C, 5 % in 60 s at 150 °C.

| Temperature | `k` (1/s) | In 8 s | In 60 s | In 300 s |
|---|---|---|---|---|
| 120 °C | 3.3e-4 | 0.3 % | 2 % | 10 % |
| 200 °C | 3.1e-3 | 2 % | 17 % | 61 % |
| 270 °C | 1.3e-2 | 10 % | 54 % | 98 % |
| 340 °C | 3.8e-2 | 26 % | 90 % | 100 % |
| 370 °C | 5.6e-2 | 36 % | 97 % | 100 % |

The product is the tag `acid_chloride`, on the stream's water (steam or liquid) if it has any, else on its lightest cut; the next holdup makes it the compound (7.4). The water the hydrolysis uses is not booked (below 1e-5 of the stream).

`caustic` (`transfer`, any site with oil): `hydrolysable_chloride` + `caustic` → `salt`, at once where both are present, 1.13 kg NaOH per kg Cl. This is the refinery's caustic injection after the desalter.

### 8.3 Hydrogen chloride: the compound and the acid

The path in a crude unit: the furnace makes the tag; the column's account turns it into the compound and gives it to the top product; it is a gas component in the overhead line and through the condenser; the reflux drum, which holds free water, takes it into `acid_chloride` on its water; the water outlet carries the acid away. A vessel that boils its water off gives the compound back.

What the compound needs of the data and the thermo model:

| Item | Requirement |
|---|---|
| Dataset | `chemsep_hydrogen_chloride` through `tools/compound-import` (a `selection-hcl.json`), from ChemSep record 1904, which is complete: Tc 324.69 K, Pc 8.31 MPa, acentric factor 0.126, heat capacity 20 to 1 600 K, both viscosities, heat of formation, entropy, triple point 158.97 K (`freezing`, no crystal record). PR78 on it: vapour pressure within 7.5 %, heat of vaporization within 4.3 % (review 4.2) |
| Formula | Chlorine joins `MaterialCatalog.FORMULA_ELEMENTS` and `Thermochemistry.ELEMENTS`. The heating value is 0 (it burns as an inert) |
| Hazard | A `hazard` sub-record, so a vent, a flare or a leak of it reaches the air through the pollution system as it stands |
| Pairs with hydrocarbons | The rule of the refinery compounds (DWSIM `kij_pr.dat`, else the DECHEMA value, else 0) gives 0 for every pair: none of the three files holds one. A fair value: its solubility in paraffins is near ideal (review 4.2) |
| Pair with water | A library pair, `k_ij` = 0, with its reason. No `henry_in_water`: with one, `DerivedPairs` would fit a pair to the solubility and the cubic would hold hydrochloric acid beside the tag |
| Flash | As any compound. At parts per million it needs no special case: the trace cutoff folds trace phases, not trace compounds, and the flush acts below 1e-24 |
| Column | Not on V3's axis and in no neural seed: the running column and the simulator's column carry it on their accounts and give it whole to the top product, as inert particles go whole to the bottoms |
| Identity | A compound added to a package moves no identity of a view without it: no world is restated |
| Kernel, engine | No change: no electrolyte model, no new phase |

At `k_ij` = 0 the cubic still puts some of the compound into the water of a wet stream, about as it does for hydrogen sulfide. That is harmless: the absorption takes the compound from every phase of a wet holdup.

### 8.4 Surface coking (`threshold_deposit`, a heat side that carries oil)

It replaces the Kern–Seaton placeholder of the fouling-corrosion batch for oil sides (owner decision O1 there: "until the reaction module"). Water sides keep Kern–Seaton.

`dRf/dt = p · [3.17e-4 · exp(−5773 K / T_f) · v^(−0.66) − 5.1e-9 · v^1.6]`, in m²K/W per process second, not below 0.

- The form is Ebert and Panchal's threshold model in Panchal's 1999 reading with Polley's activation energy (48 kJ/mol); the two constants are fitted to the game's fouling time, 0.0009 m²K/W in 10 process hours at 350 °C and 1 m/s.
- `T_f`: the film temperature, `T_bulk + 0.55 (T_wall − T_bulk)`, `T_wall` from the side's duty, area and film.
- `v`: the passage speed. `p = clamp(asphaltene share of the oil / 0.03, 0.2, 3)`.
- Window: `T_f` ≥ 473 K.

| Speed | Deposit starts at `T_f` | Hours to 0.0009 at 350 °C | At 400 °C |
|---|---|---|---|
| 0.5 m/s | 185 °C | 5.5 | 2.7 |
| 1 m/s | 250 °C | 10 | 4.6 |
| 2 m/s | 337 °C | 70 | 11 |
| 3 m/s | 402 °C | none | none |

Mass: the formation term × deposit conductivity (1 W/mK) × density (1 200 kg/m³) × area is the coke formed. The net part leaves the oil's `asphaltenes` and becomes the side's deposit (the `Rf` fact that exists). The part the flow removes becomes `coke_fines` on the same carriers.

### 8.5 Bulk coking (`first_order`, vessels and a column's bottom)

`asphaltenes` → 60 % `coke_fines`, 40 % deposit. `k = 3e-5 s⁻¹ · exp(−25 258 K · (1/T − 1/673.15 K))` (210 kJ/mol, the usual value for residue cracking), window 613 K and above.

| Temperature | Asphaltenes coked per hour |
|---|---|
| 340 °C | 0.3 % |
| 370 °C | 1.9 % |
| 400 °C | 10 % |
| 430 °C | 42 % |

In a column the deposit raises the tray fouling that the fouling-corrosion batch's WP8 already keeps (`trayFouling`), by the deposit's share of 2 % of the bottoms. In a vessel the deposit is booked and has no effect yet. `coke_fines` count as solid share in `ErosionLaw` and in the column's tray fouling. They are not a `SolidInventory` population in this batch (option O3).

### 8.6 Why none of these books heat

| Transformation | Largest amount | Heat against the stream's |
|---|---|---|
| Hydrolysis | 30 mg HCl per kg of crude, about 2 MJ/kg | 60 J/kg against 9e5 J/kg of preheat: 7e-5 |
| Acid chloride into water | 75 kJ/mol | The same order |
| Surface coking | 240 kg of coke in 10 hours of 50 kg/s | 1.3e-4 of the mass, below 1e-4 of the duty |
| Bulk coking | 1.9 % per hour of 5 % asphaltenes at 370 °C | 1e-3 of the mass per hour × about 0.5 MJ/kg: below 1e-4 |

## 9. Corrosion

Changes to `science/integrity`:

- `CorrosionStandard.digest` takes `S` and `A` from the tags `sulfur` and `acid` of the oil it reads, no longer from the static sub-record. With default contents the numbers are those of today.
- **M12, acid chloride** (`Mechanism.ACID_CHLORIDE`, appended): acts where free water meets chloride acid, as the tag `acid_chloride` on the water or as the compound not yet absorbed (the wet end of a condenser). `m = (acid chloride + hydrogen chloride − ammonia − caustic) / kg of free water`, in mol/kg, not below 1e-7; `pH = −log10 m`. The ammonia is all the node holds, as the neutralisation of 7.4 takes it.
- The acid dew point: with hydrogen chloride the first acid drop forms above pure water's dew point (17 K in the OGJ example), and the cubic does not know it. A column's top zone and its condenser therefore count as wet for M12 up to 15 K above the table's `waterBelow`. A heat side of the network has only its state's wet flag: a known miss.
- A heat side reads both its ends: the hotter end for the dry mechanisms, as today, and the colder end for the wet ones. A condenser's acid attack is at its cold end, which the hotter-end digest of the fouling-corrosion batch does not see. Carbon steel: `min(25, 2.5 · 10^(−0.5 (pH − 2.25))) · 2^((min(T, 366 K) − 311 K)/55 K)` mm/y, none above pH 5.5.

| pH | 38 °C | 93 °C |
|---|---|---|
| 1 | 10.5 | 21 |
| 2.25 | 2.5 | 5 |
| 3.6 | 0.5 | 1.1 |
| 4.5 | 0.19 | 0.37 |
| 5.5 | 0.06 | 0.12 |

Material factors: chrome steel 1, stainless steel 0.3 (it pits and cracks in chloride), nickel alloy 0.02. Nickel alloy keeps its place as the answer for an acid overhead. The anchors are recalled from the hydrochloric acid table of API RP 581 Part 2 Annex B and must be read against it before the constants are fixed (the search of this task did not reach the table).

**Per part (D10).** A running column today has one wall on its bottoms. It becomes a list of parts, each with its own loss, rate and mechanism:

| Part | Fluid read | Material |
|---|---|---|
| Shell zone per product (top, each side draw, bottom) | That product of the installed split table: its recoveries of the feed, temperature, pressure; the tags by the same recoveries | The column's |
| Top zone, wet | Wet when the top temperature is below the table's `waterBelow`; then the liquid water is one tenth of the overhead's water (the first condensate) | The column's |
| Condenser process side | The overhead at the condenser outlet; wet as the product is | The condenser's own |
| Reboiler process side | The bottoms | The reboiler's own |
| Pumparound cooler process side | Its draw | The cooler's own |

Each is computed once per installed table, as the bottoms' is today. A part through trips the column (`LEAKING`) and names the part. The Operation page's wear table shows one row per part. The reflux drum and every other vessel or heat side of the fluid network already have their own records. In the simulator's Materials tab a unit shows one row per part, and the governing part chooses the material.

## 10. The electric desalter

- `EquipmentShape.Kind.DESALTER`: a horizontal vessel on saddles with a transformer housing on top, electrode grids inside, a crude and water inlet low, the oil outlet on top, the brine outlet at the bottom, an FE terminal. Built by the constructor from a design (`DesalterDesign`), sized by plan area.
- Registration: one closed vessel with an oil and a water phase port (the phase ports that exist), and an electric store (`ElectricStores`).
- Power: `0.3 kW/m²` of plan area at 100 °C, doubling every 40 K (the oil conducts better hot): 16 kW at 100 °C and 32 kW at 140 °C for 54 m². Without power `d` falls to 0.1 mm.
- Status: `NO FIELD`, `NO WASH WATER`, `GASSING` (the vessel holds gas: the field is off), and the salt in and out in PTB.
- Simulator: a modelled unit with a tile, a port rule and a lang line (`FlowsheetPaletteCoverageTest`); Sizing rows: `N` ≥ 9 (Must), 110 to 150 °C and 3 to 8 % wash water (Should).
- Model, sound and drawings by the `models-and-sfx` skill and `tools/equipment-drawings/`.

## 11. Simulator

A sheet stream carries a `TagSet`; a feed takes its assay's contents. Units pass tags by their recoveries, heat units apply hydrolysis and surface coking at their sized passage, the desalter its law. The Materials tab's digest reads the tags and M12. A vessel unit applies the release and the absorption of 7.4 to its outlet streams; the column gives hydrogen chloride to its top product. All on the client, as today.

## 12. Performance

| Item | Where | Bound |
|---|---|---|
| Tag transport | The island's worker, once per interval | Skipped without tags; one factorization without a phase draw; else one per carrier and class that holds a tag (at most 38) |
| Transformation coefficients | Server thread, at the 5 s cadence | 256 sites a pass; a site outside its window costs one comparison |
| Hydrogen chloride | Islands that carry it | One compound more in their flash (about 1 in 30 for an overhead system); one TP flash per exchanging vessel and interval |
| The step's Newton, its booking, the certificates | | No change |
| Column parts | Once per installed table | One digest per part |
| Memory | Per stock with tags | 11 tags × 13 carriers doubles |

## 13. Formats and config

| Format | Now (with fouling-corrosion) | This batch |
|---|---|---|
| Fluid facts schema | 29 | 30: a vessel's `tags`, a generator spec's contents, the accounts' tags, a column unit's parts, the tag ledger. Absent: default contents, one wall on the bottoms |
| Fluid checkpoint state format | 52 | 53: junction tags, site coefficients |
| Wire protocol | `icheme-5` | `icheme-6`: views with contents, the desalter's page |
| Simulator sheet file | 2 (3 reserved, to be released) | The next free number: stream tags |
| Material data | | Kinds `solutes` and `reactions`; `content` sub-record; assay `content`. In no identity. The compound `HydrogenChloride` with its dataset and pairs, in `icheme:refinery` (its revision bumped; `materials-index.json`) |
| Interval result | | `BoundaryTransfer.Kind.SOLUTE`, appended |
| Corpus | | `compat/fluid/schema-30` |

Numbers are reserved on `main` when the build starts (COLLABORATION.md section 3), on the owner's word.

Config: `reactions.enabled` (off: tags still ride, nothing transforms), `desalting.enabled`.

## 14. The frame for reactor units

For the next design task; decided here only so that this batch fits it.

- A reactor is a unit on the `Ports` hook with accounts, as `ColumnOperation` is. One model solve per operating point gives a **yield table**: products per feed compound and tag, duty per feed. A drift band asks a new solve. The solve runs on the worker as a plant job.
- Its products are the existing compounds and cuts with new tags (cracked naphtha is the naphtha cuts with other contents). Mass is exact on the compounds; a tag that becomes a compound (sulfur to H2S) takes its mass from its carrier.
- Its heat is booked on its account: by Hess's law from the `thermochemistry` sub-records where every participant is a real compound, by a stated heat per kg converted for lumps (the cuts' estimated formation enthalpies are too rough for a difference).
- A reactor with real holdup (stirred reactor, coke drum) uses the vessel form of section 7.2; one without holdup the residence time of its bed, riser or coil.
- Catalyst state (activity, coke, metals) is a fact of the unit.

The models and what each lacks: [REACTION_MODELS_REVIEW.md](REACTION_MODELS_REVIEW.md), sections 3 and 4.

## 15. Work packages

After `2026-11-16-fouling-corrosion` is on `main`. Five stages, each leaving `main` working.

| Stage | WP | Content | Lane, hot files |
|---|---|---|---|
| A | WP1 | `science/solute`: `TagKind`, `TagSet`, kinds `solutes` and `reactions` in `MaterialCatalog`, the `content` sub-record (aliases of `corrosion`), assay `content` and the fit in `tools/crude-grades/`, the eleven records | thermo: `MaterialCatalog` |
| A | WP2 | `TagTransport`; `Inventory.tags`; the result and commit wiring; the replay step; generator contents; facts schema 30, state format 53, the corpus image; `TagLedgerTest` | fluid laws, runtime: `FluidFacts`, `FluidCheckpointCodec`, `IslandCoordinator` |
| A | WP3 | The digest on tags (`CorrosionStandard`); references exact with default contents | thermo |
| B | WP4 | `science/reaction` (the three laws), `runtime/fluid/Reactions`, `TagPlan`; hydrolysis, caustic, bulk and surface coking; `FoulingLaw`'s oil rows removed | runtime: `FluidWorldAuthority` |
| B | WP4b | Hydrogen chloride: the compound's records and pairs, chlorine in the formula elements, the hazard; the exchange of 7.4 (`PassiveIntervalSolver.solve`, `Kind.SOLUTE`, the plan in the command, island growth); the ledger across compound and tag | thermo, fluid laws: `MaterialCatalog`, `ConservativeTransport` (one constant), `IslandCoordinator` |
| B | WP5 | M12 and its factors; a heat side's colder end for the wet mechanisms; `coke_fines` in the solid share | thermo |
| C | WP6 | The desalter: kind, design, model, registration, law, page, sound | equipment: `EquipmentShape`, `EquipmentStructure` |
| D | WP7 | Tags through the column's, still's and converter's accounts; the column's parts and their facts; the Operation table | plant: `ColumnOperation`, `ColumnFacts` |
| E | WP8 | Simulator: stream tags, the desalter unit, the Materials tab per part | flowsheet: `SheetSizing` |
| E | WP9 | Device pages: a Contents group (sulfur wt%, salt PTB, the water's pH); releases of a tag with a `hazard`; tutorial page | client, runtime presentation |

WP2 and WP6's model can run side by side; so can WP4 and WP7 after WP2, and WP8 and WP9. Each work package gets its full brief (files, methods, numbers, test commands, pitfalls) when it starts.

Results that move: none in stage A (default contents give today's digest). Stage B moves the fouling of oil sides from Kern–Seaton to the threshold law: the review states old and new values per test.

## 16. Owner's options

- **O1, HCl: decided** (owner, 2026-11-16, revision 2): a compound. Sections 7.4 and 8.3.
- **O2, zone material.** One material per column today. A real column clads its top. Option: a second material for the top zone (one shape field).
- **O3, coke fines.** Designed as a particulate tag. Option: turn them into a `SolidInventory` population at the next vessel, so that filters cake and beds settle. It needs a mass exchange with the heaviest cut in the vessel's refresh.
- **O4, metals.** One tag (Ni + V). FCC models treat them apart; two tags cost one more row.
- **O5, the numbers** of sections 8 and 9 are game-level fits to the stated anchors, as the corrosion matrix is.

## 17. Risks

- The tag pass rebuilds transport from `PipeTransfer`. An interval that booked a transfer the history does not hold (a release, a melt) must be covered boundary by boundary; `TagLedgerTest` runs on the hold-fuzz worlds to find them.
- Default contents (D8) must be expanded at every place two stocks meet: a missed place loses tags silently. One helper, `TagSet.of(inventory, defaults)`, and no other read.
- A column product's wetness is read from `waterBelow` only; a tray below the dew point inside the top zone is not seen.
- The desalter's numbers rest on typical viscosities; the mod's own viscosity model for the heavy cuts decides the real table, and the drop size is then fitted once.
- M12's anchors are recalled, not read (section 9).
- The exchange of 7.4 and the certificates: `IslandCertificate` reads a transfer of material at a vessel as a flush, which refuses the certificate, unless its kind is a rate a steady island repeats (`VENT`, `SPILL`, `ATMOSPHERE`: `IslandCertificate.rate`). `SOLUTE` must join those kinds, or a steady overhead drum would solve every slice; a replay then scales the exchange linearly with the span, as it scales the others. Whether `REPLAY_REVISION` moves with it is decided in WP4b.
- Hydrogen chloride between a heater and the next holdup is a tag, so a gas composition read on that line does not show it.
- A column whose top product is led to no vessel with free water never absorbs its hydrogen chloride; it stays a gas component to wherever the gas goes. That is the physics.
