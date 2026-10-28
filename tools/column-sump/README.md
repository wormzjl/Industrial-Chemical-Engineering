# column-sump

Purpose: the probes, sweeps and software renders of the column sump enlargement: the sump (bottom tangent to bottom tray) as a stack of clearances instead of max(2 m, D/2). Batch `documentation/2026-10-27-column-sump/` (`COLUMN_SUMP_SURVEY.md`, `COLUMN_SUMP_REVIEW.md`, `figures/`).

Nothing here was detached from a tracked path: every probe ran from the worktree's scratch `tmp/sump/` and was never on a tracked path, so no re-attach patch applies. `patches/sump-rule-property.patch` is a measuring aid, not removed code (below).

## Running

From the worktree root, without Gradle (another session held the first Gradle lane). The science tree imports nothing from Minecraft, so a javac class path from any checkout's `build/moddev/clientLegacyClasspath.txt` serves (gson, ejml, annotations; the JUnit API for `Table`):

```
CP="$(cat tmp/sump/cp.txt)"   # the jars of build/moddev/clientLegacyClasspath.txt, ';'-joined, plus annotations 24.1.0 and junit-jupiter-api
find src/main/java/com/wormzjl/icheme/science -name '*.java' > tmp/sump/science-sources.txt
javac -proc:none -encoding UTF-8 -cp "$CP" -d tmp/sump/after-classes @tmp/sump/science-sources.txt
javac -proc:none -encoding UTF-8 -cp "tmp/sump/after-classes;$CP" -d tmp/sump/after-probe tools/column-sump/*.java tools/multi-machine-framework/f3-nozzle-rows/NewSweep.java tools/multi-machine-framework/f3-nozzle-rows/RowSweep.java src/test/java/com/wormzjl/icheme/science/equipment/EquipmentLayoutGoldenTest.java
java -cp "tmp/sump/after-probe;tmp/sump/after-classes;src/main/resources;$CP" com.wormzjl.icheme.science.equipment.<Probe> <args>
```

Use `MSYS_NO_PATHCONV=1` in Git Bash. `Stack.java` is in the default package: `java tools/column-sump/Stack.java ...`. The baseline ran the same lines on `995dbd9c`'s sources into `tmp/sump/base-classes`.

## Entry points

- `ColumnRender.java`: software render of columns at one scale, bottoms aligned (adapted from `../multi-machine-framework/w3-arrows/ArrowRender.java`): real shell cell polygons, sprites, shade, culled back faces, depth buffer, a pipe stub at every port. Args `out.png pxPerMetre view spec...`, view `front|back|left|right|iso|iso3|low`, spec `diameter:trays:config[:skirt]` with `NewSweep.trays`' configs (`kettle`, `steam+draw`, `kettle+steam+draw+PA`, `full`). Prints each nozzle's height over the bottom tangent.
- `PerDesign.java`: NewSweep's 315 designs, one line each: refused and why, or every side nozzle's role, side and height over the bottom tangent. `compare.js <base> <after>`: designs newly refused or built, each nozzle's move less the sump's growth, side changes.
- `Sumps.java`: the return size and the sump height at every half-metre diameter.
- `Table.java`: writes the layout table (`EquipmentLayoutGoldenTest.line` over its shapes) to a file, to re-record `src/test/resources/compat/layouts/layouts.txt` without a failing Gradle run.
- `Crowded.java`, `Stripped.java`: the failing tests' designs (`ColumnStructureTest`'s crowded 4 m column and the 2 m, 6-tray column with sump steam, with and without a kettle): every side nozzle's side and height, placed or not, and the platform levels.
- `Stack.java`: stacks render sheets with a label each (the batch's `figures/sump-before-after.png`).
- `patches/sump-rule-property.patch` (against `995dbd9c`): `bottomSpace` read from the system property `sump.rule` (`a,b[,cap[,floor]]`: max(floor, a + b·D) to the next half metre, at most cap; `base` the old rule), compiled alone into a classes folder put first on the class path, to compare candidate rules in one build.
- Also used, unchanged: `../multi-machine-framework/f3-nozzle-rows/NewSweep.java` and `RowSweep.java` (refusals, nozzles off their band, ladder and deck clashes).

## Results

- `results/sweep-before.txt`, `sweep-after.txt`: NewSweep at `995dbd9c` and on the change (whole metres): 103 → 93 refused, off their process height 1 → 1, cage clashes into a pipe block 0 → 0, walks parted 0 → 0.
- `results/per-design-base.txt`, `per-design-after.txt`: PerDesign on both; `results/compare.txt`: `compare.js` on them: 0 newly refused, 10 newly built, every tray nozzle moved by exactly the sump's growth. The half-metre stack (first form) also had 0 newly refused on this grid, but 32 pumparound returns changed side and `ColumnStructureTest`'s crowded design lost a draw.
- `results/sweep-rule-*.txt`: NewSweep under the property patch: `base`, `2.5,0.3` and `3,0.4` (forms compared before the stack was chosen).

## History

- 2026-10-27: written for the sump enlargement (owner request "Enlarge the bottom sump of the distillation column so it looks more real, find some engineering drawings for reference").
