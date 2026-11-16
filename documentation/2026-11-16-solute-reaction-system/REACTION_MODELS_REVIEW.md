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
7. **Open:** the acid chloride anchors (API RP 581's table was not reached); the Ebert–Panchal constants; the heat of coking. Section 5 lists them.

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

| Transformation | Model | Anchors | Status |
|---|---|---|---|
| Desalting | Stokes settling of water drops in the oil, `N = v_s A / Q`, removal `E · N/(1+N)`; drop size 1.3 mm with the field, 0.1 mm without; contact `E = 1 − exp(−W/0.02)` | One stage removes about 90 % of the salt and two stages up to 99 % (**read**). A single stage removed 90 % of NaCl, 50 % of CaCl2, 40 % of MgCl2 (**read**, OGJ). Operating range 93 to 149 °C (**read**). A model that finds the drop that just settles from Stokes' law and the residence time (**read**, İYTE). Wash water 3 to 8 %, a field-grown drop near 1 mm (**recalled**) | Drop sizes fitted to the first anchor; the oil viscosities of the plan's table are typical values, to be replaced by the mod's own |
| Chloride hydrolysis | First order, `k = 180 exp(−5190/T)` | MgCl2 starts near 120 °C, CaCl2 near 210 °C, NaCl above 500 °C (**read**). About 90 % of MgCl2 at 340 °C (**read**). Most MgCl2 hydrolyses at the flash zone's 650 °F (**read**, OGJ). About 10 % of the salt is MgCl2 and CaCl2 (**read**, OGJ; other sources give more: the plan's default is one fifth, per assay). Gray, Eaton and Le 2008 measured the kinetics in model oils (**recalled**, not found by the search) | One lump for Mg and Ca chloride; the 60 s of the anchors is an assumed laboratory time |
| Acid chloride corrosion | Rate by pH of the free water, `2.5 · 10^(−0.5 (pH − 2.25))` mm/y at 38 °C, doubling to 93 °C | Condensed HCl reaches pH 1 to 2 at the dew point (**read**). The overhead is kept 15 K above the dew point; wash water dilutes the acid (**read**). pH is the main variable (**read**). API RP 581's rates by pH: about 100 mpy at pH 2.25 and 20 mpy at pH 4.25 at 100 °F (**recalled**) | The table itself must be read |
| Surface coking | `dRf/dt = α Re^β Pr^(−0.33) exp(−E/RT_f) − γ τ_w` (Ebert and Panchal 1995; Panchal 1999 with `Re^−0.66`; Polley 2002 with the wall temperature and `Re^0.8` removal), reduced to speed alone | The four parameters are fitted per crude from plant data (**read**). The linear shear term is its known weakness (**read**). `E` = 68 kJ/mol (1995) and 48 kJ/mol (Polley) (**recalled**) | Constants fitted to the game's fouling time; the form and `E` are the literature's |
| Bulk coking | First order in asphaltenes, 210 kJ/mol | Coke forms after an induction period, when the asphaltenes pass their solubility limit (Wiehe 1993, **read**). Residue cracking 200 to 230 kJ/mol (**recalled**; the search returned 235 kJ/mol for n-hexadecane and scattered residue values) | No induction period: the 340 °C window stands in for it |

## 3. Catalytic and thermal units: model and requirements

