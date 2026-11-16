# Supercritical fluids: plan

Status: proposed 2026-11-15, base `e9edc974b` (main 0.31.0). Nothing is built. The limits it answers:
[SUPERCRITICAL_LIMITS_REVIEW.md](SUPERCRITICAL_LIMITS_REVIEW.md) (ids A1..D4 below). Stages S1 to S5 wait for the
owner's decisions O1 to O7; S0 needs only O1.

## 1. The idea

A supercritical fluid is one phase whose properties are continuous. So the plan adds no phase, no slot and no saved
field. It does three things:

1. **One descriptor.** A node is a *dense one-fluid node* when it holds one fluid phase on one root and its isotherm
   is supercritical: `a/(b R T) < SUPERCRITICAL_ATTRACTION_RATIO` (`PhaseIdentification`, since S1: the kernel's own `0.45724 / 0.07780` = 5.87712, so that a pure component's test is exactly `T > Tc`; the exact PR ratio is 5.87736) for the phase's mixture `a`, `b` on the untranslated cubic (the
   cubic then has one root at every pressure for this composition; for a pure fluid this is exactly `T > Tc`). The
   constant is the kernel's own (`PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO`'s comment); `Z` and `v/b` are on
   `PhaseTraits` since 0.31.0, and `a`, `b` are on the phase state it is formed from. No mixture critical point is computed, and no averaged critical constant is read.
