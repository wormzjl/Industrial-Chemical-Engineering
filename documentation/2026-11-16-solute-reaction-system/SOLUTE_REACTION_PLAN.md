# Solute and reaction system: design (revision 6)

Revision 6 (owner, 2026-11-16, on the ammonium salts: "This is basically a reaction, needs to be handled by the reaction module"): every chemical change is a `reactions` record of one module. The kind `equilibria` of revision 3 becomes the law `ionisation`; a salt that forms from two gases is the law `equilibrium` (7.5), which uses up its gases exactly: outside a holdup through their accounts, settled at the next holdup (D15). The separate kind and law that review 4.7 first proposed are withdrawn. Sections 4, 7.1, 7.4, 7.5, 12, 13, 15, 16, 17.

Revision 5 (owner, 2026-11-16): the options are decided. O2: one material per column. O3: coke fines become a solid population in a vessel (5.5). O4: two tags, `nickel` and `vanadium`. O8: the plan's game values. And the owner's note "later in game the crude composition will be randomized, consider that": contents become a function of a crude stated by value (5.1, 5.6), the tag pass is judged with tags on every oil island (12), and the later models read the cuts and the tags only (14, review 7.6). Sections 4, 5, 8.5, 11 to 17.

Revision 4 (owner, 2026-11-16: "explore more and justify your selection"): after the model survey (review section 7) four laws of this batch change. Desalting gains a drag correction (8.1); the hydrolysable chloride is magnesium's and 12 % of calcium's (5.2, 8.2); wall fouling is written on Reynolds and Prandtl numbers and wall shear at 40 kJ/mol (8.4); bulk coking waits for the resins to fall below a solubility limit (8.5). M12's slope is corrected (9) and the law's constants are read (7.4).

Revision 3 (owner, 2026-11-16: "Level 1: speciation"): acids and bases in water follow one speciation law, solved at holdups and in units on accounts, outside the kernel. It replaces revision 2's absorption, limit, neutralisation rule and pH formula (sections 4, 5, 7.4, 8.2, 8.3, 9, 11, 12, 13, 15, 16, 17).

Revision 2 (owner, 2026-11-16: "I mean model the HCl as an components"): hydrogen chloride is a compound of the network package. A tag becomes the compound, and the compound a tag on water, at holdups (sections 4, 5.2, 7.4, 8.2, 8.3, 9, 12, 13, 15, 16, 17).

Batch `2026-11-16-solute-reaction-system`, branch `claude/solute-reaction-system-01ef6c`, cut from `main` at `adb413b4d` (0.34.0). Status: design, no code. It builds on batch `2026-11-16-fouling-corrosion` (`science/integrity`, the walls, the column's wear), which is on `main` since 0.34.3 (`228443411`, 2026-11-16). This branch does not hold that merge yet: the build starts from a merge of `main` into it.

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
- "later in game the crude composition will be randomized, consider that" (section 5.6)
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
| D7 | Hydrogen chloride is a compound, `HydrogenChloride`, in `icheme:refinery` (owner, revision 2). The compound is its molecular part; its ions in water are the tag `acid_chloride`. |
| D12 | Acids and bases in water follow one **speciation law** (owner, revision 3): mass action between each volatile's molecular part (a compound, which the cubic sees) and its ions (a tag on the water, which it does not), closed by the charge balance. It is solved in wet holdups at an interval's start and in units on accounts, never inside the step's Newton or the kernel. The split, the neutralisation and the pH are its outputs. Later acids and bases (H2S, CO2, amines) add records, not rules. |
| D8 | A stock without tag data has its datasets' default contents. Only a stock that differs stores amounts, so an old save needs no conversion and a fluid without contents pays nothing. With randomized crudes every crude stock differs: explicit tags are the usual case, and the costs are judged for it (section 12). |
| D13 | A crude is stated **by value**: its amounts on the shared cuts and one factor per tag on the cuts' default profile (owner's note on randomized crudes, 5.6). No law, record or saved value names a grade. Contents are a pure function of those numbers. |
| D14 | Coke fines are a tag while they travel and become a **solid population** in a vessel (owner, O3): the same mass leaves the carrier cut. From there the existing solids machinery settles, carries and filters them (5.5). |
| D15 | Every chemical change is a record of the **reaction module** (owner, revision 6): rate laws and equilibria are laws of `reactions` records, with stated participants, window and sites. A reaction uses up what it uses. A volatile compound has an **account** outside the kernel, its ion tag: a reaction at a passage posts to the accounts, with a sign, and the next holdup settles them with the compound (7.4, 7.5). |
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
| `ion` | Optional: `cation` or `anion` (one charge per mole). The tag then counts in the charge balance of the speciation law (7.4) |
| `hazard` | Optional, as a compound's: the channel and reference for a release to the air |
| `maximum_per_kg_carrier` | The largest content a cut can hold (kg per kg). A factor or a target that asks for more is cut off there |
| `solid` | Optional: `material` (a `solids` record), `size_micrometres`, `flag_mass_fraction`. The tag becomes this solid in a vessel (5.5) |

Default contents: a `content` sub-record on a pseudocomponent's dataset, kg of tag per kg of the cut, with its own revision, in no identity. It is the **default profile**: how each content is spread over the boiling range. It replaces the `corrosion` sub-record of the fouling-corrosion batch: `sulfur_mass_percent` and `acid_number` become the defaults of the tags `sulfur` and `acid`; `fouling` is replaced by the asphaltene content (section 8.4). The old field names are read as aliases.

A crude's contents are a function, `science/solute/ContentProfile` (pure, no catalog type):

`content[tag][cut] = min(maximum_per_kg_carrier, factor[tag] × profile[tag][cut])`

- An assay gains an optional `content` block with one of two forms. `factors`: one number per tag (absent: 1). `targets`: whole-crude values (sulfur wt%, nitrogen, nickel and vanadium in mg/kg, asphaltenes and resins wt%, acid number, salt in PTB, the hydrolysable share of the salt); the catalog finds the factor that meets each target on the assay's own cut amounts, and states the value reached where the cap cuts it off.
- The same function serves a crude that is no datapack record (5.6): it needs the cut amounts and the factors, nothing else. There is no fit per grade in `tools/crude-grades/`; the tool only writes the default profile.
- Every assay then has its own sulfur, which closes decision D9 of the fouling-corrosion batch.
- The salt of a crude tags its heaviest cut that holds at least 1 % of the crude's mass (brine and crystals dispersed in the oil); assays stay dry.

### 5.2 The tags of this batch

