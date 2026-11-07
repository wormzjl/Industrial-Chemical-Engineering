# Simulator sizing, control and equipment probes

Batch: [2026-11-06-simulator-sizing](../../documentation/2026-11-06-simulator-sizing/README.md), prepared for 0.22.0; not merged.

This folder retains the batch's offline measurements, model authoring references and software renderers. These are investigation tools, outside every production and gate source set. The product and permanent tests remain in `src/main`, `src/test` and `src/fluidGameTest`.

## Entry points

- `CableStress.java`: one-run data/power graph, route and FE checks, CPU/allocation/heap measurements, with the earlier FE loop as a bounded comparison. `representative` uses well-distributed UUIDs; the default also includes deliberately colliding identities. The earlier-loop comparison is historical, not an alternative shipped implementation.
- `PowerLookRender.java`: current data/power conduit software views, including sparse identification bands.
- `StationRender.java`: constructor/simulator views using the current authoring sources in `tools/pump-sprites/`.
- `StationModels.java`, `check_models.py`: station model/collision/resource/facing probes.
- `archive/`: original one-off probes, texture generators, scripts and experimental patches, grouped by their original task folder. `source-inventory.tsv` records the original path and byte count. These sources describe several intermediate revisions; do not apply an archived patch to current code without reviewing it.

## Run from the repository root

All outputs belong in an indexed `tmp/<name>/` folder. For Java tools that import game types, use the development runtime classpath and compiled main classes/resources (the archived task's run script shows its historical classpath). Compile with `javac -proc:none -cp <runtime-classpath> -d tmp/<name>/classes <source>` and run with that directory plus the same classpath. Set Java's temporary directory inside that task folder.

```text
java -cp <probe-and-runtime-classpath> CableStress tmp/<name> representative
java -Djava.awt.headless=true -cp <probe-and-runtime-classpath> PowerLookRender tmp/<name>
python tools/simulator-sizing/check_models.py
javac -d tmp/<name>/classes tools/pump-sprites/ConstructorAssets.java tools/pump-sprites/SimulatorAssets.java tools/simulator-sizing/StationRender.java
java -Djava.awt.headless=true -cp tmp/<name>/classes StationRender src/main/resources/assets/icheme tmp/<name>/constructor constructor
```

Run timing probes alone. A software render does not qualify live Minecraft lighting or chunk synchronisation. The one final integration lane remains `tools/merge-lane/lane.sh`; this folder does not duplicate it.

## Evidence and history

- 2026-11-06–07: sizing/crossflow, tank access, rotating equipment, wired controls, shared conduit placement, FE supply, models and GUI investigations. Product work ends at `c45cea075` before merge preparation.
- 2026-11-07: archived 174 source/script files from the batch's indexed scratch folders; retained the originals because the branch is unmerged. No production helper was detached: the reachability audit found 638 of 638 production classes reachable.
- Figures and tables: main checkout `research/2026-11-06-simulator-sizing/evidence/`. Compressed logs: main checkout `tools/simulator-sizing/logs/simulator-sizing-logs-2026-11-07.tar.gz`. Both remain local and ignored. The batch review distinguishes historical measurements from the final verification lane.
