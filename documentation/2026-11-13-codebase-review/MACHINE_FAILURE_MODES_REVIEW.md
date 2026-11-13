# Rotating machines: failure modes, rates and coherence

Base `259d22a2c`, 2026-11-13. Written by the main thread from [MACHINES_REVIEW.md](MACHINES_REVIEW.md) (ids M-01 to M-20, K1, K2, N1 to N7), in the order AGENTS.md sets for a physics report. Nothing was run; every number is from the formulas in the code. The cost side (what runs per dispatch, commit and decision) is in MACHINES_REVIEW section 3.4; B1 measures it.

## 1. Expected behaviour

A machine at process-simulator depth has an operating point on its curve, an efficiency and a duty. It breaks when it is run in a way an operator can see and correct, and it does not break in clean running (owner D34). The same fault should cost the same kind of damage on every machine it can reach:

| Fault | Real machines it damages |
|---|---|
| Vapour at a liquid pump's suction (NPSHA near NPSH3) | centrifugal pumps erode; a liquid ring cavitates near its vapour pressure |
| No liquid in a liquid machine | every liquid pump seizes |
| Liquid in a gas machine | a dry screw and a reciprocating compressor break fast; a centrifugal compressor erodes |
| Wet steam through the last stages | a steam turbine's blades erode above about 12 % moisture |
| Running far below best-efficiency flow | a centrifugal pump heats and recirculates |
| More power than a part is rated for | a gearbox pits; a motor is protected by its drive; a generator by its load rule |

A protective trip or limit prevents the damage; wear is what the fault does when it is left standing. Lives are in process time, one repair kit repairs any worn machine, and a machine's page says the fault in one word. The driver pays exactly the work its machine gives the fluid, divided by the efficiencies between them.

## 2. What the code does

1. **Only liquid pumps and dry screws wear.** Cavitation by Gülich's intensity (8 h of process time at the peak) and running dry (40 min); a dry screw wears on liquid above 1 % by volume as a step, while its noise ramps to 10 % (M-06). Compressors, liquid rings, turbines and gearboxes take any fault free.
2. **A failed dry screw stays failed.** The repair kit accepts pump casings only, and the device page reads health for liquid movers only (M-02, checked).
3. **Both protective trips cannot fire.** Every motor compiles as a variable-speed motor, so the overload trip is unreachable (checked); every steam turbine is stepped governed, so the overspeed trip is unreachable. Only `NO_POWER` is live. The code guide says both trips exist (M-03).
4. **The driver and the gas disagree on the shaft power.** The solve gives a compressor's gas its work at the suction's own heat-capacity ratio and wet efficiency. The train is charged a power recomputed with k = 1.4 and no wet factor (checked); a set-flow in-line compressor is divided by a second efficiency. Electricity drawn against the work the gas gets: +13 % on propane at ratio 3, +4 % on steam or methane, −2.6 % to −11 % on a wet centrifugal machine, +54 % on an in-line set-flow compressor (M-01). A variable-speed drive in Auto inverts the curve with k = 1.4 too: +3.7 % pressure at ratio 2 on a k = 1.2 gas (M-07).
5. **One plan word means two things.** `FAILED` carries both "health 0" and "no electricity"; the health rule's "switch back on" overwrites the electric close, so an unpowered in-line pump runs every other slice (M-04, inferred from the code, not run).
6. **A gearbox's rated power is saved and never read** (M-13). A failed pump reads `RUNNING` on its Drive group (M-16).
7. **Rates are coherent** on process time, with two stale comments (M-17).

## 3. Proposal

### 3.1 Fixes (no owner choice: the physics forces them)

| Fix | Finding | What moves |
|---|---|---|
| The step result carries each mover's shaft power once; the train, the stores and the column's member power read it | M-01, M-07 | electricity draws, store lives and generator loads by the sizes in section 2, item 4; no network reference reads them |
| The repair kit takes every worn machine; every worn mover's page reads its health | M-02, M-16 | nothing |
| One availability plan per mover (run or not), decided once from failed, unpowered or switched off | M-04 | an unpowered in-line pump stays closed; to confirm on a test before the fix |
| Motors with 12 poles do not throw | M-19 | nothing |

