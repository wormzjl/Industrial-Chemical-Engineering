# W4: the batch still on the one equation of state, with water

Batch [2026-11-11-aqueous-solvents](README.md), 2026-11-12, at ab21425ad. Owner: "Write W4 design". It follows
[AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md) section 7 (W4: "a 10 % wash starts near 92.5 C with y near 0.26")
and the owner's rules of 2026-11-11: one equation of state for the still and the column, three fluid phases from the
physics, no hold, "performance friendly (CPU/RAM)" (2026-11-12). Nothing here is built.

## 1. Expected behaviour, in numbers

- A wash of 10 % ethanol by volume is 0.0332 mole fraction ethanol (7.89 g in 89.8 g of water). At 1 atm it starts
  to boil at 92.5 C and its first vapour holds 0.26 ethanol (Perry's table; the derived model is within 0.12 K and
  0.004 on that table).
- A pot still (no column) follows Rayleigh: the distillate starts at 0.26 and falls as the pot goes to water at 100 C.
- A rectifier never passes the azeotrope: 0.906 at 78.1 C on the derived model (measured 0.894 at 78.15 C). With 8
  trays at a reflux ratio of 3 from that wash the first distillate is about 0.8 (my McCabe-Thiele by hand on
  Perry's table; the gate computes it on the model). The two latent heats (38.6 and 40.7 kJ/mol) make the molar
  flows nearly constant.
- Water and an oil that do not mix boil together below both boiling points: water with n-hexane at 61.4 C with a
  vapour of 0.791 hexane on the model (measured 61.6 C, 0.79). The condensate settles into two layers.

## 2. What the code does now

- `StillColumn` (the bubble-point method, Wang and Henke, on `HydrocarbonModel.phase`) has the compounds as its
  basis and puts water at zero in every phase it evaluates (`conserved`). The equation of state under it already
  carries water as a compound (W3a).
- In the world (`StillHead.run`) the column gets the pot's compounds without its water. Water's share at the head is
  fixed at 1: every mole of water vapour the pot releases leaves with the distillate.
- So an ethanol and water wash is distilled as pure ethanol: the column returns R/(R + 1) of the ethanol and none of
  the water, and the distillate comes out leaner than the pot's own vapour. That is the defect W4 removes.
- The sheet's unit (`BatchStillUnit`) refuses any charge with water ("a pot still with free water foams and bumps").
- `BatchStill.step`, the pot's law, is used by the sheet's batch (`StillBatch`) only; in the world the pot is a
  network vessel and its vapour is the network's own equilibrium, three phases included since W3b.

## 3. Design

**D1. Water is one more component of the column.** Where the model carries water (`HydrocarbonModel.wet()`), the
column's basis is the model's conserved basis, water last; `conserved` goes. Water's critical constants for the
Wilson start come from the model's own record. A list without water (the spine packages) keeps the compounds.
`StillHead.run` passes the whole pot and takes water's share from the column like every other share.

**D2. A liquid is stated by the engine where water is in it.** The method evaluates a stage's liquid as one liquid.
That is wrong for a liquid that settles into two (the single liquid's fugacities are too high, so it boils too
cold). Two liquids matter in two places, and both are stated with the engine's own TP equilibrium
(`FluidThermodynamics.flashTP`), with no new thermodynamics:
- the pot (once a solve): its bubble point as one liquid, then the engine's split at that temperature; with two
  liquid slots, the bubble point of the oil layer, the split again there, until the temperature stands (two or
  three rounds). The vapour is the oil layer's; K is that vapour over the pot's overall composition, which is all the
  balance of the stage above reads.
- the condensate (once an iteration): the same, on the head vapour's composition. It gives the two layers'
  amounts and enthalpies.
A liquid with no water in it is never sent to the engine. A refused flash reads as one liquid.

**D3. The reflux is drawn from the oil layer first** (a decanter, section 5). Per mole of distillate the condensate
is R + 1 moles: the oil layer O, the aqueous layer A. The reflux takes the share ρo = min(1, R/O) of the oil layer
and ρa = max(0, (R - O)/A) of the aqueous one. With ωi the oil layer's share of component i in the condensate, the
share of i that goes back is si = ρo ωi + ρa (1 - ωi). One liquid gives si = R/(R + 1) for every component: today's
column exactly. In the method:
- the condenser's row of the balance becomes -(R/si) x0,i + V1 K1,i x1,i = 0 (today -(R + 1) x0,i; a component with
  si = 0 has x0,i = 0); x0 is the reflux, and the distillate is (R + 1) y1,i (1 - si);
