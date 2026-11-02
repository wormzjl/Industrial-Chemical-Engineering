# Cooling tower verification

Batch: `documentation/2026-10-30-water-cooling-tower/`.

`verify.ps1` runs the quick and slow gates, exact chain-100 regression, fresh fluid GameTests and the established 1000-seed campaigns (cold random, bundled random, bundled defaults, eight workers). Run from PowerShell with `./tools/cooling-towers/verify.ps1`; select stages with `-Steps @('gates','regression')`. It refuses concurrent test workers or dev clients. Outputs and the GameTest world are in the indexed `tmp/tower-verification/`; it removes nothing.

`verification.init.gradle` changes only this invocation's GameTest working directory. The campaign sources remain in `tools/unified-thermo-port-forms/`; they are not added to ordinary gates.

Software renders reuse `tools/storage-vessel-renders/StorageRender.java`. Compile it against `build/classes/java/main`, with classes and images under this worktree's `tmp/`. It now accepts any designed unit and optional tower columns/rows following its views argument.

`fill-forms/` keeps the fill forms measured in the branch review. `MarchedTowerTransfer.java.txt` is the eight-segment fill removed from `TowerTransfer` (as on `a2e9a478e`), and `TowerTableProbeTest.java.txt` writes its table `table.csv` (1840 points): copy them into `src/test/java/com/wormzjl/icheme/science/fluid/network/` as `OldTowerTransfer.java` and `TowerTableProbeTest.java`. `CorrelationTowerTransfer.java.txt` is the correlation form the owner measured and set aside for the Merkel rating (on `435014e2e`), and `Fit.java` fits its constants:

```bash
java tools/cooling-towers/fill-forms/Fit.java tools/cooling-towers/fill-forms/table.csv
```

`relief/Relief.java` draws one 4 m louver panel three ways, real slats, a baked sprite and a normal-map shader, and writes the sprite, the normal map and a comparison sheet: `java -Djava.awt.headless=true tools/cooling-towers/relief/Relief.java tmp/relief/louver`.

`relief/TowerSprites.java` bakes the towers' eight sprites, the natural tower's concrete, fill and floor among them: `java -Djava.awt.headless=true tools/cooling-towers/relief/TowerSprites.java src/main/resources/assets/icheme/textures/block`.

History: 2026-11-02 — added `fill-forms/` in the branch review; the marched fill was detached from `TowerTransfer`.

History: 2026-10-30 — added the verification lane and wet-tower support to the existing software renderer. No product code was detached into this folder.
