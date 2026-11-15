# Simplifications and accuracy trades: owner options

Base `259d22a2c`, 2026-11-13. Every row moves a reference or a player-visible number, so each is an option for the owner (AGENTS.md, "Small physics simplifications"). Numbers are the reviews' estimates from the formulas, not measurements; "to measure" marks a number a quick check must give before the change. Ids are the reviews' ([CODEBASE_REVIEW.md](CODEBASE_REVIEW.md), section 2). "Rec." is the main thread's recommendation: **yes**, **later** (after a named step) or **no**. None breaks conservation; none overrides an owner rule unless the row says which rule it asks to revisit.

## 1. Column V3

| Id | Change | Effect at usual states | At the extremes | Deletes | Risk | Rec. |
|---|---|---|---|---|---|---|
| D1 | The C++ pass off by default until the Rust pass returns Java's bits | none on a machine without the library; products move by the 7e-13 relative measured on 2026-10-28 where the library was used | the same | nothing now; 742 C++ lines at stage (a) | none; no speed-up was measured. Revisits the owner's choice of 2026-10-29 (keep the C++ default) | yes |
| D3 | One closure per strategy. A: the running column's warm pass and its recovery certify at 1e-3 like its first solve. B: strict (1e-8) for the first solve too | A: a warm result is certified at 1e-3 in log flow; the temperature gate (1e-6 K) does not move. B: the first solve costs the strict ladder | A: a tray's energy row may be out by 30 kW on a 300 mol/s feed | about 5 lines; one of the two result labels | A widens what a setpoint change publishes; B makes Start slower | A, measured on B1's warm solve |
| D4 | The audit's material and energy limits equal the closure (they are 1.0 today) | none: a converged attempt is already below the closure | a state out by 1e-4 is refused where it passes today | nothing | none expected | yes |
| D6 | The learned route's budget in a physical unit (property evaluations and linear solves) instead of checkpoint calls | the set of requests that fall back to the classical start moves; to measure on the 405-case population | the same | up to 30 lines; the port need not copy 73 poll sites | revisits D2 of batch 2026-11-05-neural-retrain | later: keep polls and pin the count per fixture until the port's stage (d) |
| P1 | The analytic block Jacobian solves and certifies; the finite-difference one becomes the test oracle | an accepted state stays within two final steps of the same root (2e-8 in log flow, 3e-6 K at 600 K); iteration paths move | a case that converges today only through difference noise or the coarse rung may be refused | about 350 production lines, 382 moved to tests, about 700 fewer lines to port | accept or refuse can move on a borderline column | yes, after B1 confirms the Jacobian's share |
| L1 | Delete the inside-out trial (first 4-tray grid of a featureless column, at most two passes) | states within two closures; the first rung may take more Newton iterations | hard feeds on the 4-tray rung | about 330 Java lines; the native pass loses its only caller | to measure: delete if it saves under 5 % of a cold solve | yes if the measure says so |
| L2 | The last analytic step is the step evidence; no verification cascade | at most one final step | a state the cascade certifies today iterates once more | about 110 lines | needs P1 | later, with P1 |
| L3 | Three dampings everywhere (eight today) | paths only | cases that need the fourth to eighth damping | about 20 lines | to measure | later |
| L4 | Delete the pressure ladder below 100 kPa if no shipped preset or vacuum fixture needs it | none if the direct attempt never fails | a vacuum column only the ladder reaches is refused | about 150 lines | to count first | later, counted |
| T2 | Drop a sunk trace point inside the Newton loop; reinsert only between attempts | the audited bound does not move (one floor per sink edge) | drop and reinsert chatter | about 300 lines, three budgets | the measured cases the solver comment names decide | later, after the flat layout |
| F13 | The column's ideal gas held at each component's own range, as the network's | about 1e-16 inside the package range | about 1 kJ/mol (0.3 % of h) 50 K past the edge for a C20 cut (assumed slope) | about 45 lines, a second ideal-gas form | column references past the package range | yes |
| F15 | The truncated flash returns its reference with the frozen support | the feed split moves within the policy's budgets | the same | about 250 lines | the column review must confirm what the column needs from the reduced split | later |
| F29 | One water model for both solvers: water on the axis only | steam's cross term about 0.3 % of a latent heat; saturation to measure | needs a condenser with free water as three-phase stages in V3 | the free-water rows | high: V3 is a two-phase algorithm | no, not before the Rust port |

