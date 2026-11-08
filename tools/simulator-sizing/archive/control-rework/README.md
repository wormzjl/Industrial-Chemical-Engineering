# Superseded control geometry diagnostics

Batch: 2026-11-06-simulator-sizing, 2026-11-07. The maintained final probe is ../../ControlPortProbe.java.

- SeamProbe inspects raw PIPE facet edges. Its initial cell-polygon variant counted UV-tile subdivisions as unmatched edges; those counts did not prove a gap. The retained raw-facet version finds no hole in the tested two-arm transitions.
- NormalProbe checks repeated-vertex normal consistency; worst discrepancy was roundoff, not the reported disconnection.
- ControlPortRender made the initial two-arm software views. Its historical data_cable texture predates the shared power_cable finish; do not use those images to judge current colour.

Compile all three with the current runtime classpath, plus ../../PowerLookRender.java, into an indexed worktree tmp folder. Run com.wormzjl.icheme.science.equipment.SeamProbe/NormalProbe or ControlPortRender (the latter takes an output folder). They are offline tools, outside all gate/product source sets.

The actual reproduced defect is an offset tee/cross whose body ignored its endpoint coordinates; the final maintained probe and permanent ConduitGeometryTest cover it. The old production source is available from git show 4682156e:src/main/java/com/wormzjl/icheme/science/equipment/LineGeometry.java. No production helper was detached and no gate membership changed.
