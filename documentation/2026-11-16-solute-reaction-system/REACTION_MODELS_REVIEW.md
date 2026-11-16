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
   - Compounds: four C6 isomers and methylcyclopentane (isomerization), acetylene (steam cracking), elemental sulfur (a later Claus unit); a coke solid record.
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
| 10 | Compounds | Isomerization, steam cracking, Claus | 51 compounds | Four C6 isomers, methylcyclopentane, acetylene, sulfur, with the unit that needs them (`tools/compound-import`) |
| 11 | A coke solid and its fuel record | Coking, FCC | `demo_particle`; fuel `coal_coke` | One `solids` record |
| 12 | Catalysts | Every catalytic unit | Nothing | A material kind later: activity, decay, poisons, life |
| 13 | Reactor volumes | Residence time of units without holdup | Drawn shapes of the reactor kinds | Bed, riser, drum and coil volumes as design numbers of each kind |
| 14 | Real compounds for the light naphtha | Isomerization | The cut `crude_pc01` (309 to 357 K) | To decide with that unit: split the cut into C5 and C6 compounds at the unit, or treat isomerization as a change of a tag |

| 15 | Data range in pressure | Hydrotreating, hydrocracking (50 to 200 bar) | The `fluid_domain` of the cuts and most compounds ends at 20 bar; hydrogen's and methane's at 300 bar | The kernel continues with a notice. Qualify the cuts to 200 bar: evidence, not a model change |
| 16 | Hydrogen dissolved in the oil | A trickle-bed law written on the liquid's hydrogen | Every hydrogen pair is zero by rule (owner, 2026-11-04), so the dissolved amount is not a measured one | Write the rate laws on the gas's hydrogen partial pressure |

### 4.1 What the background thermo model must do

Read at `adb413b4d`: `EnergyReference`, the `properties` datasets, `thermo-kernel.md`, `fluid-thermo-port.md`.

- **This batch: nothing.** Tags and their transformations read temperature, pressure, phase amounts, densities and the oil's viscosity. They change no property and are in no identity.
- **The kernel and the equilibrium engine stay as they are for the reactor units too.** A reactor's products are existing compounds and cuts, flashed as any stream. There is no reactive flash: the unit solves its kinetics or its chemical equilibrium on its own, in the ideal gas, and hands amounts to the engine.
- **Energy datum.** The network's datum is sensible, zero at 298.15 K (`EnergyReference.sensible`, `formationDataQualified` false), so a stream's enthalpy holds no heat of formation. A reaction's heat is therefore booked by the unit as its own term (row 8, and the plan's section 14). The other form, formation offsets on the datum (`EnergyReference` carries per-compound offsets and a rebase), would restate every world and rest on the cuts' estimated values: not proposed.
- **Heat capacity range** (row 7): every dataset's fit, the cubic water's too, ends at 900 K and is held beyond. Hydrotreating, hydrocracking, coking and FCC risers (to about 830 K) are inside. The FCC regenerator (about 1 000 K), steam cracking and SMR (1 100 to 1 200 K) are outside. A refit moves the fluid identity, so it is its own data batch.
- **Entropy** (row 6): a new field on a sub-record that is in no identity, so it restates nothing.
- **Pressure** (row 15) and **hydrogen in oil** (row 16), above.
- Not checked: the steam virial (IF97 Region 2's `B`) above Region 2's 1 073 K, at the reformer's 20 to 30 bar.

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
