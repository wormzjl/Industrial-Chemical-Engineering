# aqueous-solvents

Purpose: offline checks for the aqueous phase (water with water-miscible solvents).

Batch: [2026-11-11-aqueous-solvents](../../documentation/2026-11-11-aqueous-solvents/README.md). Status: In use since 2026-11-11. Index: [../INDEX.md](../INDEX.md).

## How to run

From the repository root (Node 22, no dependencies, about 10 s):

```
node tools/aqueous-solvents/ethanol-water-vle.mjs
```

## Entry points

| File | Role |
|---|---|
| `ethanol-water-vle.mjs` | Bubble points of ethanol and water at 101.325 kPa against the 15 points of Perry's table: PR78 with classical mixing (k_ij scanned from -0.30 to 0.20), NRTL with Antoine pressures, and NRTL with ethanol's reference fugacity from PR78's own saturation (plan decision D2). Prints the errors, the azeotrope, a 10 % ABV wash and each point |

## History

- 2026-11-11: written. PR78 at its best k_ij (-0.10): AAD 1.56 K, no azeotrope. NRTL: AAD 0.25 K, azeotrope 0.897 at 78.17 C. See AQUEOUS_SOLVENTS_PLAN.md section 2.
