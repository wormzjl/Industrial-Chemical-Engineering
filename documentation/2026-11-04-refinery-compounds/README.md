# 2026-11-04-refinery-compounds

Purpose: the major refinery compounds to add to the thermo package, their data and sources, and a freezing point for every compound.

Status: Planned (review only, 2026-11-04). Index: [../INDEX.md](../INDEX.md).

## Documents

- [REFINERY_COMPOUNDS_REVIEW.md](REFINERY_COMPOUNDS_REVIEW.md): physics, the catalog today, proposals P1 to P4 for the owner.
- [REFINERY_COMPOUNDS_DATA.md](REFINERY_COMPOUNDS_DATA.md): M, Tb, freezing point, Tc, Pc, omega, E-PPR78 groups and sources for 49 new compounds; freezing points for the 10 existing compounds and the 12 crude fractions.

## Code areas

None changed. A build would touch `src/main/resources/data/icheme/materials/` (new `components`, `properties`, `spine` records, a new package) and, for P2 option A, `science/material/MaterialCatalog` (a new record kind).

## History

- 2026-11-04: review and data tables written on `claude/refinery-compounds-thermos-252973`. Data read from the local CoolProp 8.0.0 fluid files (18 fluids), the CoolProp 8.0.0 fluid pages, the NIST WebBook phase-change pages and Cheméo (ChemSep v8.3 acentric factors), by two research subagents; benzene's CoolProp row re-read verbatim. No test run (documents only).

## Open items

- Owner answers: P1 tiers, P2 (A separate record kind, or B a field on `properties`), P3 crude fractions (a, b or c), P4 fusion enthalpy.
- At build time: confirm n-hexadecane Tb (NIST gives 554 ± 10 K); re-read every copied digit from its source page; take Cp, liquid density and viscosity from the sources in the data file, section 5.
