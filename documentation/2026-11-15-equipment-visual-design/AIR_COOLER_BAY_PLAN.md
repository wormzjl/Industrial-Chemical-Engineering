# A larger air cooler bay (plan)

Date: 2026-11-15. Batch `2026-11-15-equipment-visual-design`, finding K16 of [VISUAL_REVIEW.md](VISUAL_REVIEW.md).
Status: planned, nothing built.

## 1. Owner request

2026-11-15: "make max size single AC model larger (as well as their duty) and correspond to realistic scale".

## 2. Expected behaviour

An API 661 air cooler bay has tubes 9 to 12 m long (to 18 m) and is 3 to 6 m wide. A bundle wider than about 3.5 m does
not travel by road, so a wide bay holds two bundles side by side under shared fans. A bay has two fans of 1.8 to 4.9 m,
their tips at 60 m/s at most, their inlets about 0.6 fan diameters or more above grade. Columns stand every 6 m or so.
The duty follows the bundle face: the game's air takes 3,500 W/K per m² of face, 70 kW/m² at a 20 K rise.

## 3. What the code does now

- `AirCoolerDesign`: `Sizes(1.5, 3, .5, COMPUTED, MAX_BAY_LENGTH, 3, MAX_SUPPORT, ...)`; `ProcessUnit.MIN_BAY_LENGTH = 2`,
  `MAX_BAY_LENGTH = 6`. The largest bay, 3 m by 6 m: face 12.2 m², two 1.9 m fans, 61 m² of bare tube, about 0.86 MW at
  20 K, 12 kW of fans.
- `UnitRatings.bundleFace` = (width - 0.22)(length - 1 - header); `fans` picks 1 to 3 fans by covered area;
  `fanDiameter` fits the ring (`RING_OD` 1.12) in width - 0.32 and in the fan's share of the length.
- `ProcessUnit.FanStack.of(bundle, fan)`: the plenum 0.2 + 0.4 fan deep, the ring at most `RING_DEPTH` 0.9 deep, the
  motor at most 1 m. `minSupport` is 3 for every size, `defaultSupport` 4.
- `build`: one bundle, a header box at each end, legs under the headers' ends only (`POST` 0.2 m), one inlet nozzle on
  the front header (`stub = .1 + .04 w`), fans turning at `FAN_SPEED` whatever their size.
- `HeatSides.airCoolerSurface` = 5 m² of bare tube per m² of face; `airCoolerPassage` counts tubes over width - 0.22;
  `fanPower` = 1 kW per m² of face at full speed.
- `ProcessUnit.bays()` lists every bay; `AirCoolerSizing.sizes()` builds the simulator's choices from it (fewest banks,
  then least face, then fewest bays).

## 4. Design

### 4.1 Sizes

- Width 1.5 to 6 m in 0.5 m steps; length to 12 m (`MAX_BAY_LENGTH = 12`). A shape field's range grows; no field changes
  meaning, so saved bays load as they are.
- Every size that exists today (width up to 3 m and length up to 6 m) keeps its mesh, its solids and its layout row.
  Each rule below says where it starts.

