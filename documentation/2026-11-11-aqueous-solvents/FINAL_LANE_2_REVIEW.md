# Second final lane of the aqueous-solvents batch

2026-11-12, on `87a769cdd` (the code of `184da5a4c`: main 0.28.0 merged, the four fixes, the translation). One run of
`tools/merge-lane/lane.sh head` in a sub-thread (Opus medium; owner: "run the lane now"). Outputs: `tmp/final-lane-2/head/`.
Baselines: main 0.27.1 (`tmp/final-lane/base/merge-lane/head/`) and the first lane (`tmp/final-lane/head/`,
[FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md)). Counters only.

| Step | Result |
|---|---|
| Tests | 511 classes, 3322 tests, 0 failures: test 357 of 400, holdGates 12 of 12, columnGates 77 of 83, equipmentGates 22 of 28 |
| chain-100 exact | deviation 0 |
| Fluid GameTests | server crash in `converter-liquid`; fixed after the lane; one run on the fix: 96 of 96 pass |
| Junction lines | 33, counters identical to both lanes; `MIXED_GAS_COST` 313 Newton solves (both) |
| Campaign clean (cold, bundled random, defaults) | 990, 994, 1000; refusals 0 |

## Defect: a liquid converter on a dry island (fixed after the lane, main thread)
1. Expected: a converter whose rule gives water to an island built without water (W3b option A) waits, and the island
   grows wet, as for any compound the island does not carry (`Converters.grow`).
2. What the code did: the island's model lists water last and carries none. `ConversionLaws.perLitre` found water on
   the list and flashed; the phase engine refused the water (`NetworkPhaseEngine.basis`), the exception left the
   engine's tick (`DRAIN_FAILED`) and stopped the server. Main's conversion met the batch's dry models for the first time.
3. Fix: `ConversionLaws.fractions(rule, model)`: a model without water does not carry the rule's water (null), so the
   port asks for growth. `Converters.forward` asks only where the package can carry the rule's compounds (one without
   water would be recompiled at every plan). `Converters.perLitre` keys a dry and a wet model apart: both list the same
   ids, and the null of the dry island was kept after it grew wet (the first run on the fix: the converter never started).
   Gate: `ConversionRuleTest.theVolumeLawsOfSectionTwo`. Ran: `ConversionRuleTest`, `ConvertersTest`; one fluid GameTest
   run, 96 of 96 (forward 1000 L give 997.05 kg; level loop high 74.2 %).

## Campaign against main 0.27.1
- Cold: new 743 B, 65 E5, 919 E5, 6 F; fixed 85 E1, 181 E2, 118 F; 431 B to F. Newton iterations 2002115 to 1959612.
- Bundled random: none new; fixed 301 B, 896 E5, 47 F, 678 F. Newton iterations 859982 to 683188.
- Defaults: no change in holds. Newton iterations 62242 to 65438.

## Campaign against the first lane (the four fixes and main 0.28.0)
- Cold: the same held seeds; 743 E6 to B. Bundled random: 575 E2 fixed; 431 E6 to F. Defaults: identical counters.
- The two cold-start rules (`f8f634ebd`) changed no other hold of the junction or valve classes.

## New budget-class holds: seeds 6 and 431 (cold), replayed once, alone, in one run
- 6: clean. Its campaign hold was the wall deadline under load: not counted.
- 431: holds at tick 2785 on island 42 (ten crystal vessels), `Substep refinement exhausted: Phase/device active-set
  cycle`, class **E7**, which is in none of the three lanes' lists. Main held this seed as B; the first lane's replay
  held it on the wall deadline at tick 3085. Not traced further (one replay per held seed).

## Open
- Cold 431 as E7 (active-set cycle): held on main too, in another class. Owner: carry as an open item or diagnose.
- The converter fix and its GameTest run came after the lane; the lane's other steps do not load the three changed
  files except `ConversionRuleTest` and `ConvertersTest`, which ran. No further lane.
- `SimulatorResults` changed its stored layout and kept `FORMAT` 3 (MERGE_PREP_REVIEW.md): a one-line bump, owner's word.
- From the first lane, unchanged: the level loop's load-dependent overshoot, the cost findings.
