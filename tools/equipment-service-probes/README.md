# Equipment service probes

Purpose: Gradle-free probes of service cases, nozzle necks, shaft ends and drawn area on the gated equipment shapes, run on compiled classes. They decided the fixes of batch `2026-11-09-main-gate-fixes` (documentation/2026-11-09-main-gate-fixes/README.md) and checked that only the failing shapes moved.

## How to run

The probes read the shape lists of the equipment tests (`ProcessUnitTest.everySize`, `ShapeRasterizerTest.shapes`/`SHAPES`, `EquipmentLayoutGoldenTest.shapes`), so the worktree needs compiled `main` and `test` classes (any Gradle run of `equipmentGates` leaves them). From the worktree root, in Git Bash:

```bash
CP="build/classes/java/main;build/classes/java/test;build/resources/main"
mkdir -p tmp/service-probes
javac -nowarn -cp "$CP" -d tmp/service-probes tools/equipment-service-probes/src/com/wormzjl/icheme/science/equipment/*.java
java -cp "tmp/service-probes;$CP" com.wormzjl.icheme.science.equipment.Cases > tmp/service-probes/cases.txt
```

To compare two code states, run a probe on each state's classes and diff the outputs.

## Entry points

All are in package `com.wormzjl.icheme.science.equipment` (they use package-private API).

- `Cases`: one line per gated shape (758): the box, the terminal cell and every service enclosure's corners.
- `Polys`: per shape, the service polygons' area and the polygons outside the block box.
- `AreaAll`: the facet-area check of `ShapeRasterizerTest.surfacePiecesAddUpToTheFacetedArea` on `SHAPES` plus `everySize`.
- `AreaProbe`: the same check on the 1 m boot drum, piece by piece.
- `Golden <file>`: today's `EquipmentLayoutGoldenTest` lines against a recorded line file; prints removed and added lines.
- `Probe`: motor and generator terminal cells against their layout cells.
- `Probe2`: nozzles whose neck at the largest pipe is not clear (`ProcessUnitTest.NozzleRoom`).
- `Probe3`: the 0.5 m vertical drum's nozzles and tangents.
- `Shafts`: each shaft end's free block, on `everySize` and a pump assembly.
- `Chan`: the paint channel of each `Mesh.Material`.

## History

- 2026-11-09: written by the main gate fixes session in `tmp/gate-fixes/probe/`; moved here at the 0.23.1 merge preparation (tooling cleanup). Compiled and run against the merge branch's classes then.
