# Simulator sizing, control and equipment probes

Batch: [2026-11-06-simulator-sizing](../../documentation/2026-11-06-simulator-sizing/README.md), prepared for 0.22.0; not merged.

This folder retains the batch's offline measurements, model authoring references and software renderers. These are investigation tools, outside every production and gate source set. The product and permanent tests remain in `src/main`, `src/test` and `src/fluidGameTest`.

## Entry points

- `CableStress.java`: one-run data/power graph, route and FE checks, CPU/allocation/heap measurements, with the earlier FE loop as a bounded comparison. `representative` uses well-distributed UUIDs; the default also includes deliberately colliding identities. The earlier-loop comparison is historical, not an alternative shipped implementation.
- `PowerLookRender.java`: current data/power conduit software views, including sparse identification bands.
- `StationRender.java`: constructor/simulator/controller/DCS front/rear/top views using the current authoring sources in `tools/pump-sprites/`.
- `export-model-faces.py <tmp-output.tsv>`: export the two control blocks and five sensor item models for the retained `archive/sensor-service/SensorAssetRender.java`; output must stay in this worktree tmp/.
- `ControlPortProbe.java`: source-mesh offset-tee rim matching and standing/lying drum terminal close-ups; `PowerLookRender.crop` selects the model-space viewing bounds, without modifying the mesh.
- `StationModels.java`, `check_models.py`: station model/collision/resource/facing probes.
- `PortProbe.java`: inspect cast reducer endpoints, visible stencil counts and clearance samples without a client.
- `focused-gametests.init.gradle` and `focused-gametests/`: select existing GameTest methods in a fresh world, preserving their annotations and calling the real bodies. They add no permanent duplicate tests or production classes. The separate empty template only identifies the filtered namespace.
- `archive/`: original one-off probes, texture generators, scripts and experimental patches, grouped by their original task folder. `source-inventory.tsv` records the original path and byte count. These sources describe several intermediate revisions; do not apply an archived patch to current code without reviewing it.

## Run from the repository root

