# supercritical-probes

Purpose: the measurement probes and number scripts of the supercritical batch, one sub-folder per stage. Each one
measured a number a stage review states (before and after a change, a seam, a layout, a GameTest's expected state). None
is run by Gradle or by a gate; the gates of record are the batch's tests (`G3F11SupercriticalRegionTest`,
`DenseFluidGatesTest`, `DenseFluidSuctionTest`, `PlungerPumpMoverTest`, the dense GameTests).

Batch: `2026-11-15-supercritical-fluids` (`documentation/2026-11-15-supercritical-fluids/`, branch
`claude/supercritical-fluid-support-d0ffa4`). The probes' outputs, as each stage recorded them, are listed in the batch
README's open items for `research/2026-11-15-supercritical-fluids/`; the reviews quote the numbers.

## How to run

The Java probes are not compiled by Gradle. From the worktree root (Git Bash), after a Gradle compile
(`./gradlew --offline testClasses --max-workers 4`), compile one stage's `src/` against the built classes and the
science libraries, and run a probe by its class name:

    CP="build/classes/java/main;build/classes/java/test;build/resources/main;<gson 2.10.1>;<ejml-core, ejml-ddense, ejml-dsparse 0.44.0>"
    mkdir -p tmp/<name>/classes
    javac -cp "$CP" -d tmp/<name>/classes $(find tools/supercritical-probes/s4/src -name '*.java')
    java -cp "tmp/<name>/classes;$CP" com.wormzjl.icheme.science.fluid.network.S4SharesProbe > tmp/<name>/shares.txt

The jars are in the Gradle cache (`~/.gradle/caches/modules-2/files-2.1/`); `build/moddev/*LegacyClasspath.txt` lists
them. The class path uses `;` (Windows): keep it quoted. A probe in a mod package (`com.wormzjl.icheme...`) reads
package-private members, so it compiles only against the commit it names or one with the same members.

The Python scripts need CoolProp 8.0.0 (`tools/coolprop-parity/README.md`, "Environment"):

    tmp/coolprop-venv/Scripts/python.exe -I tools/supercritical-probes/dense-pump/pump_numbers.py

The Node scripts run with `node <script>` from the worktree root, with the arguments their header gives.

## Entry points

