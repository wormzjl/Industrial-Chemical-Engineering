# Thermo data license review for open-source distribution

Batch: [2026-11-04-refinery-compounds](README.md). Date: 2026-11-04. Owner request: a license compliance review of the thermo data for open-source distribution.

This is an engineering compliance review, not legal advice. **(established)** marks a point taken from a license text or an official page; **(interpretation)** marks reasoning, and "counsel" marks a point only a lawyer can settle. Code guide: no change.

Scope: every thermodynamic or physical-property data set that the mod jar ships (`src/main/resources`) or that the tracked repository publishes (`src/test/resources`, `tools/`, `examples/`, `documentation/`). `research/` and `tmp/` are git-ignored and out of scope.

Method: two read-only Opus threads. One built a provenance inventory from the records' `source`, `evidence` and `provenance` fields, the documents and the git history. The other researched each source's terms on the web. Three claims were then re-checked here against the primary pages: Artistic License 2.0 section 4, ExxonMobil's site terms, and NIST's Standard Reference Data notice.

## 1. Expected

- The repository's MIT license (`LICENSE`, `mod_license=MIT`) can cover only what the project owns: its code, and data it made itself, such as solver labels, the neural model, fits to its own results and corpus images. Third-party data keeps its own terms inside an MIT project.
- A public distribution therefore needs, for each third-party data family:
  - a right to redistribute it;
  - the attribution and notices its terms ask for;
  - the license text where the terms require it;
  - a note of changes where the terms require it;
  - a clear mark that those files are not MIT.
- Non-commercial terms cannot pass through MIT, because MIT grants every recipient commercial use.
  - On 2026-09-24 the owner settled non-commercial data clauses with "this is non-commercial use" (Burcat; NIST SRD; [UNIFIED_MULTIPHASE_THERMO_PLAN_REVIEW.md](../2026-09-24-coolprop-low-temperature/UNIFIED_MULTIPHASE_THERMO_PLAN_REVIEW.md)).
  - That covers the project's own use. It does not cover an MIT release, whose recipients may use the data commercially. This review reopens those clauses for that purpose only.

## 2. What the repository does now

- The root `LICENSE` is MIT for the whole "Software". There is no notice or third-party data file, and the jar carries no third-party license text, so every data file reads as MIT today.
- Most records name their source. The compound import writes the ChemSep copyright line and license into every record it makes. The gaps are in 3.6.

