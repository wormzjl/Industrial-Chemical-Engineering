# 2026-11-18-co2-column-input

Status: in progress since 2026-11-18, on `claude/co2-column-input-c69ef8` (not merged).

Purpose: the gas intake's air holds carbon dioxide, and a column whose feed holds CO2 solves; the crystal equilibrium of
its products and its coldest tray decides whether dry ice forms.

## The owner's request (2026-11-18)

"Add CO2 in air intake composition and make it a valid column input (if it is not, IIRC currently there is some weird
hard lock)". The column rule (option A with the coldest tray) is the main thread's design call; the owner can veto it at
the merge.

## Documents

- [CO2_COLUMN_INPUT_REVIEW.md](CO2_COLUMN_INPUT_REVIEW.md): the expected behaviour, the lock on each path, the change,
  the moved references, the owner items, what is not verified.

## History

- 2026-11-18: build (`4ddb217d1`, `f52234f3a`, and the tests and documents after them). `Air.DRY` gains CO2 at 314 ppm
  (U.S. Standard Atmosphere 1976). `V3CatalogPropertyPackage` no longer refuses a package for its crystals. The rule is
  `ColumnSolve.frozen` on the products plus `ColumnSolve.coldestTray`, and `Frozen` and `column.frozen` name the node,
  the temperature and the crystal. 889 selected classes run; the review lists the moved references and three owner items.

## Code areas

- `science/fluid/network/Air`; `science/column/v3/thermo/V3CatalogPropertyPackage`; `science/plant/column/ColumnSolve`
  (`frozen`, `Solid`, `Frozen`, `coldestTray`); `science/flowsheet/unit/ColumnBlockSolve.frozen`;
  `science/material/CrystalPhasesUnavailable` (javadoc); `en_us.json` (`gui.icheme.flowsheet.column.frozen`).
- Tests: `ColumnCarbonDioxideTest` (new), `CrystalPackageConsumersTest`, `ColumnSolveTest`, `ColumnBlockSolidsTest`,
  `ColumnRunTest`, `GasIntakeTest`, `FluidFactsCompatTest`.

## Open items

- O1 (released air books CO2 as toxic) and O2 (the refinery package's advisory text) of the review, for the owner.
- The translation commit: the other languages read `column.frozen` with two arguments.