| Tag | Carriers | Class | Unit | Used by |
|---|---|---|---|---|
| `sulfur` | cuts | bound | kg S | Sulfidation (M1); hydrotreating, FCC, coking |
| `nitrogen` | cuts | bound | kg N | Hydrotreating, hydrocracking, FCC |
| `acid` | cuts | bound | kg KOH equivalent | Naphthenic acid corrosion (M2) |
| `nickel` | cuts | dissolved (stay) | kg Ni | Hydrotreating catalyst life; FCC: hydrogen and coke (owner, O4: two tags) |
| `vanadium` | cuts | dissolved (stay) | kg V | Hydrotreating catalyst life; FCC: loss of activity |
| `asphaltenes` | cuts | dissolved (stay) | kg | Coking (8.4, 8.5), coker and FCC coke yield |
| `resins` | cuts | dissolved (stay) | kg | Coke yield; asphaltene stability later |
| `salt` | cuts, water | dissolved (heaviest cut) | kg Cl in stable chloride | Desalting; fouling later |
| `hydrolysable_chloride` | cuts, water | dissolved (heaviest cut) | kg Cl that can hydrolyse: magnesium chloride's, and 12 % of calcium chloride's (review 7.3) | Hydrolysis (8.2) |
| `acid_chloride` | water, cuts | acid gas; anion | kg Cl as HCl | The ions of hydrogen chloride in water (7.4, M12); the hydrolysis product on its way to the next holdup |
| `ammonium` | water | dissolved (heaviest cut); cation | kg N as NH4 | The ions of ammonia in water (7.4) |
| `caustic` | water, cuts | dissolved (heaviest cut); cation | kg NaOH | Converts hydrolysable chloride and acid chloride to salt; in excess, a base in the charge balance |
| `coke_fines` | cuts | dissolved (stay), particulate; `solid`: `icheme:coke`, 50 µm | kg | Erosion, tray fouling; a solid population in a vessel (5.5) |

Sulfur in H2S, mercaptans, COS and SO2 stays in their formulas, as today. Ammonia is a compound; where an acid meets it in water, its ions are the tag `ammonium`.

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

### 5.5 Coke fines become a solid in a vessel (owner, O3)

Expected. Coke particles ride the oil through exchangers and pipes. They settle where the oil stands hot and thin, stay suspended in cold, viscous oil, and a filter catches them. A 50 µm particle of coke (1 200 kg/m³) in oil of 800 kg/m³ settles by Stokes' law at 2.0 m/h in oil of 1 mPa s (a hot drum), 0.2 m/h at 10 mPa s and 4 mm/h at 500 mPa s (cold residue).

Design:

- The fines travel as the tag `coke_fines` (D4: nothing happens in a pipe). A column, a still and a converter carry the tag on their accounts to the liquid product.
- A vessel of the network (not a junction, not a fixed node) whose `coke_fines` exceed `flag_mass_fraction` (1 mg per kg of its liquid) is flagged. At the interval's start, at the site of the speciation solve (7.4), each carrier cut gives up the kilograms its tag states (`moles −= kg / M_cut`), the vessel gains that mass as a population of `icheme:coke` at 50 µm, and the tag falls to 0.
- The stock's internal energy is kept and the refresh restates T and P, so mass and energy are exact. The temperature moves by less than 0.02 K per kg of fines in a 10 t holdup.
- Booking: one `SOLUTE` transfer for the cuts' moles that leave the fluid, and the same mass gained on the solids' ledger. `SOLUTE` is a rate kind (7.4), so the exchange alone refuses no certificate.
- From there the existing machinery acts: `SolidMobility` decides whether an outlet carries them, `SlurryTransport` moves them, a still vessel settles them into a bed, an `InlineFilter` cakes, the recovered-solids item takes them out. An island that holds oil is monitored for solid events already (`SolidMobility.monitored`), so its interval driver does not change.
- A new `solids` record, `icheme:coke`: density 1 200 kg/m³ (the deposit's), heat capacity 1 300 J/kg K. Game values.
- Coke never returns to a cut.

What it replaces: in a vessel, bulk coking's deposit share was booked without effect (8.5). All the coke a vessel makes is now fines, and so a bed the player must draw off or filter. A column's bottom keeps its split, for the tray fouling.

Not changed: a wall's deposit (the `Rf` fact) stays an amount without mass, as every tag (D1). At the fouling limit it is 1.1 kg/m²: for 100 m² in 10 process hours at 20 kg/s, 1.5e-4 of the oil that passed.

The rule is the record's `solid` block, not coke's own: catalyst fines and scale later add a block.

### 5.6 Randomized crudes (owner's note)

"later in game the crude composition will be randomized, consider that."

What it means on the mod's data. The grades are already distributions on twelve shared cuts and the light ends (`tools/crude-grades/grades.mjs`: Riazi's distribution from an API gravity, a light end share and a residue share). A random crude is one more distribution on the same cuts, with contents of its own. The cuts themselves are not randomized (owner, O9: "Yes, cuts stay").

What already serves it:

- Contents ride as amounts per (tag, cut) (D1, D2). Two crudes blend exactly, in a tank, a pipe or a column; nothing is keyed by a grade.
- Every law of section 8 and M12 reads the tags and the stream's own state (viscosity, density, Reynolds and Prandtl numbers). None holds a constant per grade.
- The corrosion digest reads tags (WP3), not a cut's static numbers.

What changes in this plan:

1. Contents are a function of the crude's numbers (5.1, D13), not a table fitted per grade.
2. A source states its crude by value: a generator's spec, a Forge fluid entering the network and a sheet's feed hold the cut amounts and the factors. A random crude is drawn once and saved as a fact with the declared revision of the law that drew it. It is never drawn again and never keyed by a hash.
3. A cap per tag (`maximum_per_kg_carrier`) and the salt's 1 % rule (5.1), because a random distribution can leave a cut almost empty.
4. Explicit tags are the usual case (D8): the tag pass is judged with tags on every oil island (section 12).
5. The device page's Contents group (WP9) is how the player learns a crude: it shows what a tank holds, not a grade's name.

The ranges the laws are checked for (typical crudes, recalled; game values for the later batch to draw from):

| Content | Range | The plan's default (medium crude) |
|---|---|---|
| Sulfur | 0.1 to 4 wt% | 1.5 |
| Nitrogen | 0.02 to 0.5 wt% | Set with the profile (WP1) |
| Nickel, vanadium | 1 to 100, 1 to 400 mg/kg | Set with the profile (WP1) |
| Asphaltenes | 0.1 to 15 wt% | 3 |
| Resins | 2 to 25 wt% | 9 |
| Acid number | 0.05 to 4 mg KOH/g | The fouling-corrosion batch's |
| Salt | 5 to 300 PTB | 50 |
| Hydrolysable share of the salt | 5 to 35 % | One sixth |

At the extremes:

- Wall fouling: `p` runs from its floor 0.2 to its cap 3 (8.4), so the time to the fouling limit differs 15 times between the cleanest and the dirtiest crude.
- Bulk coking: a crude with more asphaltenes than 0.4 of its resins cokes at once above 340 °C (8.5). The draw must keep the ratio below 0.3, or name such a crude unstable on purpose.
- Acid: the chloride that reaches the overhead scales with salt × hydrolysable share. The worst crude gives 12.6 times the default's (300 PTB × 0.35 against 50 PTB × 1/6): the overhead water's pH is 1.1 lower and M12's rate 2.4 times higher without a desalter. Two desalting stages (97 %) bring it below the default's.
- A blend adds asphaltenes and resins. Two stable crudes always give a stable blend: the incompatibility of real blends is not modelled.

The reactor units (section 14) must answer to the crude as well. Review 7.6 lists what each selected model reads from the feed.

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