- the flow update reads the reflux's enthalpy hR where it reads h0: V(j+1) (H(j+1) - hj) = (R + 1) H1 - R hR - hj;
- the energy out of the pot and the column per mole of distillate is (R + 1) H1 - R hR; the condenser's duty is the
  top vapour times (H1 - hC), hC the whole condensate's enthalpy. With one liquid hR = hC = h0, the same arithmetic
  as now.
The shares si are the previous iteration's (the condensate's composition moves little between iterations). They are
continuous where a second layer appears (a layer of no amount changes nothing) and where the oil layer runs out.

**D4. Trays hold one liquid; a tray that would hold two warns.** With the oil layer returned first, trays carry
the oil liquid and its dissolved water, and steam passes up through them (it lowers the tray temperatures, which
the column does not see today). At convergence each tray with water in it is checked once with the engine; a tray
whose liquid would settle raises a notice ("water on the trays"), never a failure: the owner's rule for the V3
column's free water, and the plan's for W5.

**D5. The sheet.** `BatchStillUnit` takes water on its axis. Its refusal narrows to what its text says: a charge
whose liquid settles into two liquids at the pot's start (free water). A pot that settles during the batch ends
the batch there with the same text as a warning (`Split` says the pot holds two liquids). `BatchStill.step` stays a
one-liquid pot; the world's pot is the network's and may hold two.

**D6. Saves.** No facts field changes: a still's saved accounts are already on the island's list, water included.
The column's profile is a warm start and is not saved; a profile of the old basis is dropped by its length check.

**D7. Cost.** No list without water in the pot does more than now. With water: at most 3 engine flashes for the
pot a solve, 1 an iteration for the condensate, 1 a tray at the end. A flash costs 0.7 to 2 ms on a 46-compound
liquid (section 44 of the W3b plan) and far less on a wash's three compounds. No cache, nothing kept between
solves, nothing on the tick: the world's column runs on the engine's worker, the sheet's on the run's thread.

## 4. What moves and what does not

- Does not move: every still result on a list without water, and on a wet list whose pot holds no water. The
  existing references of `BatchStillTest`, `StillHeadTest`, `StillRuntimeTest`, `BatchStillUnitTest` and
  `StillSizingTest` are the gate; a last digit that moves is reported, not re-pinned silently.
- Moves: a pot with water. A wash is rectified. A wet oil's water still leaves with the distillate (the oil layer
  supplies the reflux), and its steam now lowers the trays' temperatures.

## 5. Choices the physics does not force

1. **The reflux from a condensate of two layers.**
   - a. Oil layer first (D3; recommended). It is today's rule for wet oils as its limit, it keeps the trays one
     liquid, and it is continuous. Water and ethanol with an entrainer (hexane) then behaves as a Dean-Stark head.
   - b. Both layers in the condensate's proportion. One statement, but water runs down an oil column: every tray
     must then be stated with the engine at every iteration (about 8 times the flashes), and the bubble-point
     method is known to converge badly on trays of two liquids.
   - c. Never the aqueous layer. A wash with a trace of oil would lose its reflux at once: a jump. Not proposed.
2. **Free water on the sheet.** Kept refused (D5), with its present text. The alternative is to let the sheet run
   a pot of two liquids as the world does; it needs the pot's law on two liquids and is left out.

## 6. Tests and gates

Each class once, with `--tests`, after its step; no whole task.

| Gate | Proves |
|---|---|
| `BatchStillTest`: the wash's bubble point 92.5 C within 0.5 K, first vapour 0.26 within 0.01 | water on the basis; the plan's W4 row |
| the same, a rectifier (8 trays, R = 3; 4 trays, R = 1): the distillate against a McCabe-Thiele in the test on the column's own bubble points, within 0.02; never above the azeotrope; more trays or reflux never lower it | D1, the energy update on a polar pair |
| the same, a batch on the wash: components and energy close to 1e-9 of the charge; the pot ends near 100 C | the pot's law with water |
| the same, water with n-hexane in the pot: 61.4 C within 0.3 K, vapour 0.791 within 0.01; the reflux holds under 1 % water | D2, D3 |
| the same, the wash with n-hexane from 1e-6 to 1e-2 of the charge: the distillate's composition moves by less than 0.01 between neighbours | D3 is continuous |
| the same, a wet naphtha: water's share at the head is 1 within 1e-6; a cold top tray raises the notice and the solve stands | D3's limit, D4 |
| the same and `StillHeadTest`: flashes counted, 0 on a dry pot | D7 |
| the existing classes of section 4 | nothing dry moved |
| `StillRuntimeTest`: a wash in a placed still, the ledger exact, the distillate richer than the pot's vapour | the world's head |
| `BatchStillUnitTest`: a wash accepted; oil over water refused with the present text | D5 |