`✓` an input the mod has (or the plan's tags give); `✗` lacking.

| Unit (kind) | Model chosen | Why this one | Inputs | Heat |
|---|---|---|---|---|
| Hydrotreating (`TRICKLE_BED_REACTOR`) | Sulfur in two classes per cut, each first order, with H2S inhibition `1/(1 + K p_H2S)²` and a hydrogen pressure term (Korsten and Hoffmann 1996; Girgis and Gates 1991). Nitrogen and metals first order. Olefins saturate fully, aromatics to an equilibrium | Two classes give the apparent order of 1.5 to 2 of real feeds without a sulfur speciation | `sulfur`, `nitrogen`, `metals` ✓; the refractory share ✗ (a rule by boiling point: dibenzothiophenes boil above about 310 °C); `olefins`, `aromatics` ✗; hydrogen pressure ✓; space velocity from the bed volume ✗; catalyst activity and metals on catalyst ✗. Products H2S, NH3 ✓ | Exothermic, by hydrogen consumed per class (stated values); major for cracked feeds |
| Hydrocracking, distillates (`TRICKLE_BED_REACTOR`) | Stangeland 1974: each cut cracks first order with a rate rising with its boiling point, into lighter cuts by one distribution function | It runs on pseudocomponents by boiling point, three shape parameters | Boiling points ✓; `nitrogen` (it poisons the acid sites) ✓; hydrogen consumed per kg converted ✗ (stated ratio: the cuts' hydrogen is fixed); catalyst ✗ | Exothermic, per hydrogen consumed |
| Hydrocracking, residue (`EBULLATED_BED_REACTOR`) | Five lumps: residue, vacuum gas oil, distillates, naphtha, gases, ten first-order paths (Sánchez, Rodríguez and Ancheyta 2005; 380 to 420 °C, **read**) | The lumps are cut groups | As above; `asphaltenes`, `metals` ✓ | As above |
| FCC (`FCC_CONVERTER`) | Five lumps: gas oil (second order), gasoline, LPG, dry gas, coke (Ancheyta-Juárez 1999, after Weekman 1968 and Lee 1989), activity falling with coke on catalyst; regenerator: coke burnt, the heat balance sets the catalyst circulation | Coke must be a lump for the heat balance; LPG apart from dry gas for the gas plant. The ten-lump Mobil model (Jacob 1976) needs ring and side-chain carbon, which no input gives | Boiling range ✓; Conradson carbon ✗ (from `asphaltenes` and `resins`); `metals`, `nitrogen`, `sulfur` ✓; aromaticity ✗; catalyst as a circulating solid ✗; riser and regenerator volumes ✗. Products: light olefins, butanes ✓, cuts with tags, SO2 in the flue gas ✓ | Cracking endothermic (stated per kg); coke burn by Hess's law (carbon to CO2 and CO); the unit is heat balanced: major |
| Delayed coking (`DELAYED_COKER`) | Yields from Conradson carbon (Gary and Handwerk: coke 1.6 × CCR wt%, gas 7.8 + 0.144 CCR, naphtha 11.29 + 0.343 CCR, gas oil the rest; **recalled**), reached by a first-order approach in the coil and drum; the drum fills with a coke bed | Robust, one input | Conradson carbon ✗ (as FCC); `sulfur`, `metals` to the coke ✓; a coke solid record ✗; drum volume ✗ | Endothermic, major: fitted so that a 495 °C furnace outlet gives a 440 °C drum overhead (heater outlet 900 to 950 °F, drum 780 to 840 °F: **read**) |
| Steam cracking (a fired coil) | Ethane, propane, butane: the molecular schemes of Sundaram and Froment 1977 (5 and 10 reactions). Naphtha: Kumar and Kunzru 1985, one first-order primary step to a fixed product slate and 21 secondary reactions | Real compounds, small schemes, heats by Hess | Every product but acetylene ✓; the naphtha's paraffin, naphthene, aromatic split ✗; steam ratio ✓; coil temperature and residence time (0.1 to 0.5 s) from the coil ✓; heat capacity above 900 K ✗ | Endothermic, major, by Hess's law |
| SMR and shift (a catalyst-tube furnace ✗, fixed beds) | Equilibrium of reforming and shift with an approach temperature. Xu and Froment 1989 only if tube profiles are wanted | Reformer outlets stand within 5 to 15 K of equilibrium; two equations instead of a tube integration | CH4, H2O, CO, CO2, H2 ✓; entropy ✗; heat capacity above 900 K ✗; steam to carbon ratio ✓ (coke guard); sulfur in the feed ✓ (poison guard) | Endothermic, major, by Hess's law |
| Isomerization (fixed bed) | Reversible first-order steps among the C5 and C6 isomers, benzene saturation; three catalyst families with their own temperature windows (Chekantsev, Gyngazova and Ivanchina 2014, **read** as the reference model) | Equilibrium-limited: the constants must come from Gibbs data | n-pentane, isopentane, n-hexane, cyclohexane, benzene ✓; 2- and 3-methylpentane, 2,2- and 2,3-dimethylbutane, methylcyclopentane ✗; entropy ✗; the light naphtha is the cut `crude_pc01`, not real compounds ✗ | Mildly exothermic; benzene saturation major where benzene is present |
| Reforming (fixed or moving bed) | Smith 1959: naphthenes to aromatics, paraffins to naphthenes, two hydrocracking paths, with his equilibrium expressions | The smallest model that makes hydrogen and aromatics | Paraffin, naphthene, aromatic split ✗; hydrogen ✓ | Endothermic, major |

Later, with the same frame: amine treating and caustic washing (a `dissolved` tag on water and a `transfer` law), Claus, alkylation, gasification (the gasifier kinds exist as drawings).

## 4. Inputs lacking

| # | Input | Needed by | Have | Proposal |
|---|---|---|---|---|
| 1 | Nitrogen, metals, asphaltenes, resins per cut | Hydrotreating, hydrocracking, FCC, coking | Sulfur and acid number per cut (fouling-corrosion) | Tags with default contents per cut and a target per assay (plan section 5) |
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

**Proposal** (plan option O6): the pair fitted to the acid at 1 mol/kg, with the absorption to the tag kept as the owner described it, and capped at 1 mol/kg of water.

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
