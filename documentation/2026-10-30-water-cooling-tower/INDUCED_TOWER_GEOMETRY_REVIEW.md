# Induced-draft tower geometry follow-up

Code: `98789eaa`, `44044ff2` on `kimi/water-cooler-fixes`; 2026-10-30. Unmerged, no version bump.

## Expected behaviour

- Every induced-draft support leg reaches the basin floor and remains visible and solid below the basin rim.
- The square fan deck and the guarded fan inside its cowl collide over their full rendered extent, without collision projecting beyond the cell.

## What the code did instead

- Leg boxes began at `y = 3`, the basin rim, leaving their lower 3 m absent.
- Only the circular fan cowl had collision. The first repair covered the square deck but left the fan centre pass-through; the owner's screenshot identified the remaining defect.

## Correction for owner validation

- The same leg boxes now begin at `y = 0` and retain their existing upper ends and sections.
- A 0.125 m (`Access.DECK`) annular solid follows the square deck, clipped to the cell bounds.
- A cylinder of radius `0.35 × cell width` fills the cowl from deck level to the grille, covering the rotor and grille for collision without changing the rendered fan or airflow model.
- No nozzle, saved shape field or occupied-cell layout changed. Earlier nozzle entries were not approved or rewritten.

## Verification

- At `98789eaa`, the geometry regression failed before the correction and `CoolingTowerGeometryTest` passed 5/5 afterward. `slowGates` passed independently; software `iso`/`top` renders were inspected. The quick suite's sole failure remained the known older tower layout golden.
- At `44044ff2`, `compileJava` and `compileTestJava` passed. The new assertions cover fan collision at the bottom and top of the cowl.
- The fan follow-up's tests were not executed because the owner's dev client was running. Its deterministic layout probe gave unchanged shell counts/stamps for all three induced sizes, so the layout table needs no further edit.
- No client was launched by the agent; the owner performs the in-game checks. `git diff --check` passed before the code commit. New tracked tools: none.