## 7. Failure modes to expect

- The method stalls near the azeotrope (K near 1 at the top): the iteration limit is 300; report the count, do not
  raise the limit.
- The condensate flips between one and two layers from one iteration to the next: the shares are continuous at the
  onset, so the profile must still settle; if it does not, average the shares of two iterations and report.
- The engine's flash at a bubble point returns a trace of vapour: read the two liquid slots' compositions and take
  the layers' amounts from the balance, not from the slots' amounts.
- Water at zero in a wet list changes a sum's order: the dry references show it.
- A pot of water alone (the wash's end) is one component at its boiling point: the single-stage path must stand.

## 8. Build order

1. W4a, `StillColumn`: D1 to D4 and the gates of `BatchStillTest`.
2. W4b, the world: `StillHead`, `Stills` (the critical temperatures, the notice), `StillHeadTest`, `StillRuntimeTest`.
3. W4c, the sheet: `BatchStillUnit`, `StillBatch`, `StillCurve`, the en_us texts (the GUI skill first), `BatchStillUnitTest`.
4. The code guide (fluid-science, process-simulator) and this batch's README.

To check in the code before step 1, not read for this design: how `StillCurve` and the still's door label components;
where a column's failure reason is shown, for the notice; whether any caller builds a `StillColumn.Profile` by hand.

## 9. Delegation, for the owner's approval

**Sub-thread** (`start_session`, 1-h cache): builds steps 1 to 4 above, one commit a step, each with its own test
classes run once. Done: the gates of section 6 pass, the dry references are unmoved, the report lists every count
and every reference that moved. It is not main-thread work: it needs Gradle compiles and test runs (about 2 to 5
minutes each, 2 to 3 hours in all). It waits on those runs only; reports every 50 minutes, at once on a decision, and
at the end.

- Model and effort: Opus high. Step 1 changes a solver's equations and has convergence risk (the table's "hard");
  steps 2 to 4 are standard and stay in the same thread for its context.
- Thread: a new one with this document as its brief. The live sub-thread (`w3b-network`) holds the engine's
  context but runs on Fable, which the owner named for the Jacobian work only; the table gives Fable to very hard
  tasks or to a thread the owner names. If the owner names Fable for W4, the live thread is reused instead while
  its context is live.

## 10. Owner: approved (2026-11-12)

Owner, verbatim: "Approve both": the design, with section 5's choice 1 a (the oil layer first) and choice 2 as
written (free water refused on the sheet), and section 9's delegation. A new sub-thread `w4-still` on Opus high
builds steps 1 to 4 in this worktree with this document as its brief; the orchestrator records its reports here
and in the README. Not built yet.

## 11. W4a measured; D4 corrected (2026-11-12)

