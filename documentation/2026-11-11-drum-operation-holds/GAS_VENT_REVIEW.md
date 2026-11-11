# Drum operation holds: defects 1 and 4, options for the owner (2026-11-11)

Not fixed: each fix rests on a choice the physics does not force. Defects 2, 3 and 5: [DRUM_HOLDS_REVIEW.md](DRUM_HOLDS_REVIEW.md).

## 1. Gas-vent setup hold
- **Expected.** The level loop's 7.95 m3 drum holds nothing (a D54 bag, slack at 1 atm); its vent goes to a 1 atm nitrogen generator, its outlet through a valve and a 6 m leg to a void. When the feed law starts (19.81 kg/s at 3 bar), the generator fills the empty bag with nitrogen at 1 atm (about 9 kg, at up to the 0.25 m line's capacity), and the leg holds gas at the drum's pressure, a 6 m nitrogen column (70 Pa). Then nothing flows down the leg until liquid reaches the outlet nozzle.
- **What the code did** (rows of `2026-11-11-minecraft-time-delta/GAS_VENT_HOLD_REVIEW.md`, read against the mint code):
  - The mint seeds the leg's foot junction from what reaches it (D55, `supplierSeeds`). Its sweep passes through a vessel that holds no fluid, so the feed's water reaches the foot: it holds 1.0 kg in 1 L, liquid, not the drum's nitrogen. The rows confirm it.
  - The drum's ends are dry at the step's start (D54, `dryEnd`), so the balance stands the foot on its water column to the void: 70.7 kPa, 30.6 kPa under the drum's 1 atm. This is a balance at the edge of its no-flow range, which defect 3's fix does not move (it moves only a junction left on the hull's bound).
  - In the step the generator's gas runs through the drum as a junction (1.40 kg/s) and down onto 1 kg of water 30 kPa below it. Newton asks 470 to 500 kg/s down the leg; steps halve to 1e-7 s; the substep limit holds the island.
- **Measured here** (factor 1, `run.sh 1` with the generator vent and defects 2, 3 and 5 fixed): the same holds, the same numbers (0.0369 of 0.1 s, 503 accepted, 521 rejected). The status of the first case now reads "HELD: round deadline": a deadline (an answer past the wall budget), not a worker exception as the earlier review read it. Headless (`tools/drum-operation-holds` (`GasVentDrainTest`), everything built at once, the feed at 3 bar from the start): clean with a void vent, a generator vent and an open leg. The hold needs the setup's sequence (the feed generator charged at 42.4 kPa, then the law and 3 bar).
- **Options** (the seed of a leg under a vessel holding nothing is a convention, not physics):
  - (a) The mint's sweep does not pass through a vessel holding no fluid by its bottom (LIQUID) port: a junction below such a vessel takes the gas that reaches the vessel (here the vent generator's nitrogen at the drum's pressure), or the state it was built with if none does. Recommended. It amends D55's sweep-through rule (owner decision of 2026-10-19).
  - (b) As (a) for every port of an empty vessel, so its side and top lines seed from their own suppliers too.
  - (c) The solver takes the fictitious water out in the step (a junction emptying under gas in one step): the deeper change, no recommendation.
  - Either (a) or (b) would need one run of the level loop at factor 1 and the 200-seed quick check.

## 4. A liquid-full drum that stays full (finding 3)
- **Expected, in the model's physics.** A closed drum is rigid once a step leaves it full (batch 2026-11-10-rigid-reservoir). Liquid can leave only as gas comes in, or as the drum flashes to water's vapour pressure, 3.5 kPa at 300 K. Then the leg's foot stands at 3.5 + 1000 x 9.81 x (1.5 + 6) = 77 kPa, under the void's 101.3 kPa, so the drum cannot drain. On a void vent (a void only takes) the drum stays full with outflow = feed. That is what the code does: on a void vent finding 3 is correct.
- **The generator vent does not settle it.** A generator only gives (`boundaryAllowed`). Headless, with the generator vent, the bag first fills with nitrogen, and the feed then compresses it: 309.8 kPa at 71 % full after 60 s. That is a blanketed drum with no relief, not a vented one. The rerun the task asked for was not made: defect 1 still holds the setup.
- **Options for the rig (`LevelLoopGameTests` keeps its fill at a tenth and its ramp until one is chosen):**
  - (A) A tee on the vent: a 1 atm nitrogen generator (gas in when the drum falls below 1 atm) and a void behind a back-pressure valve at 1.1 bar (gas out above it). That is a blanketed, relieved drum, as a plant runs one. It needs (a) or (b) of defect 1 for the setup. Recommended.
  - (B) A two-way boundary (a vent that gives its own gas back at its pressure): a new device kind and rule. Not recommended for this batch.
  - (C) Keep the void vent and the fill: finding 3 is the rig's property, documented as correct.
