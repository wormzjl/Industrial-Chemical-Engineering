# 2026-11-16 solute and reaction system

Status: Planned (design, 2026-11-16). No code. Branch `claude/solute-reaction-system-01ef6c`, cut from `main` at `adb413b4d` (0.34.0). It builds on batch `2026-11-16-fouling-corrosion`, which is not on `main` yet; no work package starts before that batch merges.

Purpose: solutes as tags on the pseudocomponents and on water (salt, sulfur, nitrogen, metals, resins, asphaltenes and ordinary solutes), carried with the flow without touching the solver; transformations of tags under stated conditions in equipment (desalting, chloride hydrolysis to HCl, acid water corrosion, coking as fouling and fines); an electric desalter; corrosion evaluated per part of a column and its exchangers; the frame and the model requirements for the later catalytic units.

Lane (when built): new packages `science/solute` and `science/reaction`; hot files `MaterialCatalog`, `FluidFacts`, `FluidCheckpointCodec`, `IslandCoordinator`, `FluidWorldAuthority`, `ColumnOperation`, `ColumnFacts`, `EquipmentShape`, `EquipmentStructure`, `SheetSizing`. Not touched: `PassiveStepSolver`, `StepEquations`, `ConservativeTransport`. Class: new feature; stage A moves no reference, stage B moves the fouling of oil sides (stated in its review).

## History

- 2026-11-16, design: the owner's request (plan section 1). Read: the code guide, the fouling-corrosion plan, README and `integrity.md` from `claude/fouling-corrosion-final-lane-f2943c`, `ConservativeTransport`, `PipeTransfer`, `SolidInventory`, `SplitTable`, the material data. Eight web searches for the kinetic models: forms and qualitative anchors confirmed, few constants found; every constant is marked read or recalled in the review. The laws' tables computed with a scratch script (not kept). Written: [SOLUTE_REACTION_PLAN.md](SOLUTE_REACTION_PLAN.md), [REACTION_MODELS_REVIEW.md](REACTION_MODELS_REVIEW.md). No build, no test run.
- 2026-11-16, owner, during the design: "solute 'tags' the pseudocomponent and water, and they retain their physical properties"; "compounds with different 'tag' can do mixing as they would". Taken as decisions D1 and D2: an amount per tag and carrier compound, no effect on any property, amounts add on mixing.

## Documents

- [SOLUTE_REACTION_PLAN.md](SOLUTE_REACTION_PLAN.md): the request, what exists, the physics in numbers, tags (records, classes, transport), transformations (frame, residence time, the five of this batch with their tables), corrosion M12 and per-part evaluation, the desalter, formats, the frame for reactor units, work packages, owner's options, risks.
- [REACTION_MODELS_REVIEW.md](REACTION_MODELS_REVIEW.md): the model chosen for each non-catalytic transformation and each catalytic or thermal unit, its inputs, the inputs lacking, what the searches did not find, sources.

## Code areas

None yet.

## Open items

- The owner's options O1 to O5 (plan section 16): HCl as a tag on water (designed) or as a compound; a second material for a column's top; coke fines as a tag or as a solid population; one metals tag or two; the fitted numbers.
- The start of the build: after the fouling-corrosion merge; then the format numbers are reserved on `main` (facts schema 30, state format 53, `icheme-6`, the next sheet file number) on the owner's word, and each work package's delegation is approved one by one.
- Before WP5: read the hydrochloric acid table of API RP 581 Part 2 Annex B against M12's anchors.
- Before WP6: the desalter's table on the mod's own oil viscosities; fit the drop size once.
- The next design task: the catalytic and thermal units (review sections 3 and 4), starting with the inputs lacking: hydrocarbon type tags, entropy on `thermochemistry`, heat capacity above 900 K.
