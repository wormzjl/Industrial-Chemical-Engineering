# Reaction models and their inputs: review

Batch `2026-11-16-solute-reaction-system`, 2026-11-16. Research only: no code, no run. The design that uses it: [SOLUTE_REACTION_PLAN.md](SOLUTE_REACTION_PLAN.md).

## Summary

1. **Scope.** Which kinetic model each reaction of the owner's list should use, what each model needs as input, and which of those inputs the mod lacks. The non-catalytic transformations (desalting, chloride hydrolysis, coking) are designed in the plan; the catalytic and thermal units get the model and its requirements only.
2. **Method.** The inputs the mod has were read from the code and the material data at `adb413b4d` and from the fouling-corrosion branch. Eight web searches were made (proportionate, as the owner asks for research). They confirmed the forms and the qualitative anchors and returned almost no constants. Every constant in this review is marked **read** (from a search result) or **recalled** (from the literature as I know it, not re-read here). A recalled constant is checked against its source when its work package starts.
3. **Non-catalytic.** Desalting: Stokes settling with a field-grown drop size and a wash-water contact factor. Hydrolysis: first order in the hydrolysable chloride, fitted to two anchors. Surface coking: the Ebert–Panchal threshold form. Bulk coking: first order at 210 kJ/mol. Acid chloride corrosion: a rate by pH. All five are game-level fits to anchors, like the corrosion matrix.
4. **Catalytic and thermal units.** One lumped model per unit that runs on the existing cuts and compounds: hydrotreating by two sulfur classes with H2S inhibition; hydrocracking by Stangeland's boiling-point kernel (distillates) and a five-lump scheme (residue); FCC by a five-lump riser with a coke-burn heat balance; delayed coking by Conradson-carbon yields with a first-order approach; steam cracking by molecular schemes for the light feeds and a one-step naphtha scheme; SMR and shift by equilibrium with an approach temperature; isomerization by reversible first-order steps; reforming (not named by the owner, needed for hydrogen and octane) by Smith's four reactions.
5. **Inputs lacking**, in order of how many models wait for them:
   - Hydrocarbon type per cut (paraffin, naphthene, aromatic, olefin): FCC, reforming, steam cracking, hydrocracking. All twelve cuts share one Watson K band (11.6 to 12.0), so crudes do not differ in type today.
   - Nitrogen, metals, asphaltenes, resins per cut and the salt of each assay: the tags of the plan.
   - Standard entropy (or Gibbs energy of formation) of the real compounds: every equilibrium (SMR, shift, isomerization, reforming, aromatics saturation). The `thermochemistry` sub-record holds the formation enthalpy only.
   - Ideal-gas heat capacity above 900 K: steam cracking and SMR run at 1 100 to 1 200 K, and the fits end at 900 K (held constant beyond).
   - Reaction heats of lumped reactions: the cuts' formation enthalpies are a Cragoe CHx estimate, too rough for a difference between cuts.
   - Compounds: hydrogen chloride (this batch), four C6 isomers and methylcyclopentane (isomerization), acetylene (steam cracking), elemental sulfur (a later Claus unit); a coke solid record.
   - Catalyst records and the reactor kinds' volumes (bed, riser, drum, coil).
