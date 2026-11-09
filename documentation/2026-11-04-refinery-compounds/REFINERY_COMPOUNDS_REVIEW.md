# Refinery compounds for the thermo package: review

Batch [2026-11-04-refinery-compounds](README.md), 2026-11-04, at main 84f739baa (0.20.0). The data and every source are in [REFINERY_COMPOUNDS_DATA.md](REFINERY_COMPOUNDS_DATA.md). Code guide: no change (no code changed).

## 1. Expected behaviour (physics)

- A refinery's streams beyond crude and fuel gas need these species: hydrogen (hydrotreating, reforming, hydrocracking), H2S, NH3, COS and SO2 (sour gas, sour water, Claus), CO and O2 (hydrogen plant, regenerator, combustion), C2 to C4 olefins and butadiene (FCC and coker gas), BTX, ethylbenzene and naphthenes (reformer feed and reformate), isooctane and MTBE (blending), mercaptans and thiophene (sulfur species), and the amines MEA, DEA and MDEA (gas treating).
- A pure compound freezes at its melting line, where the solid's chemical potential equals the liquid's. At 1 atm this is the normal melting point, within 0.1 K of the triple point except for CO2: it sublimes at 194.7 K at 1 atm, and its triple point is 216.6 K at 518 kPa.
- In a mixture a component crystallizes only where its activity reaches the solid's. To first order, ln(x γ) = -ΔH_fus/R (1/T - 1/T_m). Benzene at 2 % in gasoline therefore stays liquid far below 278.7 K, while p-xylene crystallizes out of mixed xylenes near 220 to 250 K (the industrial separation). The pure freezing point alone does not place a mixture's freeze. A freeze-out model also needs ΔH_fus.
- A crude fraction is a mixture and freezes over a range: wax appears first (cloud point), then the liquid stops flowing (pour point). No single measured freezing point exists for it.

## 2. What the code does now

- The catalog holds 10 compounds (methane to n-pentane, N2, CO2, water) and 12 crude fractions. No record kind carries a freezing point.
- Triple points appear only as data: in `evidence` text; as `temperature_min_kelvin` of the nitrogen and pilot records (a data-range start, which never holds a state); as the water model's `triple_point`; and as the anchors of the two crystal records (CO2-I, ice Ih).
- Freezing is modelled only for the two crystals, through the equilibrium engine. Every other component stays fluid below its freezing point, as the owner's rule in AGENTS.md requires (a freezing point is never a validity floor).
- The E-PPR78 scheme (40 groups) covers every hydrocarbon, H2, H2S, CO, O2, Ar, NH3, SO2, COS and the mercaptans in the list. It has no group for methanol, ethanol, MTBE, DME, DMS, thiophene, HCl or the amines.
- A package axis holds at most 64 components + water (`MaterialAxis`). The network base already uses 20, so the 49 candidates cannot all join one package.

## 3. Proposal for the owner

**P1. Compound tiers.** Data and sources for all of them are in the data file.
- Tier 1 (24, process-critical): H2, H2S, CO, O2, NH3, SO2, COS, ethylene, propylene, 1-butene, isobutene, cis- and trans-2-butene, 1,3-butadiene, n-hexane, cyclohexane, benzene, toluene, ethylbenzene, o-, m- and p-xylene, methanethiol, ethanethiol. All have E-PPR78 groups. All but butadiene and the two thiols have a CoolProp reference EOS.
- Tier 2 (blending and reformer, pick to fit the axis): neopentane, 2-methylpentane, n-heptane, n-octane, isooctane, n-nonane, n-decane, n-dodecane, n-hexadecane, cyclopentane, methylcyclopentane, methylcyclohexane, naphthalene, 1-methylnaphthalene, argon.
- Tier 3 (need pair data beyond E-PPR78: constant k_ij or the `nrtl` interactions model): methanol, ethanol, MTBE, DME, DMS, thiophene, HCl.
- Out of scope (capability limit for PR78; leave out): MEA, DEA and MDEA (aqueous reactive electrolytes), H2SO4 and HF (alkylation acids), liquid sulfur (S8 polymerization), caustic.
- Route: a spine package like `pilot_cryogenic` (NASA polynomials, E-PPR78, volume translation) for Tier 1, so the k_ij come from groups, not a hand-fitted matrix.

**P2. Freezing point on every compound.** Options:
- (A, recommended) A new record kind keyed by component, for example `freezing/<component>.json`. It holds `temperature_kelvin`, `pressure_pascal`, `kind` (`triple_point` / `normal_melting` / `estimated_n_paraffin_bound`), optional `fusion_enthalpy_j_per_mol`, `source` and its own `revision`. Like the `transport` records it stands beside the packages, with its own revision outside their identities, so no package identity moves. The catalog checks that the CO2 and water values match their crystal anchors.
- (B) An optional field on each `properties` record. Each touched record's revision must then move, and with it `scientificRevision`. `V3AnchorTransformerInitializer` accepts a neural model only when that revision matches (line 139), so the column's neural seed is off until its artifacts are re-pinned.
- Either way the value never feeds a temperature range or a hold. Nothing in the solver reads it until a crystal or wax model does.

**P3. Crude fractions.** Options:
- (a, recommended) Record the n-paraffin melting point of the fraction's molar mass (Riazi and Al-Sahhaf 1995), marked `estimated_n_paraffin_bound` (178 to 389 K). It is an upper bound: at pc04 it is 254 K, while jet fuel freezes below 233 K. pc10 to pc12 lie beyond its C40 range.
- (b) Record no freezing point for fractions.
- (c) A pour-point correlation (Riazi and Daubert 1987). It needs each fraction's kinematic viscosity at 38 °C. More work, closer to what a player would see.

**P4. Fusion enthalpy.** Add `fusion_enthalpy_j_per_mol` from the same NIST pages, so a later freeze-out model has its second constant. Small extra work.

Open questions for the owner: the P1 tiers, P2 (A or B), P3 (a, b or c), P4 (yes or no). Nothing is built until these are answered.
