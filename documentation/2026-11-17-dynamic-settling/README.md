# Dynamic settling (2026-11-17)

**Status:** Planned 2026-11-17 (decisions D1, D2, D3 and D5 taken), on branch `claude/column-testing-sizing-26b5b9`. Class: PHYSICS. Lanes: fluid laws,
runtime, client; flowsheet and fluid step by the owner's decisions. Nothing is coded.

A vessel's liquids settle over time: a mixed feed forms a turbid band that a clear front crosses at the drops' settling
speed, so separation follows the residence time against the settling time, slice by slice. It replaces the time form of
batch `2026-11-17-three-phase-separator`'s law, which reads each slice afresh, and gives the same numbers at steady
flow. The drum page gains a level of separation for gas, oil and water. The stirred tank's batch needs it.

## History

- 2026-11-17: the owner asked whether dynamic settling fits the framework and for its cost, then: "The plan is
  basically longer residence time = better separation", "and simulated dynamically", "write the plan, also add some
  sort of level of separation to drum GUI. This needs to applicable to 3 phases", "with sound scientific roots of
  course". The plan: [DYNAMIC_SETTLING_PLAN.md](DYNAMIC_SETTLING_PLAN.md). The band law was run on the review plant's
  receiver before writing (`tools/dynamic-settling/band.mjs`): it gives today's carried shares at steady flow to four
  digits with 16 parts a slice. Sources were checked by search; the plan's section 10 says what was confirmed and what
  is from memory. No code, no test run.
- 2026-11-17: the owner's decisions, each the plan's recommended option: D1 B (the minority liquid is in drops), D2 B
  (Richardson and Zaki's swarm law), D3 B (a standing drum's bottom outlet draws by layer, the last package), D5 A
  (the bands are saved with the vessel's facts). D4 A (no gas bubbles) stands as the default, not asked. D1 B changes
  law 2 of the separator batch: on an oil-rich feed the water leaves clean, and `SeparationDrawTest`'s
  undersized-boot case moves when package 2 is built. Documents only.

## Documents

- [DYNAMIC_SETTLING_PLAN.md](DYNAMIC_SETTLING_PLAN.md)

## Code areas (when built)

`science/fluid/network/Separation`, `PassiveNetwork.Inventory`, `runtime/fluid` (`IslandSets`, `PhysicalRegistry`,
`FluidFacts`, `FluidView`, `TankOutlets`, `TankDisplay`), `client/gui/common` (`DeviceColumn`, `DeviceMeters`).

## Open items

- D6, the page's sheet, after the option sheets are rendered; D4 A (no gas bubbles) is a default the owner can overrule.
- The page's option sheets (plan section 6) are not rendered.
- Blocked until batch `2026-11-16-solute-reaction-system` releases `PassiveNetwork.java`, `IslandSets.java`,
  `PhysicalRegistry.java` and `FluidFacts.java`: packages 1b and 2. Package 1a (the law alone) is free.
- To read before package 1a: Richardson and Zaki's exponent steps (from memory in the plan; the owner fetches the
  paper if it is behind a paywall: Trans. Inst. Chem. Eng. 32 (1954) 35, reprinted doi:10.1016/S0263-8762(97)80006-8).
