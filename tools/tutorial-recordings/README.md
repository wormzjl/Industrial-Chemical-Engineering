# Tutorial recordings

Batch `2026-11-12-tutorial-system` (P2a). Offline tools that record, from the mod's own data, what a tutorial card
draws, so a card reads one small asset once and computes nothing while it draws.

## CrudeBarrel

Writes `src/main/resources/assets/icheme/tutorial/recordings/crude_barrel.json` for the cards `barrel` and `products`:
per grade (medium, light, heavy, oil sand), the components in boiling order (mass share, colour from the component
record's `appearance.color`, the range it boils over), the six bands' shares (gas below 20 °C, naphtha 20 to 180,
kerosene 180 to 240, diesel 240 to 360, gas oil 360 to 565, residue above), the share boiled off below every 5 K from
250 K to 900 K, and the sulfur (`provenance.specification.sulfur_mass_percent`). The computation is
`BarrelData.compute` in `science/tutorial/cards/`; `CrudeBarrelTest` recomputes it and holds the asset equal.

Rules: amounts by mass; a pure compound boils at its normal boiling point (its property record); a petroleum fraction
boils evenly between `cut.lower_kelvin` and `cut.upper_kelvin`, and one with no upper bound boils above its lower one.

Run after a change to an assay, a component's cut or colour, or a light end's boiling point:

```
./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordCrudeBarrel
```

It prints the band shares and writes the asset; commit the asset.

## StillRun (P2b)

Writes `src/main/resources/assets/icheme/tutorial/recordings/still_run.json` for the film `still_run` and the card
`pot_temperature`. It runs the still law (`StillRunRecorder`, test source) on 500 L of medium crude, 60 % full and
flashed at 25 °C, with a solid fire at full firing and design cooling water at 25 °C. The run goes until the law
stops it: boiled dry, overheated with no heat, or refused. The file holds at most 240 points: time, pot and head K,
level, distilled and vented shares, the rate to the spout, the part coming over and the status. It also holds the lines
first drop, end of naphtha, end of kerosene (when reached) and end, each with process s, pot K and minutes of play at
the time factor 4, and the charge's part shares. `StillRunRecordingTest` reruns the law and holds the file equal
within 1e-9.

```
./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordStillRun
```

## StillTrays (P4b)

Measures and records chapter 3's still with a column: `still_trays.json` for the cards `reflux` and `compare`. The same
still as StillRun (`StillRunRecorder.run(stages, reflux)`, test source) as a pot still and with a column of
`StillTraysRecorder.STAGES` (4) trays at reflux ratios 1 and 2. For each form it prints how the column answered on its
boiling steps (solved itself, dry: the law's single stage, fallback: the solve refused) with their pot temperature
ranges, and the two cuts: the first to the charge's gas and naphtha taken overhead (18 %), the second on to its
kerosene (29 %), each with its liquid's six parts, pot temperature and minutes of play. `StillTraysRecordingTest` reruns
the law and holds the file equal within 1e-9.

```
./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordStillTrays           # print only
./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordStillTrays -Pwrite   # and write
```

## SmallUnit (P4b)

Records chapter 4's small crude unit: `small_unit.json` for the card `balance` and the steps `balance` and `heat`. It
runs the bundled example "Small crude unit" (`SmallUnitRecorder`, test source) as bundled (H-101's outlet 390 °C) and
with the outlet at `-Psecond=K` (default 643.15, 370 °C), and writes each run's status, crude in, the top (off-gas and
naphtha), side and bottom products by mass without water, their shares and the heaters' duties. `-Ptries=` prints other
outlets only. `SmallUnitRecordingTest` (holdGates) reruns both and holds the file equal within 1e-9.

```
./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordSmallUnit           # print only
./gradlew --offline --init-script tools/tutorial-recordings/record.init.gradle recordSmallUnit -Pwrite   # and write
```
