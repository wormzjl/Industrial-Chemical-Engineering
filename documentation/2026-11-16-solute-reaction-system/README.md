# 2026-11-16 solute and reaction system

Status: Planned (design, 2026-11-16). No code. Branch `claude/solute-reaction-system-01ef6c`, cut from `main` at `adb413b4d` (0.34.0). It builds on batch `2026-11-16-fouling-corrosion`, which is not on `main` yet; no work package starts before that batch merges.

Purpose: solutes as tags on the pseudocomponents and on water (salt, sulfur, nitrogen, metals, resins, asphaltenes and ordinary solutes), carried with the flow without touching the solver; transformations of tags under stated conditions in equipment (desalting, chloride hydrolysis to HCl, acid water corrosion, coking as fouling and fines); an electric desalter; corrosion evaluated per part of a column and its exchangers; the frame and the model requirements for the later catalytic units.

Lane (when built): new packages `science/solute` and `science/reaction`; hot files `MaterialCatalog`, `FluidFacts`, `FluidCheckpointCodec`, `IslandCoordinator`, `FluidWorldAuthority`, `ColumnOperation`, `ColumnFacts`, `EquipmentShape`, `EquipmentStructure`, `SheetSizing`; for hydrogen chloride as a compound also `PassiveIntervalSolver` (one conversion at the interval's start), `ConservativeTransport` (one boundary kind) and `IslandCertificate` (that kind as a rate). Not touched: `PassiveStepSolver`, `StepEquations`, the booking. Class: new feature; stage A moves no reference, stage B moves the fouling of oil sides (stated in its review).

## History

- 2026-11-16, design: the owner's request (plan section 1). Read: the code guide, the fouling-corrosion plan, README and `integrity.md` from `claude/fouling-corrosion-final-lane-f2943c`, `ConservativeTransport`, `PipeTransfer`, `SolidInventory`, `SplitTable`, the material data. Eight web searches for the kinetic models: forms and qualitative anchors confirmed, few constants found; every constant is marked read or recalled in the review. The laws' tables computed with a scratch script (not kept). Written: [SOLUTE_REACTION_PLAN.md](SOLUTE_REACTION_PLAN.md), [REACTION_MODELS_REVIEW.md](REACTION_MODELS_REVIEW.md). No build, no test run.
- 2026-11-16, owner, during the design: "solute 'tags' the pseudocomponent and water, and they retain their physical properties"; "compounds with different 'tag' can do mixing as they would". Taken as decisions D1 and D2: an amount per tag and carrier compound, no effect on any property, amounts add on mixing.
- 2026-11-16, owner: "How's the background thermo model requirement?". Read `EnergyReference` and the datasets' ranges; the review gains rows 15 and 16 and section 4.1: no change to the kernel or the engine; the network's datum is sensible, so a unit books its reaction heat itself; heat capacity fits end at 900 K (the FCC regenerator, steam cracking and SMR are outside); the cuts' data range ends at 20 bar (hydroprocessing is outside); hydrogen pairs are zero, so rate laws use the gas's hydrogen pressure. Documents only.
- 2026-11-16, owner, on option O1: "I mean model the HCl as an components". Plan revision 2: `HydrogenChloride` is a compound of `icheme:refinery`; a tag becomes the compound in a holdup without free water and the compound a tag on water in a holdup with it (plan 7.4), booked at the interval's start as a `SOLUTE` boundary transfer; the column carries it on its accounts to the top product, off V3's axis; M12 reads the compound and the tag, and a heat side's colder end for the wet mechanisms. Read for it: `DerivedPairs` (a pair with water without data is `k_ij` = 0; a `henry_in_water` record would fit one), `FluidThermodynamics` (the trace cutoff folds phases, not compounds), `PassiveIntervalSolver.flushTraces` (the restate at T and P that the exchange copies), `IslandCertificate` (a material transfer at a vessel refuses a certificate unless its kind is a rate: `SOLUTE` joins them). Documents only.

## Documents

- [SOLUTE_REACTION_PLAN.md](SOLUTE_REACTION_PLAN.md): the request, what exists, the physics in numbers, tags (records, classes, transport), transformations (frame, residence time, the five of this batch with their tables), corrosion M12 and per-part evaluation, the desalter, formats, the frame for reactor units, work packages, owner's options, risks.
- [REACTION_MODELS_REVIEW.md](REACTION_MODELS_REVIEW.md): the model chosen for each non-catalytic transformation and each catalytic or thermal unit, its inputs, the inputs lacking, what the searches did not find, sources.

## Code areas

None yet.

## Open items

- The owner's options O2 to O5 (plan section 16): a second material for a column's top; coke fines as a tag or as a solid population; one metals tag or two; the fitted numbers. O1 is decided: hydrogen chloride is a compound.
- The start of the build: after the fouling-corrosion merge; then the format numbers are reserved on `main` (facts schema 30, state format 53, `icheme-6`, the next sheet file number) on the owner's word, and each work package's delegation is approved one by one.
- Before WP5: read the hydrochloric acid table of API RP 581 Part 2 Annex B against M12's anchors.
- Before WP6: the desalter's table on the mod's own oil viscosities; fit the drop size once.
- The next design task: the catalytic and thermal units (review sections 3 and 4), starting with the inputs lacking: hydrocarbon type tags, entropy on `thermochemistry`, heat capacity above 900 K.