A new material kind, `reactions`: `id`, `revision`, `source`; `uses` and `makes` (tags with mass ratios; for the two equilibrium laws also volatile compounds, each with its account tag and an exponent, and a solid); `law` (one of a closed list, below) with its parameters; `window` (temperature range, and which of oil, free water, gas must be present); `sites` (equipment classes); `enthalpy` (`none`, a stated J/kg, or `hess` for real compounds: section 14).

| Law | Form | Used by |
|---|---|---|
| `first_order` | `k = A exp(−E/RT)` on a tag, optionally only on its excess over a `limit` (a factor on another tag) | Hydrolysis, bulk coking (8.5 uses the limit) |
| `transfer` | A tag moves between carriers at a rate the site's law gives | Desalting, caustic |
| `threshold_deposit` | Formation minus removal on a heat side's wall | Surface coking |
| `ionisation` | Mass action between a volatile's molecular part and its ion in free water, all records closed together by the charge balance | Acids and bases in water (7.4) |
| `equilibrium` | A product of activities against `K(T)`; the excess reacts to a solid, and the solid gives the gases back below it | Ammonium chloride (7.5); later the bisulfide, amine salts, carbamate |

The first three are rate laws: a coefficient at the cadence, applied by the tag pass. The last two are equilibria: reached at once where they are evaluated.

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

### 7.4 Acids and bases in water: the speciation law

Each volatile acid or base has two forms. Its **molecular part** is the compound: the cubic sees it, with its pair, in every phase. Its **ions** exist only in free water and are a tag on that water: no pressure, no flash. One law splits a wet holdup's amount between the two (review 4.5 and 4.6).

Record: a `reactions` record with the law `ionisation` (revision 6; revision 3 had a kind of its own, `equilibria`). Its fields:

| Field | Meaning |
|---|---|
| `id`, `revision`, `source` | As every record; in no identity |
| `volatile`, `ion` | The compound and its ion tag |
| `type` | `acid` (the ion is an anion) or `base` (a cation) |
| `constant` | `ln K = a + b/T`. Acid: `K = γ² h m / f`. Base: `K = m / (h f)`. `h` is the hydrogen ion and `m` the ion, in mol per kg of free water; `f` is the compound's fugacity |
| `strong` | The holdup is solved whenever the volatile or its ion is present |
| `activity` | Optional: Bromley's `B` for the `γ` of a strong acid. Absent: `γ` = 1 |
| `enthalpy` | `none`, or `from_constant` (`−R b` per mole ionised), booked where it is a major heat: the amine units |

The records of this batch:

| Volatile | Ion | `K` at 25 °C | Slope `b` | Check |
|---|---|---|---|---|
| `HydrogenChloride` | `acid_chloride` | 2.03e6 mol² kg⁻² atm⁻¹; strong; `B` = 0.1433 | 9 000 K | Returns 4.0e-12, 3.2e-9, 3.3e-7 and 1.85e-4 atm over 0.003, 0.1, 1 and 6 mol/kg acid (real 4.0e-12, 3.2e-9, 3.3e-7, 1.9e-4). `K` and its slope read (Sander's compilation, after Clegg and Brimblecombe); `B` recalled (Bromley 1973) and kept so (owner, O5) |
| `Ammonia` | `ammonium` | 1.05e11 atm⁻¹ (solubility 59.8 mol kg⁻¹ atm⁻¹ over the ammonium ion's 5.69e-10, pKa 9.245) | 10 490 K (4 200 + 6 290) | Read (Sander's compilation; Bates and Pinching's slope recalled) |

Water's own product: 1e-14 at 25 °C, slope −6 710 K.

**The solve**, for one holdup with `W` kg of free water:

1. Totals: `N_k` = the compound's moles in all its phases + its ions.
2. The compound's fugacity per mole, from the kernel at the committed state: `F_k = P / Σ_p (n_p / φ_k,p)` over the phases present (`n_p` the phase's moles, `φ_k,p` the compound's fugacity coefficient in it; at infinite dilution if the vessel holds none). Then `f_k = F_k · (N_k − ions_k)`.
3. Ions as a function of `h`. Acid: `ions = N · a/(1 + a)`, `a = K F W / (γ² h)`. Base: `ions = N · b/(1 + b)`, `b = K h W F`.
4. The charge balance, one unknown: `h − Kw/h + (Σ cations − Σ anions)/W = 0`, over the ions of step 3 and the excess `caustic`. Its left side rises with `h`, so it has one root: bisection on pH from −2 to 15 (`DeterministicMath`). `γ` is taken at the ionic strength of the pass before; three passes.
5. Each compound is set to `N_k − ions_k` and each ion tag to `ions_k`. If a molecular share moved by more than 1 % of `N_k`, steps 2 to 4 run once more.

**When.** Only in a holdup with free water that holds a `strong` volatile or its ion, excess `caustic`, or at least one acid and one base of the records. A single weak acid or base in water is left to its pair, which already holds its own weak ionisation. So no world of today is solved and no reference moves until a record for H2S or CO2 is added; the sour water batch states those.

**Without free water** every ion of a volatile returns to its compound (the law at `W` → 0): a vessel boiled dry gives its hydrogen chloride or its ammonia back as a gas. Where an `equilibrium` record names both, the two together stay as the salt, up to its constant (7.5).

What it returns at 25 °C, all ammonia dissolved:

| In the water, mol/kg | pH |
|---|---|
| Hydrogen chloride 0.00274 (100 ppm) | 2.56 |
| with ammonia 0.00137 | 2.86 |
| with ammonia 0.00274 | 5.90 |
| with ammonia 0.00548 | 9.24 |
| with caustic 0.00274, no ammonia | 7.00 |

How it is booked:

1. `Reactions` flags the holdups to solve (a bit per island and record). The island's command carries, per flagged vessel, the ions it held at the last commit.
2. `PassiveIntervalSolver.solve` runs the solve once, at the interval's start, where the crystals' conversions run: the compounds' amounts change and the vessel is restated at its temperature and pressure (one TP flash, as `flushTraces` restates a node). The moles and the energy difference are a `BoundaryTransfer` of a new kind, `SOLUTE`.
3. The tag pass reads the interval's `SOLUTE` transfers and books the same moles on the ion tags. Chlorine and nitrogen close exactly across compounds and tags (`TagLedgerTest`).

- Within an interval the split is frozen; what arrives is split at the next start. Until then the flash decides where an arriving gas sits, and what leaves a boiling vessel comes from the molecular part it held at the start. Both ask for a pair that keeps a fair molecular share in the water (8.3).
- A unit on accounts solves the same law on its accounts, with no solver part: a column on the free water of its top product, to which it gives all the hydrogen chloride of its feed (8.3); later an absorber and a regenerator on each stage.
- For an amine the bound gas is 7 to 10 % of the solution's mass, too much for a tag without mass: a record's `ion` may then name a compound. That is the amine batch's decision (review 4.5).
- An island that receives the compound and does not list it grows its list, as it does for a column outlet (`IslandSets`).
- A junction, a pipe and a heat side exchange nothing with a compound. The ion tag is the compound's **account** there (D15): the hydrolysis in a heater posts its product to `acid_chloride`, and a reaction that uses a compound at a passage posts the amount with a minus sign (7.5). Step 1's totals take the accounts with their sign, so the next holdup settles them: the compound is set to the total less the ions.
- An account may be below zero only as such a debt. A vessel that receives a debt larger than the compound it holds sets the compound to 0 and keeps the rest of the debt until the compound arrives. A page shows a negative account as 0.

This is the one place where the interval changes: one solve at its start for the flagged vessels, one enum constant in `ConservativeTransport`, and `SOLUTE` among the kinds a certificate replays as a rate (`IslandCertificate.rate`). The step's Newton, its booking and the kernel do not change.

### 7.5 Equilibrium with a solid: ammonium chloride

Owner, revision 6: the salt's formation is a reaction and belongs to this module. `NH3 + HCl ⇌ NH4Cl(s)` is a `reactions` record with the law `equilibrium`. Expected behaviour, numbers and sources: review 4.7.

| Field | Value |
|---|---|
| `uses` | `Ammonia` (account `ammonium`) and `HydrogenChloride` (account `acid_chloride`), exponents 1 and 1. Activity: the partial pressure in the gas |
| `makes` | The salt: 53.49 g/mol, 1 530 kg/m³. On a wall it is the wall's `saltFouling` (kg); in a vessel it is the two ion tags held without water |
| `constant` | `ln K = 34.29 − 21 193 K/T`, bar² (from the formation data, recalled; within 8 K of the published chart fit) |
| `window` | Forward only without free water; with free water the salt is ions (7.4) |
| `sites` | Heat side passages, vessels |
| `enthalpy` | `none`: 176 kJ/mol on parts per million, 0.5 kW against about 20 MW of cooling in the hydrotreater example |

**At a heat side passage**, in the tag pass, on what passed in the interval:

1. `Reactions` gives the side's coefficient at the cadence: `K` at the wall's colder end, the pressure, and whether that end holds free water.
2. Dry. Each gas's moles that passed are the compound's (`PipeTransfer`) plus its account's, with its sign: `p = P · n / n_gas`. The pressure that reacts is `δ = ½ [(p_a + p_b) − √((p_a − p_b)² + 4K)]` where it is positive, and the moles `ξ = (δ/P) · n_gas`.
3. The wall's `saltFouling` rises by `ξ` moles of salt and each account falls by `ξ` (D15). Because step 2 reads compound plus account, a colder side further on does not use the same gas twice.
4. Wet. The wall's salt dissolves, up to the water that passed times the solubility (0.3 kg per kg): the wall's mass falls and each account rises by the same moles, on the water.

**In a vessel**, at the interval's start, inside the solve of 7.4 (its totals already take the accounts):

- With free water: `ionisation` alone. The salt cannot exist.
- Without: the paired part of the two accounts is the salt, held as residue; an unpaired rest returns to its gas. The residue then meets the gas: above `K(T)` gas joins it, below `K(T)` it gives gas back, by the same formula, bounded by what is there. The compounds move by `SOLUTE`, as in 7.4.
- A vessel's residue acts on nothing in it. It is ions again when water returns.

**What the salt does.** A wall's salt resistance is `g · mass / (density · deposit conductivity · area)`, `g` = 10 (the coking law runs 13 times a plant's). It joins the wall's `fouling` in the level the compile reads: each film `h/(1 + h Rf)` and the narrowed passage, which exist. Under it, a wall whose gas holds water vapour corrodes (M13: 2.5 mm/y on carbon steel, recalled order, with M12's material factors).