| Folder | File | What it measured | Review |
|---|---|---|---|
| `s2/src` | `DenseLawProbe` | The dense-fluid laws on the network package (viscosity, film, acoustic limit, heat capacity ratio) | `S2_NETWORK_REVIEW.md` |
| `s2/src` | `SweepProbe` | `DenseFluidGatesTest`'s sweeps first: each law's largest neighbour change, the slot line | same |
| `s2/src` | `SlotRuleProbe`, `SlotAfterProbe`, `MixtureSlotProbe` | The slot rule by density (pure and mixtures), the states that move | `S2C_NETWORK_REVIEW.md` |
| `s2/src` | `SlotReaderProbe`, `ZoneEdgeProbe` | What still reads the slot; a dense CO2 pump's suction edge | same |
| `s2/src` | `CampaignDrawProbe` | Whether the hold-fuzz campaigns draw dense states | same |
| `s2/src` | `LoopLayoutProbe` | The dense GameTests' loop layout and volumes | same |
| `s2/src/idlen` | `IdentityLength`, `RevisionLength`, `FirstLiquid` | The fluid identity's and the property revision's length against the NBT limit (S3 fix) | `S3_DATA_REVIEW.md` section 4 |
| `s2` | `loop_estimate.py`, `letdown_check.py`, `nozzles_check.py` | CoolProp estimates of the three dense GameTests' expected states | `S2C_NETWORK_REVIEW.md` section 6 |
| `s3/src` | `ReStampExample` | Read, stamp, write: the bundled simulator examples on a new data identity; `install.py` puts its output in place | `S3_DATA_REVIEW.md` |
| `s4/src` | `S4SharesProbe`, `S4SeamProbe`, `S4SheetProbe` | A pump's and a compressor's shares and E across reduced density, the seam, the simulator's readers, before and after | `S4_MACHINES_REVIEW.md` |
| `s4/src/s4` | `CompressorLawProbe` | The compressor's head law against the Helmholtz reference | same |
| `s4` | `compare.mjs` | Two `S4SharesProbe` outputs side by side: the fields that moved, and whether a non-dense row moved | same |
| `s4b/src` | `S4bProbe`, `S4bSidesProbe`, `S4bPumpProbe`, `S4bSheetProbe` | E with the flash test, the critical isenthalp's two sides, a pump's own weight 0.5 to 1.0, the sheet's readers | `S4_MACHINES_REVIEW.md` (S4b sections) |
| `s5` | `DenseProto.java.txt` | The `GuiPrototypes` block of the S5 renders (a CO2 tank at four densities, a pump at two); paste into `tools/heat-gui-renders/` | `S5_PRESENTATION_REVIEW.md` |
| `s8/src` | `PlungerExport`, `ScrewExport` | The built `PLUNGER_PUMP` design's polygons, in `tools/plunger-forms/`'s format | `S8_PLUNGER_PUMP_REVIEW.md` |
| `s8/src` | `ArrowProbe`, `ArrowProbe2`, `ArrowProbe3` | Each plunger nozzle's neck and whether `PortMarkings` draws its arrows | same |
| `s8` | `Bores.java`, `Cells.java` (default package) | The nozzles' bores and the cells of the four models | same |
| `s8` | `compare.js` | The built design's polygons against the scratch form A (counts by material, bounds, text) | same |
| `merge` | `digest.js` | The engine digest in record mode against the reference: the differing lines, and whether each differs only by the grade token | `MERGE_MAIN_0_33_REVIEW.md` section 7 |
| `merge` | `tokens.js` | The revision-token table of the material records both sides of a merge changed | same |
| `dense-pump` | `pump_numbers.py` | An isentropic pump step on dense fluids against the mod's `rho_s g H` | `DENSE_PUMP_RESEARCH_REVIEW.md` |
| `dense-pump` | `pd_numbers.py` | A reciprocating pump's clearance loss on a compressible fluid | `PD_PUMP_RESEARCH_REVIEW.md` |
| `p3/src` | `P3FormsProbe`, run by `p3/run.sh coarse\|dense tmp/<name>` | The column feed flash's forms F1, F2, F3 and D, the production flash (classification by the stability test) on the grids of `COLUMN_FEED_REVIEW.md`, against the frozen flash, the D1 flash and the engine; its dense output gives `V3FeedFlashStabilityTest`'s states. Batch `2026-11-16-supercritical-followups`, base `3a48e08b4` | `2026-11-16-supercritical-followups/P3_COLUMN_FEED_REVIEW.md` |
| `p3/src` (lane fixes) | `P3WetProbe`, run by `p3/run.sh wet tmp/<name>`; `V3FeedFlashD1`, `V3FeedFlashP3` (frozen copies) | The wet grid (ethanol/water, ethanol/water/n-hexane, Tia Juana crude with water; 570 states) and the dry two-liquid scan (207 states): the frozen, 0.34.0, lane and production flashes per state, the guarantee counts (`LANE_FIXES_REVIEW.md`) |

The scripts keep the `tmp/` paths they ran with (`tmp/supercritical-*`, `tmp/plunger-forms/`); give the new paths when
you run one again. None was recompiled after its stage's last commit.

## History

- 2026-11-16: `p3/` added by package P3 of batch `2026-11-16-supercritical-followups` (the column feed flash's forms).
- 2026-11-16: `p3/` gains `P3WetProbe` (`run.sh wet`) and the frozen copies `V3FeedFlashD1` (0.34.0) and `V3FeedFlashP3`
  (the lane's flash) for the lane fixes (`LANE_FIXES_REVIEW.md`); `P3FormsProbe` counts engine splits answered as one phase.
- 2026-11-16: gathered from the batch's `tmp/` folders at the merge preparation (the cleanup of `merge-prep`). Edit
  scripts, Gradle logs, stored results, compiled classes and outputs stayed in `tmp/`.
