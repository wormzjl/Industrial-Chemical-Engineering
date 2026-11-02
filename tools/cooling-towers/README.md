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

`poly-counts/` counts what a tower costs: `PolyCount.java width fill columns rows` gives a fan tower's drawn polygons by material, `NaturalCount.java diameter...` a natural tower's cells, chunk polygons by material, sections touched and meshing time. Both are in package `com.wormzjl.icheme.science.equipment`: compile them with `-cp build/classes/java/main` into a folder under `tmp/` and run them with that folder and the main classes on the class path.

`native.init.gradle` puts the `runMcpClient` lane's game directory under `tmp/tower-native/client` and turns off `prepareMcpControlMod`, for one isolated client with the pinned bridge jar copied in by hand. `verify.ps1` stops if any other test worker or client runs, so the merge's lane used `tools/valve-hold-probes/final-lane/lane.sh` with `HEAD_WT` and `RUNID` instead.

History: 2026-11-02 — added `poly-counts/`, moved from `tmp/` in the merge preparation; documented `native.init.gradle`.

History: 2026-11-02 — added `fill-forms/` in the branch review; the marched fill was detached from `TowerTransfer`.

History: 2026-10-30 — added the verification lane and wet-tower support to the existing software renderer. No product code was detached into this folder.