| Stream | Salt forms below | Unwashed |
|---|---|---|
| Crude overhead, 1.5 bar, HCl 50 ppm, ammonia 100 ppm | 130 °C, above the water dew point | The overhead exchanger fouls before water condenses |
| Hydrotreater effluent, 60 bar, HCl 2 ppm, ammonia 0.2 % | 193 °C | 0.54 kg/h on 200 m²: `FOULED` in 29 process hours |
| Any of them with free water at the wall's colder end | Never | The salt goes to the sour water as ions |

Conservation: nitrogen and chlorine close exactly over the compounds, the accounts and the walls' salt (`TagLedgerTest`). Nothing changes in the step's Newton, its booking, the kernel or the certificates.

Later salts are records of the same law: the bisulfide (with H2S's `ionisation` record), amine hydrochlorides, the carbamate by its exponents (review 4.7, the method's reach).

## 8. The transformations of this batch

### 8.1 Desalting (`transfer`, in every vessel that holds oil and free water)

Salt on the oil moves to the water at the rate `k = E · v_s / H`:

- `v_s = g d² (ρ_w − ρ_o) / (18 μ_o (1 + 0.15 Re^0.687))`, `Re = ρ_o v_s d / μ_o`: Stokes with Schiller and Naumann's drag correction, by five fixed-point passes, with the oil's viscosity and both densities from the vessel's state. A 1.3 mm drop in hot crude settles at `Re` near 17, where plain Stokes gives twice the speed (review 7.3).
- `d`: 1.3 mm in a desalter with its field on, 0.1 mm in any other vessel and in a desalter without power.
- `H`: the oil layer's height.
- `E = 1 − exp(−W / 0.02)`: the share of the brine that met the wash water and coalesced, `W` the water to oil mass ratio of what entered over the last slice (of the holdup when nothing entered). The rest stays in drops of about 10 µm, which do not settle.
- `hydrolysable_chloride` moves at half the rate (a single stage removes about 90 % of sodium chloride and 40 to 50 % of the others: OGJ, review section 2).