All outputs belong in an indexed `tmp/<name>/` folder. For Java tools that import game types, use the development runtime classpath and compiled main classes/resources (the archived task's run script shows its historical classpath). Compile with `javac -proc:none -cp <runtime-classpath> -d tmp/<name>/classes <source>` and run with that directory plus the same classpath. Set Java's temporary directory inside that task folder.

```text
java -cp <probe-and-runtime-classpath> CableStress tmp/<name> representative
java -Djava.awt.headless=true -cp <probe-and-runtime-classpath> PowerLookRender tmp/<name>
python tools/simulator-sizing/check_models.py
gradlew.bat --no-configuration-cache --init-script tools/simulator-sizing/focused-gametests.init.gradle runFluidGameTestServer "-PfluidFocusedTests=PumpHealthGameTests#anInlinePumpPublishesItsCrackle" -PfluidFocusedOut=tmp/<name>/world -PfluidGameTestRunId=<fresh-name>
javac -d tmp/<name>/classes tools/pump-sprites/ConstructorAssets.java tools/pump-sprites/SimulatorAssets.java tools/pump-sprites/ControlAssets.java tools/simulator-sizing/StationRender.java
java -Djava.awt.headless=true -cp tmp/<name>/classes StationRender src/main/resources/assets/icheme tmp/<name>/constructor constructor
```

For the cabinet/sensor finishes and drum/junction figures:

```text
java -Djava.awt.headless=true -Djava.io.tmpdir=tmp/<name> -cp tmp/<name>/classes StationRender src/main/resources/assets/icheme tmp/<name>/controller controller
python tools/simulator-sizing/export-model-faces.py tmp/<name>/models.tsv
javac -proc:none -cp <runtime-classpath> -d tmp/<name>/classes tools/simulator-sizing/archive/sensor-service/SensorAssetRender.java tools/pump-sprites/probes/SensorSheet.java tools/simulator-sizing/PowerLookRender.java tools/simulator-sizing/ControlPortProbe.java
java -Djava.awt.headless=true -Djava.io.tmpdir=tmp/<name> -cp <probe-and-runtime-classpath> SensorAssetRender tmp/<name>/models.tsv tmp/<name>/renders
java -Djava.awt.headless=true -Djava.io.tmpdir=tmp/<name> -cp tmp/<name>/classes SensorSheet tmp/<name>/renders tmp/<name>/sensors.png
java -Djava.awt.headless=true -Djava.io.tmpdir=tmp/<name> -cp <probe-and-runtime-classpath> ControlPortProbe tmp/<name>/renders
```

Run timing probes alone. A software render does not qualify live Minecraft lighting or chunk synchronisation. The one final integration lane remains `tools/merge-lane/lane.sh`; this folder does not duplicate it.

`fluidFocusedOut` defaults to `tmp/merge-prep/focused-world`; the init script refuses any resolved directory outside this worktree's `tmp/`. Use a fresh named child directory for a new run and retain prior results. This does not change the normal GameTest source set or gate.

## Evidence and history

- 2026-11-07: `4682156e` cabinet/sensor finish and `76ad7889` connected-control/offset-junction follow-up. Figures/audits are in main `research/2026-11-06-simulator-sizing/evidence/control-finish/` and `evidence/control-rework/`; the latter holds the refreshed 0.22.0 jar/verification manifest. Separate compressed archives are `simulator-sizing-control-finish-logs-2026-11-07.tar.gz` and `simulator-sizing-control-rework-logs-2026-11-07.tar.gz` under the main logs folder. The before/after tee uses LineGeometry from `4682156e` versus `76ad7889`; prepend the separately compiled old class to the probe classpath to reproduce the 8/8 missing-rim baseline. `archive/control-rework/` retains superseded diagnosis sources, not gate code. No original or older jar was deleted.

- 2026-11-07: `efa22cfe` adds equipment protection/deconstruction and the validated focused-world output property; seven new permanent GameTests and 51 affected existing paths resolve through filtered runs. Constructor removal software renders use `tools/heat-gui-renders/GuiPrototypes.java`. Current jar/figure/manifest: main `research/2026-11-06-simulator-sizing/evidence/equipment-protection/`; logs: main `tools/simulator-sizing/logs/simulator-sizing-protection-logs-2026-11-07.tar.gz`. Earlier release evidence remains intact.

- 2026-11-06–07: sizing/crossflow, tank access, rotating equipment, wired controls, shared conduit placement, FE supply, models and GUI investigations. Initial product work reached `c45cea075` before merge preparation; later follow-ups are listed below.
- 2026-11-07: archived 174 source/script files from the batch's indexed scratch folders; retained the originals because the branch is unmerged. No production helper was detached: the reachability audit found 638 of 638 production classes reachable.
- 2026-11-07: `3515b1ff` resolves the final lane's stale assertions and short-reducer stencil visibility. `PortProbe` and the optional focused GameTest registrar retain that investigation without duplicating permanent test bodies. Final archive: main checkout `tools/simulator-sizing/logs/simulator-sizing-final-logs-2026-11-07.tar.gz`; the inspected jar and verification manifest are in `research/2026-11-06-simulator-sizing/evidence/release/`.
- Figures and tables: main checkout `research/2026-11-06-simulator-sizing/evidence/`. Compressed logs: main checkout `tools/simulator-sizing/logs/simulator-sizing-logs-2026-11-07.tar.gz`. Both remain local and ignored. The batch review distinguishes historical measurements from the final verification lane.

- 2026-11-08: late integration at `050cd109` closes deferred shared-line/DCS/GUI/native-interface checks. The final full lane at `891c8acf5` was not repeated; only affected filters ran. 206 distinct JUnit checks and 25 dedicated-server methods resolve through preserved follow-ups. Reachability reports no orphan production class; source counts are 669 main, 489 test and 36 GameTest files. Current 0.22.0 package, all native drawings/proofs, 838-entry applicability matrix and verification manifest are under canonical `research/2026-11-06-simulator-sizing/evidence/readiness-2026-11-08/`; logs are `simulator-sizing-readiness-logs-2026-11-08.tar.gz` in the main local logs folder.
- 2026-11-08: moved 177 byte-verified earlier archived sources and 102 late source/scripts out of scratch; preserved generated results and previous jars. The late archive has a SHA-256 inventory, and the readiness archive retains this pass's authoring/audit scripts. Historical patch scripts are not current migrations. Accepted model regeneration is `tools/equipment-drawings/update.py`; `ControlAssets` now shares local cabinet bounds with collision through `DcsGeometry.localParts`.