First report of the sub-thread, nothing committed. Measured: dry pots bit for bit against HEAD's column; the wash
92.574 C and 0.2567 (expected 92.5 and 0.26); the rectifier within 0.004 of the in-test McCabe-Thiele (4 trays at
R = 1: 0.4765 against 0.4800; 8 trays at R = 3: 0.7995 against 0.8009); the engine's azeotrope 0.8965 (section 1's
0.906 was the offline prototype's; measured 0.894); the wash's batch closes energy to 1.6e-15; water with n-hexane
61.139 C and 0.7936 (the engine's own values; section 1's 61.4 and 0.791 were the prototype's).

Three statements of this plan did not hold:

1. Section 6, wet naphtha: water's share at the head is 0.987, not 1. The oil liquid carries its dissolved water
   back. Accepted as the physics; the gate is a share of at least 0.98 with the returned water equal to the water
   dissolved in the liquid back to the pot.
2. D7: a pot of two layers takes about 5 flashes, not 3. Accepted; the gate allows 6.
3. D4 was wrong. I expected the oil layer's reflux to keep every tray one liquid. A water and hexane column stands
   every tray on the edge of the gap, and a tray read as one liquid there ran away cold (18 C over a 61 C pot):
   excess water raises the one liquid's pressure, its bubble point falls, it takes more water.
   - Expected: a tray in the gap holds two liquids; its temperature is its oil layer's bubble point.
   - Corrected D4: a tray that settles is stated like the pot (the engine's split, the oil layer's bubble point, K
     against the tray's overall liquid, both layers flowing down). A tray is sent to the engine only while it is
     known to settle, when it falls more than 0.1 K below the stage above it, and once at convergence; the solve
     ends only when every tray with water passed in its final state. The notice stays, on physical numbers.
   - Cost: the trays' flashes are those of the trays that settle, an iteration each, and one test a wet tray at
     the end. No tray of a liquid without water is tested.
   - Gates added: no tray colder than the stage above it by more than 0.1 K; the water and hexane profile flat
     within 0.1 K at the pot's temperature and vapour.

## 12. Two liquids on the trays: section 11's D4 withdrawn (2026-11-12)

Report `tmp/w4-still/reports/W4A_TWO_LIQUID_TOP.md`; nothing committed yet. Three forms compared (the limit).

1. Expected. A tray in the gap holds two liquids at its oil layer's bubble point. Water with n-hexane, 4 trays,
   R = 3: every tray at 61.14 C under the same vapour (0.7936 hexane), the tray liquid the oil layer at saturation
   (water about 0.003); the reflux takes 0.945 of the oil layer, the distillate is about 0.18 hexane, 0.82 water.
2. What the two built forms did.
   - One-liquid trays (D4 as approved) from a start at the pot's whole liquid: the trays start half water, read
     as one liquid (unstable; the 18 C tray), and cycle by 5 to 30 K. Relaxation converged some cases and not
     others, and slowed the wash's 8 trays at R = 3 from 33 to 110 iterations.
   - Trays stated like the pot (section 11): hexane at 3e-7 and water with hexane fail in 300 iterations; wet
     naphtha takes 399 flashes against 34 and returns 55 % of its steam to the pot.
   - Cause of the second (derived from the code): K over the tray's whole liquid makes the method read the tray's
     vapour as proportional to its liquid, and in the gap the vapour does not depend on it. One tray contracts by
     S/(1+S), S = V y_w / (L x_w), about 92 at the edge of the gap: 0.989 an iteration. A remedy is a three-phase
     Newton column, not this batch's.
   - The first fails by its start, not its answer: at the answer each tray is the oil layer at saturation, where
     one liquid's K is Henry's (about 69 for water) and smooth through the edge of the gap. From an oil-layer
     start the first balance returns it: water x = 0.8256 / 276 = 0.0030.
3. Decision (third form, ordered; section 11's corrected D4 is withdrawn, D4 stands as approved in section 10):
   - where the pot settles, a cold start puts the pot's oil layer on the condenser and the trays;
   - the relaxation goes;
   - a stall rule ends the iterating: 20 iterations with no new least change (300 stays the bound);
   - where the solve stalls, or converges with a wet tray more than 1 K colder than the tray above it, and a
     liquid of the still settles (pot, condensate or a tray), the answer is the pot's single stage with a notice,
     "two liquids on the trays: the column does not rectify". A dry stall fails as today.
4. Known limit, the owner's to veto. A still whose trays hold two liquids for real (an entrainer top; a reflux
   that draws on the aqueous layer, such as steam distilling a heavy oil under reflux) runs as a pot still with
   the notice. For a light immiscible trace the quasi-steady column was not its physics: returned oil first, the
   steady column needs more of it on the trays than the pot holds. The distillate steps at the onset (wash with
   hexane: about 0.72 to 0.26 ethanol near 1e-7 of the charge). The alternative is a three-phase column
   (two-liquid trays, a Newton method): a later batch, about ten times the flashes on wet oil, and wet oil's
   water then cycles on the trays instead of leaving overhead.
5. Gates ordered: water with hexane converged in 10 iterations or fewer, trays within 0.1 K of 61.14 C; the wash
   unmoved at 33 iterations; wet naphtha 0.98 or more in 34 flashes or fewer; dry pots bit for bit; no converged
   answer with a tray more than 1 K colder than the stage above; a stalled solve's cost on the 46-compound list.

## 13. W4a and W4b committed; a wash never boils in a placed still (2026-11-12)

- The sub-thread committed before section 12's order reached it: W4a `473c4f770` (the column with water, one-liquid
  trays, the relaxation still in it; two liquids at the top end as "no convergence") and W4b `ee3bce248` (the head
  takes every share, water's among them, from the column; the status line adds "WATER ON THE TRAYS"). Section 12's
  third form follows as its own commit. Neither diff is reviewed by the orchestrator yet.
- W4b's finding (`tmp/w4-still/reports/W4B_WASH_POT_NO_VAPOUR.md`), a network defect, not the column's:
  1. Expected: 60 L of a 10 % wash in an open pot at 1 atm under 10.95 kW reaches 92.5 C in about 30 s, then
     boils at about 0.27 mol/s and follows its bubble point towards 100 C.
  2. What the network did: the pot heats as one liquid through its bubble point, 377.4 K at 300 s and 391.0 K at
     600 s, and releases nothing. An open pot vents only a state that holds a gas (`OpenVessel.releases`), and the
     wash's node never gains one. Pentane in the same rig boils; water alone in a closed pot boils.
  3. Where to look (read, not measured): `FluidThermodynamics.phaseCheck` adds a phase only where the TP answer
     (`equilibriumTP`) holds one the node lacks, the vessel's own split (`crossing`, then `vesselSplit`) keeps it
     above the vanishing share, and the solve's record has not refused it. Three candidates: the TP answer of an
     aqueous liquid with ethanol above its bubble point holds no gas; the own split refuses the first bubble;
     the pot's pressure is not the ambient.
- The placed-wash gate (`tmp/w4-still/StillRuntimeTest.keep`) waits for that fix. Its diagnosis is a new task: the
  owner's approval is asked first.
- Owner, 2026-11-12: "Approve". The live sub-thread `w4-still` (Opus high) diagnoses the wash that never boils
  after the column's third form, and fixes it where the physics forces the fix; otherwise it returns options.
  Its brief names four candidates with their signatures: the pot off the slack branch of `crossing` (its pressure
  then climbs the bubble curve, about 1.9 bar at 391 K); a TP answer with no gas over an aqueous liquid; the
  vessel's own split refusing the first bubble; the check never reaching the pot. Controls: water alone in the
  same open pot, pentane charged cold.
- Meanwhile the sub-thread committed W4c `bc51003fb` (the sheet's still takes water that stays in one liquid) and
  the code guide `73fbe584d`; neither is reviewed by the orchestrator yet.

## 14. The third form measured (2026-11-12)

Report `tmp/w4-still/reports/W4A_THIRD_FORM.md`; built, not committed yet.

- As expected: dry pots 0 of 4410 values moved; the wash unmoved, 8 trays at R = 3 back at 33 iterations (4 trays
  at R = 3: 21, was 34); water with n-hexane, 4 trays, R = 3: 7 iterations, every tray 61.14 C, top vapour 0.7936,
  distillate 0.179 hexane (section 12: 61.14, 0.7936, about 0.18); at R = 5 the pot still with its notice; the
  hexane trace rectified to 3e-8 and a pot still from 1e-7 to 3e-6 (the distillate steps 0.7214 to 0.2547).
- Not as section 12 said:
  1. From 1e-5 to 1e-2 of hexane the column converges again: a rising profile, every tray reported as settling,
     the top trays at 36 to 45 C under a condensate near 56 C, the reflux returning the hexane (distillate 1e-4).
     A tray that would settle, read as one liquid, boils too cold; the wet naphtha shows the same by 0.78 K (head
     24.32 C under its condensate's 25.10 C).
  2. Flashes: 21 for water with hexane and 38 for the wet naphtha (ordered 20 and 34).
  3. The wet 46-compound crude fails in its first iteration, "no bubble point on stage 2", 11 flashes at about
     18 ms a flash.
- Decided:
  1. Point 1 is D4's notice, not a failure: the owner's rule for the V3 column (a tray below the water dew point
     is a warning, not a rejection). No new trigger. A test of the head against its condensate's bubble point
     would sit 0.2 K from the wet naphtha at 1 K, and the gap grows with the steam in the pot's vapour, so wet oils
     would lose their column on a threshold the physics does not give. A split of every settling tray costs about
     8 flashes a tray. Known error, the owner's to veto: where trays would settle their temperatures read low, by
     about 1 K on the wet naphtha and 10 to 20 K under an entrainer top; the notice says so.
  2. The fallback's trigger is widened, same form: any failure of the iteration in a solve where a liquid of the
     still settles answers the pot still with its notice. Expected cause of the crude's failure (to confirm): a
     hot pot's vapour is mostly steam, the oil layer is under the reflux, tray 1 receives free water.
  3. The flash counts are accepted as measured.
- Cost to record: one wet solve's time on the wet naphtha and the crude, and when the world submits one.