In a through-flow vessel this gives a removal of `E · N/(1 + N)`, `N = v_s A / Q` (`A` the plan area, `Q` the oil's volume flow). A desalter of 54 m² at 0.184 m³/s:

| Crude, temperature | Oil viscosity (typical) | `N` | Water settled | Salt removed at 5 % wash |
|---|---|---|---|---|
| Medium, 60 °C | 8 mPa s | 3.9 | 79 % | 73 % |
| Medium, 90 °C | 4 mPa s | 6.3 | 86 % | 79 % |
| Medium, 120 °C | 2 mPa s | 9.1 | 90 % | 83 % |
| Medium, 150 °C | 1.2 mPa s | 11.5 | 92 % | 84 % |
| Medium, 120 °C, no field | 2 mPa s | 0.11 | 10 % | 9 % |
| Heavy, 120 °C | 12 mPa s | 1.3 | 56 % | 52 % |
| Heavy, 150 °C | 6 mPa s | 2.2 | 69 % | 63 % |
| Heavy, 150 °C, half the flow | 6 mPa s | 4.4 | 81 % | 75 % |

Wash water: 1 % gives `E` = 0.39, 3 % 0.78, 5 % 0.92, 8 % 0.98. Two stages in series reach 97 %.

Temperature acts through the viscosity, as the owner asked. Its upper limit needs no rule: where the water boils the vessel holds no free water and nothing transfers. A storage tank settles its brine too, in days (`k` = 5e-6 1/s at 25 °C, a time constant of 54 process hours).

Not modelled: the mix valve's pressure drop, demulsifier, water carried over in the oil (the phase draw takes clean oil).

### 8.2 Chloride hydrolysis (`first_order`, heat sides, vessels, a column's bottom)

`hydrolysable_chloride` → `acid_chloride`, mole for mole of chlorine. `k = 180 s⁻¹ · exp(−5190 K / T)`, window 393 K and above, oil present. A fit to two anchors for magnesium chloride: 90 % in 60 s at 340 °C, 5 % in 60 s at 150 °C. Calcium chloride gives up 3 % of its chlorine in laboratory steam and under 15 % in a plant, sodium chloride below 1 %: only 12 % of the calcium chloride's chlorine is in the tag, the rest is `salt`. With a salt of 75 % sodium, 15 % magnesium and 10 % calcium chloride, an assay's default hydrolysable share is one sixth. Naphthenic acid raises the share of calcium and sodium (a patent, review 7.3): not built.

| Temperature | `k` (1/s) | In 8 s | In 60 s | In 300 s |
|---|---|---|---|---|
| 120 °C | 3.3e-4 | 0.3 % | 2 % | 10 % |
| 200 °C | 3.1e-3 | 2 % | 17 % | 61 % |
| 270 °C | 1.3e-2 | 10 % | 54 % | 98 % |
| 340 °C | 3.8e-2 | 26 % | 90 % | 100 % |
| 370 °C | 5.6e-2 | 36 % | 97 % | 100 % |

The product is the tag `acid_chloride`, on the stream's water (steam or liquid) if it has any, else on its lightest cut; the next holdup splits it by the law of 7.4 (a dry one makes all of it the compound). The water the hydrolysis uses is not booked (below 1e-5 of the stream).

`caustic` (`transfer`, any site with oil): `hydrolysable_chloride` + `caustic` → `salt`, at once where both are present, 1.13 kg NaOH per kg Cl. This is the refinery's caustic injection after the desalter. In water, `acid_chloride` + `caustic` → `salt` in the same way, so the charge balance of 7.4 only ever sees the excess of one of them.

### 8.3 Hydrogen chloride: the compound and the acid

The path in a crude unit: the furnace makes the tag; the column's account turns it into the compound and gives it to the top product; it is a gas component in the overhead line and through the condenser; the reflux drum, which holds free water, takes it into `acid_chloride` on its water; the water outlet carries the acid away. A vessel that boils its water off gives the compound back.

What the compound needs of the data and the thermo model:

| Item | Requirement |
|---|---|
| Dataset | `chemsep_hydrogen_chloride` through `tools/compound-import` (a `selection-hcl.json`), from ChemSep record 1904, which is complete: Tc 324.69 K, Pc 8.31 MPa, acentric factor 0.126, heat capacity 20 to 1 600 K, both viscosities, heat of formation, entropy, triple point 158.97 K (`freezing`, no crystal record). PR78 on it: vapour pressure within 7.5 %, heat of vaporization within 4.3 % (review 4.2) |
| Formula | Chlorine joins `MaterialCatalog.FORMULA_ELEMENTS` and `Thermochemistry.ELEMENTS`. The heating value is 0 (it burns as an inert) |
| Hazard | A `hazard` sub-record, so a vent, a flare or a leak of it reaches the air through the pollution system as it stands |
| Pairs with hydrocarbons | The rule of the refinery compounds (DWSIM `kij_pr.dat`, else the DECHEMA value, else 0) gives 0 for every pair: none of the three files holds one. A fair value: its solubility in paraffins is near ideal (review 4.2) |
| Pair with water | For the molecular part only; the law holds the acid's own chemistry. A `henry_in_water` record of 0.1 atm at 25 °C and 10 atm at 100 °C, from which `DerivedPairs` fits its pair (`k_ij` about −0.68 and −0.54 on the stand-in). Sized, not measured: large enough that the water holds 97 % of a gas arriving within an interval and that a boiling vessel has molecular acid to give off (9 to 14 % of the acid at the azeotrope), small enough that the azeotrope stays the law's (review 4.6). At `k_ij` = 0 a flowing drum loses the gas between two solves; at −1.0 the azeotrope moves to 38 % |
| The law | The `ionisation` record of 7.4 |
| Flash | As any compound. At parts per million it needs no special case: the trace cutoff folds trace phases, not trace compounds, and the flush acts below 1e-24 |
| Column | Not on V3's axis and in no neural seed: the running column and the simulator's column carry it on their accounts and give it whole to the top product, as inert particles go whole to the bottoms |
| Identity | A compound added to a package moves no identity of a view without it: no world is restated |
| Kernel, engine | No change: no electrolyte model, no new phase |

What the law and the pair give together (review 4.6, on the stand-in):

| Case | Model | Real |
|---|---|---|
| Refinery water, 100 ppm: share as ions | 100 % | 100 % |
| Boiling 1 mol/kg acid at 760 mmHg: it levels at | 20.6 % by mass | 20.2 % |
| The same at 50 mmHg | 27.8 % | 23.4 % |
| Hydrogen chloride lost when 80 % of the water is gone, 760 and 50 mmHg | 9 %, 2 % | Small |
| Boiling temperature at the azeotrope, 760 mmHg | 371.5 K | 381.7 K |

Heat and vacuum do not strip dilute acid water, in the model as in a plant; acid stronger than the azeotrope gives hydrogen chloride off; a vessel boiled dry releases all of it. The boiling temperature is low because the ions, a tag, do not raise the water's boiling point (the owner's rule for tags).

### 8.4 Surface coking (`threshold_deposit`, a heat side that carries oil)

It replaces the Kern–Seaton placeholder of the fouling-corrosion batch for oil sides (owner decision O1 there: "until the reaction module"). Water sides keep Kern–Seaton.

`dRf/dt = p · [0.0575 · Re^(−0.66) · Pr^(−0.33) · exp(−4811 K / T_f) − 1.59e-10 · τ_w]`, in m²K/W per process second (`τ_w` in Pa), not below 0.

- The form is Panchal's 1999 reading of Ebert and Panchal's threshold model: deposition falls with the Reynolds number, removal rises with the wall shear. The side's law already has `Re`, `Pr` and the friction factor (`τ_w = f ρ v² / 2`), so cold, viscous crude fouls more, as it does.
- 40 kJ/mol: refinery preheat trains refit to 21 to 47 kJ/mol. The 48 and 68 kJ/mol of the original papers are fits to coker feed at 360 to 420 °C (review 7.3; revision 3 used 48 with speed alone, and gave no fouling at 230 °C and 2 m/s, where exchangers do foul).
- The two constants are fitted to a threshold of 3 m/s at 230 °C on the literature's benchmark oil (`Re` 17 100, `Pr` 10, 7.2 Pa at 2 m/s) and to the game's fouling time, 0.0009 m²K/W in 10 process hours at 350 °C and 1 m/s. At the benchmark the law gives 1.9e-9 m²K/J against a measured 0.3e-10 to 3e-10: the game fouls about 13 times faster than a plant, by choice.
- `T_f`: the film temperature, `T_bulk + 0.55 (T_wall − T_bulk)`, `T_wall` from the side's duty, area and film. `p = clamp(asphaltene share of the oil / 0.03, 0.2, 3)`.
- Window: `T_f` ≥ 473 K.

