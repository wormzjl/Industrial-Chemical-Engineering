# Nozzle probe (casings without drivers)

Purpose: list which nozzle pipe blocks of placed pumps, compressors and turbines move when their drawn drivers leave them
(AGENTS.md: a moved nozzle pipe block needs the owner's approval).

Batch: `2026-11-03-rotating-machinery`, work package W4. Status: in use since 2026-11-03; run it again before any change that moves a machine's nozzles.

## How to run

1. Copy `NozzleProbe.java` to `src/test/java/com/wormzjl/icheme/science/equipment/` and run
   `./gradlew equipmentGates --tests "*NozzleProbe"`. It writes `tmp/rotating-w4/probe-new.txt`: one line per pump,
   reciprocating compressor, centrifugal compressor and steam turbine size, second size and support, with each nozzle's
   pipe block in the box (`ROLE=x,y,z`).
2. For the "before" side, run the same probe on the code before the change. For the turbines that is a scratch worktree
   at the batch's base commit (`803e3c5e4`), with the kinds `STEAM_TURBINE` and `CONDENSING_TURBINE` and the size loop
   over `minDiameter` to `maxDiameter` by `diameterStep` (that build has no `ProcessUnit.sizes`). For the casings it
   was the current code with the three old formulas put back: crank `.5 + .35 * b`, impeller `least`, centrifugal
   shaft `h + .05 + max(d, hm + .25)`.
3. Compare:
   - `perl compare-casings.pl before.txt after.txt`: per kind, the shapes whose pipes stay, shift together (the
     refresh absorbs that), or move apart; the shapes valid before and refused after.
   - `perl compare-turbines.pl turbines-before.txt turbines-after.txt`: back-pressure turbines' exhaust beside their
     steam inlet; condensing turbines' steam inlet shift.
   - `perl compare-layouts.pl old-layouts.txt new-layouts.txt`: the layout table (`EquipmentLayoutGoldenTest`) before
     and after, the new lines' `duty=` stripped.
4. Remove the probe from `src/test` again.

## Entry points

- `NozzleProbe.java`: a JUnit class in package `com.wormzjl.icheme.science.equipment`,
  one test that writes the probe file.
- `compare-casings.pl`, `compare-turbines.pl`, `compare-layouts.pl`: `perl <script> <before> <after>`.

## History

- 2026-11-03 (W4): first sweep found the 0.5 m crank moving one header of 515 of 1,570 reciprocating-compressor shapes;
  the bottles' reference height (0.5 + 0.35 b) fixed it: every pump, compressor and centrifugal-compressor nozzle stays.
  Turbines (owner-approved, D5): 6 of 25 back-pressure sizes keep both nozzles, 19 move the exhaust one block; every
  condensing turbine's steam inlet moves 5 to 7 blocks across and 3 to 6 up, its four condenser nozzles go.
