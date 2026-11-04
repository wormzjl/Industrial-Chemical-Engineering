# W2 campaign review: D16 (one vapour-lock fact per solve, floor at the liquid's enthalpy)

Batch `2026-11-03-rotating-machinery`, W2, 2026-11-04, commit `7b46b3b05` (owner decision D16). One full campaign (1000
seeds, three distributions, 8 workers, alone). Before it, the affected tests (98 classes, the hold gates) pass.

## 1. The campaign

| Distribution | Base (0.19.2) | D13 (`a8accb372`) | D15 (`f53708de3`) | D16 | New against base |
|---|---|---|---|---|---|
| cold random | 991 | 992 | 991 | 992 | none (431 gone) |
| bundled random | 993 | 993 | 993 | 994 | 896 (E5) (6, 301 gone) |
| bundled defaults | 1000 | 1000 | 1000 | 1000 | none |

- D15's two active-set cycles are gone: cold 181 and bundled 575 run clean.
- Counters are back at D13's level (bundled random backtracks 828,332 against 1,047,931 at D15 and 813,651 at D13;
  cold within 1 %). Refused registrations: 0. Seed times are not compared (machine load varied between runs).
- Through the batch: E8 (563, 896 defaults) gone at D13, E5 (575, 952) gone at D14 and D16.

## 2. Bundled 896: the one new hold

1. **Expected behaviour** (traced in `D14_HOLDS_REVIEW.md`). Crude drains about 52.8 kg/s through tee n0 into a void,
   so n0 stands at its flash onset (bubble point 214.78 kPa). A pump draws from n0 through a 1 cm line with about
   148 Pa of NPSH at the step's start. Within the step the tee falls to its bubble point to pass the main stream, the
   pump's NPSH goes to 0, and it is vapour-locked: about 0 flow, the island STEADY at 52.76 kg/s.
2. **What the code does.** D16 reads the lock on the start states: +148 Pa is above the band (0.01 Pa), so the pump is
   not locked and goes to its suction limit with its suction anchored at the floor. The tee must then flash below the
   floor, below the anchored suction, and the line would have to flow backwards: no root, the same hold as D14
   (tick 2138, residual 14.2 on the tee's mass row). D15 closed it because the balance read the drained point, which
   is what made 575 and 181 cycle.
3. **Proposals for the owner.**
   - **(a) Recommended: the suction-limit floor never stands above its supplier** (D14 review option B): the anchored
     suction is min(floor, the supplier's end pressure less its lift) on the trial point, so a supplier that falls to
     its bubble point takes the pump's delivery to zero continuously, inside the row, with no mode change and no
     reading of a drained point for a mode. It deletes nothing yet, and risks a kink where the supplier crosses the
     floor (a non-smooth Newton). Untested.
   - (b) A supplier junction that already stands within its own flash band at the start (n0 here) counts as boiling
     for the lock: it uses its own pressure as the floor, as a two-phase supplier does. It needs a band for "within",
     which the physics does not set.
   - (c) Leave 896 as a known hold: 994 clean against the baseline's 993.

Water unification (the D16 build's finding): water's `waterFloor` could join the PH bisection, deleting `waterFloor`,
`vapourPressure`'s water branch, the balance's `Math.min` and the carry's water alternative; water is read on the
suction junction, not the walk's supplier, and pure-water PH through the engine is unverified. Proposed for later.

Code guide: no change yet (the merge refresh covers W2).