On the benchmark oil:

| Speed | Deposit starts at `T_f` | Hours to 0.0009 at 250 °C | At 350 °C | At 400 °C |
|---|---|---|---|---|
| 0.5 m/s | below the window | 23 | 5.2 | 2.9 |
| 1 m/s | below the window | 38 | 8.3 | 4.7 |
| 2 m/s | below the window | 77 | 14 | 7.6 |
| 3 m/s | 230 °C | 243 | 20 | 10.5 |
| 4 m/s | 269 °C | none | 30 | 14 |
| 5 m/s | 304 °C | none | 52 | 19 |

Mass: the formation term × deposit conductivity (1 W/mK) × density (1 200 kg/m³) × area is the coke formed. The net part leaves the oil's `asphaltenes` and becomes the side's deposit (the `Rf` fact that exists). The part the flow removes becomes `coke_fines` on the same carriers.

### 8.5 Bulk coking (`first_order`, vessels and a column's bottom)

Two records, both with `k = 3e-5 s⁻¹ · exp(−25 258 K · (1/T − 1/673.15 K))` (210 kJ/mol, the usual value for residue cracking), window 613 K and above:

- `resins` crack at `2k` (to the oil: the tag only falls).
- `asphaltenes` above the solubility limit `0.4 × resins` → `coke_fines` at `k`; in a column's bottom 60 % `coke_fines` and 40 % deposit. Below the limit nothing cokes. The `first_order` law gains an optional `limit` (a factor on another tag) for this.

This is Wiehe's phase-separation picture on the two tags the cuts carry: the resins keep the asphaltenes in solution, heat destroys the resins, and coke appears after an induction period (review 7.3). The limit 0.4 and the factor 2 are fits to an induction of tens of minutes at 400 °C (recalled). They stay as game constants (owner, O5).

| Temperature | Induction, asphaltenes to resins 1 to 3 | 1 to 2.6 | Asphaltenes coked per hour after it |
|---|---|---|---|
| 340 °C | 33 h | 7 h | 0.3 % |
| 370 °C | 4.9 h | 1.0 h | 1.9 % |
| 400 °C | 50 min | 11 min | 10 % |
| 430 °C | 10 min | 2 min | 42 % |

A residue with more asphaltenes than 0.4 of its resins cokes at once above 340 °C; a usual one passes a column's bottom, where it stays minutes, without coke. The 60 to 40 split of a column's bottom is a game rule. Past the induction, a residue with 10 % asphaltenes held 10 minutes at 400 °C in a vessel makes up to 1 700 mg of coke per kg: a bed of up to 17 kg per 10 t that passed.

In a column the deposit raises the tray fouling that the fouling-corrosion batch's WP8 already keeps (`trayFouling`), by the deposit's share of 2 % of the bottoms. In a vessel there is no deposit share: all its coke is fines, and the fines become a solid population there (owner, O3; section 5.5). `coke_fines` still on their way count as solid share in `ErosionLaw` and in the column's tray fouling, as the population does once it exists.

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
- **M12, acid chloride** (`Mechanism.ACID_CHLORIDE`, appended): acts where free water holds chloride acid. Its pH is the speciation law's (7.4). In a holdup the ions are already split, so the charge balance alone gives it: `h − Kw/h = (acid_chloride − ammonium − caustic) / kg of free water`, in mol, `pH = −log10 h`. At a heat side's wet end and in a column's top zone no solve has run; there the law's acid side is used: all the hydrogen chloride counts as ions, and the ammonia as ions up to the acid. That is exact below pH 7, and M12 does not act above pH 5.5.
- The acid dew point: refiners find the first acid drop above pure water's dew point (17 K in the OGJ example). Hydrogen chloride and water alone give about 0.5 K (review 4.3); salts and cold surfaces make the rest, and the cubic knows neither. A column's top zone and its condenser therefore count as wet for M12 up to 15 K above the table's `waterBelow`. A heat side of the network has only its state's wet flag: a known miss.
- A heat side reads both its ends: the hotter end for the dry mechanisms, as today, and the colder end for the wet ones. A condenser's acid attack is at its cold end, which the hotter-end digest of the fouling-corrosion batch does not see. Carbon steel: `min(25, 2.5 · 10^(−0.35 (pH − 2.25))) · 2^((min(T, 366 K) − 311 K)/55 K)` mm/y, none above pH 5.5. The slope 0.35 is what the two anchors ask for (100 mils a year at pH 2.25, 20 at pH 4.25); revision 3's 0.5 missed the second by half (review 7.3).

| pH | 38 °C | 93 °C |
|---|---|---|
| 1 | 6.9 | 13.7 |
| 2.25 | 2.5 | 5 |
| 3.6 | 0.84 | 1.7 |
| 4.5 | 0.41 | 0.82 |
| 5.5 | 0.18 | 0.36 |

Material factors: chrome steel 1, stainless steel 0.3 (it pits and cracks in chloride), nickel alloy 0.02. Nickel alloy keeps its place as the answer for an acid overhead. The anchors are recalled from the hydrochloric acid table of API RP 581 Part 2 Annex B; the search of this task did not reach the table. The owner keeps the recalled anchors (O5): they are game constants.

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

A sheet stream carries a `TagSet`; a feed states its crude by value (cut amounts and one factor per tag, D13), filled from an assay or typed, so a sheet can hold a crude that no datapack names. Units pass tags by their recoveries, heat units apply hydrolysis and surface coking at their sized passage, the desalter its law. The Materials tab's digest reads the tags and M12. A vessel unit applies the speciation law of 7.4 to its outlet streams; the column gives hydrogen chloride to its top product and solves the law on that product's free water. All on the client, as today.

## 12. Performance

| Item | Where | Bound |
|---|---|---|
| Tag transport | The island's worker, once per interval | The usual case with randomized crudes: every oil island carries tags. One factorization without a phase draw; else one per carrier and class that holds a tag (at most 38). Each is one scalar per node, against the step's Newton with a block per node and several factorizations per step. Skipped without tags |
| Coke fines to a solid | The island's worker, at an interval's start, flagged vessels only | One bit test per vessel; a flagged vessel: one subtraction per carrier and the refresh's restate, which the interval's start runs for a stock that may convert anyway |
| Transformation coefficients | Server thread, at the 5 s cadence | 256 sites a pass; a site outside its window costs one comparison |
| Hydrogen chloride | Islands that carry it | One compound more in their flash (about 1 in 30 for an overhead system) |
| Speciation | The island's worker, at an interval's start, flagged vessels only | Per vessel: one fugacity evaluation per phase (at most 3), one root in pH (60 bisection steps × 3 passes over at most a handful of records), one TP flash when an amount moved. A vessel is flagged by one bit test; an island without a record's species pays nothing |
| The salt's equilibrium (7.5) | Server thread at the cadence; the island's worker in the tag pass and in the speciation solve | A bit test per island. Per side that may react: one `exp` at the cadence, one square root and three additions per interval in the tag pass. Per flagged dry vessel with both accounts: one `exp`, one square root. The solver sees only a level step of a wall's deposit: one island recompile, as today's fouling (about one per 520 intervals unwashed, none under a continuous wash) |
| The step's Newton, its booking, the kernel, the certificates | | No change (`SOLUTE` joins the rate kinds) |
| Column parts | Once per installed table | One digest per part |
| Memory | Per stock with tags | 13 tags × 13 carriers doubles (1.4 kB) |