2. **Laws read traits, not labels.** Every law that asks "gas or liquid" of such a node reads its reduced density
   `δ = 3.9513730355914 b / v` (1 at the cubic's critical volume) or a typed "no saturation". Subcritical nodes keep
   their laws bit for bit, because a subcritical one-root phase never has a supercritical isotherm.
3. **The band answers instead of holding.** A pure fluid above `Tc` and `Pc` cannot split, so its boxes go. A
   near-critical split that does not converge is answered as one fluid with a notice.

Ruled out:

- A fourth slot or a saved "supercritical" amount: a facts change with no physical content (the amounts are one
  phase's), and every layout, codec and view would grow.
- A mixture critical-locus calculation: heavy, and not needed to decide any law above.
- Pure-component or mole-averaged critical constants as the test for a mixture: wrong for stability (earlier review,
  [../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md](../2026-09-24-coolprop-low-temperature/SUPERCRITICAL_SUPPORT_REVIEW.md)).
- A supercritical tray in the column or the still: two phases are its construction. It stays a typed refusal.

## 2. Owner decisions

| Id | Decision | Options (first is the recommendation) |
|---|---|---|
| O1 | Which fluids and uses first | **A** dense gases and carbon dioxide, propane, ethylene, ethane: lines, vessels, pumps, compressors, exchangers. **B** A and supercritical water and steam (boilers, turbines to 30 MPa): needs a water model above 647 K, see S6. **C** A and process units (solvent deasphalting, extraction): see S7 |
| O2 | Slot of a dense one-fluid node (storage and display row only, once S2 is in) | **A** by density: oil slot when `δ ≥ 1` (the critical isochore, through the critical point; the trait the laws use). **B** keep `PIP > max(1, Z)`. Both agree on every one-root state inside today's data ranges (`PhaseIdentification`'s class comment), so subcritical references stay exact either way |
| O3 | The critical band | **A** remove boxes 3 and 4; a failed in-band split is one fluid on its lower-Gibbs root, graded research-only, with a status notice; `CriticalBandHold` is deleted. **B** remove boxes 3 and 4 only; other in-band failures still hold. **C** no change. A's cost: a split with `Σ z (ln K)² < 0.1` is not separated; mass and energy stay exact, the node's density is off by at most the two phases' gap (to measure in S0) |
| O4 | Pressure ranges | **A** raise each qualified dataset's `pressure_max_pascal` to the pressure S0 qualifies (target 30 MPa for carbon dioxide and the light gases). **B** leave 2 and 10 MPa; dense states run with an "outside data range" notice and the 10 MPa input box |
| O5 | Dense viscosity | **A** dilute viscosity plus the Jossi-Stiel-Thodos residual in `δ` (needs `Tc`, `Pc`, molar mass only; no new data), for dense one-fluid nodes. **B** keep the dilute value (about half the real one for carbon dioxide at 320 K, 10 MPa; to measure) |
| O6 | Mathias-Copeman curvature step at `Tc` (A6, X34) | **A** leave it and state its size from S0. **B** a C2 blend over a few kelvin |
| O8 | Pairs of a crude cut with a light gas (today zero) | Options A to D of [SUPERCRITICAL_DATA_REVIEW.md](SUPERCRITICAL_DATA_REVIEW.md), P5; sized in S0 before the choice |
| O9 | A missing pair between real compounds | **A** the E-PPR78 table already in the data. **B** zero, as today. Moves every mixture with such a pair; sized in S0 |
| O10 | Critical volume | **A** an appended dataset field (ChemSep for real compounds, the cut's own measured density for a cut). **B** the cubic's own value |
| O7 | A dense-phase distribution in the hold-fuzz campaign | **A** none; three GameTests and the gates of S2 judge. **B** add one distribution (more campaign time on every later batch) |

## 2a. After S0 (2026-11-15): what the measurement changes

S0 is done ([SUPERCRITICAL_QUALIFICATION_REVIEW.md](SUPERCRITICAL_QUALIFICATION_REVIEW.md); the main thread checked the
test results, the Gradle logs and the diff: no file under `src/main` changed). The recommendations of section 2, revised:

| Id | Measured | Recommendation now |
|---|---|---|
| O3 | No request failed on 36 isotherms of four binaries. Answering one fluid at the first tie line below 0.1 is off by -2.7 to +17 % in density (the two products still differ up to 2.4 times); at a tie line of 0.03 or less, within 1 % (two states) | **B**: remove boxes 3 and 4 only; an in-band failure stays a hold. A is not taken as written. If S2's GameTests meet such a hold, a fallback below a tie line of 0.03 is the option to measure then |
| O4 | Outside the band, density within 7 % and `cp` within 12 % to 30 MPa for carbon dioxide, nitrogen, methane, ethane, propane, and hydrogen from 273 K; ethylene but one state (`cp` -12.1 % at the edge of box 1); bundled n-hexane to 4.6 MPa, toluene to 6.2 MPa. Mixtures (three binaries, 105 states): within 5.0 % | **A**, with the liquids measured (M8, 2 to 30 MPa): **30 MPa** for carbon dioxide, nitrogen, propane (liquids hold their bounds to 30 MPa), methane, ethane (no liquid state inside their 293.15 K domain floor) and hydrogen (from 273 K); **ethylene 20 MPa** with `cp` declared at 13 % (edge of box 1) and 14 % (cold liquid at 141 K, an error it already has at 2 MPa), or 2 MPa with nothing declared; **n-hexane 4.6 MPa, toluene 6.2 MPa** (their supercritical density limit). Pressure does not degrade a liquid: near `Tc` the density error is largest next to saturation (-3 to -8 %, present at today's 2 MPa) and is +1 to +3.6 % at 30 MPa |
| O5, O10 | Dilute viscosity is 13 to 96 % of the reference (carbon dioxide at 319 K, 10 MPa: 49 %). With the residual: -16 to +8 % on the reference critical density, -3 to +28 % on the cubic's | **O5 A with O10 A** (the critical volume as a dataset field). The published coefficients are a tenth of those in S0's brief; the test uses the published ones |
| O6 | Only water has a Mathias-Copeman alpha; its `cp` step at `Tc` is 1.5 % at `Pr` 0.5 and 4.4 % at `Pr` 2 | **A**: leave it and declare it |
| O8 | The network's cut-gas pairs exist already (E-PPR78 on paraffinic counts); hydrogen is 0 by the owner's rule | Closed. Hydrogen's rule is the owner's, and matters only for hydrotreating at pressure |
| O9 | 197 nonpolar pairs at zero; E-PPR78 gives -368 at 500 K for one of them | **B**: leave zero |
| Water (S6) | The bare cubic: -30 % density at 650 K and 25 MPa; within 5 % from 700 K; `cp` within 6 % from 800 K; enthalpy 550 to 750 J/mol low at 800 to 900 K | S6 is needed only for 647 to about 750 K; turbine inlet states (800 K and above) are already usable |
| Cuts | Surrogates: pc01 and pc02 within 5.7 % to 30 MPa, pc03 to 20 MPa, pc04 to 10 MPa; the error grows with the acentric factor | Ranges for pc01 to pc04 by surrogate; pc05 to pc12 stay at 2 MPa and `EXTRAPOLATED` above |

Section 2's table keeps the options as first written; this table is the current recommendation. S1 with O3 B is one
edit (`pureBandBox`) plus the trait fields; `CriticalBandHold` stays.

## 2b. Decisions (owner, 2026-11-15: "Accept all")

The owner accepted the recommendations of section 2a as they stand: O1 A; O2 A (a dense node's slot by density); O3 B
(band boxes 3 and 4 removed, an in-band failure stays a hold); O4 A with M8's ranges (30 MPa for carbon dioxide,
nitrogen, methane, ethane, propane, hydrogen; ethylene 20 MPa with `cp` declared at 13 and 14 %; n-hexane 4.6 MPa,
toluene 6.2 MPa); O5 A with O10 A; O6 A; O7 A; O9 B. O8 is closed.

Two findings of the main thread after the acceptance, from S0's per-state rows and the code, stated so the owner can
overrule:

1. **Boxes 3 and 4 are also where the model is least accurate above `Tc`.** In them the density error is 6.8 to 10.9 %
   and `cp` to 13 % (carbon dioxide 8.7 %, ethane 9.7 %, propane 10.2 %, ethylene 10.9 % in density), against 5.6 % and
   11 % outside every box. The network reads no grade (only `diagnostics().criticalBand()` on a failed answer), so
   removing the boxes changes no number a player sees; it changes the grade and the band flag of answers there, the
   type of a failure there (a rejected trial where it was a typed hold), and one label: a split of a feed that is 95 %
   one compound, whose two products are both dense, is `LIQUID_LIQUID` there as it is everywhere else outside the
   band (`DryCore.twoPhaseResult`, `CarriedCrystals.carriedSplit`). O3 B is carried out as accepted; the datasets'
   evidence text declares 11 % density and 13 % `cp` for `Tr` 1.0 to 1.2 at `Pr` 1.5 to 3.
2. **A "dense one-fluid node" is every one-phase node above its mixture's cubic critical temperature, so it includes
   ordinary gases** (nitrogen, methane or hydrogen at ambient temperature and any pressure). Every law S2 changes for
   such nodes therefore acts on states the references hold today (chain-100, the junction lines, the GameTests, the
   campaigns). S2 keeps them exact by making each new law identical, bit for bit, to today's below a reduced density
   (the residual viscosity is zero there, the film is the gas film, a liquid port stays dry), with a C1 blend above it.
   The threshold and the blend's width are an assumption the physics does not force: S2's review gives them as an
   option with numbers. The plan's S2 text "each only for `State.dense()`" is read with this rule.

Decisions after S2b's stop conditions (2026-11-15):

3. **O2 A stands, on a corrected premise (owner: "Keep O2 A").** Section 2's claim that the two slot rules agree on
   every one-root state inside the old data ranges is false for nitrogen: 11 states at 145 to 177 K and 6.1 to 9.8 MPa
   are liquid by the identification parameter and vapour by density ([S2_NETWORK_REVIEW.md](S2_NETWORK_REVIEW.md)
   section 6). Today's rule calls nitrogen at 303 K a liquid from 16.6 MPa (0.62 of its critical density), methane at
   295 K from 15.2 MPa; by density they are a gas to 28.5 and 18.4 MPa. The rule is carried out in S2c, after the laws
   that still read the slot (level head, suction, boil-up) read the traits, with the moved states listed.
4. **The identity bound (main thread's call, for the owner to overrule).** S3's critical-volume segment lengthened the
   fluid identity (refinery 8,024 to 10,086 characters). `MaterialCatalog.MAX_IDENTITY` is 21000 in place of 16384:
   the fluid checkpoint stores the model's thermodynamic revision, about three times the identity, as one NBT string of
   at most 65,535 bytes, so 21000 is the largest identity that can be saved; the model checks its revision against that
   limit when it is built ([S3_DATA_REVIEW.md](S3_DATA_REVIEW.md) section 4). About 95 compounds in a network package,
   74 before the change, 85 before S3.

5. **S2b's three options stand as implemented** (owner, 2026-11-15: shown the numbers, asked for no change): the
   viscosity onset between reduced densities 0.25 and 0.5; the film bounds 0.5 and 1.5; the film weight on every
   single-phase state ([S2_NETWORK_REVIEW.md](S2_NETWORK_REVIEW.md) sections 2 and 3).
6. **O2 A reaches mixtures (main thread's call inside the owner's decision).** The engine labels a one-root mixture by
   the identification parameter itself, so the rule sits on every network answer of one fluid phase on a supercritical
   isotherm, whatever the engine's label; the engine, its digest, the column and the simulator keep their labels
   ([S2C_NETWORK_REVIEW.md](S2C_NETWORK_REVIEW.md)). Their label can differ from the network's slot for a dense mixture:
   changing the engine's rule is its own item.
7. **The dense GameTests have their own namespace** (`icheme_dense_test`; `-PfluidGameTestNamespaces`, default both), so
   a new fluid GameTest can be run alone and the lane still runs all 101. Two runs of the whole fluid GameTest task were
   made with the owner's word ("Run it now", "Run once more"); the three dense tests then passed alone (run
   `s2c-dense2`). One existing test, `LevelLoopGameTests.aBlanketedDrumOfTwoCyclesHoldsItsLevelOnTheCadence`, passed in
   the first whole run and failed in the second on the same production code: not settled, left to the final lane.

8. **Main moved during the batch; it is merged in before S4 (owner, 2026-11-15: "Approve, merge first").** Main
   0.31.1 (`a6b12684d`, the fluid step cost batch) merged as `8bac3e6e7`: two conflicts, both documents; the affected
   classes, chain-100 and the dense GameTests pass. Main then took the tools large files batch and one `FORMATS.md` line
   (batch 2026-11-15-pollution-system reserves protocol version `icheme-2`): merged as `10c9578b2`, no conflict, no
   test run (documents and `tools/` only). `mod_version` on the branch is main's 0.31.1.
9. **The merge to main is a standing instruction (owner, 2026-11-16).** The owner wrote "auto merge when done". Asked
   whether that covers (1) this batch into main when it is finished, (2) the `tools/` cleanup branch into main, or
   (3) both, the owner answered "3". So this batch merges to main with no further question once every stage, the final
   lane and the merge preparation are done and the lane is clean. A failure, a hold or a moved reference that the
   reviews did not announce stops the merge and goes to the owner. The owner named no version: 0.32.0 is the main
   thread's proposal, stated again in the merge preparation report. No push. Every delegation still needs its own
   approval.
10. **S4 checked by the main thread (2026-11-16); its form of the pump's suction edge is open.** The diff, the stored
   results (test 254 classes and 1,702 tests, holdGates 136, equipmentGates 13, none failed; chain-100 deviation 0;
   dense GameTests 3 of 3) and the review agree. The brief asked for E "as a density" above Pc. Its consequence, which
   the brief did not foresee: a pump that draws one dense fluid at reduced density 0.5 to about 1.1 through a line is
   vapour-locked, where a lighter and a denser fluid both run. Nothing separates in one fluid above its critical
   temperature, so the main thread proposes to the owner that such a fluid has no edge from its density (E only where
   its isenthalp really flashes), and that a compressor's droplet wear reads no droplets in it
   ([S4_MACHINES_REVIEW.md](S4_MACHINES_REVIEW.md) section 8, options 1 and 5). Until the owner answers, S4 stands as
   implemented.

11. **S4b: one dense fluid has no second phase at a machine (owner, 2026-11-16: "ok but use a new thread").** The
   correction of point 10, on a new sub-thread. A pump's suction edge of one dense fluid stands only where its isenthalp
   really flashes; it has no vapour pressure in either slot; a compressor reads no droplets in it. On the sheet a dense
   stream has no edge and no bubble-point warning. The remaining seam in E is on the isenthalp through the critical
   point (E about Pc on one side, none on the other; reduced density 1.07 at 310 K, 1.18 at 320 K, 1.31 at 330 K), with
   the suction 1.0 to 6.5 MPa above it. Checked by the main thread: the diff, the stored results (test 235 classes and
   1,533 tests, holdGates 136, none failed; chain-100 deviation 0; dense GameTests 3 of 3)
   ([S4_MACHINES_REVIEW.md](S4_MACHINES_REVIEW.md)).
12. **The pump's own bounds (owner, 2026-11-16: "Use 0.5 to 1.0").** The owner asked how a centrifugal pump really
   behaves on a supercritical fluid. The research ([DENSE_PUMP_RESEARCH_REVIEW.md](DENSE_PUMP_RESEARCH_REVIEW.md)): it
   keeps its head, its rise follows the density, and it is run down to about the critical density. So a pump's
   liquid-like weight runs from reduced density 0.5 to 1.0 (`pumpLiquidLikeWeight`): the full head from the critical
   density, 0.91 at 0.73, the gas rule at 0.5. The compressor and the film keep 0.5 and 1.5. Not decided, from the same
   review: a minimum-density row on the simulator's pump sheet (S5), the rise on the average density (its own batch),
   the pump's bubble readers on a dense fluid (the churn, the `GAS` word: S5).
13. **S5: what the player reads (owner, 2026-11-16: "Accept all").** One dense fluid above reduced density 0.5 reads
   "Dense fluid" in either slot, "Vapor" at or below. A pump on it makes no churn and gets no `GAS` word, and its page
   shows the head share. The simulator's pump sheet has a row "Suction density", at least 0.86. Two choices were the main
   thread's: the label's source is the client's own catalog (no wire field; the fallback is the old label), and the pump
   page reads "Head share". Checked by the main thread: the diff (the server's part is one observed array), the stored
   results (every gate group, chain-100 deviation 0, dense GameTests 3 of 3), the renders
   ([S5_PRESENTATION_REVIEW.md](S5_PRESENTATION_REVIEW.md), with its eight owner items).
14. **The column's feed (D1, D2; owner, 2026-11-16: "Approve both").** A feed with no two-phase split is answered as one
   phase, labelled by the slot rule; a tray at or above its mixture's critical point keeps its refusal with a text that
   names the cause. Built on its own branch and merged into the batch branch after the main thread's check (`30498aaf1`).
   One assertion moved: the Tia Juana crude at 901 K and 250 kPa, refused before, is a vapour. Two owner options stay
   open: O1 (102 one-phase feeds still refused) and O2 (181 real splits answered as one phase since before the batch)
   ([COLUMN_FEED_REVIEW.md](COLUMN_FEED_REVIEW.md)).
15. **A plunger pump (owner, 2026-11-16: "add a positive displacement pump head for supercritical service, research
   first", then "Accept all").** A reciprocating plunger pump of its own on the existing displacement law, with no
   clearance term, the work as flow times rise, no chilled head, and a sheet type with no minimum-density row
   ([PD_PUMP_RESEARCH_REVIEW.md](PD_PUMP_RESEARCH_REVIEW.md), [PLUNGER_PUMP_PLAN.md](PLUNGER_PUMP_PLAN.md): stage S8).
   Its model is the main thread's ("model the pump head here"); of three rendered forms the owner chose "Form A", the
   horizontal monoblock. Order ("Pump first, merge last"): S8 is built on the branch as it stands; main is merged into
   the branch at the very end, before the final lane.
16. **Main is at 0.32.0** (the pollution system batch, `f305ac6b0`: `icheme-2`, facts schema 26). This batch's version
   proposal is 0.33.0. Two `FormatsTableTest` cases fail on the branch until that merge: `FORMATS.md` holds the pollution
   batch's two reserved rows, whose constants arrive with main.
17. **S3's ranges moved four grade pins**, found by S5's run. A state at 12 MPa was beyond the 10 MPa envelope and read
   `EXTRAPOLATED`; inside the 30 MPa envelope it reads `ESTIMATED_DECLARED_ERROR`, every number the same to the bit.
   The three test cases (`FluidTpEquilibriumTest`, `FluidTpEquilibriumPhUvTest`) now ask at 36 MPa, beyond the new
   envelope, and one asserts that 12 MPa is inside. The engine digest's reference (`EngineDigestTest`, first at request
   313) is re-recorded once by the final lane: only the grade token of states between 10 and 30 MPa may differ.
18. **The order changed, and three numbers (owner, 2026-11-16: "Keep the order", then "uh merge main first").** The
   plunger pump's law is saved, so it needs fluid facts schema 28 and checkpoint state format 51; the pump's plan
   had missed this. Main is at 0.33.0 (`d756e121a`: schema 27, `icheme-3`), so main is merged into the branch before
   S8, and the numbers are reserved on main (`66a87f9d5`, with `icheme-4` in case a constructor payload changes). This
   batch's version proposal is 0.34.0.
19. **Main 0.33.0 merged into the branch** (`dfc9d9e55`, the rare gases' critical volumes `0f4230144`;
   [MERGE_MAIN_0_33_REVIEW.md](MERGE_MAIN_0_33_REVIEW.md)). Checked by the main thread: the three commits, the numbers
   (0.33.0, facts schema 27, checkpoint 50), the stored results (no failure left), a clean status. The scratch form A
   compiles and runs unchanged on the merged kit; the kit's relief nuts take each model from 1,989 to 1,275 polygons.
   One reader of the slot is new on main: the burst release splits a vessel's contents by slot and sends a liquid's
   volatile share to the air. For a dense fluid in the liquid slot that share is `min(1, Psat(298.15 K) / 1 atm)`
   per compound, so carbon dioxide, nitrogen, methane, ethane, propane, ethylene and hydrogen go to the air whole, and
   a dense hexane or toluene (hot) spills as the liquid it becomes at ambient. The amounts are right; only the burst
   block's word and the two reported masses say "liquid" for it. No change; an owner item.
20. **S8, the plunger pump, built and checked** ([S8_PLUNGER_PUMP_REVIEW.md](S8_PLUNGER_PUMP_REVIEW.md)). Form A
   failed three equipment gates as first drawn; the main thread changed the form, and no gate is exempted. On PP-3 and
   PP-10 the coupling face is one block further back and PP-10's discharge riser one block nearer the block (a shaft
   block stands two cells clear of a pipe block). The pump's nozzles take the largest pipe whose neck stands clear,
   never less than the design pipe, as a column's do. Owner, 2026-11-16, asked "Keep the nozzle rule and the longer
   guard on the two small pumps?": "Keep both". The necks are 3 cm longer so that each carries its flow arrows (481 of
   485 nozzles). Checked by the main thread: the diff of the saves (each writer's plunger case stands before the generic
   one), the law, the placed form against the scratch, the stored results, a clean status. One change after it, the
   main thread's: the pump page's head share is the centrifugal curve's, so a plunger or a set-flow pump on one dense
   fluid reads none (the thread's owner item 8, closed).
21. **Main moved once more** (`ab6298d03`: the fouling and corrosion batch reserved facts schema 29, shape fields 13,
   sheet file 3 and `icheme-5`; documents only). Merged into the branch by the main thread. `icheme-4`, reserved for
   S8, was not needed and its row is gone; main's row goes with this batch's merge.
22. **The final lane, its four moved printed lines, and the merge** ([FINAL_LANE_REVIEW.md](FINAL_LANE_REVIEW.md),
   [MOVED_RESULTS_TRACE_REVIEW.md](MOVED_RESULTS_TRACE_REVIEW.md)). The lane, once at `a599419dd`: 3,938 tests with no
   failure, chain-100 exact, 114 of 114 GameTests, 33 of 33 junction lines, the campaign 993 / 995 after one replay /
   1000 with no new hold left; the digest's line 313 re-recorded. Four printed lines that no test asserts moved with no
   stage naming them, so the merge stopped. One is S1's removed band (five frost-point rows, the grade label). Owner,
   2026-11-16, asked "Trace the three unexplained moved values before merging?": "Trace first". The trace (five runs by
   stage): S2b or S2c moved all three; the pump drain's trace amount by rounding, the melt gate's creep by 2.1e-9
   inside the Newton tolerance, and the cryogenic methane vessel's sixth interval onto the other of two step paths (the
   one main 0.31.0 printed), inside the 5 % step cap. The main thread checked the two branches in the code: the residual
   is exactly zero at or below a quarter of the critical density, and a liquid slot takes the dense law only on a
   supercritical isotherm, so a settled ordinary state is bit for bit. That the new branches are reached only by trial
   states in two of the three cases is read from the code, not traced. Owner, asked "Accept the three moved values and
   merge to main as 0.34.0?": "Accept and merge".

## 3. Stages

Each stage is one package for one thread, on its own worktree, in the lane named. Line numbers are not given: the
files were split in 0.31.0, so each brief names symbols.

### S0. Measure (no production code)

Goal: the numbers the decisions and the declared errors rest on. The 2026-09-24 batch measured the supercritical
region to 10 MPa on the pilot engine (density within 5.9 %, `cp` within 6.6 %, 10.6 % next to box 1); S0 measures the
network's model and the range above 10 MPa, and does not repeat that work. Data per need, the crude cuts and the
rule for a lack: [SUPERCRITICAL_DATA_REVIEW.md](SUPERCRITICAL_DATA_REVIEW.md).

- Extend `tools/coolprop-parity/` and its tables under `src/test/resources/science/thermo/coolprop/` with a
  supercritical grid per fluid: `Tr` 1.0 to 2.0 (steps 0.02 to 1.2, then 0.1), `Pr` 0.5 to 4, for carbon dioxide,
  nitrogen, methane, ethane, hydrogen (present tables), plus propane and ethylene if O1 A names them, plus water for
  sizing S6.
- New qualification test `G3F11SupercriticalRegionTest` (`src/test/java/.../science/thermo/qualification/`, pattern of
  `G3F1PureFluidOracleTest` and `G3F10QualifiedRegionTest`): the network's model (`HydrocarbonModel` on the derived
  cubic) against the tables for density, enthalpy difference from the ideal gas, `cp`, and the isothermal acoustic
  bound; prints the worst and the 95th percentile error per fluid and per reduced box. It asserts only the declared
  errors it prints (recorded once).
- In the same test: the `cp` step at `Tc` (A6), the gap between the two products of a split at
  `Σ z (ln K)² = 0.1` on three binaries (O3 A's bound), the dilute against the reference viscosity (O5).
- Crude cuts: the model against surrogate reference fluids at equal reduced state (n-hexane, n-heptane, toluene,
  n-decane, n-dodecane for pc01 to pc04); pc05 to pc12 have no surrogate and stay `EXTRAPOLATED`.
- Pairs: the bubble pressure of a crude with 1 % of carbon dioxide, nitrogen, hydrogen sulfide, hydrogen or methane
  at 0.5, 2 and 10 MPa, with zero and with each option of O8 and O9.
- Read (no run) the campaign distributions in `tools/` and state whether any seed reaches `Pr > 1` with `Tr` in 1 to 1.3.

Commands: `./gradlew test --tests "*G3F11SupercriticalRegionTest"` (one run; it loads only the thermo core).
Done: `SUPERCRITICAL_QUALIFICATION_REVIEW.md` with one table per fluid and a recommended pressure range per dataset.

### S1. Engine: the band and the descriptor (thermo core lane)

Files: `science/thermo/phase/FluidTpEquilibrium.java` (`pureBandBox`), `CriticalBand.java`, `DryCore.java`
(`hydrocarbon`, `merged`, `failed`), `PhaseTraits` and `PhaseIdentification.java`, `EquilibriumResult.java`.

1. `science/thermo/PhaseTraits.java` (a record of root count, `Z`, `v/b`, the identification parameter): add the
   component `attraction` = `a / (b R T)`, filled in `PhaseState.traits` from the `a`, `b` that
   `CubicPhaseEvaluator.fill` already stores through `setIdentification`; `supercriticalIsotherm()` =
   `attraction < PhaseIdentification.SUPERCRITICAL_ATTRACTION_RATIO` (5.87712, the kernel's ratio; S2 reads the constant); `reducedDensity()` = `VAPOUR_BRANCH_VOLUME_RATIO / volumeRatio()`. No evaluation
   added. `PhaseTraitsTest` checks the new component on every converged digest answer.
2. `pureBandBox`: delete boxes 3 and 4 (O3). `G3F10QualifiedRegionTest` moves with them; the grade inside the old
   boxes becomes the contract's, or `EXTRAPOLATED` past the dataset's pressure.
3. O3 A: in `DryCore`, the path that today returns `failed(...)` with the `CRITICAL_BAND` detail returns the `merged`
   answer (one `FLUID` phase on the feed's lower-Gibbs root, `Diagnostics.criticalBand` true, `RESEARCH_ONLY`).
   `EquilibriumResult.failedInCriticalBand` and its constructor argument go; `FailureKind` is unchanged.
4. Pitfalls: `UNSTABLE_PRODUCT` and `UNRESOLVED_PRODUCT` are not band failures and still hold (a fourth phase). The
   three-slot path (`ThreeSlotFlash`) has its own audit: only its two-slot fallback's band failure is merged. A merged
   answer must not seed a later split (`Workspace.ownSplit` false).
5. Rust twin: none (the engine is Java only; the Rust library is the column's).

Tests: `./gradlew test --tests "*EngineDigestTest" --tests "*FluidTpEquilibriumTest" --tests "*FluidTpEquilibriumP3Test" --tests "*PhaseTraitsTest" --tests "*EngineFailureKindTest" --tests "*G3F5CriticalRegionPathTest" --tests "*G3F10QualifiedRegionTest"`.
The digest proves that only banded requests move: the review lists each moved line with its old and new status.
Done: no digest line outside the band moves; no request ends `CRITICAL_BAND`.

### S2. Network: one mapping, laws on traits (fluid step lane; after S1)

Files: `science/fluid/thermo/FluidThermodynamics.java` (`adopt`, `placed`, `State`), `HydrocarbonModel.java`,
`NetworkStreams.java`, `NetworkWater.java`, `WaterSaturation.java`, `ThermoHold.java`;
`science/fluid/network/` `HeatCurve.java`, `MoverSuction.java`, `ReleasePressure.java`, `PhaseDraw.java`,
`StepEquations.java`, `PassiveStepSolver.java` (mover row), `Pool.java`; `science/fluid/solver/PhaseLayout.java`.

1. `State.dense()`: true when one fluid slot is active, its phase has one root and a supercritical isotherm.
   `State.reducedDensity()` of that phase. Computed from the phase record the state already holds.
2. `placed`: a `FLUID` phase with a supercritical isotherm goes to the slot O2 names; every other phase as today.
   `adopt`: the `CriticalBandHold` branch goes with O3 A; a merged answer adds the notice
   `near a critical point: phases merged` through `DataRangeNotices`. `ThermoHold` permits `UnsupportedPhases` only.
3. "No saturation": `NetworkWater.saturationPressure` and `WaterSaturation.pressure` return a typed absence at and
   above `Tc` in place of `+∞`; `MoverSuction`'s release pressure returns the same absence for a dense node. Each
   caller (grep `saturationPressure`, `releasePressure`) states what absence means: no suction floor, no cavitation,
   no flashing, superheated.
4. Laws, each only for `State.dense()`:
   - `HeatCurve` films: the knot's film is the liquid film at `δ ≥ 1.5`, the gas film at `δ ≤ 0.5`, a C1 blend
     between (the bounds are an assumption: an owner option in the stage's review, with the films at three states).
   - `NetworkStreams.phaseVelocityLimit`: min(configured, acoustic) from the phase's own compliance.
   - `PhaseDraw` and the ports: a dense node has no interface, so `VAPOR`, `LIQUID` and `BOOT_HC` ports draw it;
     `BOOT_WATER` does not. Level head: the fluid's density over the vessel's height.
   - The mover row's heat capacity ratio: the phase's own (`NetworkStreams.heatCapacityRatio`) in place of 1.4.
   - `boilUp`, `Pool`: a dense pool does not boil: zero rate, no division by the latent heat.
5. `MixtureViscosity.liquidSource`: with O5 A the supercritical slot rule (`VAPOR_DATA` for the oil slot) is deleted;
   a dense node reads dilute plus residual from either slot. `MixtureViscosity.revision` moves.
6. `BalanceStart`: left (a seed). Changed only if S2's GameTests show a cold-start hold.
7. Pitfalls: a node next to the slot line changes its active slot between passes, so the layout must not treat the
   change as a phase appearing (`PhaseActiveSet`, `PhaseLayout.support`); `HydrocarbonModel.RootAbsent.liquidBesideVapour`
   must use the same rule as `placed`; the prepared refill must keep its bits (`PreparedRefillTest`); a revision key
   `dense-traits-v1` joins `FluidThermodynamics.revision()`, so saved state rebuilds once from the facts (state, not
   facts: no corpus image).

Tests: `./gradlew test --tests "*FluidThermodynamicsTest" --tests "*NetworkPhaseEngineTest" --tests "*HydrocarbonModelTest" --tests "*HeatCurveTest" --tests "*PreparedRefillTest" --tests "*DataRangeContinuationTest"`,
plus new `DenseFluidGatesTest` (a carbon dioxide node swept at 10 MPa from 280 to 400 K and at 320 K from 2 to 30 MPa:
film, velocity limit, viscosity and head continuous, each step under a stated bound; no hold) and three GameTests in
`src/fluidGameTest` (a carbon dioxide loop with a cooler across the pseudo-critical line and a pump; a nitrogen
bottle at 20 MPa let down through a valve; a full dense drum drawn from its top and its bottom nozzle; each removes
what it registers and closes mass and energy exactly).
Done: chain-100 exact, the 33 junction lines and the printed lines identical (final lane); the campaign equal to the
baseline line for line, unless O7 B adds a distribution.

#### S2 as carried out (main thread, 2026-11-15, after S1 and S3)

S2 is split in three, and four points of the list above changed after the code was read:

- **S2a** (structure, every reference exact): the root's `PhaseTraits` on `HydrocarbonModel.Phase`;
  `State.oneFluidSlot()`, `dense()`, `reducedDensity()`.
- **S2b** (stated shift, dense states only): one slot viscosity function (dilute plus the residual, with an onset
  between reduced densities 0.25 and 0.5 so that gases below 0.25 keep their values); the film of a single-phase point
  by reduced density (gas film to 0.5, liquid film from 1.5); the acoustic velocity limit for a dense fluid in the
  liquid slot; the heat capacity ratio of a dense fluid; the slot rule O2 A; `DenseFluidGatesTest`.
- **S2c** (a later brief): the typed "no saturation", the movers' suction, level head, boil-up, the three GameTests.

1. **The ports need no law.** `PhaseDraw.order` ends every port's order with the phases it does not prefer, so a
   node holding one phase is drawn through any port today, whatever its slot. Item 4's port rule is dropped.
2. **The viscosity is wired in one place.** Seven callers read `model.viscosity.vapor` or `.liquid` on a slot
   directly; all go through one slot function. `liquidSource`'s `VAPOR_DATA` stays for a supercritical compound
   dissolved in a subcritical liquid (item 5 said deleted).
3. **The film reads the reduced density of every single-phase point, not only of a dense node.** Ordinary liquids
   (reduced density near 3) and gases (below 0.5) keep their film to the bit; the rule needs no isotherm test. Its seam
   is at a bubble point above `Tr` 0.96; the alternative's is at `Tc` near `Pc`. Both go to the owner as an option
   in S2's review, with the bounds 0.5 and 1.5.
4. **The cold campaign reaches dense states** (nitrogen at 126 to 170 K up to 6 MPa, reduced density 0.3 to 1.8), so
   its lines can move with S2b; "equal to the baseline line for line" holds for the bundled campaign only. The final
   lane's classifier judges the cold one.

S2c as briefed (2026-11-15, after the suction, head and boil-up code was read): the slot rule O2 A; no typed
"no saturation" (the release-pressure code already answers "no such pressure" as no limit: three callers are checked
instead); boil-up needs no change; the level head of a LIQUID port is the one law that still jumps at the slot line
(carbon dioxide at its critical density in a 5 m vessel: 23 kPa on 7.4 MPa) and is measured, not changed, since making
it follow the density needs Jacobian entries the block structure does not have; the pump and compressor readings of a
dense fluid are reported for S4; the three GameTests.

### S3. Data: ranges and viscosity (materials lane; beside S1)

- O4 A: `pressure_max_pascal` and the evidence text of each dataset S0 qualifies; each record's own `revision` bumped;
  the package hull, the grading and the input box follow with no code. `FORMATS.md`: no row changes.
- O10 A: `critical_volume_m3_per_mol` appended to the datasets (absent: the cubic's value); the cuts' pressure
  ranges by surrogate (pc01 to pc04); a pair library for the cuts if O8 is not A. Each record's own revision.
- O5 A: the residual viscosity in `science/fluid/transport/MixtureViscosity.java`, its own `revision`.
- Tests: `MaterialCatalogTest`, `FluidPropertyCoverageTest`, `G3F9DataRangeBoundaryTest`, `RangeContinuationGateTest`.

### S4. Machines and the simulator (machines lane; after S2)

- `science/machine/GasEntrainment.java`, `Wear.java`: entrainment, wet gas and cavitation are laws of two phases. On a
  dense one-fluid node none applies; the pump's rise follows the delivered density as today. Running dry: a pump on a
  dense fluid below a reduced density (owner option in the stage's review; proposal `δ < 0.5`).
- NPSH3 floor: absent saturation means no floor.
- The compressor's head at `Z` 0.3 to 0.6: read the law first (`science/machine`, the mover row); if the discharge
  comes from the real enthalpy it stands; if from an ideal-gas exponent, an option with the error from S0.
- Simulator (`science/flowsheet/FlowThermo.java` `p_v`, `unit/MoverUnit`, `unit/EjectorUnit`, `sizing/`): the same
  absence; line and machine sizing by density where it reads a phase label (P3-29).
- Tests: the classes' own tests, `SheetSizingTest`, `FlowsheetExamplesTest`; no in-game check.

S4 as briefed (2026-11-15, after S2c measured the steps): a pump's vapour share and a compressor's condensed share of
one dense fluid follow the liquid-like weight of S2b's film (1 - w and w between reduced densities 0.5 and 1.5) in
place of the step at the slot line; the pump's suction limit above the critical pressure becomes a density, not the
slot line; the compressor's head law at low compressibility is measured, not changed; the simulator reads the same
functions. Not in S4: the head seam, a vent or blanket on a dense vessel in the liquid slot, the engine's labels.

### S5. Presentation (client lane; after S2)

Read `.claude/skills/gui-creation/SKILL.md` first. A dense node's row reads "Dense fluid" (the label rule is a
presentation function of `State.dense()` and `δ`; no saved or wire field beyond one flag in the view), its level
reads "no interface". `en_us.json` only; the translation commit at merge preparation. Judged by software renders
(`tools/heat-gui-renders/`) and the gates; one in-game check offered to the owner at the end.

### S6. Supercritical water (only with O1 B)

Water above 647 K is the bare cubic today (A7). Options for the owner, with S0's water numbers: **A** continue
`WaterShare`'s gaps above `Tc` on IF97 Region 3 and the full Region 2 (a wider reference, against the rule "do not
add new reference models": the owner's call). **B** the bare cubic with its declared error and a notice. Then the
input box to 30 MPa, the steam turbine and the HRSG coil on "no saturation" (C4).

### S7. Process units (only with O1 C)

A dense solvent beside a heavy liquid is already two fluid phases to the engine (`LIQUID_LIQUID` or `VAPOR_LIQUID`,
A5) and settles by density in a drum. First a GameTest of propane and vacuum residue at 4 to 5 MPa across propane's
critical temperature, to see what the existing vessel gives. A new unit is planned only from that result.

### Column (beside any stage, column lane)

D1: `V3FeedFlash` answers a feed above its mixture critical point as one phase (the Rust twin in the same batch, or
the native ceiling lowered below it). D2: a tray whose two roots coincide is the typed refusal it is; its text names
the critical point.

## 4. Order, verification, merge

- Order: S0 → decisions → S1 beside S3 → S2 → S4 beside S5 → one final lane (`tools/merge-lane/lane.sh`) → merge
  preparation (`merge-prep` skill; a minor version proposed). The column item can run beside any of them.
- Before S1 starts beside another batch: `documentation/reference/COLLABORATION.md` (hot files:
  `FluidThermodynamics`, `PassiveStepSolver`, `StepEquations`, `FluidTpEquilibrium`).
- Results that move: banded engine answers (S1), dense nodes' films, limits and viscosities (S2, S3), nothing
  subcritical. Each stage's review states old and new values with their bound. Conservation stays exact.
- Formats: no facts field, no schema number. The state basis moves once (the revision key and the records' revisions).

## 5. Delegation proposed (each needs the owner's approval when its stage opens)

| Stage | Type | Model and effort | Why |
|---|---|---|---|
| S0 | Sub-thread (runs one Gradle test) | Opus medium | a harness with a clear brief |
| S1 | Subagent writes, main thread runs the tests | Opus high | engine internals, a digest to keep |
| S2 | Sub-thread in its own worktree | Opus high | the step's laws, GameTests to run |
| S3, S5 | Subagent | Opus medium | data and labels with a clear brief |
| S4 | Subagent | Opus high | machine laws |
| Final lane | Sub-thread | Opus medium | runs and reads lanes |