## 2. Where the column meets the network

| Id | Change | Effect at usual states | At the extremes | Deletes | Risk | Rec. |
|---|---|---|---|---|---|---|
| C2 | Price each product at its specific enthalpy: one flash per product, not m + 1 | 0 at the reference composition | at most about 0.2 kJ/mol of product, about 0.3 % of a condenser duty, at the edge of the composition drift band; a tenth of the 3 % closure band; returned exactly at the outlet booking | about 25 lines; about 90 % of the post-solve flashes (about 520 at 64 compounds and 8 products) | a draw with a large composition move | yes |
| C3 | Price on V3's own product enthalpies | a few tenths of a percent of the throughput's enthalpy move from the cycle's closure to the outlet booking, at least 440 ticks later; run totals identical | several percent of a small condenser | about 60 lines; the column job's last network flashes but the feed temperature | the booking difference goes to water or air by product temperature | later, after C2 is measured |
| C5 | One `ColumnJob` for the sheet and the world (one route, one screen ratio, one energy closure) | the sheet's duties move by the basis gap | a design that solved on the sheet and was refused in the world now agrees | about 40 lines | the sheet's references | yes; the closure is the world's |
| C14 | One `SplitTable` constructor; an old table fails validation | one Start solve per running column on the first load | the same | about 50 lines | a table is state, so the rule allows it | yes |
| C21 | A member pump's power follows the reflux flow, not the feed share | member power moves with the reflux ratio | the same | nothing | power numbers only | yes, with M-01 |

## 3. Fluid step and laws

| Id | Change | Effect at usual states | At the extremes | Deletes | Risk | Rec. |
|---|---|---|---|---|---|---|
| X-01 | A one-way end (void, generator, blocked way, dry end) as a law in the edge row, the branch frozen inside a Jacobian build | none where the flow runs the allowed way | near zero drive the run opens inside the step, not one step later: flows under the 1 Pa reopen band, up to about 0.17 kg/s of water or 0.01 kg/s of nitrogen in a 1 m run of 0.05 m | about 230 lines: a latch set at five places, the allowed-restart rule, the interval's refusal and repeat | the slope kink at zero flow, three orders between a liquid and a gas, is why the closures are taken before the pass today | later: three forms on the quick check, one campaign |
| X-02 | The pump's suction limit as a head factor of NPSHA over NPSH3 | none above the knee | between NPSH3 and the breakdown line the flow moves by the knee's width, a few percent; the same limit at the floor | about 300 lines, one mode, the vapour-lock fact, the cavitation retry | a steep factor is a stiff row where seeds 181, 575 and 896 held; the mode is saved by ordinal in state. Revisits D14 and D15 of the rotating-machinery batch | later, after X-01, on the same campaign discipline |
| X-03 | Drop the rate solve; the pressure balance serves the interval's guard | guard and seed decisions only; no state | the cold rate seed | about 150 lines, 20 branches | to check with the interval's cold seed | later |
| L-F1 | The cooling tower's fan speed planned once per step (as the air cooler's); option B also freezes its air flow | steady state unchanged | one step of lag, under 5 % of a transient with a basin time constant of about 500 s; option B under 0.3 K on a 10 K range | the nested root searches leave the residual | step-lag oscillation at high gain | yes (the speed); B to measure |
| L-F11 | A heat curve's phase boundary bisected to 1e-3 K (1e-5 K today) | the plateau edge moves about 2e-6 relative | the same | about 14 flashes per boundary per resample | heat-side references at 1e-6 | yes |
| L-F14 | Gas viscosity by Herning-Zipperer (O(C)) instead of Wilke (O(C²)) | within 5 % of Wilke on hydrocarbon gas; turbulent pressure drop moves 1 to 2 % | 10 to 20 % with hydrogen | about 10 lines; 1 600 terms per gas viscosity at 40 compounds | a second model, where the owner's rule is "no new reference models"; every gas line's reference | no, unless B1 shows Wilke among the top costs |
| L-F7 | Delete `ScheduledTransfer` (no production creator; 52 call sites) | none | none | about 80 lines | tests move to a boundary generator or void | yes (STRUCTURE; listed for visibility) |

## 4. Equilibrium engine