## 13. Formats and config

| Format | Now (with fouling-corrosion) | This batch |
|---|---|---|
| Fluid facts schema | 29 | 30: a vessel's `tags`, a generator spec's contents, the accounts' tags, a column unit's parts, the tag ledger. Absent: default contents, one wall on the bottoms |
| Fluid checkpoint state format | 52 | 53: junction tags, site coefficients |
| Wire protocol | `icheme-5` | `icheme-6`: views with contents, the desalter's page |
| Simulator sheet file | 2 (3 reserved, to be released) | The next free number: stream tags |
| Integrity facts | A wall: loss, fouling, wrecked | A wall's `saltFouling` (kg; absent: 0), inside schema 30 |
| Material data | | Kinds `solutes` and `reactions` (five laws; no kind `equilibria`); `content` sub-record; assay `content` (`factors` or `targets`); the `solids` record `icheme:coke`. In no identity. The compound `HydrogenChloride` with its dataset, its `henry_in_water` and its pairs, in `icheme:refinery` (its revision bumped; `materials-index.json`) |
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
- A model reads the feed's cut amounts and tags, never a grade (D13): a randomized crude and a blend must give their own yields. The twelve cuts share one Watson K band, so a crude's character (paraffinic, naphthenic, aromatic) can only be tags: `naphthenes` and `aromatics` per cut (`bound`; paraffins by difference; `olefins` for cracked streams: review section 4, row 4) join the tags in the reactor design, and a random crude draws the first two. A model whose constants were fitted to one feed gets a feed factor on these tags (review 7.6).

The models and what each lacks: [REACTION_MODELS_REVIEW.md](REACTION_MODELS_REVIEW.md), sections 3 and 4.

## 15. Work packages

After `2026-11-16-fouling-corrosion` is on `main`. Five stages, each leaving `main` working.

