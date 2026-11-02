# Cooling tower defect follow-up

Code: `dd71de17` on `kimi/water-cooler-fixes`, based on `codex/water-cooling-tower` at `2433b1355`; 2026-10-30. Unmerged, no version bump.

## Expected behaviour

- For a fixed tower, fan power depends on fan speed, not the solved air or water state; the fan laws give a cubic speed relation.
- Both tower screens show the accepted basin bottom pressure in the player's pressure unit.
- A wet basin exchanges sensible heat without evaporating; sprayed fill remains the only evaporative loss.
- A natural-draft tower accepts pure liquid water at 80 °C and cools it, including during empty-basin startup.

## What the code did instead

- `TowerTransfer.fill` multiplied fan pressure by the solved dry-air flow, so water loading and inlet temperature changed the reported power at constant speed.
- `FluidDeviceScreen` omitted the already-published `FluidView.Tank.bottomPressure` from tower meters.
- `TowerTransfer.surface` evaporated or condensed basin water and booked its latent enthalpy in addition to sensible exchange.
- No separate natural-draft admission defect was reproduced on the base: direct transfer, coupled spray and empty startup all accepted 353.15 K water. Atmospheric decompression is isenthalpic, so its small temperature change is not an inlet-temperature rejection.

## Correction for owner validation

- Fan power now applies the fan laws at the declared full-speed air loading of 2 kg/(s m²): fixed ambient density and geometry leave power proportional to speed³. Natural-draft power remains zero.
- Both tower Overview pages read bottom pressure from the scheduled tank snapshot, immediately below basin temperature. Empty basins display `—`.
- Basin surface exchange now has zero evaporation and latent duty; warm and cold sensible exchange remain. Fill evaporation is unchanged.
- No temperature special case was added. Regression tests pin 80 °C natural operation over 40/60/80 m diameters, five loadings, a coupled network and empty startup.

## Verification

- Focused `CoolingTowerTest`, `CoolingTowerRuntimeTest` and `DeviceColumnTest`: passed in 18.745 s.
- `slowGates`: passed independently in 4 min 31 s. `git diff --check`: passed before the code commit.
- Quick suite: 1,833 tests ran with one failure, the pre-existing `EquipmentLayoutGoldenTest.everyLayoutIsTheRecordedOne`. Its goldens predate earlier nozzle/structure repairs; this task changes no geometry and does not approve moving nozzle pipes.
- Native GUI verification was left to the owner at their request. No client was launched in this follow-up, and no fluid GameTests were run.
- New tool folders: none; the existing `tools/cooling-towers/` lane is unchanged.

## Owner's GUI check

Open each tower's Overview and find **Bottom pressure** directly below **Basin temperature** in Readings. A wet basin should show top pressure plus hydrostatic head; an empty basin shows `—`. With 80 °C pure water connected to the natural tower, confirm positive inlet flow and cooling on the scheduled update.