| Bay | Face, m² | Fans | Bare tube, m² | Duty at 20 K | Fan power |
|---|---|---|---|---|---|
| 3 by 6 (today's largest) | 12.2 | 2 of 1.90 m | 61 | 0.86 MW | 12 kW |
| 3 by 12 | 28.9 | 3 of 2.35 m (check) | 145 | 2.0 MW | 29 kW |
| 6 by 6 | 24.9 | 1 of 3.80 m | 124 | 1.7 MW | 25 kW |
| 6 by 12 (new largest) | 58.9 | 2 of 4.55 m | 294 | 4.1 MW | 59 kW |

The 6 m rows use the face of 4.2. The build prints the table from `UnitRatings` and `HeatSides` and corrects it.

### 4.2 Ratings (`science/equipment/design/UnitRatings.java`, `HeatSides.java`)

- A bay wider than 3 m holds two bundles: `UnitRatings.finWidth(width)` = width - 0.22, less 0.12 for the centre frames
  above 3 m. `bundleFace` and `HeatSides.airCoolerPassage` (its tube count) use it. Nothing changes up to 3 m.
- `fans` and `fanDiameter` do not change: they already give two 4.55 m fans to a 6 by 12 m bay (coverage 0.55).
- No rating constant changes. The duty and the fan power grow with the face.

### 4.3 Support and drive (`AirCoolerDesign.java`, `ProcessUnit.FanStack`)

- `minSupport(size, length)` = max(3, ceil(0.6 fan + 0.2 + 0.4 fan + 0.35 min(fan, 0.9))): the ring's inlet 0.6 fan
  diameters above grade. Today's fans (1.9 m at most) give 3. A 4.55 m fan gives 6.
- `defaultSupport` = max(4, minSupport).
- The fan's shown speed: `FAN_SPEED` up to a 1.9 m fan, then `FAN_SPEED × 1.9 / fan` (constant tip speed). Check
  `UnitDesign.soundPitch` and the fan loop for a read of `FAN_SPEED`; the sound must not change for today's sizes.
- `FanStack` does not change (the motor's cap of 1 m and the ring's 0.9 m hold at 4.55 m).

### 4.4 The model (`AirCoolerDesign.build`)

- Two bundles above 3 m wide. Per bay: two fin boxes and two header boxes at each end, each half the bay less the
  frames, with side frames at the centre line as at the sides. The plenum, the fans and the motors stay one set per bay
  over the whole width.
- Nozzles of a two-bundle bay: each bundle's header takes a stub (`.1 + .04 × w / 2`) into a manifold
  (`u.manifold`, as parallel trains have), the bay's inlet on the manifold's top and its outlet under the outlet
  manifold. A bank of two-bundle bays in parallel carries one manifold over all its bundles.
- Legs: a post line every 6 m at most along the tubes (a bay longer than 6 m gets one line at its middle, or two at its
  thirds above 12 m, which no size reaches), with the cross beam the header lines have. A bay wider than 3 m uses posts
  0.3 m square and beams 0.3 m deep. Both are solids; they exist only on new sizes.
- The motor beams stay one under each row of motors.
- No walkways or ladders: the review's open item 1 (access steel) is the owner's and is not decided.
- Detail by the batch's rules: no new nut polygons except the stubs' `flangeBolts`; fins stay the sprite.

### 4.5 Simulator (`science/flowsheet/sizing/AirCoolerSizing.java`)

- `sizes()` reads `ProcessUnit.bays()`, so the new bays join the choices with no code change. A duty that took several
  small bays may now take one large bay: the sizing results of the examples move.
- Report every sized air cooler that changes in the tests' examples, old and new (bay, bank, face). This is the expected
  effect; a result that gets more bays or more face than before is a defect.
- A column's air condenser (`ColumnComponents`, `ColumnBlueprint`) picks from the same list: report its changes the
  same way. A saved column design keeps the bays it holds.

### 4.6 What does not change

Saved shapes, the facts schema, wire formats, lang lines (the constructor's rows step the sizes generically; check
that no text states "3 m" or "6 m").

## 5. Work packages

**A-W1 Sizes, ratings, support** (`AirCoolerDesign`, `ProcessUnit` constants, `UnitRatings`, `HeatSides`)
- 4.1 to 4.3. Tests: `HeatSidesTest`, `UnitSizingTest`, `ProcessUnitTest`, `EquipmentShapeCompatTest`,
  `ConstructorColumnTest`, `HeatUnitPowerTest`. A new case pins the table of 4.1 and that every bay of today's range
  has today's face, fans, surface and support.

**A-W2 The model** (`AirCoolerDesign.build`)
- 4.4. Tests: `ProcessUnitTest`, `ShapeRasterizerTest`, `EquipmentLayoutGoldenTest` with three new rows (6 by 12,
  3 by 12, 6 by 6, each one bay); the three rows of today must not move. `EquipmentInstruments` and
  `EquipmentServices` place parts on the bay: run their tests if they load the design.
- Scenes `AIR_COOLER_MAX` (6 by 12), `AIR_COOLER_LONG` (3 by 12) and `AIR_COOLER_BANK_MAX` (6 by 12, two in parallel)
  in `EngineeringExport`; `update.ps1 -Only AIR_COOLER`; look at the iso, +X and -Z views beside today's 3 by 4 m bay.
- Polygons by material for the new scenes; today's scenes must count the same.

**A-W3 Simulator and documents** (`AirCoolerSizing` only if a rule must change)
- 4.5. Tests: `CoolerSizingTest`, `SheetSizingTest`, `SizingCoverageTest`, `FlowsheetUnitsTest`, `ColumnComponentsTest`,
  `ColumnBlueprintTest`, `CrudeUnitSampleTest`, and the others that fail to compile or load the bay list.
- A corpus image under `src/test/resources/compat/equipment/` with a 6 by 12 m bay and a 3 by 12 m bay.
- Code guide: `equipment-designs.md` (the air cooler's paragraph), `simulator-sizing.md` if a result rule is described.

All Gradle runs use `--tests` filters and `--max-workers 4`. No whole task, no repeated test.

## 6. Pitfalls

- `minLength` walks lengths up to `MAX_BAY_LENGTH` until the fans cover 0.4 of the face. Check that every width to 6 m
  has a least length, and that `check` refuses none of the listed bays.
- `Bank` allows 6 bays in parallel and 4 in series: a 6 by 4 bank of 6 by 12 m bays is 36 m by 48 m. Check the largest
  box against the structure limits (`EquipmentShape` sizes, the constructor's ghost) and report it; do not shrink the
  bank limits without the owner.
- The plenum's hopper (`u.transition`) spans the whole bay width above 3 m: its triangles get long. Look at it from
  below in the views.
- `Part.of("ac_fan")` and `ac_fan_ring` are Blender parts scaled by the fan diameter: at 4.55 m a blade is wide. Look
  at the rotor; do not add polygons to it.
- A bay's FE terminal sits at the foot of its front corner leg: it must stay on a post of the new leg grid.
- `ProcessUnit.java` and `AirCoolerDesign.java` carry R-W4's changes: build on a tree that has them.
- The fan sound's pitch may read the shown speed. Today's sizes must sound as they do.

## 7. Done

- The three packages pass their named tests; today's three layout rows are unchanged.
- The report holds the table of 4.1 as measured, the views, the polygon counts, the simulator's moved results and
  anything the plan had wrong.
- The owner has seen the views of the 6 by 12 m bay.