| Stage | WP | Content | Lane, hot files |
|---|---|---|---|
| A | WP1 | `science/solute`: `TagKind`, `TagSet`, kinds `solutes` and `reactions` in `MaterialCatalog`, the `content` sub-record (aliases of `corrosion`), `ContentProfile` (the pure function of 5.1, with `ContentProfileTest`: a target met, a cap reached, an almost empty cut, two crudes blended), assay `content` (`factors` or `targets`), the default profile written by `tools/crude-grades/`, the thirteen records | thermo: `MaterialCatalog` |
| A | WP2 | `TagTransport`; `Inventory.tags`; the result and commit wiring; the replay step; generator contents; facts schema 30, state format 53, the corpus image; `TagLedgerTest` | fluid laws, runtime: `FluidFacts`, `FluidCheckpointCodec`, `IslandCoordinator` |
| A | WP3 | The digest on tags (`CorrosionStandard`); references exact with default contents | thermo |
| B | WP4 | `science/reaction` (the three rate laws), `runtime/fluid/Reactions`, `TagPlan`; hydrolysis, caustic, bulk and surface coking; `FoulingLaw`'s oil rows removed | runtime: `FluidWorldAuthority` |
| B | WP4b | Speciation. `science/solute/Speciation`: a pure function (totals, `F`, free water, temperature → ions, pH), with `SpeciationTest` on the numbers of 7.4 and 8.3. The law `ionisation` and its two records. Hydrogen chloride: the compound's records, `henry_in_water` and pairs, chlorine in the formula elements, the hazard. The solve at the interval's start (`PassiveIntervalSolver.solve`, `Kind.SOLUTE`, the flags and ions in the command, island growth); the ledger across compounds and tags | thermo, fluid laws: `MaterialCatalog`, `ConservativeTransport` (one constant), `IslandCoordinator` |
| B | WP4c | Coke fines to a solid (5.5), after WP4b, at its site: the `solid` block, the record `icheme:coke`, the flag and the amounts in the command, the exchange with `SOLUTE`, the ledger across cuts, tags and solids. Tests: mass and energy exact through one exchange; a hot drum settles a bed and a filter cakes; a vessel below the flag keeps its tag. The hold-fuzz campaign gains a coking world; its counters judge the cost | fluid laws: `IslandCoordinator` |
| B | WP4d | The law `equilibrium` and the ammonium chloride record (7.5), after WP4b: accounts with a sign in `TagSet` and in step 1's totals; the passage's posting in the tag pass; the dry holdup's residue in the solve; a wall's `saltFouling` and its share of the level; M13. Tests: nitrogen and chlorine exact through a deposit, a wash and a settlement; the deposition temperatures of 7.5's table; two sides in a row do not use the gas twice; a debt larger than the compound waits. In this batch (owner, O10) | thermo, runtime: `Integrity`, `IslandCoordinator` |
| B | WP5 | M12 and its factors; a heat side's colder end for the wet mechanisms; `coke_fines` in the solid share | thermo |
| C | WP6 | The desalter: kind, design, model, registration, law, page, sound | equipment: `EquipmentShape`, `EquipmentStructure` |
| D | WP7 | Tags through the column's, still's and converter's accounts; the column's parts and their facts; the Operation table | plant: `ColumnOperation`, `ColumnFacts` |
| E | WP8 | Simulator: stream tags, a feed's crude by value (cut amounts and factors), the desalter unit, the Materials tab per part | flowsheet: `SheetSizing` |
| E | WP9 | Device pages: a Contents group (sulfur wt%, nickel and vanadium, asphaltenes, salt PTB, the water's pH), which is how the player reads a crude that has no name; releases of a tag with a `hazard`; tutorial page | client, runtime presentation |

WP2 and WP6's model can run side by side; so can WP4 and WP7 after WP2, and WP8 and WP9. Each work package gets its full brief (files, methods, numbers, test commands, pitfalls) when it starts.

Results that move: none in stage A (default contents give today's digest). Stage B moves the fouling of oil sides from Kern–Seaton to the threshold law: the review states old and new values per test.

## 16. Owner's options

- **O1, HCl: decided** (owner, 2026-11-16, revision 2): a compound. Sections 7.4 and 8.3.
- **O2, zone material: decided** (owner, 2026-11-16): one material per column, no new field. An acid overhead is answered by the desalter, by neutralising, or by a column of nickel alloy.
- **O3, coke fines: decided** (owner, 2026-11-16): a solid population in a vessel, so that beds settle and filters cake. Section 5.5, WP4c. The mass comes from each carrier cut by its own tag, not from the heaviest cut alone.
- **O4, metals: decided** (owner, 2026-11-16): two tags, `nickel` and `vanadium`.
- **O5, the numbers** of sections 8 and 9 are game-level fits to the stated anchors, as the corrosion matrix is. **Decided for the recalled constants** (owner, 2026-11-16: "Keep recalled values"): the five papers of this batch (review 7.5, rows 1 to 5) are not fetched. The hydrolysis rate, M12's anchors, bulk coking's limit and factor, Bromley's `B` and the fouling constants stay at their recalled values, each marked recalled where it is stated. No work package waits for a read.
- **O6, hydrogen chloride's pair with water: settled by O7.** With the law in place the pair serves the molecular part only and is sized, not fitted to the acid (8.3). The options A to C of revision 2 (pair 0, a pair of −1.0 with a tag limited to 1 mol/kg, the pair alone) are withdrawn. The size (0.1 atm at 25 °C, 10 atm at 100 °C) is the owner's to change: a larger constant gives a truer azeotrope at 50 mmHg (25.2 % at 1 and 30 atm) and holds less arriving gas (81 %).
- **O7, acids and bases in water: decided** (owner, 2026-11-16: "Level 1: speciation"). One speciation law at the exchange sites: sections 7.4, 8.3 and 9. Not chosen: rules per substance (revision 2), and electrolyte thermodynamics in the kernel, which stays the answer if acid gas must one day flash correctly out of rich amine in any network equipment (review 4.5).
- **O8, three numbers the survey could not source: decided** (owner, 2026-11-16: the plan's game values; review 7.3), each marked a game value in the reactor design:
  - The coker's coke factor: 1.2 to 1.7 of the Conradson carbon by drum pressure and recycle ratio. Not the textbook's fixed 1.6.
  - A sediment limit on residue hydrocracking conversion, at about 70 % (60 to 80 % in real ebullated beds, recalled), with game constants.
  - One activation energy per isomerization catalyst family.
- **O9, what a random crude varies** (the owner's note; section 5.6):
  - **Decided** (owner, 2026-11-16: "Yes, cuts stay"): the twelve shared cuts and the light ends stay. A random crude draws its amounts on them, so no identity of the package moves with a crude.
  - Assumed, for the owner to correct when the randomization is designed: contents are one factor per tag on one profile over the boiling range. A crude cannot have, say, its sulfur in the naphtha only.
  - Assumed likewise: a crude's character (paraffinic, naphthenic, aromatic) arrives with the reactor design as type tags (section 14). Until then crudes differ in boiling range and contents, not in character.
- **O10, ammonium salts on cold walls** (owner's case of the hydroprocessing wash water; review 4.7). Revision 5 had the water's end (the speciation law in the wet separator) and not the salt: nothing precipitated, a wash prevented nothing, and neutralising a crude overhead with ammonia was free.
  - **How: decided** (owner, 2026-11-16: "This is basically a reaction, needs to be handled by the reaction module"). A `reactions` record with the law `equilibrium`, which uses up its gases exactly (D15, section 7.5). The kind `salts` and the law `salt_deposit` of the first proposal, which took nothing from the stream, are withdrawn.
  - **When: decided** (owner, 2026-11-16: "Yes, this batch"): WP4d, in stage B after WP4b. The reaction module is built here, and the record already acts in a crude overhead neutralised with ammonia.
  - Either way the bisulfide's record and H2S's `ionisation` record come with the hydroprocessing and sour water design.

## 17. Risks

- The tag pass rebuilds transport from `PipeTransfer`. An interval that booked a transfer the history does not hold (a release, a melt) must be covered boundary by boundary; `TagLedgerTest` runs on the hold-fuzz worlds to find them.
- Default contents (D8) must be expanded at every place two stocks meet: a missed place loses tags silently. One helper, `TagSet.of(inventory, defaults)`, and no other read.
- Coke as a solid (5.5) puts solids into ordinary oil service. A hot vessel then holds a bed, and beds and cakes are located events of the solid driver: more of them means more searched steps and more chances of a hold in the classes that involve solids (still pipes, closures). WP4c measures it by the campaign's counters on a coking world. Whether a vessel with a growing bed keeps a steady certificate is read there too; if it does not, the flag's threshold is the lever.
- Accounts with a sign (D15, 7.5). A debt rides its tag's carrier: `acid_chloride` goes with the vapour, as the gas it stands for; `ammonium` strands on the heaviest cut's liquid, while ammonia goes with the vapour. Where liquid and gas part before a holdup, the ammonia's debt reaches a vessel that holds little ammonia and waits there. The totals stay exact; the place is wrong by parts per million of the gas. Every reader of an account must take a negative value: WP4d lists them.
- The salt's equilibrium is taken as reached at one colder end per side: no rate, no place along a tube bank, no crystals carried on.
- One material per column (O2): the player cannot clad a top. The only answers to an acid overhead are upstream (desalter, caustic, ammonia) or a whole column of nickel alloy.
- Randomized crudes (5.6): the tag pass is paid on every oil island, where this plan first counted on islands without tags. Its bound is unchanged, its use is not; the campaign's counters on a salted crude are the measure. A drawn crude outside the table's ranges, or with asphaltenes above 0.4 of its resins, meets laws at their clamps.
- A column product's wetness is read from `waterBelow` only; a tray below the dew point inside the top zone is not seen.
- The desalter's numbers rest on typical viscosities; the mod's own viscosity model for the heavy cuts decides the real table, and the drop size is then fitted once.
- M12's anchors are recalled, not read (section 9); the owner keeps them (O5), so the rates are game values, not API RP 581's.
- The exchange of 7.4 and the certificates: `IslandCertificate` reads a transfer of material at a vessel as a flush, which refuses the certificate, unless its kind is a rate a steady island repeats (`VENT`, `SPILL`, `ATMOSPHERE`: `IslandCertificate.rate`). `SOLUTE` must join those kinds, or a steady overhead drum would solve every slice; a replay then scales the exchange linearly with the span, as it scales the others. Whether `REPLAY_REVISION` moves with it is decided in WP4b.
- The speciation's limits, all stated in review 4.5 and 4.6:
  - The split is frozen within an interval. A boil-off faster than the molecular share (9 to 14 % of the acid at the azeotrope) per interval strips less than it should until the next solve; a gas arriving in a drum is held by the pair only (97 %).
  - Ions have no mass and do not change the water's properties. Right at parts per million; at the azeotrope the liquid's mass is 20 % short and it boils 10 K low. Strong acid as a bulk liquid is not this batch's aim.
  - `F` is taken as constant during a solve; the second pass of step 5 covers strong acid.
  - A `henry_in_water` makes hydrogen chloride a gas for `DerivedPairs`: its pairs with non-polar compounds then go to the E-PPR78 branch, which has no group for it. WP4b checks that the branch gives 0 and does not refuse; else the water pair is stated as a library pair with the two fitted values.
  - The pair (about −0.68 to −0.54) is more negative than any pair the solver has met. The build measures it on the 200-seed quick check with worlds that hold the compound.
  - The constants of ammonia and the fitted constants for amines and sour water are not read yet.
- Hydrogen chloride between a heater and the next holdup is a tag, so a gas composition read on that line does not show it.
- A column whose top product is led to no vessel with free water never absorbs its hydrogen chloride; it stays a gas component to wherever the gas goes. That is the physics.
