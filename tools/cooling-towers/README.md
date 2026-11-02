# Cooling tower verification

Batch: `documentation/2026-10-30-water-cooling-tower/`.

`verify.ps1` runs the quick and slow gates, exact chain-100 regression, fresh fluid GameTests and the established 1000-seed campaigns (cold random, bundled random, bundled defaults, eight workers). Run from PowerShell with `./tools/cooling-towers/verify.ps1`; select stages with `-Steps @('gates','regression')`. It refuses concurrent test workers or dev clients. Outputs and the GameTest world are in the indexed `tmp/tower-verification/`; it removes nothing.

`verification.init.gradle` changes only this invocation's GameTest working directory. The campaign sources remain in `tools/unified-thermo-port-forms/`; they are not added to ordinary gates.

Software renders reuse `tools/storage-vessel-renders/StorageRender.java`. Compile it against `build/classes/java/main`, with classes and images under this worktree's `tmp/`. It now accepts any designed unit and optional tower columns/rows following its views argument.

`correlation/` holds the fit behind `TowerTransfer`: `table.csv` (1840 points of the marched fill) and `Fit.java`, which prints the constants and the errors by operating class:

```bash
java tools/cooling-towers/correlation/Fit.java tools/cooling-towers/correlation/table.csv
```

`MarchedTowerTransfer.java.txt` is the eight-segment fill removed from `TowerTransfer` (as on `a2e9a478e`), and `TowerTableProbeTest.java.txt` writes the table: copy them into `src/test/java/com/wormzjl/icheme/science/fluid/network/` as `OldTowerTransfer.java` and `TowerTableProbeTest.java`. `MerkelTowerTransfer.java.txt` is the measured middle form, a Merkel rating with one root, to swap in if the correlation's accuracy is not enough.

History: 2026-11-02 — added `correlation/` in the branch review; the marched fill was detached from `TowerTransfer`.

History: 2026-10-30 — added the verification lane and wet-tower support to the existing software renderer. No product code was detached into this folder.