| # | Data family | Where | Origin | Terms | In jar | Risk |
|---|---|---|---|---|---|---|
| 1 | Tier 1 compounds, their liquid volume points, the freezing sub-records of 11 older datasets, and the O9 thermochemistry (added 2026-11-08, batch 2026-11-04-open-thermo-data: ideal-gas heats of formation and formulas) | `properties/chemsep_*.json` (24, about 5,300 numbers), `transport/chemsep_liquid_reference.json`, `freezing` blocks, the `thermochemistry` blocks of the 33 compound datasets of `icheme:refinery` and of the water model | ChemSep v8.3 as embedded in DWSIM 10.2.5. Constants copied; Cp fitted; viscosity sampled; liquid points computed at Tr = 0.7; heats of formation and formulas copied | Artistic License 2.0 (file header; established) | yes | low once N1 and N2 are done |
| 2 | TJL light-end viscosities, dissolved-gas viscosity, DWSIM API exports | `properties/tjl19_*.json`, `transport/dissolved_viscosity.json`, test `materials/dwsim-*viscosity-api.json` | DWSIM 10.2.3/10.2.5 runs on ChemSep data | DWSIM is GPL-3.0 (established); the output of a GPL program is not itself GPL (interpretation); the ChemSep data inside is Artistic-2.0 | yes | low |
| 3 | PR k_ij from DWSIM's own table | 21 pairs of `interactions/dwsim_refinery_pr78.json`, 10 of `interactions/tjl20.json` | `kij_pr.dat`, with no header and no stated origin | GPL-3.0 by inclusion (established); the values are probably facts (interpretation) | yes | low to medium |
| 4 | PR k_ij from DECHEMA | 35 pairs plus 1 owner exception in `dwsim_refinery_pr78`; Isobutane/N-butane in `tjl20` | Knapp et al. 1982, DECHEMA Chemistry Data Series VI, through ChemSep `pr_ip.dat` (1992) and `pr.ipd` (2009) | ChemSep copyright line, no license line. US: numbers are facts. EU: a few dozen pairs are an insubstantial extraction (interpretation) | yes | low |
| 5 | Crude cuts and the TJL assays | `properties/crude_pc01-12.json`, `assays/tjl19.json`, `tjl20.json` | The project's DWSIM characterisation of the Ledezma-Martinez 2019 thesis TBP data (Watkins 1979 via Chen 2008), with Riazi-Daubert Tc and Pc | Facts from a thesis, plus DWSIM output | yes | low; citation missing (3.6) |
| 6 | ExxonMobil assays and the fits made from them | `assays/{wti_light_export,upper_zakum,bonga,dalia,cold_lake_blend}.json`; the fluid presets `wti_light_export` and `cold_lake_blend`; the Dalia viscosity fits (`methods/crude_pr78.json` `liquid_mixture`, the crude_pc liquid tables) | ExxonMobil assay PDFs. Yields rebinned; sulfur, nitrogen and metals copied; viscosities fitted | ExxonMobil site terms (established, 3.1) | yes | **high** |
| 7 | NIST WebBook values | `properties/nitrogen.json` (Shomate Cp, viscosity tables), the methane Cp fit, `transport/liquid_calibration.json` (8 points), the water vapour Shomate in `water/water.json` | NIST Chemistry WebBook (SRD 69) | SRD Act copyright (established, 3.2) | yes | **medium** |
| 8 | NIST raw output and checks | `tools/fluid-probes/f4/nist/*.tsv` (7 files, about 800 rows), `src/test/resources/fluid/reference/*`, the JANAF (SRD 13) uncertainties in the spine records | NIST WebBook, JANAF | As 7 | the spine only | **medium** |
| 9 | CoolProp | `pilot_*` datasets, the spine's ideal-gas coefficients, the COS and methane constants, test `coolprop/*.json` (with its LICENSE), `tools/pilot-transport-tables` | CoolProp ae81610e / 8.0.0 | MIT (established); its notice goes with substantial portions | yes | low once N1 is done |
| 10 | NASA polynomials | `spine/*.json` | NASA CEA `thermo.inp` (github.com/nasa/cea) | Apache-2.0 per the record | yes | low once N1 is done |
| 11 | IAPWS | `water/water.json`, `crystals/ice_ih.json`, `WaterRegion1.java`, `SteamVirial.java` | IF97, R10-06, R14-08, SR6-08 | Free use with attribution to IAPWS (established) | yes | low |
| 12 | Papers | `group_interactions/eppr78_2022.json` (Jaubert et al. 2022, SI Table S4, 356 pairs); `crystals/carbon_dioxide_i.json` (Jaeger and Span 2012; Span and Wagner 1996); the Riazi and Al-Sahhaf 1995 freezing estimates; GERG-2008 as NIST's AGA8 code writes it | Formulas and coefficients | Formulas and coefficients are not protected as such in the US (interpretation). Per its record, the E-PPR78 article is CC BY 4.0 and the SI file states no license; attribute as CC BY | yes | low |
| 13 | Journal and textbook tables used by tests | `src/test/resources/science/thermo/solid-co2-holdouts/*.tsv` (11 files, about 790 rows); `column/v3/holland-example-3-2.json` | HAL (CC BY-NC 4.0), Spiral (CC BY-NC-ND 4.0), Davis 1962 (Wiley, owner-provided), two University of Michigan dissertations, NIST ThermoML; Holland 1981 (McGraw-Hill) | Non-commercial, no-derivatives and all-rights-reserved terms | no; the repository only | **medium** |
| 14 | The project's own | the neural model and its labels, solver outputs, corpus images | the project's solver | MIT (owner) | yes | none |
| 15 | Copies in documents and tools | `REFINERY_COMPOUNDS_DATA.md` (about 75 rows, with some NIST values), older data documents, `tools/wp5-legacy-digest` (a second copy of the 959ea0d materials) | as the families they copy | as those families | no | as those families |
| 16 | Flammability limits and the cut combustion estimates (added 2026-11-08, O9 of batch 2026-11-04-open-thermo-data) | the limits of 28 compound datasets and the 12 cut estimates (the `flammability` blocks, and the `thermochemistry` blocks of the cuts) | NIOSH Pocket Guide to Chemical Hazards (18 compounds); US Bureau of Mines Bulletin 627, Zabetakis 1965, Table A-1 (10 compounds, PDF in the main checkout's `research/2026-11-04-open-thermo-data/usbm-bulletin-627/`); Cragoe 1929, US Bureau of Standards Misc. Publ. 97 (cut heats, a formula); Burgess-Wheeler (cut lower limits, a method) | NIOSH: CDC material, public domain (established, cdc.gov/other/agencymaterials.html). Bulletin 627 and Cragoe 1929: US Government works, no copyright (established, 17 U.S.C. 105). Methods: not protected (interpretation) | yes | none; cite the sources (N1) |

## 3. Findings, by risk

### 3.1 High: the ExxonMobil crude assays

- The repository holds five assays, two fluid presets and the Dalia viscosity fits derived from ExxonMobil's assay PDFs. Each record keeps the source URL and the PDF's SHA-256, so the provenance is complete.
- ExxonMobil's site terms (established):
  - one copy for personal, non-commercial use, or in connection with buying ExxonMobil products;
  - no copying to a website;
  - storing, posting elsewhere, distributing and altering need advance written permission.
- Whether rebinned and fitted numbers count as site "content", and whether browse-wrap terms bind, is for counsel (interpretation). The conservative reading forbids public redistribution under any license, MIT or not.
- If the mod or repository is already public, this applies to it now, not only to an open-source release.

### 3.2 Medium: NIST Standard Reference Data

- The Standard Reference Data Act (15 U.S.C. 290e) lets NIST copyright SRD. NIST's SRD notice reserves all rights and allows no reproduction without prior permission (established). The WebBook's fluid pages and JANAF are SRD.
- What the repository holds:
  - Shomate Cp fits and a methane Cp fit made from WebBook values;
  - nitrogen viscosity tables sampled from the WebBook;
  - eight liquid calibration points copied from it;
  - raw WebBook output, about 800 rows, in `tools/fluid-probes/f4/nist/`.
- Reading (interpretation): a fitted coefficient is a transformation, while sampled tables and raw copies are reproductions.
- The physics behind these values is published, and an MIT implementation exists in CoolProp:
  - nitrogen viscosity: Lemmon and Jacobsen 2004;
  - nitrogen equation of state: Span et al. 2000;
  - ideal-gas Cp: the same sources NASA CEA, Apache-2.0, carries.
  So the same quantities can come from open sources.

### 3.3 Medium: journal and textbook tables in test resources

- The solid-CO2 holdout tables copy journal digits and headers verbatim.
  - The CC BY-NC and CC BY-NC-ND files may be redistributed non-commercially with attribution, the latter unmodified, but never under MIT.
  - The Davis 1962 table is Wiley copyright and needs permission for public redistribution.
  - The dissertations remain their authors' copyright. ThermoML is NIST TRC data; treat it like 3.2 unless its own terms say otherwise.
- The Holland 1981 test transcribes one worked example's coefficients from a copyrighted textbook. That is small and factual, and fair use is arguable in the US (interpretation).
- None of these is in the jar, but the repository publishes them.

### 3.4 Low to medium: DWSIM's `kij_pr.dat` and the DECHEMA pairs

- `kij_pr.dat` is part of DWSIM (GPL-3.0) and gives no origin. One of its values equals a DECHEMA value, which suggests it was transcribed from literature.
  - If counsel held the values copyrightable, GPL share-alike would conflict with MIT. As facts, they are not (interpretation).
  - 31 bundled pairs come from it.
- DECHEMA's printed volumes are copyrighted, but numerical parameters are facts in the US (Feist v. Rural, 1991).
  - The EU sui generis database right protects only substantial extraction, lasts 15 years, and does not reward investment in creating the data (CJEU C-203/02).
  - 36 pairs from a 1982 compilation are insubstantial, and its term has probably run (interpretation).
  - ChemSep LITE and DWSIM ship the same values openly.

### 3.5 Low once the notices exist: ChemSep, under the Artistic License 2.0

- The license defines the Package to include derivatives of its files, so the converted subset is a Modified Version (established).
- Section 4 requires documenting how the Modified Version differs, plus one of three options (established):
  - (a) offer it back to the copyright holder;
  - (b) give it a different name and leave the original installable;
  - (c) license it under the original license, or under one that keeps the source freely available and bans license fees.
- MIT does neither. **The ChemSep-derived files therefore cannot be relabelled MIT.** The plain route is (c)(i): keep them under Artistic-2.0, keep the copyright line, document the changes and ship the license text.
- Code that merely uses the Package is not a Modified Version (section 9), so the mod's code stays MIT. Bundling in the jar is allowed when no license fee is charged for the Package (sections 7 and 8).
- chemsep.org states that ChemSep LITE may be used for any purpose, and its older LITE license asks for an acknowledgement (established).
- REFINERY_COMPOUNDS_DATA.md quotes a third party describing some ChemSep values as DIPPR-derived. The project relies on ChemSep's own license grant; the residual risk is low (interpretation).

### 3.6 Low: open sources, and the attribution gaps

- CoolProp (MIT), NASA CEA (Apache-2.0), IAPWS (attribution) and the papers need only their notices and citations in one place (N1).
- Attribution gaps in the records:
  - `interactions/tjl20.json` has no per-pair source. It comes from `kij_pr.dat`, plus one DECHEMA pair.
  - `assays/tjl19.json` and `tjl20.json` cite nothing. They come from Ledezma-Martinez 2019 and, through it, Watkins 1979.
  - `crude_pc01-07` and the tjl19 constants say "see MATERIALS.md", which gives no source. They come from the DWSIM 10.2.3 reconstruction.
  - The Dalia viscosity fits do not name ExxonMobil.
  - The CO2 fusion enthalpy (9019 J/mol) comes from a secondary source: Wikipedia, quoting Air Liquide. It is one fact, but a primary value would be cleaner.

### 3.7 Checked, and not used

- **Aspen HYSYS and DIPPR 801.** DIPPR licensees may not publish its contents, and AspenTech distributes DIPPR (established). No record takes numbers from either. The thesis's HYSYS-generated properties were not used; the TJL records say so.
- **Burcat.** Its database is non-commercial and is not used; the spine takes NASA CEA.
- **Extracted but unread files.** The import extracted `chedl_thermo.json` and `dwsim.xml`, but no script reads them.

## 4. Proposal for the owner

### Needed before any public release; no data value changes

- **N1. Third-party data notice.**
  - Add `THIRD_PARTY_DATA.md` at the root and ship it in the jar. For each family of section 2, it lists the files, source, license, citation and changes.
  - Add the license texts the terms require under `licenses/`: Artistic-2.0, Apache-2.0, and CoolProp's MIT notice.
  - Have the README and `LICENSE` say that the code is MIT and that the listed data files keep their own terms.
- **N2. ChemSep-derived files as Artistic-2.0.**
  - Mark them by path in the notice, and keep the copyright line each record already carries.
  - Add a "changes from ChemSep v8.3" section: the selection of compounds; unit conversions; least-squares Cp fits; viscosity sampling with the Andrade continuation; liquid points at Tr = 0.7; freezing values copied.
- **N3. Close the gaps of 3.6 in the notice.** The notice can cover them without touching any record, so no revision moves.

### Owner decisions

- **D-A. ExxonMobil assays** (3.1):
  1. Ask ExxonMobil for written permission to redistribute the reduced data.
  2. Replace them with assays under open terms. Candidates to verify: US government publications, such as the Department of Energy's Strategic Petroleum Reserve assays, and literature assays like the TJL set.
  3. Keep them out of public releases, for example in a private datapack.

  Recommended: 1 and 2 in parallel, and publish no release containing these assays until one of them succeeds. Replacing them changes physics: the presets and the Dalia liquid-viscosity fit move identities and column results, so it belongs in a batch of its own.
- **D-B. NIST data** (3.2):
  1. Ask NIST for permission; the SRD Act provides for it.
  2. Re-derive the same quantities from open implementations of the same correlations: CoolProp for the nitrogen viscosity and the liquid points, NASA CEA or CoolProp for the ideal-gas Cp. Move the raw NIST output in `tools/` to `research/`.
  3. Keep them under the 2026-09-24 statement, which does not fit an MIT release.

  Recommended: 2. It is the same physics from MIT and Apache sources. The numbers differ slightly, and every island carries nitrogen, so every island restates once and the ledger books the energy differences. Moving the raw copies needs no physics change and can go first.
- **D-C. Test tables** (3.3):
  1. Move the Wiley, NC-ND and dissertation tables to `research/` and have their tests skip when the files are absent. This narrows the published gate suite (AGENTS.md: a cleanup does not narrow a suite), but the tests still run on the owner's machine.
  2. Keep all of them, each marked with its own terms in the notice. The Wiley table still needs permission.

  Recommended: 1 for the Wiley and NC-ND files, plus a permission request for the Davis 1962 table if its test matters. The CC BY-NC files can stay if they are marked.
- **D-D. DWSIM `kij_pr.dat` pairs** (3.4):
  1. Keep them, credited to DWSIM (GPL-3.0) in the notice, as facts.
  2. Re-source each pair from the primary literature or ChemSep's DECHEMA table, with zero where none exists (as D3). This changes k_ij values, so it is a physics change.

  Recommended: 1, and 2 only if counsel advises it.

## 5. Sources

- Artistic License 2.0: https://opensource.org/license/artistic-2-0 ; FSF on its GPL compatibility: https://www.gnu.org/licenses/license-list.html#ArtisticLicense2
- ChemSep distribution page: http://www.chemsep.org/program/distribution.html ; DWSIM ships the ChemSep files beside the Artistic text: https://github.com/DanWBR/dwsim/tree/windows/DWSIM.Thermodynamics/Assets/Databases
- DWSIM (GPL-3.0): https://github.com/DanWBR/dwsim ; history of `kij_pr.dat`: https://github.com/DanWBR/dwsim/commits/windows/DWSIM.Thermodynamics/Assets/kij_pr.dat
- US facts doctrine: https://www.law.cornell.edu/supremecourt/text/499/340 ; 17 U.S.C. 102: https://www.law.cornell.edu/uscode/text/17/102 ; EU Directive 96/9/EC, chapter III: https://www.legislation.gov.uk/eudr/1996/9/chapter/III ; CJEU C-203/02: https://curia.europa.eu/en/actu/communiques/cp04/aff/cp040089en.pdf
- NIST SRD notice: https://www.nist.gov/srd/public-law ; 15 U.S.C. 290e: https://www.law.cornell.edu/uscode/text/15/290e ; WebBook fluid pages: https://webbook.nist.gov/chemistry/fluid/ ; NIST open-data policy for non-SRD works: https://www.nist.gov/open/license ; JANAF: https://janaf.nist.gov/janbanr.html
- CoolProp license: https://github.com/CoolProp/CoolProp/blob/master/LICENSE
- IAPWS outputs: https://iapws.org/about-us/outputs
- ExxonMobil terms: https://corporate.exxonmobil.com/global-legal-pages/terms-and-conditions ; assay page: https://corporate.exxonmobil.com/what-we-do/energy-supply/crude-trading/assays-available-for-download
- DIPPR contract: https://aiche.org/sites/default/files/docs/pages/DIPPR-webcontract.pdf ; secondary distributors: https://www.aiche.org/dippr/events-products/801-database/secondary-distributors