| Id | Change | Effect at usual states | At the extremes | Deletes | Risk | Rec. |
|---|---|---|---|---|---|---|
| P25 | Inside one PH or UV search: start each flash from the previous iterate, test products only at the accepted iterate, one cold solve at the answer | T and P inside the search's own tolerance (1e-11 relative); energy inside 1e-6 N R T | near a phase boundary a warm flash can hold a split one iterate longer | nothing; most of a search's stability tests | to measure with B1's count of TP solves per PH and UV request | yes, after B1 |
| X30 | One N-slot flash for every compound list (the two-phase flash is the three-slot one with a slot dropped) | amounts within the flash tolerance (about 1e-9 relative) | a phase under 1e-10 of the feed loses the minor-fraction precision; outcomes near a critical point can change | about 510 lines | every network reference re-recorded | later, after the engine is split (D engine) |
| X31 | The lever rule for every binary three-phase step | the same state within 1e-11 in T | about 30 more TP solves per request on such a step; water with one hydrocarbon at its boiling step is a usual state | about 540 lines | cost | no, unless B1 shows the step is rare |
| X32 | Exact inner volume solves in the nested UV search | inside the same tolerances; a few more TP solves far from the answer | none | about 40 lines, five flags, two recorded wrong-side defects' machinery | cost | yes |
| X33 | One stability test with the long budget | verdicts equal; answers inside the flash tolerance | none | about 15 lines | small | yes |
| D09, F19 | One cubic solver: delete `HydrocarbonModel`'s second one | translations move by the root's rounding (about 1e-12 relative) | the same | about 45 lines | network references at the last bits | yes |

## 5. Process simulator against the world

| Id | Change | Effect | Deletes | Risk | Rec. |
|---|---|---|---|---|---|
| P3-02 | A compressor stage's discharge temperature on the world's polytropic exponent | ratio 3 from 300 K: 175 °C instead of 137 °C, against the 150 °C row | 6 lines, a finite-difference heat capacity | more compressors read yellow | yes |
| P3-03 | The ejector's load on vapour amounts in both the sheet and the world | none on dry loads; a wet suction's need falls by its liquid share | 12 lines | the sheet's need on wet loads | yes |
| P3-13, L-F12 | The sheet's standard exchanger on the world's cell march | the sheet's duties move by the cell discretisation; to measure on the same curves first | about 60 lines, a second counterflow law | unmeasured | later, after a gate that compares both |
| P3-14 | The sheet's PH loop replaced by an engine PH request without crystals | within the flash tolerance | 55 lines | needs the engine's request | later |
| P3-15 | Chimney friction on the stream's viscosity | at most 5 % on the inlet need | 1 line | none | yes |

## 6. Equipment and saves

| Id | Change | Effect | Risk | Rec. |
|---|---|---|---|---|
| E20 | The layout stamp as a declared revision per design (it is an FNV hash today) | one refresh of every placed structure at the switch; facts kept | the golden test must fail when cells change and the revision does not | yes |
| R11 | The certificate's graph digest (SHA-256) replaced by a declared replay revision | certificates of saved islands are dropped once and rebuilt (state) | a decode drift would go unseen but for the codec's round-trip validation | yes |
| P3-06 | Built-in control channel ids as a declared, append-only table; the MD5 kept as an alias reader | none while no channel is renamed | a facts reader change; needs a corpus image | yes, before any role or channel is renamed |
| R20 | Refuse a world from before the envelope instead of resetting it | such a world no longer opens | revisits owner D4 | owner's view |
| E14 B, E21 | Drums on the units' skirt and saddle; one manway for every kind | drawing changes; one refresh of those kinds | owner's taste | owner's view |
| R3 | Present only devices that have a consumer; a first open of an idle device waits at most one bucket (5 s) | presentation cost falls with the share of unwatched devices | a "waiting" line on first open | yes, after R1 and R2 are measured |
| R7 | Close each island's entry at its own completion, not the whole round | coupled sides may read a newer partner | results of coupled islands can move | later, with a reason for today's rule sought first |

## 7. Not proposed

- The carried-crystal rule (D23) and the near-pure rule (D21): about 450 lines of owner decisions, left as they are.
- `VALVE_CONFLICT` and the series-regulator rules: a property of the control set, not a law to fold.
- The vertical-line content (`columnEnds`): an averaged column errs by up to 19.5 kPa on a 4 m water and gas riser.
- The dry-vessel and emptying-vessel rules (D54): open question 6 of the step review asks the owner's view before any proposal.
- The column's feed flash on the equilibrium engine (F14): it would add the dependency the de-correlation removes.
