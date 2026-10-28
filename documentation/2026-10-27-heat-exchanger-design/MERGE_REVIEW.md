# Merge review: heat exchangers and the GUI toolkit (2026-10-28)

Branch `claude/heat-exchanger-design-cf05d1` on main 0.16.2 (`cc5da444`, merged in at `342c6d22`). It carries batches 2026-10-27-heat-exchanger-design (W1 to W8, F1, the owner test fixes, the water steam virial, the heat GUI), 2026-10-28-gui-toolkit and 2026-10-28-gui-toolkit-evaluation. Proposed version 0.17.0 (minor: a feature batch); the owner chooses.

## Final lane (run alone, `tools/heat-exchanger-design/merge-lane/lane.sh`; the baseline is main 0.16.2, same script and harness)

| Step | Branch (gates `cbefb793`, campaign `36d21cd2`) | Main 0.16.2 |
|---|---|---|
| `test slowGates --rerun-tasks` | `test` 1796, `slowGates` 188, 0 failures | 1654 / 188 |
| Junction lines | 33, identical once `ms=` and `allocatedMB=` are removed | 33 |
| `MIXED_GAS_COST` | 299 Newton solves | 299 |
| Printed lines | 8540 | 8506 |
| Chain-100 | exact (re-captured at `4521b65c` under the virial steam) | |
| Fluid GameTests | 36/36 (run `heat-merge-final`) | |
| Campaign cold random | clean 975, the same 25 held seeds and classes | 975 |
| Campaign bundled random | clean 994, the same 6 held seeds; seed 241 E2 to E6 | 994 |
| Campaign bundled defaults | clean 1000 | 1000 |

- Campaign counters: cold within 0.2 %; bundled random 9 % fewer implicit solves (121,924 to 111,204) and Newton iterations (942,773 to 850,820); defaults equal. Seed 241 is held on both sides ("Singular Newton Jacobian" becomes "Newton iteration limit", 11 to 14 holds): not a new held world, so not minimized.
- Printed lines, timings and hashes removed: numbers move by 1e-6 relative or less in the tests that hold water vapour (the virial steam). With every number masked, the lines that differ are the new heat tests (`ExchangerCouplingTest` 22, `HeatSideTopologyTest` 3, `ExchangerRelayTest` 1), the restated `steam-v2` and `schema-5` images (`FluidFactsCompatTest`), the certificate hash (identity 8), the legacy path digest, and the solver paths of three water tests. `PhasePortPriorityTest` oil-water overflow rejects 17 slices (was 20; one slice now names "Conservative reconstruction fails equation gate"), `FluidPumpedFillLineTest` 1 (was 2), `WaterIntoCryogenicMethaneTest` reorders its reasons. The worst ledger stays where main has it (energy 1.1e-13, fed-0.1).

## The campaign harness

- Since 0.16.2 the engine refuses a portless mover, and the hold-fuzz harnesses (`tools/unified-thermo-port-forms`) placed them, so every drawn mover was refused and skipped. `61648ed8`: a drawn pump or compressor goes in line on a straight pipe joined along its facing; an upright one is that pipe without its mover. The draws are unchanged. `36d21cd2`: a label for kinds the harness never draws (`HEAT_SIDE`).
- With it, main 0.16.2 gives clean 975 / 994 / 1000, the counts of 0.15.0's lane with the old harness, and no placement is refused.

## Formats

- Facts: fluid facts schema 5, a heat side's unit, appended (corpus `compat/fluid/schema-5`); corpus `steam-v2` for the water revision. Equipment shape fields and the layout table: unchanged.
- State: checkpoint state 29 (main 22), certificate graph identity 8 (main 2). A 0.16.2 world drops its state and rebuilds from its facts.
- Material: `separate-free-water-v3`; a world with free water restates once on load.
- Wire, no compatibility work: `fluid-16` (main `fluid-15`); `equipment-13`, `assembly-2` and column protocol 13 unchanged.

## Checks outside the lane

- In game: the owner's own test of the review world, its findings fixed (OWNER_TEST_REVIEW.md). The GUI toolkit's screens have not been seen in game. The review world is rebuilt in `run/saves`.
- `git ls-files -i -c --exclude-standard`: empty. Not seen anywhere: a click on an exchanger that a column's report holds opens the column's screen (`AssemblyService.holder`), so that side's Check lines is out of reach by its unit.

## Code guide, tools, changelog

- Code guide (`a6308f8a`, snapshot `f20ceefa`): new chapter `heat-exchangers`; updated README, `fluid-science`, `fluid-runtime`, `equipment`, `machine-assemblies`, `column-v3`, `instrument-accessories`, `thermo-materials`, `client-gui`, `verification`. `equilibrium-engine` stays at `3a527f1e`, its code unchanged since.
- Tools: `tools/heat-exchanger-design/` (study harnesses; `review-probe/`, detached in `f20ceefa` and `44ba7c7a`; `merge-lane/`), `tools/heat-gui-renders/`, the harness port in `tools/unified-thermo-port-forms/`. Logs: `tools/heat-exchanger-design/logs/merge-lane-logs-2026-10-28.tar.gz` in the main checkout (local).
- CHANGELOG [Unreleased]: Added, heat exchangers; Changed, the GUI toolkit and the water steam; Removed, the Holland preset and the review probe.

## Open items (after the merge)

- Water: start a seeded junction's flows from its seeds (WATER_STEAM_VIRIAL_REVIEW.md finding 1, the owner's call); a restatement at fixed volume (finding 3).
- GUI: Chinese lines for the kit's keys (124 unused keys in `zh_cn.json`); the calculator's client units; scrolling at small windows; the fired heater's duty target (GUI review (d)).
- Heat: W9 (the column hook); the split-range pass valve (W8); the click on a held exchanger (above); the other open items in README.md.
