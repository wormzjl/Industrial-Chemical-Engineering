# aqueous-solvents

Purpose: offline checks for the aqueous phase (water with water-miscible solvents).

Batch: [2026-11-11-aqueous-solvents](../../documentation/2026-11-11-aqueous-solvents/README.md). Status: In use since 2026-11-11. Index: [../INDEX.md](../INDEX.md).

## How to run

From the repository root (Node 22, no dependencies, about 10 s):

```
node tools/aqueous-solvents/ethanol-water-vle.mjs
node tools/aqueous-solvents/pr-huron-vidal.mjs
powershell -NoProfile -ExecutionPolicy Bypass -Command "& 'tools/compound-import/Extract-DwsimData.ps1' -OutDir tmp/dwsim-groups -Files unifac.txt,unifac_ip.txt,unifac_ll_ip.txt,modfac.txt,modfac_ip.txt,chemsep1.xml"
node tools/aqueous-solvents/group-derived-pairs.mjs tmp/dwsim-groups
```

## Entry points

| File | Role |
|---|---|
| `ethanol-water-vle.mjs` | Bubble points of ethanol and water at 101.325 kPa against the 15 points of Perry's table: PR78 with classical mixing (k_ij scanned from -0.30 to 0.20), NRTL with Antoine pressures, and NRTL with ethanol's reference fugacity from PR78's own saturation (plan decision D2). Prints the errors, the azeotrope, a 10 % ABV wash and each point |
| `pr-huron-vidal.mjs` | One model for every phase: PR78 with water on the cubic (a Mathias-Copeman alpha fitted to the Wagner and Pruss saturation line, 0 to 200 C) and the Huron-Vidal mixing rule with the b-weighted NRTL. Prints water's saturation, heat of vaporization and liquid heat capacity; the pair fitted at 1 atm with constant energies and its azeotrope at other pressures; the pair with energies linear in T fitted also to NRTL bubble points at 20 and 300 kPa. About 1 min |
| `group-derived-pairs.mjs` | No authored pair parameters: reads DWSIM's modified UNIFAC (Dortmund) tables, derives each pair's Huron-Vidal energies (and, in its last section, alpha) from the compounds' group counts through the equation of state, and checks ethanol and water (Perry's table, 20 and 300 kPa), the ethanol and n-hexane azeotrope and miscibility, water and n-hexane, and a ternary's two liquids. Also prints the group model placed directly inside the rule (residual form and MHV1). About 2 min |

## History

- 2026-11-11: written. PR78 at its best k_ij (-0.10): AAD 1.56 K, no azeotrope. NRTL: AAD 0.25 K, azeotrope 0.897 at 78.17 C. See AQUEOUS_SOLVENTS_PLAN.md section 2.
- 2026-11-11: `pr-huron-vidal.mjs`. Water's saturation within 0.012 %; the pair at 1 atm 0.10 K with constant energies (wrong azeotrope trend with pressure), 0.18 K with energies linear in T (azeotrope 0.958 at 20 kPa, 0.860 at 300 kPa; NRTL 0.928, 0.868). See the plan's section 2.
- 2026-11-11: `group-derived-pairs.mjs`. Energies and alpha derived from group counts: ethanol and water 0.12 K, azeotrope 0.906 (0.928 at 20 kPa, 0.878 at 300 kPa); ethanol and n-hexane azeotrope 0.318 at 59.8 C, one liquid at 25 C; water and n-hexane two liquids, 15 to 60 times too soluble (the table's own values). The group model directly inside the rule: 0.72 K, azeotrope 0.864, a false ethanol and n-hexane split. See the plan's section 2.
