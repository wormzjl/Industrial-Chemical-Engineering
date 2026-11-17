# Dynamic settling (2026-11-17)

**Status:** In build 2026-11-17: packages 1a (the band law) and G (the page's parts that need no data) built, on branch
`claude/column-testing-sizing-26b5b9`; 1b, 2 to 5 wait for batch `2026-11-16-solute-reaction-system`'s files. Class:
PHYSICS. Lanes: fluid laws, runtime, client; flowsheet and fluid step by the owner's decisions.

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
- 2026-11-17: the page's option sheets, drawn by an Opus subagent in `GuiPrototypes.separation` (prototypes only, no
  kit or screen change) with the group's English lines: A, a bar a phase; B, a meter a phase; C, A's group and a
  section of the vessel on the glass; each on a half-settled standing drum and a lying drum over its gas limit. What a
  build needs: A a labelled bar row in the kit (`Column.Bar` has no label, colour or value); B nothing; C that row,
  a Minecraft-free section drawing and a glass shared by the table and the drawing. Not run: any test.
- 2026-11-17: the owner picked the sheet: "C: bars and section" (D6 C). The swarm law's exponent steps were checked
  again: 4.65, 4.45 Re^-0.1 and 2.39 hold; the 0.2 to 1 step's factor (4.35) stays from memory, 2 % in speed at most.
  Package 1a is no longer waiting for the paper. Documents only.
- 2026-11-17: package 1a, the band law (commit `9b25df078`): `Separation.Band`, `Slice`, `advance` (16 parts),
  `swarmExponent`, `dispersed`; `SettlingBandTest` (10 tests) holds section 3's steady shares to 1e-4, the clean
  periods within 5 %, the batch times, the swarm factors. The swarm batch is checked within 1 % or one slice of 1 s
  (59.2 s clears on the 60th slice). No vessel reads it. Plan section 11.
- 2026-11-17: package G, the page's parts that need no data (commit `7a51f5e84`): the kit row `Column.Gauge`, the
  Separation group (`DeviceColumn.withSeparation`, `DeviceMeters.separation`), `VesselSection`; `GuiPrototypes.separation`
  draws from them (its local bar, section and overlay overload removed); gates in `DeviceColumnTest`, `DeviceMetersTest`,
  `KitLayoutTest`, new `VesselSectionTest`. The eight separation and carry-over sheets rendered and looked at. Then the
  selection of `affected.mjs` ran once: test 360 classes, 2147 tests; holdGates 5 classes, 47 tests; columnGates 1
  class, 5 tests (stored pass); equipmentGates 1 class, 21 tests; no failure, no reference moved. Not run: the
  `fluidSolverRegression` step, the GameTest and the campaign steps the selection names for `Separation.java` (the
  final lane's), `LanguageFilesTest`, `UnitTextTest`.

## Documents

- [DYNAMIC_SETTLING_PLAN.md](DYNAMIC_SETTLING_PLAN.md)

## Code areas (when built)

`science/fluid/network/Separation`, `PassiveNetwork.Inventory`, `runtime/fluid` (`IslandSets`, `PhysicalRegistry`,
`FluidFacts`, `FluidView`, `TankOutlets`, `TankDisplay`), `client/gui/common` (`DeviceColumn`, `DeviceMeters`).

## Open items

- D4 A (no gas bubbles) is a default the owner can overrule.
- Blocked until batch `2026-11-16-solute-reaction-system` releases `PassiveNetwork.java`, `IslandSets.java`,
  `PhysicalRegistry.java` and `FluidFacts.java`: packages 1b and 2. Package 1a (the law alone) is free.
- Not blocking: the factor of Richardson and Zaki's 0.2 to 1 step (4.35 from memory; Trans. Inst. Chem. Eng. 32
  (1954) 35, reprinted doi:10.1016/S0263-8762(97)80006-8).
- Built: package 1a and the page's parts that need no data (G). The screen reads neither until packages 2 and 3
  (the bands on the inventory, the view's levels and band heights, a protocol number).