M-01 is the one fix that changes numbers a player sees. It is listed under the owner's decisions for visibility, with the recommendation to take it.

### 3.2 Owner options: the failure-mode matrix

D34 chose "cavitation and dry running only, keep this simple". Every new row asks the owner to widen it. Two lives cover every row, so no config key is added: the erosion life (`cavitationProcessHours`, 8 h) and the seizure life (`dryProcessMinutes`, 40 min). Each row is driven by a quantity the solve already computes, except N3.

| # | Machine | Fault | Driving quantity | Life at a typical fault | Word | The player's fix |
|---|---|---|---|---|---|---|
| K1 keep | Centrifugal and in-line pump | cavitation | σ = NPSHA/NPSH3 | 8 h at σ 2.06 | CAVITATION | raise the suction level, cool the feed, slow the pump |
| K2 keep | Every liquid pump | running dry | dry flag | 40 min | GAS | prime it |
| N1 | Centrifugal pump | below 30 % of best-efficiency flow | flow over rated flow at speed | 40 min dead-headed; 2.7 h at 15 % | LOW FLOW | open the discharge, add a minimum-flow recycle |
| N2 | Centrifugal compressor | wet gas | condensed mass share at the suction | 8 h at 5.5 % and above; 16 h at 3 % | WET GAS | knock-out drum upstream |
| N3 | Reciprocating compressor | liquid | suction liquid volume share (**one more read per solve**) | 40 min at 10 %; 400 min at 1 % | LIQUID | knock-out drum |
| N4 | Steam turbine | wet exhaust | condensed share at the exhaust node | 8 h at 20 %; 16 h at 16 % | WET EXHAUST | more superheat, a higher exhaust pressure |
| N5 adjust | Dry screw | liquid | suction vapour share (exists) | the step at 1 % becomes the noise's ramp: 400 min at 1 %, 40 min from 10 % | LIQUID | knock-out pot |
| N6 | Liquid ring | ring cavitation | suction pressure over the seal's vapour pressure | 11 h at 4 kPa; none above 6.3 kPa | CAVITATION | air bleed, a stage ahead |
| N7 | Gearbox | over its rating | through-power over rated power | option A (recommended): none, the train is limited to the rating; option B: 8 h at twice the rating | GEAR LIMITED | a bigger gearbox |

No wear, by construction: a motor (its drive's current limit protects it), a generator (its load rule), a centrifugal compressor's surge (the anti-surge recycle is inside the law; an optional word `RECYCLE` shows the power it spends, M-05), engines and gas turbines (owner P11), shafts, fans, ejectors, the creative motor and the Create coupling.

Recommended set: N1, N2, N4 and N5 with the structural change below, and N7 option A. N3 costs a read per solve and N6 is a narrow case; both can follow.

### 3.3 Owner option: the unreachable trips

- **A (recommended):** delete the fixed-motor and ungoverned-turbine branches; keep the two trip names for saves; correct the guide. The steam turbine then follows P11, like the engines.
- **B:** restore an ungoverned turbine so the overspeed trip can fire.

### 3.4 Structure (results identical)

- One `MachineHealth` for every machine, generalised from `PumpHealth`, with a fault table in `Wear` (`CAVITATION`, `DRY`, `LOW_FLOW`, `WET_GAS`, `LIQUID`, `WET_EXHAUST`), each with its intensity and its class. The sound reads the same intensity as the wear. The saved facts stay a map from device to health (M-12).
- Status words as one enum with a lang key each, across drivers (M-20).
- `science/machine` imports only `science/math`: the ejector law and its rated constants move in; the `FlowControl` wrapper moves out (M-14).
- `ShaftTrains` keeps the trains; burners, flares, chimneys, prime movers, stores and health hang on one plant hook list (section 3.4 of the review).

### 3.5 What each option risks

- N1 makes a dead-headed pump fail in 40 minutes where it runs forever today: a player's standing spare pump on a closed discharge is the case to state in the changelog.
- N4 reads the exhaust node, which may mix other streams; the turbine's own exhaust quality would need one PH flash per commit.
- N5 lengthens the life at 1 % liquid tenfold.
- N7 option A can limit a train that runs today on an under-rated gearbox.
