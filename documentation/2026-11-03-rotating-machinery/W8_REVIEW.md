# W8: process simulator support, review

Commits `13a2bda76` (the laws), `3d615e6a1` (turbine sizing, drawing-only drivers and the duty table) and `7de10b5ac` (the scrolling glass). They implement `W8_BUILD_PLAN.md` form A (D42), D43 and D44. Three Opus medium subagents built them, and they are reviewed here.

## 1. What was built

- **Pump tile** (`MoverUnit`). Shaft power = Q Δp / η, with η = `CentrifugalPump.rated(Q, H).efficiency()` (Corripio). The whole shaft power heats the discharge.
  - Readings: hydraulic power, efficiency, a proposed 2-pole motor (`Motor.ratingFor`, 20 % spare) with its IE3 efficiency, electric power, and FE/t on the door.
  - Worked case, 100 m³/h at 80 m: hydraulic 21.8 kW, η 0.702, shaft 31.1 kW, motor 45 kW, η 0.940, electric 33.0 kW, 661 FE/t.
- **Compressor tile.** It shows the same driver readings. Its shaft power is unchanged, because the world's closure already divides the polytropic work by 0.78 (A158). The plan's "discharge runs hotter" was wrong.
- **Steam turbine tile** (`TurbineUnit`, modelled). The isentropic work comes from the world's `FlowControl.Turbine` closure. η is `typicalEfficiency` at the turbine's own power, found by fixed-point passes, and the exhaust is at a set pressure (default 3.5 bar).
  - Worked case, 2 kg/s at 40 bar and 400 °C to 4 bar: a drop of 530 kJ/kg (steam tables give 528), η 0.536, 568 kW, 12.7 kg/kWh.
- **`TurbineSizing`** (D43). Auto takes the smallest `MachineDuty` power that is at least 1.2 × need, the lowest steam class that holds the inlet, and the nearest exhaust. For example, 850 kW at 40 bar to 3.5 bar gives 1,120 kW, HP. The constructor's own refusals read red. `TURBINE_SPARE` reads a ratio (A166).
- **Drivers.** The motor, generator and gearbox are drawing-only on the turbine's lit tile. `FlowsheetPaletteCoverageTest` gains `drawnOnARunningTile` (A165), and `SizingCoverageTest` gains a turbine sample (A164).
- **Column door** (D43, D44). The Equipment glass shows one banded pump duty table (flow, head, shaft power, NPSH3, motor) from Size all's pump ratings, with dashes and a note before any rating. The connection list follows it.
  - The glass scrolls with its own bar. A row whose columns do not fit stacks them under their headings (`SheetBuilder.grid`).
  - The Results page's sheets scroll the same way (A171).
- **Pump kind.** The Standard / In-line keys and the Reflux pump group are gone. The saved field in the column design tags is kept (append-only rule) and new designs write STANDARD (A167). The assembly still takes either pump.

## 2. Tests

- **Simulator references re-recorded,** each by about 0.08 K, because the crude pump P-101's 23 kW of losses now heat the crude:
  - `SizeAllSampleTest` (H-101 78.38 → 78.36 MW);
  - `UnitSizingSampleTest.e101` (81.8 → 81.7 K);
  - `FlowsheetColumnTest` pumparound case (S-4 102.4 → 102.48 °C, furnace 98.10 → 98.06 MW);
  - `FlowsheetUnitsTest` (the pump's power is now its shaft power).
- **`SentDesignTagsTest`** had been failing since W4 added the shape's duty field: an old image reads back with its default duty. The test now strips that, as it already does for the trim count. My affected-test selection after W4 had missed it.
- **Affected sets run here:**
  - 468 tests on `3d615e6a1`, all passing except the two expected zh_cn cases;
  - 123 tests on `7de10b5ac`, with no failures;
  - the subagents' runs: `CalculatorColumnTest` 21, `SimulatorColumnsTest` 7 of 9, 64 hold-gate tests, `TurbineSizingTest` 4, `LimitTest` 12.
- **Expected failures** until the translation commit: `SimulatorColumnsTest.everyDoorFitsAtEveryWidthInChinese` and `everyPanelAndPageColumnFitsInChinese`.
- **GameTests:** fluid 45 of 45 and column/simulator 11 of 11 (run `w8door1`).

## 3. Readings and open items

- **A159:** FE/t on the door uses the client's `joulesPerFE`. A run's result does not depend on the config.
- **A160:** the compressor's proposed motor is 2-pole.
- **A161:** the turbine is rated at its own power.
- **A162:** the turbine tile refuses only an exhaust that is not below the inlet, as the world does.
- **A163:** the driver is a reading on the tile door, not a Sizing page row.
- **A168:** a narrow glass stacks each pump into its name over five readouts.
- **A172:** Stream sits just before the numbers.
- **Open, A173:** the Feed page's stream glass (`StreamDoors`, shared by every stream door) still drops rows and cuts cells at small sizes. This was there before W8 and is not changed.
- **Open, A174:** at 380 wide the Equipment glass content is 624 px, because each pump stacks. A more compact pump table is an option for the owner.
- **Open, F2:** an old sheet holding `reflux_pump: IN_LINE` still draws an in-line pump in the schematic. Sheets have no compatibility work.

Code guide: no change yet. The simulator chapter (units, sizing, the column door) changes at the merge refresh.
