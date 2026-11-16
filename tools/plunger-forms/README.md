# plunger-forms

Purpose: option sheets for an equipment model's form. `PlungerForms` draws candidate forms of a unit with the kit's own
parts on a host shape and writes their polygons; `render.py` turns them into flat option sheets (one scale per row, a
1.8 m figure). Kept as the tool for the next model choice between forms.

Batch: `2026-11-15-supercritical-fluids`, stage S8 (the plunger pump; `PLUNGER_PUMP_PLAN.md`,
`S8_PLUNGER_PUMP_REVIEW.md`). The owner chose form A, the monoblock, from the sheets (2026-11-16); the built kind
`PLUNGER_PUMP` (`PlungerPumpDesign`) carries it with the main thread's shaft fix and longer necks.

## How to run

From the worktree root (Git Bash), after `./gradlew --offline testClasses --max-workers 4`:

    CP="build/classes/java/main;build/resources/main;<gson 2.10.1>;<ejml 0.44.0 jars>"
    mkdir -p tmp/<name>/classes tmp/<name>/geometry
    javac -cp "$CP" -d tmp/<name>/classes tools/plunger-forms/src/com/wormzjl/icheme/science/equipment/PlungerForms.java
    java -cp "tmp/<name>/classes;$CP" com.wormzjl.icheme.science.equipment.PlungerForms tmp/<name>/geometry

`PlungerForms` is in the mod's package to reach package-private parts of the kit; it compiled against the S8 commits
(`dc84378ea` and before). It writes `<form>_<m³/h>.json` (polygons with a material each). Then, with an authoring
Python that has numpy and Pillow:

    cp tools/plunger-forms/render.py tmp/<name>/ && python -I tmp/<name>/render.py

`render.py` reads `geometry/` beside itself and writes the sheets (`forms-30.png`, `form-a.png`, `forms-sizes.png`) there. Its
z-buffer is the one of `tools/equipment-drawings/rendering.py` with a paint-like palette. A new unit needs new `Dim`
rules and form methods in `PlungerForms`, and its materials in `render.py`'s `BODY` and `COLOURS`.

`tools/supercritical-probes/s8/` holds `PlungerExport` (the built design's polygons in this format) and `compare.js`
(the built design against a scratch form).

## Entry points

- `src/com/wormzjl/icheme/science/equipment/PlungerForms.java`: `main(out dir)`, `write(file, unit)`, forms A (`monoblock`), B (`pots`) and C.
- `render.py`: `sheet(title, note, rows)`, `colour(material, normal)`.

## History

- 2026-11-16: written in `tmp/plunger-forms/` for S8 (three forms at 30 m³/h, then form A at the four models); kept at the
  merge preparation. The renders and the geometry are listed for `research/2026-11-15-supercritical-fluids/` in the batch
  README; the compiled classes stayed in `tmp/`.
