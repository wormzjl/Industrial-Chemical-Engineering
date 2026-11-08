# W6b Create coupling: build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9`, with W6a merged (`d16ecab51`). Binds:
- plan section 8 (the compat module, the power rule and its draw), section 9 (the coupling's readings);
- D7 (one FE per tick trades for 34.13 su, so 1 su = 1.465 W), D8 (Create drives industrial shafts only);
- W3's trains, W7's machine pages, and the `gui-creation` skill.

## 1. Expected behaviour (the plant first)

- **What it is.** The coupling is a one-block driver. Create's rotation comes in on one face, and an industrial shaft end leaves the other face. The train turns at Create's speed times the gearbox ratio. Gearing changes speed, not power.
- **How much power it is.** A Create network's spare stress (capacity less load, in su) is spare power at 1.465 W per su:
  - the 30 kW pump train draws 20,500 su, which is 40 large water wheels (512 su each);
  - a 100 kW train draws 68,000 su.
  
  This is the D7 rate, so a Create-driven pump is a late-game build. No round trip through FE gains energy.
- **Speeds.** Create runs at 256 rpm at most. A 2-pole pump at 2950 rpm needs 11.5:1 from 256 rpm, or 46:1 from 64 rpm. Plan 5.3 allows up to 1:64 on a Create input; today `ShaftNetwork.MAXIMUM_RATIO` is 20. W6b raises the limit to 64 only for a gearbox whose slow line is driven by a coupling.

## 2. Form A, as plan section 8 decided (2026-11-03); owner pick D40

- **Driver.** A new `ShaftTrain.Drive.CREATE` runs the creative motor's step (`limited`):
  - its set speed is Create's present |rpm|;
  - its power limit is what the Create network has to spare, plus what the coupling already draws.
  - A train short of power slows by the damped step, showing `POWER LIMITED` (a slipping coupling). Create's network never overstresses.
- **Stress.** The coupling's Create stress impact follows the train's demand: `impact = min(demand, limit) / (1.465 W × |rpm|)`, in su per rpm.
  - It is set on the engine's schedule, from the published result (`KineticNetwork.updateStressFor`), never on the tick.
  - The other way, Create's speed and spare stress reach the engine as queued events, from `onSpeedChanged` and `updateFromNetwork`. A change smaller than the deadband wakes nothing.
- **Compat module.** `compat/create/**` holds the block, its block entity (a Create `KineticBlockEntity`), its Create renderer (Create's half-shaft) and a recipe under a `mod_loaded` condition.
  - It registers behind one guarded entry point, `ModList.isLoaded("create")`, and no other class names a Create type.
  - A world opened without Create loses its couplings, as it loses every Create block.
  - The new `ShaftNetwork.Kind.CREATE_COUPLING` and `Drive.CREATE` live in our own code, saved by name.
- **Page.** It is a W7 machine page with no switches; Create drives it. The status is `RUNNING`, `POWER LIMITED` or `NO ROTATION`.
  - Readings: Create speed (rpm), the train speed after the gearbox, stress drawn (su), the network's spare stress (su) and shaft power (kW).
  - Prototype first; you look.
- **Not built:** industrial shafts driving Create (D8), and a simulator tile, which comes with W8 like the other drivers.

## 3. Form B, simpler (proposed simplification)

- **What changes.** The coupling has a rated power that the player sets on its page, in kW. That rating fixes its Create stress impact, like any Create machine: `rating / (1.465 W × |rpm|)` su per rpm. Its driver step is the creative motor's, with the rating as its limit.
  - If the rating is more than the network gives, Create overstresses the whole network, as it does for any of its own machines. The train then stops with `NO ROTATION`.
- **What it deletes.** The spare-stress read and its event, and the impact update on every publication. Create sees one fixed impact, as for its own blocks.
- **What it costs.** The network carries the full rating even while the pump takes less. A 30 kW rating holds 20,500 su whether the pump uses 30 kW or 5 kW. Energy is wasted, but none is gained, so no loophole opens.
- **What it risks.** A player who rates too high stops their own Create network. That is Create's own rule, but the industrial side cannot prevent it.

## 4. Steps (either form)

1. The `Drive.CREATE` step and its unit test (speed, power limit, `NO ROTATION`), plus the gearbox ratio 64 on a coupling-driven line.
2. The compat block, block entity, renderer, model, flat textures and recipe, all behind the guard.
3. The events (Create speed, plus spare stress in A, plus the rating in B), the impact, and the facts if a part or setting is saved (append-only, with a corpus image).
4. The page prototype; then the page after your look. Texts in en_us only.
5. Two GameTests:
   - with Create: Create's creative motor → coupling → gearbox → pump casing. The pump runs, Create's network shows the impact, and an over-demand gives `POWER LIMITED` (A) or overstress and `NO ROTATION` (B).
   - without Create (`-PwithoutCreate`): the fluid GameTests still pass, and nothing loads a Create class.
6. Affected tests by `--tests`. The campaign is skipped unless the solver changes; the campaign has no couplings.