6. **Not lacking:** every SMR, shift and hydrotreating species; the FCC light products; formation enthalpies of all 52 datasets; oil viscosity, passage geometry and film temperature for the surface laws; the unit-on-accounts pattern for reactors.
7. **Open:** the acid chloride anchors (API RP 581's table was not reached); the heat of coking. Section 5 lists them.
8. **Survey** (section 7, added on the owner's request): about 130 candidate models weighed by five criteria. The commercial and molecular models fail on closed constants and on inputs the mod lacks, so the selection is the best of the open, small tier. Six selections changed (FCC, residue hydrocracking, the coke factor, hydrolysis, wall fouling, bulk coking) and two were corrected (desalting's drag, the corrosion slope). Fifteen papers hold tables still unread (7.5).

## 1. What the models may read

| Input | Source in the code |
|---|---|
| Amounts by compound and phase, T, P | A node's committed state; a split table's products |
| Boiling point, molar mass, density of a cut | `properties` datasets |
| Formation enthalpy and formula | `thermochemistry` sub-record (cuts: estimated) |
| Oil viscosity, phase densities | `MixtureViscosity`, the state |
| Passage speed, volume, duty, film | `PipeObservation`, the heat side's geometry and law |
| Contents (sulfur and the rest) | The tags of the plan, once built |

## 2. Non-catalytic transformations

The first selection. Section 7 revises four of its rows (desalting's drag, the hydrolysable share, the fouling law's form and energy, bulk coking's induction) and the corrosion slope; the plan (revision 4) holds the current laws.

| Transformation | Model | Anchors | Status |
|---|---|---|---|
| Desalting | Stokes settling of water drops in the oil, `N = v_s A / Q`, removal `E · N/(1+N)`; drop size 1.3 mm with the field, 0.1 mm without; contact `E = 1 − exp(−W/0.02)` | One stage removes about 90 % of the salt and two stages up to 99 % (**read**). A single stage removed 90 % of NaCl, 50 % of CaCl2, 40 % of MgCl2 (**read**, OGJ). Operating range 93 to 149 °C (**read**). A model that finds the drop that just settles from Stokes' law and the residence time (**read**, İYTE). Wash water 3 to 8 %, a field-grown drop near 1 mm (**recalled**) | Drop sizes fitted to the first anchor; the oil viscosities of the plan's table are typical values, to be replaced by the mod's own |
| Chloride hydrolysis | First order, `k = 180 exp(−5190/T)` | MgCl2 starts near 120 °C, CaCl2 near 210 °C, NaCl above 500 °C (**read**). About 90 % of MgCl2 at 340 °C (**read**). Most MgCl2 hydrolyses at the flash zone's 650 °F (**read**, OGJ). About 10 % of the salt is MgCl2 and CaCl2 (**read**, OGJ; other sources give more: the plan's default is one fifth, per assay). Gray, Eaton and Le 2008 measured the kinetics in model oils (**recalled**, not found by the search) | One lump for Mg and Ca chloride; the 60 s of the anchors is an assumed laboratory time |
| Acid chloride corrosion | Rate by pH of the free water, `2.5 · 10^(−0.5 (pH − 2.25))` mm/y at 38 °C, doubling to 93 °C | Condensed HCl reaches pH 1 to 2 at the dew point (**read**). The overhead is kept 15 K above the dew point; wash water dilutes the acid (**read**). pH is the main variable (**read**). API RP 581's rates by pH: about 100 mpy at pH 2.25 and 20 mpy at pH 4.25 at 100 °F (**recalled**) | The table itself must be read |
| Surface coking | `dRf/dt = α Re^β Pr^(−0.33) exp(−E/RT_f) − γ τ_w` (Ebert and Panchal 1995; Panchal 1999 with `Re^−0.66`; Polley 2002 with the wall temperature and `Re^0.8` removal), reduced to speed alone | The four parameters are fitted per crude from plant data (**read**). The linear shear term is its known weakness (**read**). `E` = 68 kJ/mol (1995) and 48 kJ/mol (Polley) (**recalled**) | Constants fitted to the game's fouling time; the form and `E` are the literature's |
| Bulk coking | First order in asphaltenes, 210 kJ/mol | Coke forms after an induction period, when the asphaltenes pass their solubility limit (Wiehe 1993, **read**). Residue cracking 200 to 230 kJ/mol (**recalled**; the search returned 235 kJ/mol for n-hexadecane and scattered residue values) | No induction period: the 340 °C window stands in for it |

## 3. Catalytic and thermal units: model and requirements

The first selection. Section 7 surveys the alternatives and revises it: FCC gains a light cycle oil lump and other constants, residue hydrocracking is solved back-mixed, the coker's coke factor follows pressure and recycle; the rest stands with refinements.

`✓` an input the mod has (or the plan's tags give); `✗` lacking.

| Unit (kind) | Model chosen | Why this one | Inputs | Heat |
|---|---|---|---|---|
| Hydrotreating (`TRICKLE_BED_REACTOR`) | Sulfur in two classes per cut, each first order, with H2S inhibition `1/(1 + K p_H2S)²` and a hydrogen pressure term (Korsten and Hoffmann 1996; Girgis and Gates 1991). Nitrogen and metals first order. Olefins saturate fully, aromatics to an equilibrium | Two classes give the apparent order of 1.5 to 2 of real feeds without a sulfur speciation | `sulfur`, `nitrogen`, `nickel`, `vanadium` ✓; the refractory share ✗ (a rule by boiling point: dibenzothiophenes boil above about 310 °C); `olefins`, `aromatics` ✗; hydrogen pressure ✓; space velocity from the bed volume ✗; catalyst activity and metals on catalyst ✗. Products H2S, NH3 ✓ | Exothermic, by hydrogen consumed per class (stated values); major for cracked feeds |
| Hydrocracking, distillates (`TRICKLE_BED_REACTOR`) | Stangeland 1974: each cut cracks first order with a rate rising with its boiling point, into lighter cuts by one distribution function | It runs on pseudocomponents by boiling point, three shape parameters | Boiling points ✓; `nitrogen` (it poisons the acid sites) ✓; hydrogen consumed per kg converted ✗ (stated ratio: the cuts' hydrogen is fixed); catalyst ✗ | Exothermic, per hydrogen consumed |
| Hydrocracking, residue (`EBULLATED_BED_REACTOR`) | Five lumps: residue, vacuum gas oil, distillates, naphtha, gases, ten first-order paths (Sánchez, Rodríguez and Ancheyta 2005; 380 to 420 °C, **read**) | The lumps are cut groups | As above; `asphaltenes`, `nickel`, `vanadium` ✓ | As above |
| FCC (`FCC_CONVERTER`) | Five lumps: gas oil (second order), gasoline, LPG, dry gas, coke (Ancheyta-Juárez 1999, after Weekman 1968 and Lee 1989), activity falling with coke on catalyst; regenerator: coke burnt, the heat balance sets the catalyst circulation | Coke must be a lump for the heat balance; LPG apart from dry gas for the gas plant. The ten-lump Mobil model (Jacob 1976) needs ring and side-chain carbon, which no input gives | Boiling range ✓; Conradson carbon ✗ (from `asphaltenes` and `resins`); `nickel`, `vanadium`, `nitrogen`, `sulfur` ✓; aromaticity ✗; catalyst as a circulating solid ✗; riser and regenerator volumes ✗. Products: light olefins, butanes ✓, cuts with tags, SO2 in the flue gas ✓ | Cracking endothermic (stated per kg); coke burn by Hess's law (carbon to CO2 and CO); the unit is heat balanced: major |
| Delayed coking (`DELAYED_COKER`) | Yields from Conradson carbon (Gary and Handwerk: coke 1.6 × CCR wt%, gas 7.8 + 0.144 CCR, naphtha 11.29 + 0.343 CCR, gas oil the rest; **recalled**), reached by a first-order approach in the coil and drum; the drum fills with a coke bed | Robust, one input | Conradson carbon ✗ (as FCC); `sulfur`, `nickel`, `vanadium` to the coke ✓; a coke solid record ✗; drum volume ✗ | Endothermic, major: fitted so that a 495 °C furnace outlet gives a 440 °C drum overhead (heater outlet 900 to 950 °F, drum 780 to 840 °F: **read**) |
| Steam cracking (a fired coil) | Ethane, propane, butane: the molecular schemes of Sundaram and Froment 1977 (5 and 10 reactions). Naphtha: Kumar and Kunzru 1985, one first-order primary step to a fixed product slate and 21 secondary reactions | Real compounds, small schemes, heats by Hess | Every product but acetylene ✓; the naphtha's paraffin, naphthene, aromatic split ✗; steam ratio ✓; coil temperature and residence time (0.1 to 0.5 s) from the coil ✓; heat capacity above 900 K ✗ | Endothermic, major, by Hess's law |
| SMR and shift (a catalyst-tube furnace ✗, fixed beds) | Equilibrium of reforming and shift with an approach temperature. Xu and Froment 1989 only if tube profiles are wanted | Reformer outlets stand within 5 to 15 K of equilibrium; two equations instead of a tube integration | CH4, H2O, CO, CO2, H2 ✓; entropy ✗; heat capacity above 900 K ✗; steam to carbon ratio ✓ (coke guard); sulfur in the feed ✓ (poison guard) | Endothermic, major, by Hess's law |
| Isomerization (fixed bed) | Reversible first-order steps among the C5 and C6 isomers, benzene saturation; three catalyst families with their own temperature windows (Chekantsev, Gyngazova and Ivanchina 2014, **read** as the reference model) | Equilibrium-limited: the constants must come from Gibbs data | n-pentane, isopentane, n-hexane, cyclohexane, benzene ✓; 2- and 3-methylpentane, 2,2- and 2,3-dimethylbutane, methylcyclopentane ✗; entropy ✗; the light naphtha is the cut `crude_pc01`, not real compounds ✗ | Mildly exothermic; benzene saturation major where benzene is present |
| Reforming (fixed or moving bed) | Smith 1959: naphthenes to aromatics, paraffins to naphthenes, two hydrocracking paths, with his equilibrium expressions | The smallest model that makes hydrogen and aromatics | Paraffin, naphthene, aromatic split ✗; hydrogen ✓ | Endothermic, major |

Later, with the same frame: amine treating and caustic washing (a `dissolved` tag on water and a `transfer` law), Claus, alkylation, gasification (the gasifier kinds exist as drawings).

## 4. Inputs lacking

| # | Input | Needed by | Have | Proposal |
|---|---|---|---|---|
| 1 | Nitrogen, nickel, vanadium, asphaltenes, resins per cut | Hydrotreating, hydrocracking, FCC, coking | Sulfur and acid number per cut (fouling-corrosion) | Tags with a default profile over the cuts and one factor per tag for each crude (plan 5.1; a random crude stores its factors, plan 5.6) |
| 2 | Salt and its hydrolysable share per assay | Desalting, hydrolysis | Nothing | Assay `content` block |
| 3 | Refractory share of the sulfur | Hydrotreating | Nothing | A rule by the cut's boiling point; no tag |
| 4 | Paraffin, naphthene, aromatic and olefin shares per cut | FCC, reforming, steam cracking, hydrotreating (hydrogen use) | One Watson K band for every cut | Three more `bound` tags with defaults from the cut's density, molar mass and boiling point (Riazi–Daubert); a cracked or treated stream then carries its own |
| 5 | Conradson carbon | FCC, coking | Nothing | Derived from `asphaltenes` and `resins`; no tag |
| 6 | Standard entropy of real compounds | SMR, shift, isomerization, reforming, aromatics saturation | Formation enthalpy only | One field on the `thermochemistry` sub-record, from the ChemSep records the import tool already reads |
| 7 | Ideal-gas heat capacity above 900 K | Steam cracking, SMR, the FCC regenerator | Fits to 900 K, held beyond | Refit the light compounds to 1 500 K (a data change with its revisions; it moves identities, so its own batch) |
| 8 | Heats of lumped reactions | Hydrocracking, FCC, coking | Estimated formation enthalpies of the cuts | A stated heat per kg converted on each reaction record; Hess's law only between real compounds |
| 9 | Hydrogen content that changes | Hydrotreating, hydrocracking | A fixed CHx per cut | Hydrogen consumed by a stated ratio; mass exact, the element balance approximate |
| 10 | Compounds | Hydrolysis; isomerization, steam cracking, Claus | 51 compounds | Hydrogen chloride in this batch (owner, 2026-11-16; plan 8.3), with chlorine as a formula element. Four C6 isomers, methylcyclopentane, acetylene, sulfur, with the unit that needs them (`tools/compound-import`) |
| 11 | A coke solid and its fuel record | Coking, FCC | `demo_particle`; fuel `coal_coke` | One `solids` record |
| 12 | Catalysts | Every catalytic unit | Nothing | A material kind later: activity, decay, poisons, life |
| 13 | Reactor volumes | Residence time of units without holdup | Drawn shapes of the reactor kinds | Bed, riser, drum and coil volumes as design numbers of each kind |
| 14 | Real compounds for the light naphtha | Isomerization | The cut `crude_pc01` (309 to 357 K) | To decide with that unit: split the cut into C5 and C6 compounds at the unit, or treat isomerization as a change of a tag |

| 15 | Data range in pressure | Hydrotreating, hydrocracking (50 to 200 bar) | The `fluid_domain` of the cuts and most compounds ends at 20 bar; hydrogen's and methane's at 300 bar | The kernel continues with a notice. Qualify the cuts to 200 bar: evidence, not a model change |
| 16 | Hydrogen dissolved in the oil | A trickle-bed law written on the liquid's hydrogen | Every hydrogen pair is zero by rule (owner, 2026-11-04), so the dissolved amount is not a measured one | Write the rate laws on the gas's hydrogen partial pressure |

### 4.1 What the background thermo model must do

Read at `adb413b4d`: `EnergyReference`, the `properties` datasets, `thermo-kernel.md`, `fluid-thermo-port.md`.

- **This batch: one compound, no model change.** Tags and their transformations read temperature, pressure, phase amounts, densities and the oil's viscosity. They change no property and are in no identity.
- **Hydrogen chloride as a compound** (owner, 2026-11-16; plan 8.3) needs a dataset and pairs like any refinery compound, and chlorine as a formula element. It needs no electrolyte model: its pair with water is a stated `k_ij` = 0 and it carries no `henry_in_water`, because the acid in water is the tag `acid_chloride`, made from the compound in every holdup that has free water. `DerivedPairs` would otherwise fit a pair to the Henry constant and the cubic would hold the acid a second time. A compound at parts per million flashes as any other. It stays off the column's V3 axis.
- **The kernel and the equilibrium engine stay as they are for the reactor units too.** A reactor's products are existing compounds and cuts, flashed as any stream. There is no reactive flash: the unit solves its kinetics or its chemical equilibrium on its own, in the ideal gas, and hands amounts to the engine.
- **Energy datum.** The network's datum is sensible, zero at 298.15 K (`EnergyReference.sensible`, `formationDataQualified` false), so a stream's enthalpy holds no heat of formation. A reaction's heat is therefore booked by the unit as its own term (row 8, and the plan's section 14). The other form, formation offsets on the datum (`EnergyReference` carries per-compound offsets and a rebase), would restate every world and rest on the cuts' estimated values: not proposed.
- **Heat capacity range** (row 7): every dataset's fit, the cubic water's too, ends at 900 K and is held beyond. Hydrotreating, hydrocracking, coking and FCC risers (to about 830 K) are inside. The FCC regenerator (about 1 000 K), steam cracking and SMR (1 100 to 1 200 K) are outside. A refit moves the fluid identity, so it is its own data batch.
- **Entropy** (row 6): a new field on a sub-record that is in no identity, so it restates nothing.
- **Pressure** (row 15) and **hydrogen in oil** (row 16), above.
- Not checked: the steam virial (IF97 Region 2's `B`) above Region 2's 1 073 K, at the reformer's 20 to 30 bar.

### 4.2 Hydrogen chloride: is the data enough, and how accurate is it

Read from the local DWSIM 10.2.5 extract (`chemsep1.xml` record 1904, `kij_pr.dat`, `pr_ip.dat`, `pr.ipd`) and checked with a scratch script, `tmp/hcl-check/pr78.mjs` (PR78 with the record's constants against the record's own correlations; no Gradle run).

**The pure compound: enough.** The record holds everything the import tool reads: Tc 324.69 K, Pc 8.31 MPa, acentric factor 0.126, critical volume, molar mass, triple point 158.97 K, ideal-gas heat capacity from 20 to 1 600 K, liquid viscosity (159 to 318 K) and vapour viscosity (200 to 1 000 K), heat of formation −92.31 kJ/mol, entropy 186.8 J/mol K.

| T (K) | Vapour pressure, ChemSep (kPa) | PR78 (kPa) | Deviation | Liquid density deviation | Heat of vaporization deviation |
|---|---|---|---|---|---|
| 160 | 14.7 | 14.7 | 0.0 % | −1.9 % | −0.2 % |
| 188.15 | 103.6 | 100.8 | −2.7 % | −1.4 % | +1.3 % |
| 220 | 451.6 | 466.6 | +3.3 % | −1.5 % | +2.1 % |
| 260 | 1 702 | 1 829 | +7.5 % | −3.2 % | +0.8 % |
| 273.15 | 2 478 | 2 625 | +5.9 % | −4.5 % | −0.3 % |
| 298.15 | 4 903 | 4 800 | −2.1 % | −8.7 % | −4.3 % |

- PR78 is within 7.5 % in vapour pressure and 4.3 % in heat of vaporization. The liquid density error grows toward the critical point, as it does for every compound of the cubic; the import's volume anchor at Tr = 0.7 corrects it there.
- The ChemSep vapour pressure correlation is itself not a clean reference: it gives 103.6 kPa at its own normal boiling point and 8.83 MPa at 320 K, above the critical pressure. Above about 300 K the cubic is the more consistent of the two.
- This matters little: in every place the design puts it, hydrogen chloride is a dilute gas near or above its critical temperature (324.7 K), never a liquid.

**The pairs: no data.** None of the three interaction files holds a pair with hydrogen chloride, and E-PPR78 has no group for it. Every pair is 0. Its ideal solubility in a liquid at 25 °C and 1 atm is a mole fraction of 0.021 (pressure over vapour pressure); measured values in paraffins are of that order (recalled), so 0 is a fair pair. The error falls on how a dry drum splits it between naphtha and gas, perhaps a factor of 1.5, on parts per million.

**The acid in water: accurate where it is simple.**

- "All of it goes into free water" is accurate. Hydrogen chloride over dilute acid follows `p = (m γ)² / K`, `K` about 2e6 mol² kg⁻² atm⁻¹ at 25 °C (recalled: Clegg and Brimblecombe) and about 5e3 at 100 °C. At pH 2.6 that is 4e-12 atm at 25 °C and 2e-9 atm at 100 °C, against 1e-5 atm of hydrogen chloride in the gas.
- The pH takes the activity coefficient as 1: an error of 0.1 pH at pH 1, less above.

**Not accurate, or lacking:**

| # | Gap | Effect | Proposal |
|---|---|---|---|
| 1 | The acid dew point. The cubic condenses water at pure water's dew point. With hydrogen chloride the first acid drop forms higher: 220 °F against 190 °F in the OGJ example (**read**), 17 K. Hydrogen chloride and water alone give about 0.5 K (section 4.3): salts and cold surfaces make the rest | The model sees no acid attack in that band | The column's top zone and its condenser count as wet for M12 up to 15 K above the table's `waterBelow` (the margin refiners keep: **read**). A heat side of the network keeps its state's wet flag: a known miss |
| 2 | Neutralisation by ammonia. The cubic dissolves ammonia by a Henry pair; in acid it reacts and is taken up whole | Reading only the dissolved ammonia would understate neutralisation; reading all of it without removing it would let the same ammonia act in every vessel | In a wet holdup, ammonia up to the acid chloride present becomes, with it, a tag `ammonium_chloride` on the water (plan 7.4): the same exchange as the absorption |
| 3 | The hydrolysis rate: two anchors and an assumed 60 s; magnesium and calcium chloride as one lump | A factor of about 2 in the hydrogen chloride made | Accept as a game-level fit; refit if Gray, Eaton and Le 2008 is read |
| 4 | Salt per assay and its hydrolysable share: the mod holds none; sources give 10 % (OGJ) to 25 % for the share | Scales the whole chain | Authored values per assay, marked estimated |
| 5 | The corrosion rate by pH: recalled anchors, and field scatter of about a factor of 3 | The life of an overhead wall | Read API RP 581's table before WP5 |
| 6 | Ammonium chloride that deposits as a salt in dry overhead exchangers | Not modelled | Later, with the tag of row 2 |

### 4.3 Hydrogen chloride with water, without an electrolyte model

Owner's question, 2026-11-16: does the mixture with water behave as intended? Computed with `tmp/hcl-check/pr78-water.mjs` (PR78, classical mixing, standard alpha: a stand-in for the kernel, good for orders of magnitude).

**Expected.** In liquid water hydrogen chloride ionises completely. Its pressure over the acid is `(m γ)² / K`: an apparent Henry constant (pressure over mole fraction) that is not constant, but is tiny everywhere.

| Acid, 25 °C | Pressure of HCl, atm | Apparent Henry constant, atm |
|---|---|---|
| 0.003 mol/kg (pH 2.5, 100 ppm) | 4.0e-12 | 7.4e-8 |
| 0.1 mol/kg | 3.2e-9 | 1.8e-6 |
| 1 mol/kg (3.5 % by mass) | 3.3e-7 | 1.9e-5 |
| 6 mol/kg (18 %) | 1.9e-4 (Perry: 1.9e-4, **recalled**) | 1.9e-3 |

Any drum with free water takes all of it. Dissolving releases 75 kJ/mol. The water's own vapour pressure falls a little.

**What the cubic does at `k_ij` = 0** (revision 2 of the plan). It treats hydrogen chloride as a gas that barely dissolves: Henry constant 3 900 atm at 25 °C, 3 100 atm at 100 °C, against 33 to 78 atm in n-heptane. That is wrong by 6 to 10 orders of magnitude. A reflux drum at 40 °C and 1.5 bar (per 100 mol: water 45, naphtha 50, gas 5) gets 0.27 % of the hydrogen chloride in its water, 26 % in the naphtha and 73 % in the gas.

The plan's absorption (7.4) takes what a wet vessel holds at each interval's start. An interval is 5 s online, 20 s of process time at the default factor. Within it the flash rules: what arrives goes mostly to the gas, and the gas leaves the drum in less than an interval. So a flowing drum would pass most of its hydrogen chloride to the off-gas. As written, the plan does not behave as intended.

**A fitted classical pair.** A negative `k_ij` gives the cubic the attraction it lacks, with no electrolyte term.

| Fit target (Henry constant in water at 25 and 100 °C) | `k_ij` at 25 °C, 100 °C | Share in the drum's water |
|---|---|---|
| None | 0, 0 | 0.27 % |
| Flat 1e-2 atm | −0.83, −1.20 | 99.9 % |
| The acid at 1 mol/kg: 1.85e-5 and 7.4e-3 atm | −1.230, −1.228 | 99.999 % |
| The acid at 0.003 mol/kg | −1.58, −1.75 | 100 % |

One constant, `k_ij` = −1.23, reproduces the acid at 1 mol/kg at both temperatures, and with it the heat of solution (the slope between them). Away from that point, at 25 °C:

| Mole fraction of HCl | Cubic with −1.23, atm | Real, atm |
|---|---|---|
| 1e-4 (0.006 mol/kg) | 1.9e-9 | 1.3e-11 |
| 0.0177 (1 mol/kg) | 6.5e-7 | 3.3e-7 |
| 0.26 (42 % by mass) | 1.6e-2 | 1 |

- Too volatile when dilute (the cubic is linear where the acid is quadratic), by a factor of 250 at 0.003 mol/kg, with no effect: below 0.1 mol/kg the pressure stays under 1e-7 atm.
- Too absorbing when concentrated, by a factor of 60 at 42 %. Only a player who feeds hydrogen chloride on purpose reaches that.
- The liquid root exists over the whole range, and the water's fugacity falls smoothly (2.65e-2 to 6.2e-3 atm at 42 %), as the real acid's does.

**The acid dew point, corrected.** With the acid's own law, 50 ppm of hydrogen chloride on the water raises the dew point by about 0.5 K (first drop 0.5 mol/kg, water activity 0.98); the fitted pair gives the same order. The 17 K of the OGJ example (row 1 of 4.2) is therefore not hydrogen chloride and water alone: salts and cold surfaces make it. The 15 K margin stays as the operating rule it is, and the plan's "colder end of a heat side" covers cold surfaces.

**Not checked.** A pair of −1.23 is far from any pair the solver has met. Its effect on flash robustness (the wet-oil false root, wet junctions) is a measurement of the build: the 200-seed quick check on worlds that hold the compound.

**Proposal** (plan option O6): a fitted pair, with the absorption to the tag kept as the owner described it, and capped at 1 mol/kg of water. Section 4.4 changes the fit's target from the acid at 1 mol/kg to the azeotrope.

### 4.4 Driving hydrogen chloride out of water with heat and vacuum

Owner's question, 2026-11-16. Computed with `tmp/hcl-check/pr78-strip.mjs` (same stand-in).

**Expected.** It does not leave dilute acid. Over 1 mol/kg acid at its boiling point the vapour holds 0.9 % of the liquid's share of hydrogen chloride at 1 atm and 0.1 % at 50 mmHg; at 100 ppm, 0.003 % and less. The water boils off first and the acid gets stronger, up to the azeotrope: 20.2 % by mass at 760 mmHg (381.7 K), 23.4 % at 50 mmHg (321.9 K) (Bonner and Wallace, **recalled**). From there acid and water leave together. Vacuum works against the aim: the acid's pressure falls faster on cooling than water's (75 against 44 kJ/mol). Hydrogen chloride comes off as a gas only from acid stronger than the azeotrope, from a vessel boiled dry, or with an azeotrope breaker (sulfuric acid, calcium chloride), which the mod does not have.

**What one constant pair can do.** The cubic gives a maximum-boiling azeotrope, as the real acid has, but one `k_ij` cannot hold both ends:

| `k_ij` | Vapour share at 1 mol/kg, 1 atm (real 0.009) | Azeotrope at 760 mmHg (real 20.2 %, 381.7 K) | At 50 mmHg (real 23.4 %, 321.9 K) | Drum share in water |
|---|---|---|---|---|
| −1.23 (the acid at 1 mol/kg) | 0.013 | None below 60 %; the boiling point runs away | 33.4 %, 325.2 K | 99.999 % |
| −1.0 (the azeotrope) | 0.13 | 22.6 %, 378.7 K | 22.8 %, 317.6 K | 99.98 % |
| −0.83 | 0.69 | 8.2 % | 8.6 % | 99.9 % |

With −1.23 a player could boil acid to a strength that does not exist. With −1.0 strong acid is right and dilute acid is 15 times too volatile.

**The tag closes the dilute end.** Up to 1 mol/kg the acid is the tag, which has no volatility: the real acid's is below 1 % of water's there. Above 1 mol/kg the excess is the compound, with the pair fitted to the azeotrope. Boiling 1 mol/kg acid away, tag and compound together:

| Water gone | HCl gone, 760 mmHg | Acid, % by mass | HCl gone, 50 mmHg | Acid, % by mass |
|---|---|---|---|---|
| 50 % | 2 % | 6.7 | 1 % | 6.7 |
| 80 % | 13 % | 13.7 | 7 % | 14.5 |
| 90 % | 31 % | 20.1 | 25 % | 21.6 |
| 98 % | 81 % | 25.5 | 81 % | 25.6 |

- The acid levels at 25.6 % (378.6 K and 317.5 K), against 20.2 % and 23.4 %: the tag's 3.5 % is unseen by the cubic and adds to its azeotrope.
- The model has no shift of the azeotrope with pressure; the real one moves 3 % by mass.
- It loses too much hydrogen chloride on the way up (31 % at 90 % of the water gone; the real acid loses little before 20 %).
- From refinery water (100 ppm) nothing leaves until 99.7 % of the water is gone, and a vessel boiled dry releases all of it: both as the real acid.

**Proposal** (option O6, B): fit the pair to the azeotrope at 1 atm (about −1.0 on this stand-in; the kernel's own fit decides), and state the tag's rule in both directions: in a wet vessel the tag holds the acid up to 1 mol/kg of free water and the rest is the compound.

### 4.5 Is an electrolyte model necessary? (hydrogen chloride now, amine absorption of H2S later)

Owner's question, 2026-11-16. Not computed with the kernel; the numbers below are ideal mass action from recalled constants and show shapes, not magnitudes.

**What the systems do.** Each is an acid or a base that turns into ions in water, and only its molecular part has a pressure.

| System | Law of the molecular part | What follows |
|---|---|---|
| Hydrogen chloride (strong acid) | `p = (γ m)² / K` | The apparent Henry constant moves over 4 decades with strength (4.3); an azeotrope (4.4) |
| H2S or CO2 in an amine | `H2S + amine ⇌ amineH⁺ + HS⁻`: free H2S proportional to `α² / (1 − α)`, α the loading in mol per mol of amine | 80 000 times between a loading of 0.01 and 0.9; the capacity ends near 1 mol per mol of amine; the constant falls about 7 times from 40 to 120 °C (MDEA), which is why a regenerator works |
| Sour water (NH3 with H2S or CO2) | `NH3 + H2S ⇌ NH4⁺ + HS⁻` | Each holds the other in the water: at 0.1 mol/kg of both, 93 % of the H2S is ionic at 25 °C and 64 % at 120 °C |
| Caustic, ammonia against acid | Charge balance | pH, neutralisation |

**What a cubic with pairs can and cannot do.** A pair gives a solubility that is linear in the dissolved amount, has no capacity and does not depend on a third compound. It cannot give a loading curve, a saturation at 1 mol per mol, or ammonia holding hydrogen sulfide. Hydrogen chloride already showed it: one constant fits one strength (4.4), and the plan's limit of 1 mol/kg, its neutralisation rule and M12's pH formula are three hand-made pieces of a speciation.

**So: three levels.**

| Level | What it is | Enough for | Not enough for | Cost |
|---|---|---|---|---|
| 0, rules per substance (plan revision 2 with O6) | A fitted pair, a tag, a limit | Refinery water at parts per million, corrosion | Any amine or sour water unit | In the plan |
| 1, speciation at the exchange sites | One law for all: mass action with fitted constants `K(T)` and Henry constants, activity coefficients inside the constants (the class of Kent and Eisenberg 1976 for amines, of Edwards et al. 1978 and API's SWEQ for sour water: **recalled**). Evaluated in wet holdups at an interval's start and on each stage of a unit on accounts (absorber, regenerator, sour water stripper). Never inside the step's Newton | Loading curves, capacity, regeneration, heat of absorption (from `K(T)`), pH, neutralisation, the corrosion inputs | Acid gas flashing out of rich amine in plain network equipment between two exchange sites; a gas met within an interval (4.3); strong acid as a bulk liquid | One small module (a one-dimensional root in pH per wet holdup that holds a listed compound); no kernel change |
| 2, electrolyte thermodynamics in the kernel | Speciation and ion activity inside every fugacity (electrolyte NRTL, or a chemical term in the excess energy of the Huron–Vidal rule) | Everything, everywhere, at every strength | | A kernel batch of the size of the aqueous solvents redesign: a speciation solve and its derivatives in every fugacity call, Java and native; phases are found from the physics, so the ions must fade smoothly outside water-rich phases; every reference moves |

**Answer.** A full electrolyte activity model (level 2) is not necessary for this batch or for amine units at game depth. A speciation law (level 1) is necessary for amine absorption and sour water; pairs cannot replace it. It is small, and hydrogen chloride should already be built on it: then the limit, the neutralisation and the pH are its outputs and no later acid or base needs a rule of its own.

**One thing level 1 leaves open for amines.** The bound gas is not a trace: at a loading of 0.5 it is 7 % of the rich solution's mass (H2S in 50 % MDEA) to 10 % (CO2 in 30 % MEA). A tag has no mass, so the bound form must be a compound there (a salt compound with the mass of both, held in the water by its pair), not a tag. The exchange of plan 7.4 must therefore allow a compound on both sides. Whether such a salt compound behaves in the cubic (no fourth phase, no share in the oil) is a measurement of the amine batch; if it does not, that batch needs level 2.

**Not known.** Ideal mass action misses measured amine loadings by about a factor of 10 at 4 mol/kg of ions (**recalled** data against my estimate), which is why the constants must be the fitted ones; they are not read yet. Kent–Eisenberg is weak below a loading of 0.05 and for H2S with CO2 together (**recalled**).

### 4.6 Level 1 chosen: the law checked, and the pair sized under it

Owner, 2026-11-16: "Level 1: speciation". Sections 4.3 and 4.4's proposals (a pair fitted to the acid, a tag limited to 1 mol/kg) are superseded; their measurements stand. Computed with `tmp/hcl-check/speciation.mjs` (the stand-in cubic; the ions unseen by it; the split solved continuously, which the engine does once per interval).

**The law for hydrogen chloride**, `f = γ² h m / K`, `K` = 2.0e6 mol² kg⁻² atm⁻¹ at 25 °C with a slope of 9 000 K, `γ` by Bromley with `B` = 0.1433 (all **recalled**): 4.0e-12, 3.2e-9, 3.3e-7 and 1.85e-4 atm over 0.003, 0.1, 1 and 6 mol/kg, against 4.0e-12, 3.2e-9, 3.3e-7 and 1.9e-4. Without the activity term the acid boils to 64 % and more: the term is needed, and one number carries it.

**The pair's part.** The law fixes the acid's pressure; the pair only decides how much molecular acid the water holds. Boiling 1 mol/kg acid away, by the pair's Henry constant at 25 and 100 °C:

| Henry constant, atm | `k_ij` at 25, 100 °C | Levels at, 760 mmHg (real 20.2 %) | At 50 mmHg (real 23.4 %) | Molecular share there | A 40 °C drum holds of an arriving gas |
|---|---|---|---|---|---|
| 0.03, 3 | −0.76, −0.66 | 23.7 % | 31.2 % | 22 %, 26 % | 99.0 % |
| **0.1, 10** | −0.68, −0.54 | 20.6 % | 27.8 % | 9 %, 14 % | 96.8 % |
| 0.31, 9.1 | −0.61, −0.55 | 20.7 % | 26.3 % | 9 %, 7 % | 93.2 % |
| 1, 30 | −0.53, −0.44 | 19.4 % | 25.2 % | 3 %, 2.5 % | 80.6 % |
| 10, 100 | −0.38, −0.33 | 19.0 % | 24.7 % | 0.9 %, 0.3 % | 35.5 % |
| Pair −1.0 (4.4) | −1.0 | 38.3 % | 41.1 % | 40 %, 47 % | 99.98 % |
| Pair 0 | 0 | 18.9 % | 24.6 % | 0 % | 0.27 % |

- With little molecular acid the azeotrope is the law's own: 18.9 % and 24.6 %, with the right shift with pressure.
- The molecular share is also what a boiling vessel can give off between two solves, and what holds an arriving gas. Both want it larger; the azeotrope wants it smaller.
- Chosen: 0.1 and 10 atm. Hydrogen chloride lost when 80 % of the water is gone: 9 % at 760 mmHg, 2 % at 50 mmHg.
- Every row boils at 371.5 K at the 760 mmHg azeotrope, against 381.7 K: the ions do not raise the boiling point.

**pH** by the charge balance, 25 °C: hydrogen chloride 0.00274 mol/kg gives 2.56; with half, equal and double the ammonia 2.86, 5.90 and 9.24; with equal caustic 7.00.

### 4.7 Ammonium salts and the wash water

Owner, 2026-11-16: "In hydroprocessing water is injected to dissolve ammonium salts in HC which would otherwise precipitate on equipment. Explain how the system deals with this simulation."

**Expected.**

- The reactor turns nitrogen into ammonia, sulfur into H2S and chloride into hydrogen chloride. As the effluent cools, two of these gases meet as a solid on a cold wall wherever the product of their partial pressures exceeds the salt's dissociation constant: `NH3 + HCl ⇌ NH4Cl(s)` and `NH3 + H2S ⇌ NH4HS(s)`.
- `ln Kp = 34.29 − 21 193 K/T` for ammonium chloride and `34.25 − 10 873 K/T` for ammonium bisulfide, in bar² (from the formation enthalpies and entropies, recalled, at constant enthalpy). Against the published chart fit `T(°F) = 523 · exp(0.0507 · ln Kp[psi²])` the chloride's temperatures are 3 to 8 K low; the bisulfide gives 29 °C at the published point of 39 psi² and 27 °C.

| Stream | Product of partial pressures | Salt deposits below |
|---|---|---|
| Crude overhead, 1.5 bar, HCl 50 ppm, ammonia 100 ppm | 1.1e-8 bar² | NH4Cl: 130 °C |
| The same, ammonia 500 ppm | 5.6e-8 | NH4Cl: 143 °C |
| The same, HCl 5 ppm, ammonia 10 ppm | 1.1e-10 | NH4Cl: 97 °C |
| Hydrotreater effluent, 60 bar, HCl 2 ppm, ammonia 0.2 % | 1.4e-5 | NH4Cl: 193 °C |
| Hydrocracker effluent, 150 bar, HCl 1 ppm, ammonia 0.5 % | 1.1e-4 | NH4Cl: 215 °C |
| Hydrotreater effluent, ammonia 0.2 %, H2S 1 % | 0.072 | NH4HS: 22 °C (never, above a cooler's 45 °C) |
| Hydrocracker effluent, ammonia 0.5 %, H2S 2 % | 2.25 | NH4HS: 52 °C (the air cooler's cold end) |

- The deposit insulates the wall and narrows the passage. Ammonium chloride takes up water from the gas and corrodes the steel under it.
- Wash water is injected ahead of the cold exchangers. Enough must be injected that about a quarter stays liquid at that point; the salts dissolve (ammonium chloride: about 0.37 kg per kg of water at 20 °C, recalled) and leave as sour water from the cold separator. The rate is set by the bisulfide's strength in that water, 4 to 8 wt%, and by the speed in the tubes, below 6 m/s for carbon steel. A feed of 100 t/h with 1 000 mg/kg of nitrogen makes 365 kg/h of bisulfide: 4.2 t/h of water at 8 %, 8.8 t/h at 4 %.
- The same salt belongs to this batch's own unit. A crude column's overhead neutralised with ammonia deposits ammonium chloride at 130 °C, above its water dew point (about 100 °C): the overhead exchanger fouls before any water condenses.

**What the design (plan revision 5) does.**

1. The three gases are compounds. In every exchanger and air cooler they stay gases at any temperature: the cubic has no solid salt, and a heat side exchanges nothing (plan 7.4). Nothing precipitates.
2. The wash water is a real stream. The flash decides how much of it stays liquid, so the quarter that must stay liquid is the kernel's own answer.
3. In the cold separator, a holdup with free water, the speciation law runs (hydrogen chloride is `strong`): the hydrogen chloride and its equivalent of ammonia become `acid_chloride` and `ammonium` on the water and leave by the boot; the pH comes from the charge balance.
4. H2S has no record in this batch, so no bisulfide forms: the sour water holds the chloride's ammonium only.
5. Without wash water the separator is dry, the gases pass on, and nothing follows from it.

The water's end is right; the reason for the water is missing. For the same reason neutralising a crude overhead with ammonia has no cost in revision 5, whatever the amount.

**How ammonium chloride itself is represented** (owner, 2026-11-16: "how NH4Cl modeled?"). It is never a substance of its own: no compound, no molecule in the gas (the real vapour is dissociated too). It is the meeting of two species, in these forms:

| Where | Form in the model | What sets it |
|---|---|---|
| Gas, and molecules dissolved in oil or water | Two compounds, `Ammonia` and `HydrogenChloride` | The cubic with their pairs |
| Free water in a holdup | Two ion tags on the water, `ammonium` and `acid_chloride`, without mass | The speciation law at the interval's start. Equal amounts at 100 ppm give pH 5.90, the real solution's |
| Between holdups, when the water evaporates in an exchanger | `ammonium` strands on the heaviest cut, `acid_chloride` rides the vapour | The tags' classes (plan 5.2, 5.4) |
| A dry holdup | Both return to the two gases | Plan 7.4, "without free water" |
| Solid on a cold wall | Revision 5: none. O10: `saltFouling` on a heat side's wall, without mass | `Kp` at the wall's colder end |
| Crystals carried by the oil, solids in a vessel | None | |

The dry holdup is wrong for this pair. Hydrogen chloride alone, or ammonia alone, does leave boiled-dry water as a gas. The two together leave crystals: at 60 °C `Kp` is 1.9e-13 bar², so a plant keeps 0.4 ppm of each in a gas at 1 bar and the rest is solid; at 200 °C it keeps 5 200 ppm. The model gives all of it back as gas at any temperature. With O10 the gases then deposit at the next cold, dry heat side, which is where the fouling matters, but a cold dry vessel shows none.

**Proposal** (plan option O10). The dry salt as a third form of the same pairs: one record kind and one law at heat sides.

- The same constant corrects the dry holdup: where a `salts` record names both ions, they return to the gases only up to `Kp` at the vessel's temperature. The rest stays in the vessel as dry residue on the two tags, as the salt of evaporated water does (plan 5.4), and is ions again when water returns. No new tag.

- Kind `salts`: `base` and `acid` (compounds), `constant` (`ln Kp = a − b/T`), `density`, `solubility`.
- Law `salt_deposit`, at a heat side passage (a site class the plan has, 7.2), evaluated at the cadence on the committed state:
  - A side without free water at its colder end: with `p_b` and `p_a` the two gases' partial pressures and `T_c` the wall's colder end, the pressure that deposits is `δ = ½ [(p_a + p_b) − √((p_a − p_b)² + 4 Kp(T_c))]`, where it is positive. The deposit grows by `g · (δ/P) · gas flow · molar mass`, on the side's own area: `dRf/dt = rate / (density · deposit conductivity · area)`.
  - A side with free water at its colder end: no deposit, and the salt it holds dissolves at `water flow · solubility`: gone within one cadence at any practical rate. So a continuous wash keeps the side clean and a wash now and then cleans it, as in a plant.
  - The deposit is a second fact of the wall, `saltFouling`, beside `fouling`: water removes salt and leaves coke. Both act through what exists: each film `h/(1 + h Rf)` and the narrowed passage.
- Nothing is taken from the stream: the deposit is an amount without mass, as the wall's coke is (plan D1). No compound moves at a passage, so conservation stays exact and the solver is not touched. The cost: the gas keeps its hydrogen chloride while the salt lies on the wall; with a continuous wash all of it ends in the sour water in a plant too.
- Under the deposit: a side that holds salt and whose gas holds water vapour corrodes at a stated rate (M13: 2.5 mm/y on carbon steel, recalled order of magnitude, with M12's material factors).
- `g` = 10, in line with the coking law's 13: the hydrotreater of the table (0.54 kg/h of salt on 200 m²) reaches the `FOULED` level in 29 process hours without wash; a plant takes ten times as long.
- Records: ammonium chloride now. Ammonium bisulfide and the H2S record of the speciation law come with the hydroprocessing and sour water design; no stream of this batch reaches its range (the crude overhead: −54 °C).

Not proposed: a deposit that holds its nitrogen and chlorine exactly. It needs a material exchange at a passage, which the frame forbids, or the chloride kept as a tag between holdups, which the owner's choice of a compound (O1) ended.

Sources of this section (search summaries; the API RP 932-B charts were not reached): [Digital Refining, the chart fit of the deposition temperature](https://www.digitalrefining.com/article/1002443/the-); [The Petro Solutions, wash water injection in hydroprocessing units](https://thepetrosolutions.com/?p=3612); [AFPM, bisulfide deposition at 80 to 150 °F and the 20 ft/s guidance](https://afpm.org/print/pdf/node/43082); [Digital Refining Q&A, 20 to 25 % of the wash water liquid](https://www.digitalrefining.com/qanda/286); [H.G. Houston, ammonium chloride corrosion in hydrotreating units](https://hghouston.com/archive/special-corrosion-topics/ammonium-chloride).

## 5. Not found, and open

- The numeric table of hydrochloric acid corrosion in API RP 581 Part 2 Annex B. The plan's M12 anchors are recalled.
- The Ebert–Panchal constants of the original papers. The plan fits its own two constants; only the form and the activation energy are taken.
- A heat of coking in kJ/kg. The plan of the coker will fit it to the drum's temperature drop.
- The activation energies of the five-lump hydrocracking scheme (the paper was found, its table not).
- A measured curve of hydrolysis against temperature for the three salts.

## 6. Sources

Read in this task:

- [Four steps solve crude-tower overhead corrosion problems, Oil and Gas Journal](https://www.ogj.com/home/article/17223883/four-steps-solve-crude-tower-overhead-corrosion-problems)
- [Corrosion in crude distillation, hydrolysis table (BazTech)](https://yadda.icm.edu.pl/baztech/element/bwmeta1.element.baztech-49c0abf0-7815-4c95-980f-7d53b87f2c39/c/Corrosion_1_23.pdf)
- [Corrosionpedia, chloride hydrolysis and caustic injection](https://www.corrosionpedia.com/?p=81393)
- [Metallurgical investigation of a crude distillation overhead system, Hydrocarbon Processing](https://hydrocarbonprocessing.com/magazine/2026/august/special-focus-plant-safety-and-environment/metallurgical-investigation-of-crude-distillation-overhead-system-a-case-study/)
- [Wilson, Ishiyama and Polley, Twenty years of Ebert and Panchal: what next?](https://www.repository.cam.ac.uk/handle/1810/267015)
- [Polley and others, fouling model parameters from plant data](https://core.ac.uk/download/185670946.pdf)
- [A desalter model with Stokes settling and a critical drop diameter, Processes 10, 2118](https://openaccess.iyte.edu.tr:443/bitstream/11147/12606/1/processes-10-02118-v2.pdf)
- [Wiehe, A phase-separation kinetic model for coke formation (abstract)](https://data.zhenzhi.com/details/1/1010095721)
- [Refining Processes Handbook, residuum processing](https://www.globalspec.com/reference/25512/203279/chapter-six-residuum-processing)
- [Chekantsev, Gyngazova and Ivanchina, Chem. Eng. J. 238 (2014) 120](https://portal.tpu.ru/SHARED/c/CHEKANCEV/Ucheba1/Tab1/CEJ11193.pdf)
- [Sánchez, Rodríguez and Ancheyta, Kinetic model for moderate hydrocracking of heavy oils (record)](https://investigacion.unirioja.es/documentos/5e81db4129995270f8358a69)

Recalled, to be read when their unit is designed: Ebert and Panchal 1995; Panchal and others 1999; Polley and others, Appl. Therm. Eng. 22 (2002) 777; Gray, Eaton and Le, Pet. Sci. Technol. 26 (2008); Wiehe, Ind. Eng. Chem. Res. 32 (1993) 2447; Korsten and Hoffmann, AIChE J. 42 (1996) 1350; Girgis and Gates, Ind. Eng. Chem. Res. 30 (1991) 2021; Stangeland, Ind. Eng. Chem. Process Des. Dev. 13 (1974) 71; Weekman, Ind. Eng. Chem. Process Des. Dev. 7 (1968) 90; Lee and others, Can. J. Chem. Eng. 67 (1989) 615; Jacob and others, AIChE J. 22 (1976) 701; Ancheyta-Juárez and others, Appl. Catal. A 177 (1999) 227; Gary, Handwerk and Kaiser, Petroleum Refining: Technology and Economics; Sundaram and Froment, Chem. Eng. Sci. 32 (1977) 601 and 609; Kumar and Kunzru, Ind. Eng. Chem. Process Des. Dev. 24 (1985) 774; Xu and Froment, AIChE J. 35 (1989) 88; Smith, Chem. Eng. Prog. 55 (6) (1959) 76; Riazi and Daubert, Ind. Eng. Chem. Process Des. Dev. 19 (1980) 289; API RP 571, RP 581, RP 932-B.

## 7. Model survey, and why each selection

Owner, 2026-11-16: "there are numerous models on the market, explore more and justify your selection". It revises sections 2 and 3 where it differs from them.

### 7.1 Method

Four research subagents (Opus, medium, approved by the owner), one per group of units, about 105 searches and page reads in all. Each listed the published families and what commercial simulators use, ranked them by the criteria below, recommended one model on its own, and only then judged the earlier selection. Their notes are scratch (`tmp/reaction-models/`); what they found is in this section. Marks as before: **read** (seen on a page, source in 7.6) and **recalled**. Publishers refused most automated reads, so most original constant tables are still unread: 7.5 lists them.

Criteria:

1. Its inputs exist: cuts by boiling point with their tags, and real light compounds. No molecular feed analysis.
2. Its constants are in the open literature, complete, for commercial conditions.
3. It gives what the game needs: yields by cut and compound, hydrogen, heat where large, coke, a simple activity, and the right response to temperature, pressure, time and feed quality.
4. Small: about ten lumps and thirty parameters, one solve per operating point.
5. Its light ends are existing compounds.

### 7.2 The families, and where each falls

| Family | Examples | Needs | Verdict |
|---|---|---|---|
| Commercial simulator kinetics | Aspen HYSYS FCC (21 lumps, **read**), hydrocracker (97 lumps, **read**), CatRef; KBC Petro-SIM; SPYRO | Ring and type analysis by boiling range; a calibration per plant | Fails 2: constants are closed. Fails 1 |
| Molecular and elementary-step | Structure-oriented lumping (Quann and Jaffe), single events (Froment), COILSIM1D | A molecular feed, thousands of species | Fails 1 and 4 |
| Lumps by chemistry | Mobil ten-lump FCC (Jacob 1976), KINPTR (13), Krane (20), Ancheyta 2000 (24) | Paraffin, naphthene and aromatic shares by carbon number, ring carbon | Fails 1 today; open again if type tags by carbon number ever exist |
| Rate laws per compound | Vanrysselberghe and Froment (sulfur), Xu and Froment (reforming of methane) | Speciation, or a profile along a tube | More than a yield table needs: a fallback |
| Small lumps by boiling range, 3 to 6 | Weekman, Lee, Ancheyta, John (FCC); Sánchez (residue) | Cut groups | **Chosen** for FCC and residue |
| Lumping on the boiling point | Stangeland 1974; continuous lumping (Laxminarasimhan 1996) | Boiling points | **Chosen** for distillate hydrocracking |
| Two reactivity classes with inhibition | After Korsten and Hoffmann, Girgis and Gates | Sulfur per cut, boiling point | **Chosen** for hydrotreating |
| Small molecular schemes | Sundaram and Froment; Kumar and Kunzru | Real compounds, one naphtha lump | **Chosen** for steam cracking |
| Equilibrium with an approach | Reforming of methane, shift, isomerization | Gibbs data | **Chosen** for reforming of methane and shift; fallback for isomerization |
| Yield correlations | Gary and Handwerk, Maples, Volk | One or two bulk properties | **Chosen** where no kinetic set is open (coking); a cross-check elsewhere |
| Threshold surface laws | Ebert and Panchal and its descendants | Speed, viscosity, film temperature | **Chosen** for fouling |

The models "on the market" in the commercial sense are the first two rows. They are better models and none can be used: their constants are not published and their inputs are analyses the mod does not have. The selection is therefore the best of the open, small tier, and its honest weakness is that every such model is fitted to one feed or one plant.

### 7.3 Per unit: candidates, selection, change

| Unit | Candidates weighed | Selected | Change from sections 2 and 3, and why |
|---|---|---|---|
| Hydrotreating | 9: Korsten–Hoffmann, Girgis–Gates, Ma–Sakanishi–Mochida (4 groups), Vanrysselberghe–Froment, Bej, Mederos–Ancheyta, Stanislaus–Cooper, Maples, commercial | Two first-order sulfur classes per cut with H2S inhibition | Kept. Four refinements: the refractory share rises smoothly with boiling point (dibenzothiophene boils near 332 °C, its 4,6-dimethyl form near 365 °C, **recalled**) instead of a step at 310 °C; ammonia also inhibits; aromatics are reversible with an equilibrium that falls with temperature; metals fill a catalyst capacity. Korsten–Hoffmann itself is one lump of order 1.6, so the class constants are refitted, not copied. Heats per mole of hydrogen: sulfur 53, nitrogen 59 to 66, olefins 124 kJ (**read**) |
| Hydrocracking, distillates | 9: Qader–Hill, Stangeland, Mohanty, continuous lumping, Elizalde, 4 to 6 lump schemes (Song 2017), structure-oriented, single-event, Aspen | Stangeland on the 12 cuts | Kept: it is the only form written on boiling points, with 4 parameters. Added: organic nitrogen lowers the activity, light ends split onto C1 to C4. Fallback and calibration source: continuous lumping, the one complete open set (**read**, Naderi 2018: `α` 1.4, `kmax` 0.24 at 410 °C; 1.2, 0.79 at 430 °C) |
| Hydrocracking, residue | 8: Sánchez 2005, Martínez–Ancheyta 2012, a slurry scheme, Callejas–Martínez, an industrial ebullated bed, continuous lumping, sediment limits, licensors | Sánchez's five lumps and ten paths, solved as a stirred reactor | Changed: an ebullated bed is back-mixed (Martínez–Ancheyta fitted the same network in a stirred reactor); its fit ends at 420 °C, commercial beds run to 440 °C (a notice). What limits a real unit is sediment, not kinetics; no open law was found (option O8) |
| FCC | 16: Weekman (3), Lee (4), Ancheyta (5), Bollas with Dave–Saraf constants (5, 9 paths), Takatsuka (6), John 2019 (6), Hagelberg (8), Jacob (10), Arbel, Han–Chung, Aspen (21), structure-oriented, single-event, Voorhies, yield correlations, Roman | Six lumps: gas oil, light cycle oil, gasoline, LPG, dry gas, coke | Changed. The five lumps had no light cycle oil and no slurry, which are 25 to 30 % of the product and two of the game's cuts. Constants: the open Dave–Saraf set (**read**, Dasila 2012: nine paths with heats, checked on a plant with its regenerator) instead of Ancheyta 1999 (unread, fitted to a laboratory test); the cycle oil paths from John 2019 once read, until then fitted to an industrial slate (**read**: fuel gas 5.4, LPG 17.0, gasoline 45.9, cycle oil 17.8, slurry 8.8, coke 5.1 %). Slurry is the unconverted feed above 343 °C. Feed quality (nitrogen, Conradson carbon, metals) acts through multipliers no paper read gives: a fit |
| Reforming | 11: Smith, Krane, Kmak, KINPTR, Van Trimpont, Padmavathi, Ancheyta 2000, Hu, Arani, Stijepovic, commercial | Smith 1959, per naphtha cut | Kept: the only open complete model on paraffin, naphthene and aromatic shares alone. Added: an activity factor for today's catalysts and a two-parameter coke law (Smith has no coke, and coke is what separates a continuous unit from a semi-regenerative one). The one readable copy of its constants is in a retracted paper and does not match the recalled ones: the original must be read |
| Isomerization | 6: Chekantsev 2014, a 15-lump scheme, single-event, Ahari, equilibrium with an approach, licensors | Chekantsev, reduced to about eight reversible pairs and benzene saturation | Kept; now **read** in full: 36 constants at 130 °C, ratios for the two other catalyst families, no activation energies. Equilibria come from the mod's own Gibbs data (the paper's are wrong for two reaction rows). The temperature response needs one assumed activation energy per family: a fit |
| Delayed coking | 11: Gary–Handwerk, Maples, Volk (Tulsa), a 4-lump Tulsa model, Del Bianco, Köseoglu, Zhou, Bozzano–Dente, Wiehe, a review, commercial; visbreaking: Singh, Kataria | Conradson carbon correlation, Gary–Handwerk splits | Kept; coefficients now **read** and checked on a worked example, with the sulfur split, metals to coke and the gas composition (all existing compounds). Changed: the coke factor 1.6 is above three commercial cases (1.3 to 1.5, **read**) and has no response to pressure or recycle: it becomes 1.2 to 1.7 by drum pressure and recycle ratio. That form is ours (option O8). The heat stays a stated value: the network's datum holds no heat of formation (4.1) |
| Steam cracking | 9: Sundaram–Froment, Kumar–Kunzru, severity correlations, Belohlav, Towfighi, Joo–Park, SPYRO, COILSIM1D, a radical network | Sundaram–Froment (ethane, propane, butane); Kumar–Kunzru (naphtha) | Kept; constants now **read** from two reproductions, which disagree on two frequency factors. Gas oil: no open model; not offered. Coil coking: no constants found; a rate on the olefins' pressure fitted to a run length. It needs acetylene, toluene, ethylbenzene and styrene as compounds |
| Reforming of methane, shift | 11: equilibrium with approach, Xu–Froment, Hou–Hughes, Numaguchi–Kikuchi, Akers–Camp, Moe, Keiski, Choi–Stenger, Christensen, the carbon limit, sulfur coverage | Equilibrium with an approach temperature | Kept. Constants **read**: `K = exp(−26 830/T + 30.114)` bar² and `exp(4 400/T − 4.036)`. Added: a carbon check on the equilibrated gas, and sulfur widening the approach. Xu–Froment only for tube profiles |
| Chloride hydrolysis | 6: Gray–Eaton–Le, Eaton's patent, Londono's thesis, practice tables, older laboratory work, equilibrium | First order, magnesium chloride | Changed. Calcium chloride gives up 3 % of its chlorine in laboratory steam and under 15 % in practice, sodium 0.6 % (**read**): it is not one lump with magnesium. The tag `hydrolysable_chloride` becomes magnesium chloride's chlorine plus 12 % of calcium chloride's; the default share of an assay's salt falls from one fifth to about one sixth. Naphthenic acid raises it (**read**, a patent): not built |
| Desalting | 7: Stokes with an overflow rate, a mixing balance, Al-Otaibi, Vafajoo, population balances (Aryafard), electrocoalescence, a flow simulation | Settling with an overflow rate | Kept, corrected: Stokes' own speed for a 1.3 mm drop gives a Reynolds number of 36, far outside its range; with the drag correction of Schiller and Naumann, now added, the drop settles at half that speed (2.1 times slower at 120 °C, 2.8 at 150 °C). `E` is read as the share of the brine that met the wash water; the rest, in fine drops, does not settle |
| Wall fouling | 12: Kern–Seaton, Ebert–Panchal, Panchal 1999, Polley (three forms), Yeap, Nasr–Givi, Saleh, refinery refits, the compatibility model, fixed resistances | Panchal's form on Reynolds and Prandtl numbers and wall shear | Changed. The earlier law used speed alone and 48 kJ/mol, which I credited to Polley; it is Panchal's, fitted to coker feed at 360 to 420 °C. At 230 °C and 2 m/s it gave no fouling, where measured rates are 0.3e-10 to 3e-10 m²K/J (**read**). Now 40 kJ/mol (refinery refits give 21 to 47, **read**), calibrated to that benchmark times the game's pace. One term, no second regime above 350 °C: a switch would be a step |
| Bulk coking | 4: Wiehe's phase separation, Rahmani–Gray, first order, lumped residue schemes | First order in the asphaltenes above a solubility limit set by the resins | Changed: coke forms after an induction period (Wiehe, **read** as a form). The resins crack and the asphaltenes they no longer hold in solution coke. Two more numbers, fitted until Wiehe's are read |
| Acid chloride corrosion | API RP 581's table only | A rate by pH | Corrected: the earlier slope (0.5 per pH) missed its own second anchor by a factor of 2; it is 0.35. Both anchors are still **recalled** |
| Speciation constants | | Plan 7.4 | Hydrogen chloride **read**: 2.03e6 mol² kg⁻² atm⁻¹, 9 000 K (Sander's compilation, after Clegg and Brimblecombe). Ammonia **read**: 59.8 mol kg⁻¹ atm⁻¹ with 4 200 K, ammonium pKa 9.245; combined slope 10 490 K, not 10 350. Bromley's `B` for hydrogen chloride still unread |

### 7.4 Where I did not follow a subagent

- Delayed coking's heat as "the enthalpy difference of products and feed": the network's datum holds no heat of formation, so that difference is not a reaction heat (4.1).
- Fouling with 68 kJ/mol above 350 °C: one term instead (above).
- Desalting as two settling classes: it gives the same numbers as the mixed share `E`, because 10 µm drops do not settle; only the wording changes.
- A magnesium ceiling at half its chlorine (the first product is Mg(OH)Cl): it contradicts the 90 % of practice; not imposed.

### 7.5 Papers whose tables are still unread

The owner offered to fetch them. In order of need: the first five serve this batch, the rest the later reactor design.

Owner, 2026-11-16: "Keep recalled values". Rows 1 to 5 are not fetched: this batch's constants stay at their recalled values, marked recalled in the plan (option O5). Rows 6 to 15 stand as the reading list of the reactor design, to be asked again there.

| # | Paper | Needed for |
|---|---|---|
| 1 | Gray, Eaton and Le 2008, Pet. Sci. Technol. 26, 1924: [doi 10.1080/10916460701402412](https://doi.org/10.1080/10916460701402412) | Hydrolysis rate and extent per salt |
| 2 | API RP 581, Part 2, Annex B, the hydrochloric acid corrosion tables | M12's anchors |
| 3 | Wiehe 1993, Ind. Eng. Chem. Res. 32, 2447: [doi 10.1021/ie00023a001](https://doi.org/10.1021/ie00023a001) | Bulk coking: rate constants and the solubility limit |
| 4 | Bromley 1973, AIChE J. 19, 313: [doi 10.1002/aic.690190216](https://doi.org/10.1002/aic.690190216) | `B` for hydrogen chloride |
| 5 | Polley, Wilson, Yeap and Pugh 2002, Appl. Therm. Eng. 22, 777 | Fouling constants on the wall temperature |
| 6 | Korsten and Hoffmann 1996, AIChE J. 42, 1350: [doi 10.1002/aic.690420515](https://doi.org/10.1002/aic.690420515) | Hydrotreating: inhibition constant, activation energy |
| 7 | Stangeland 1974, Ind. Eng. Chem. Process Des. Dev. 13 (1), 71; Mohanty, Saraf and Kunzru 1991, Fuel Process. Technol. 29, 1 | Hydrocracking: `A`, `B`, `C` |
| 8 | Sánchez, Rodríguez and Ancheyta 2005, Ind. Eng. Chem. Res. 44, 9409 ([record](https://investigacion.unirioja.es/documentos/5e81db4129995270f8358a69)); Martínez and Ancheyta 2012, Fuel 100, 193: [doi 10.1016/j.fuel.2012.05.032](https://doi.org/10.1016/j.fuel.2012.05.032) | Residue: ten rate constants and energies |
| 9 | John, Mustafa, Patel and Mujtaba 2019, Fuel 235, 1436: [doi 10.1016/j.fuel.2018.08.033](https://doi.org/10.1016/j.fuel.2018.08.033) (an open copy is on [CORE](https://core.ac.uk/download/161874963.pdf); the automated read was refused) | FCC: the six-lump table |
| 10 | Ancheyta-Juárez and others 1999, Appl. Catal. A 177, 227: [doi 10.1016/S0926-860X(98)00262-2](https://doi.org/10.1016/S0926-860X(98)00262-2) | FCC: a second constant set |
| 11 | Smith 1959, Chem. Eng. Prog. 55 (6), 76 (print only) | Reforming: the four rate and two equilibrium expressions |
| 12 | Sundaram and Froment 1977, Chem. Eng. Sci. 32, 601: [doi 10.1016/0009-2509(77)80225-X](https://doi.org/10.1016/0009-2509(77)80225-X); Kumar and Kunzru 1985, Ind. Eng. Chem. Process Des. Dev. 24, 774 (doi 10.1021/i200030a043, from one reference list) | Steam cracking: the two disputed factors, units |
| 13 | Sundaram, Van Damme and Froment 1981, AIChE J. 27, 946: [doi 10.1002/aic.690270610](https://doi.org/10.1002/aic.690270610) | Coil coking rate |
| 14 | Kent and Eisenberg 1976, Hydrocarbon Process. 55 (2), 87 (print); API publication 955 (SWEQ) | Amine and sour water constants |
| 15 | Kataria and others 2004, Ind. Eng. Chem. Res. 43, 1373: [doi 10.1021/ie0305723](https://doi.org/10.1021/ie0305723) | Visbreaking, if wanted |

Open and readable, not yet mined: [Volk, Wisecarver and Sheppard, Fundamentals of Delayed Coking (OSTI)](https://www.osti.gov/servlets/purl/807578), for the pressure and recycle coefficients.

### 7.6 Randomized crudes: what each model reads from the feed

Owner, 2026-11-16: "later in game the crude composition will be randomized, consider that". A model then cannot rest on a named grade: it must give a random crude, and any blend, their own yields from what a stream carries, the amounts on the twelve shared cuts and the tags (plan 5.6, D13). The first criterion of 7.1 is read that way.

| Unit | Reads from the feed | Constants fitted to one feed | What it needs |
|---|---|---|---|
| The laws of this batch (desalting, hydrolysis, fouling, coking, M12) | Tags and the stream's state | None | Nothing |
| Hydrotreating | Cut amounts, `sulfur`, `nitrogen` | The share of hard sulfur | That share as a function of the cut's boiling point |
| Distillate hydrocracking (Stangeland) | Cut amounts | Its three constants move with the feed's paraffin content | Type tags |
| Residue hydrocracking (five lumps) | Cut groups, `asphaltenes`, `nickel`, `vanadium` | Fitted on one heavy crude (recalled) | A factor on the asphaltene content |
| FCC (six lumps) | Cut groups, `nitrogen`, `nickel`, `vanadium`, carbon residue | Fitted on one gas oil | Crackability from `aromatics`; coke from the carbon residue; nickel to hydrogen and coke, vanadium to activity |
| Reforming (Smith) | The naphtha's paraffins, naphthenes and aromatics | None: the lumps are the types | Type tags, without which it cannot run |
| Isomerization | Real light compounds | None | Nothing |
| Delayed coking | Carbon residue, `sulfur` | None: correlations across feeds | Carbon residue |
| Steam cracking | Ethane and propane as compounds; naphtha by type | The naphtha constants | Type tags |
| SMR, shift | Real compounds | None | Nothing |

Two consequences for the reactor design:

- The hydrocarbon type per cut (section 4, row 4) was an input lacking. With random crudes it is the only way two crudes of one boiling range can differ in a reformer, an FCC or a cracker, because all twelve cuts share one Watson K band. The three `bound` tags proposed there (`naphthenes`, `aromatics`, `olefins`; paraffins by difference): a crude draws the first two as it draws its other contents, cracking makes the third.
- The carbon residue is not a tag of its own: a stated function of `asphaltenes` and `resins`, set in that design.

### 7.7 Sources read in the survey

- [Jechura, Hydroprocessing, Colorado School of Mines](https://people.mines.edu/jjechura/wp-content/uploads/sites/120/2019/02/CBEN409_08_Hydroprocessing.pdf) and [Coking](https://people.mines.edu/jjechura/wp-content/uploads/sites/120/2019/02/CBEN409_06_Coking.pdf)
- [Naderi, Shokri and Ahmadpanah 2018, continuous lumping of a hydrocracker](https://scielo.br/j/bjce/a/RHTFMYmywGbMyfrPxrwhWFd/?lang=en)
- [Song and others 2017, a six-lump industrial hydrocracker](https://www.aidic.it/cet/17/61/110.pdf)
- [The Aspen HYSYS hydrocracker's 97 lumps, Processes 8, 32](https://www.mdpi.com/2227-9717/8/1/32)
- [Dasila and others 2012, FCC riser and regenerator with the five-lump constants](https://file.scirp.org/pdf/ACES20120100020_53867384.pdf)
- [Dagde and others 2025, an industrial FCC slate and Aspen's 21 lumps](https://jchpe.ut.ac.ir/article_101533_36b6fb7f2bab94a1479cef7af591faf7.pdf)
- [Arani and others 2009, a 17-lump reforming model](https://scielo.br/j/bjce/a/JgbcwVg9mTYv9kH9wfX5kdg/?lang=en)
- [Chekantsev, Gyngazova and Ivanchina 2014](https://portal.tpu.ru/SHARED/c/CHEKANCEV/Ucheba1/Tab1/CEJ11193.pdf)
- [Kumar's 22 reactions as reproduced in Processes 8, 91](https://doi.org/10.3390/pr8010091) and [an ethane scheme, arXiv 2510.25880](https://arxiv.org/pdf/2510.25880)
- [Wilson, Ishiyama and Polley 2017, Twenty years of Ebert and Panchal](https://www.repository.cam.ac.uk/handle/1810/267015)
- [Estimating delayed coker yields, Digital Refining](https://digitalrefining.com/article/1002409/estimating-delayed-coker-yields)
- [Bromley's equation](https://Www.Wikipedia.org/wiki/Bromley_equation) and [the paper's record](https://scilit.com/publications/33a28ecb5d2668def9efec8791982d37)
- Looked up for 7.5: [John 2019 at Bradford](https://bradscholars.brad.ac.uk/entities/publication/e8f5e68a-7fcf-4a62-a058-86711c5a143a), [Martínez and Ancheyta 2012 (record)](https://manuscript.isc.ac/Inventory/10/1467160.htm), [Sundaram, Van Damme and Froment 1981 (OSTI)](https://www.osti.gov/biblio/5323351), [Stangeland 1974 (an unofficial copy)](https://www.scribd.com/document/48695179/Hydrocracking-Model)

- [Abbas, Dupont and Mahmud 2017, the equilibrium constants and Xu and Froment's parameters](https://eprints.whiterose.ac.uk/108413/17/1-s2.0-S0360319916333821-main.pdf)
- [Londono 2007, The role of clay in salt hydrolysis, MSc thesis, University of Alberta](https://ualberta.scholaris.ca/handle/123456789/84512)
- [US patent 6,902,662, reducing hydrolysis in hydrocarbon streams](https://patents.google.com/patent/US6902662B2/en)
- [Sander's Henry's law compilation: hydrogen chloride](https://henrys-law.org/henry/casrn/7647-01-0) and [ammonia](https://henrys-law.org/henry/casrn/7664-41-7)
- [Bell and others 2007, the ammonium ion's dissociation](https://publish.csiro.au/en/Fulltext/EN07032)
- [Suleman, Maulud and Man 2017, constants of amine equilibria](https://jcsp.org.pk/PublishedVersion/26e82d95-4ece-4562-8a0d-61770393c139Manuscript%20no%207,%20Final%20Gally%20Proof%20of%2011122%20(Abdulhalim%20Shah%20Maulud).pdf)
- [A review of crude desalting, Processes 9, 1226](https://openaccess.iyte.edu.tr/bitstream/11147/11443/1/processes-09-01226-v2.pdf)
