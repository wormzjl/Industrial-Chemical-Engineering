# S4: a dense fluid at a pump and a compressor (corrected in S4b)

Batch 2026-11-15-supercritical-fluids, stage S4 (brief: `tmp/supercritical-briefs/BRIEFS.md`, "S4"; plan, "S4 as
briefed"). Branch `claude/supercritical-fluid-support-d0ffa4`, after the merge of main 0.31.1 (`8bac3e6e7`).

## Summary

**Expected.** One fluid above its critical temperature has no second phase. A pump does not see bubbles in it, and a
compressor does not see droplets. What a machine feels is the fluid's density. A pump works on a dense fluid as on a
liquid, and it loses its grip as the density falls. A compressor works on it as on a gas, and it suffers as the density
nears a liquid's. Both should move smoothly with the density: nothing physical happens at the slot line (reduced
density 1).

**What the code did.** The shares were read from the slot. Carbon dioxide at 310 K crossing δ 1 changed a pump's
vapour share from 1 to 0, so its head share went from 0.530 to 1. It changed a compressor's condensed share from 0 to 1,
so its wet efficiency went from ×1 to ×0.5. The pump's suction edge E was "none" in the vapour slot and the slot line
in the liquid slot (8.3178 MPa, above Pc, where nothing flashes).

**Corrected in S4b** (owner, 2026-11-16: "ok but use a new thread"; sections 3, 4, 6 and 8 state the corrected form).
As first implemented, E of one dense fluid was a density: a pump drawing it through a line at δ 0.5 to about 1.1 was
vapour-locked at its own pressure, while a lighter and a denser fluid both ran. One fluid on a supercritical isotherm
has no second phase, so S4b keeps what follows its density (the head share, now `M(1 − w_pump)` on a pump's own bounds 0.5 to 1.0, the wet efficiency, the NPSH and
rise densities, the sizing's liquid share) and removes what needs a second phase:

- E stands only where the isenthalp really flashes, from the bubble side below Pc (`ReleasePressure.lowestHeld`): CO2
  at 320 K and 15 MPa keeps 6.5198 MPa; at 310 K, δ 1.0 to 1.06 has none, δ 1.075 has 7.377 MPa, and δ 1.20 on is S4's
  to the digit. One seam is left, at the critical isenthalp (δ 1.067, 1.181 and 1.311 at 310, 320 and 330 K).
- No p_v in either slot (the dense natural gas: 2.66 MPa → none); no droplet wear (`Supply.dropletMassShare` 0).
- The sheet: no dense E, no bubble warning, a cold suction; the compressor's discharge exponent blended to the solved
  streams' by `w`.
- 0 non-dense values moved (100 states); the commits and runs are in section 10.

**What changed in S4** (four commits; no state that is not dense moved, measured on 100 states):

- `1b4396c25`: the weight moved to the model: `NetworkStreams.liquidLikeWeight`, forwarded by
  `FluidThermodynamics`, with `GAS_LIKE_UNTIL` 0.5 and `LIQUID_LIKE_FROM` 1.5. `HeatCurve` reads it, and no film moves.
- `505832d82`, shares and suction limit:
  - one dense fluid reads vapour share `1 − w` and condensed share `w` (`InletPhase.measure`);
  - a dense trial state reads `1 − w` in `ReleasePressure.vapourVolumeShare`;
  - a dense fluid with `w > 0` is drawn as a liquid (`Drawn.condensed`, `MoverSuction.suctionFloors`);
  - its NPSH density is its own in the vapour slot too;
  - the revision token `dense-movers-v1`.
- `0b3d6a1d9`, the simulator:
  - `FlowThermo.denseAtEdge`;
  - the pump's bubble warning;
  - a dense pump suction's service;
  - the compressor sizing's liquid share.
- Tests: `DenseMachineSharesTest` (new), `DenseFluidSuctionTest` (updated for E), `FlowsheetUnitsTest`, `ServiceTest`.

**Results.**

- Shares: continuous across the slot line. The head share at the slot line is now 0.947 on both sides (it was 0.530
  and 1); the wet efficiency is ×0.75 on both sides (it was ×1 and ×0.5). The largest step between neighbours 0.05
  apart in δ is 0.065 in head share and 0.037 in wet efficiency (before: 0.47 and 0.5).
- The refusal reads neither share, so nothing is refused anywhere in the window.
- (As first implemented; corrected in S4b, section 3.) E is a density above Pc: 320 K at δ 1.25 gives 8.361 MPa (it was 7.309 MPa, on the slot line). A fluid that is
  already gas-like enough has E at its own pressure: below δ about 1.12 at 310 K, 1.10 at 320 K, 1.09 at 330 K.
- E keeps two seams (section 3), each an owner option:
  - at δ 0.5, E goes from none to the suction's pressure;
  - where the isenthalp crosses Tc above Pc, the weight stops applying, and E drops by 0.36 to 0.65 MPa.
- Compressor law (step 5, measured, not changed):
  - isentropic head error −4 to −7 % up to δ about 1, and −14.4 % at δ 1.64 (CO2 320 K, 15 to 30 MPa);
  - the engine's discharge temperature at the law's enthalpy is within 2.1 K;
  - the simulator sizing's ideal-gas discharge estimate is 17 to 32 K high on a dense suction.
- Wear (the compressor's wet gas wear corrected in S4b, section 4): a pump on a dense fluid has no p_v, so no cavitation read and no cavitation wear (pure CO2; unchanged). A
  centrifugal compressor's wet gas wear now reads `w`: it erodes fully from δ about 0.65, where before it eroded only
  in the liquid slot.
- Runs:
  - the affected classes since the merge (`8bac3e6e7`), all passed: test 254 classes, 1,702 tests; holdGates 136
    tests; equipmentGates 13 tests; chain-100 deviation 0;
  - the three dense GameTests alone, run `s4-dense1`: 3 of 3 passed. The loop's pump draws δ 1.79, so `w = 1`, and the
    loop runs at the same states as before S4.

## 1. The shares

`InletPhase.measure`, for one dense fluid (`State.dense()`, with some fluid measured), whichever slot holds it:

- **A pump's vapour share** is `1 − w_pump`, on a pump's own liquid-like weight
  (`FluidThermodynamics.pumpLiquidLikeWeight`: reduced densities 0.5 to 1.0). These bounds came in S4b, by the owner's
  choice of option 1 B ("Use 0.5 to 1.0") in [DENSE_PUMP_RESEARCH_REVIEW.md](DENSE_PUMP_RESEARCH_REVIEW.md). A real
  centrifugal pump keeps its full head on one dense fluid; only its rise `ρ g H` falls with the density. Pumps run carbon
  dioxide down to 400 to 500 kg/m³ (δ 0.86 to 1.07), and one test pump ran stably at 340 kg/m³ (δ 0.73).
- **A compressor's condensed share** is `w`, on the film's weight (`liquidLikeWeight`, 0.5 to 1.5), as S4 made it. The
  compressor's wet efficiency, the sizing's liquid share and the film (`HeatCurve`) did not move.

Each smoothstep is exactly 0 at or below δ 0.5 and exactly 1 from its upper bound. So outside the windows the shares are
the slots' own values to the bit:

- the vapour share is 1 at or below δ 0.5 (the gas rule) and 0 from δ 1.0;
- the condensed share is 0 at or below δ 0.5 and 1 from δ 1.5.

The slot rule puts a dense fluid in the liquid slot from δ 1, so in the liquid slot a pump reads what it read before S4,
to the bit.

- "Liquid-like at all" (`InletPhase.stream`'s and `liquidDensity`'s `liquidLike`, `MoverSuction.suctionFloors`) tests
  `w > 0`, which is δ above 0.5 for both weights: no change.
- A port's stream drawn from a dense state is that fluid (the state has one fluid slot).
- A dense state may also carry solids. The two shares are of the fluid only, so solids do not enter them; the largest
  solid share is read as before.
- An ordinary gas above its Tc is `dense()` too (nitrogen at 300 K). Its δ is below 0.5 up to about 13 MPa (0.385 at
  10 MPa), so there it reads exactly what it read before.

Carbon dioxide at 310 K, the head share at each state's own pressure (`tmp/supercritical-s4b/after-pump/S4bPumpProbe.txt`;
"film's" is S4 as first implemented):

| δ | slot | w (film's) | w_pump | head share before S4 | on the film's bounds | on the pump's (S4b) | wet efficiency (S4, kept) |
|---|---|---|---|---|---|---|---|
| 0.30–0.50 | V | 0 | 0 | 0.340–0.459 | the same | the same | ×1 |
| 0.55 | V | 0.007 | 0.028 | | 0.482 | 0.503 | ×0.996 |
| 0.60 | V | 0.028 | 0.104 | 0.487 | 0.516 | 0.596 | ×0.986 |
| 0.65 | V | 0.061 | 0.216 | | 0.560 | 0.723 | ×0.970 |
| 0.70 | V | 0.104 | 0.352 | | 0.613 | 0.853 | ×0.948 |
| 0.73 | V | 0.134 | 0.440 | | 0.649 | 0.913 | ×0.933 |
| 0.75 | V | 0.156 | 0.500 | | 0.674 | 0.944 | ×0.922 |
| 0.80 | V | 0.216 | 0.648 | 0.517 | 0.739 | 0.986 | ×0.892 |
| 0.85 | V | 0.282 | 0.784 | | 0.803 | 0.998 | ×0.859 |
| 0.90 | V | 0.352 | 0.896 | | 0.862 | 0.99989 | ×0.824 |
| 0.95 | V | 0.425 | 0.972 | | 0.911 | 0.999999 | ×0.787 |
| 0.9995 | V | 0.499 | 1 − 3e-6 | 0.530 | 0.947 | 1.000 | ×0.750 |
| 1.0005 | L | 0.501 | 1 | 1 | 0.948 | 1 (exactly) | ×0.750 |
| 1.05–1.20 | L | 0.575–0.784 | 1 | 1 | 0.972–0.998 | 1 (exactly) | ×0.713–×0.608 |
| 1.50–1.80 | L | 1 | 1 | 1 | 1 | 1 | ×0.5 |

- The largest step in head share between neighbours 0.05 apart is 0.129 (δ 0.65 to 0.70). It was 0.065 on the film's
  bounds, and 0.47 at the slot line before S4.
- The head share is continuous across the slot line: 1 to the double below it (a vapour share of 3e-6), exactly 1 above it. `FlowsheetUnitsTest`'s 0.2 %
  agreement does not read it.
- Nitrogen at 150 K across the line: head share 0.468 and 1 before S4, 0.933 and 0.934 on the film's bounds, 1 and 1
  now.
- Nitrogen at 300 K: at 15 MPa (δ 0.569) 0.704 → 0.736; at 25 MPa (0.899) 0.949 → 0.99996; at 30 MPa (1.043) 0.990 → 1.
- The dense natural gas at 300 K: δ 0.6 0.630 → 0.703, δ 0.9 0.928 → 0.99995, 1 from δ 1.0.
- 100 states compared before and after the pump's weight (`after-pump/shares-compare.txt`). No state that is not dense
  moved, and no state at δ 0.5 or below moved. The dense ones moved only in the vapour share and the head share. Their
  condensed share, wet efficiency, p_v, E and limit did not move.

**The refusal across the window.** The refusal (`InletPhase.decide` through `Mover.refuses`) reads only a dry suction
and, at a compressor, solids above the trace. Neither share enters it. So a dense fluid is refused nowhere in the
window, as before (`DenseMachineSharesTest.neitherMachineIsRefusedAcrossTheWindow`).

**Densities.**

- A set-flow pump's rise (`MoverSuction.riseDensity`, from `InletPhase.density`) and a machine's rise (the suction
  stream) read the dense fluid's own density in either slot, before and after. No reader defect there.
- The NPSH density (`InletPhase.liquidDensity`) read NaN in the vapour slot. It now reads the fluid's own density
  wherever `w > 0`. It feeds the breakdown NPSH, which adds to p_v only, so it changes nothing for one dense fluid (no p_v).
  It also feeds the pump page's NPSH3 reading, which was blank in the vapour slot.

## 2. Where E is evaluated

- E is read once per solve per pump, on the step's start states (`suctionFloors`, then `zoneEdge`, cached by state).
  Its trials are full PH equilibria (`equilibriumPH`), not tangent-built states, so `dense()` there is each trial's own.
- The mode tests and the suction-limit row read the stored E, not a trial (`cavitationMargin`, `suctionLimit`).
- The supply shares are read on the committed graph's states (`readSuctions`), which are full states.

## 3. The suction limit E (corrected in S4b)

**Expected.** One fluid on a supercritical isotherm has no second phase. Nothing separates in it as its pressure falls:
its density falls, and a pump's rise `ρ g H` falls with it (the head share `M(1 − w_pump)`, section 1). A gas-release edge
exists only where the stream, throttled at its own enthalpy, really splits into a liquid and a vapour.

**The form (S4b).**

- `ReleasePressure.vapourVolumeShare` of a dense trial state reads 0 in either slot: it releases no vapour. Before S4
  the liquid slot read 0 and the vapour slot NaN.
- `MoverSuction.zoneEdge`: for a dense supplier the shortcut "the supply's vapour share is at or past the clean share,
  so E is the supplier's pressure" does not fire. That share is `1 − w_pump`, a density. Every other stream keeps the shortcut.
- For a dense supplier, E stands only where the stream holds a vapour at the pressure found:
  `ReleasePressure.lowestHeld`, the same bisection as `lowest`, kept only where its trial at the returned pressure is
  above 0. Every other caller reads `lowest` as before, so no other E moves.
- p_v (`MoverSuction.release`): one dense fluid has none, in either slot (section 4).

**Why the test separates a flash from a label change.** Carbon dioxide at 310 K, the isenthalps traced near Pc
(`tmp/supercritical-s4b/after/S4bSidesProbe.txt`). Both stay one dense fluid down to the critical pressure (304.22 K at
7.385 MPa) and split just below it:

- δ 1.03, the gas side: at 7.375 MPa the stream is two phases with a vapour share of 0.875, entered from the dew side.
  It is past the clean share (0.31) at once, so the last passing trial is the dense state, share exactly 0: no edge.
- δ 1.10, the bubble side: at 7.375 MPa the vapour share is 0.038, then 0.221 and 0.295 at 7.355 MPa. It crosses the
  clean share continuously, and E is 7.3526 MPa, with a vapour share above 0 there.

**E before S4, as first implemented, and now** (`after/S4SharesProbe.txt`, `after/S4SeamProbe.txt`):

| State | Before S4 | S4 | S4b |
|---|---|---|---|
| CO2 320 K, 15 MPa (δ 1.643) | 6.5198 MPa (a flash) | 6.5198 MPa | 6.5198 MPa |
| CO2 310 K, δ 0.6 (7.405 MPa) | none | 7.4052 MPa (its own pressure) | none |
| CO2 310 K, δ 0.9995 (8.3251 MPa) | none | 8.3251 MPa (its own pressure) | none |
| CO2 310 K, δ 1.0005 (8.3265 MPa) | 8.3178 MPa (the slot line) | 8.3265 MPa (its own pressure) | none |
| CO2 310 K, δ 1.10 (8.4770 MPa) | a flash, 7.27 to 7.54 MPa band | 8.4770 MPa (its own pressure) | 7.3526 MPa (a flash) |
| CO2 310 K, δ 1.20 to 1.475 | | 7.1741 to 6.5248 MPa | the same, to the digit |
| N2 150 K, δ 1.0005 (7.0593 MPa) | 7.0535 MPa | 7.0593 MPa (its own pressure) | none |
| CO2 320 K, δ 1.25 (11.079 MPa) | 7.3093 MPa (the slot line) | 8.3612 MPa (a density) | 7.3093 MPa (a flash) |
| CO2 330 K, δ 1.30 (13.771 MPa) | 7.4262 MPa | 9.4241 MPa | none (gas side) |
| natural gas 300 K, δ 1.6 (30.61 MPa) | 14.249 MPa | 15.811 MPa | none |

- 100 states compared with S4 (`after/shares-compare.txt`): no state that is not dense moved. The dense ones that moved
  are E and the limit (none, or the flash below Pc) and the natural gas's p_v (none).
- No suction limit on a dense state without a flash: every dense row with E none also has the limit none.
- The seam at δ 0.5 (S4: none to the suction's own pressure) is gone: δ 0.49, 0.51 and 0.6 read none
  (`DenseFluidSuctionTest`).
- A pump drawing carbon dioxide at 310 K through a 10 m line and a junction, at δ 0.6, 0.8, 1.0 and 1.1, is not
  vapour-locked, delivers, and runs on its head share `M(1 − w_pump)` (0.596, 0.986, 1, 1; on the film's bounds 0.516, 0.739, 0.948, 0.987). At δ 1.1 its limit is the
  flash, 7.35 MPa, 1.1 MPa under the suction (`DenseMachineSharesTest`).

**The seam that is left.** On the isenthalp through the critical point, E goes from none (gas side) to about Pc (bubble
side). Its place, by bisection on the supplier's δ (`after/S4bProbe.txt`):

| Isotherm | Seam at δ | Suction there | E just past it | Suction above E |
|---|---|---|---|---|
| 310 K | 1.0667 | 8.423 MPa | 7.3813 MPa | 1.04 MPa |
| 320 K | 1.1805 | 10.707 MPa | 7.3815 MPa | 3.33 MPa |
| 330 K | 1.3105 | 13.872 MPa | 7.3816 MPa | 6.49 MPa |

The brief expected the seam at δ about 1.19 at 310 K, where S4's E dropped. That drop was where S4's density crossing
met Tc, not the critical isenthalp. Without the density crossing, the real flash shows from δ 1.075 at 310 K:
7.3772 MPa, falling to 7.2002 MPa at δ 1.1875 and joining S4's own flash rows at δ 1.20 (7.1741 MPa). Past the seam the
pump's suction stands 1 to 6.5 MPa above E, so the seam does not lock a pump. It moves only where its suction is pulled
that far down.

**As first implemented (S4).** A dense trial read `1 − w`, so above Pc E was a density, and where the supplier's own
`1 − w` passed the clean share E was the supplier's pressure: below δ about 1.12 at 310 K (clean share 0.32 at
8.5 MPa), 1.10 at 320 K and 1.09 at 330 K. A pump drawing such a fluid through a line had its suction limit at the
supplier's own pressure and was vapour-locked from δ 0.5 to about 1.1, where a lighter (δ 0.49, head share 0.459) and a
denser fluid (δ 1.2) both ran. The plan's section 2b point 10 cites this band; S4b removes it.

Mixtures: the dense natural gas (CH4 0.90, C2H6 0.06, C3H8 0.03, N2 0.01) at 300 K has no edge at δ 0.6 to 1.6 now: the search
finds no pressure where the stream holds a vapour within the clean share (measured; its isenthalps were not traced). As
first implemented its E was 10.44 to 17.79 MPa, a density.

## 4. Wear (step 4; corrected in S4b)

**Expected.** Cavitation needs a vapour pressure, and droplet erosion needs droplets. One dense fluid has neither: what
follows its density is the head and the efficiency, not wear.

- **Cavitation.** Wear reads σ = NPSHA / NPSH3 against p_v. One dense fluid has no p_v in either slot now
  (`MoverSuction.release`). A pure one already had none (`leaveOutGases`), so σ is NaN and the intensity 0. A dense
  mixture whose heavier compounds stay below their Tc read the bubble pressure of those compounds in the liquid slot (the
  natural gas at 300 K: 2.660 MPa at δ 1.0005, 2.736 MPa at δ 1.6) and none in the vapour slot, a slot reader. It reads
  none in both now (`DenseMachineSharesTest.aDenseNaturalGasHasNoVapourPressureInEitherSlot`).
- **A compressor's wet gas wear.** `readSuctions` books the supply's droplet share (`InletPhase.Supply.dropletMassShare`:
  its condensed share, 0 for one dense fluid, which `Supply.dense` marks) into the solve's condensed shares. They feed
  `StepObservations`, `MachineHealth`, `Wear.wetGas` and the `WET GAS` word. The wet efficiency still reads `w`
  (`wetShare`). A steam turbine's exhaust moisture (`StepObservations.exhaustMoisture`) reads the same droplet share.

  | CO2 310 K, δ | w | `Wear.wetGas`, S4 | `Wear.wetGas`, S4b | wet efficiency (both) |
  |---|---|---|---|---|
  | 0.6 | 0.028 | 0.46 | 0 | ×0.986 |
  | 0.7 | 0.104 | 1 | 0 | ×0.948 |
  | 1.0 | 0.500 | 1 | 0 | ×0.750 |
  | 1.4 | 0.972 | 1 | 0 | ×0.514 |

  A centrifugal compressor's solved row carries 0 at δ 0.7, 1.0 and 1.4, while its wet efficiency reads `w`
  (`DenseMachineSharesTest.aCompressorOnADenseFluidSeesNoDroplets`).

**What else reads a pump's vapour share** (`vapourShares[edge]`, which is `1 − w_pump` for one dense fluid, section 1).
None changed in S4b; these go to S5. CO2 at 310 K on the pump's weight (`after-pump/S4bPumpProbe.txt`; on the film's
bounds, `after/S4bProbe.txt`):

| Reader | δ 0.6 (7.405 MPa) | δ 0.73 (7.849 MPa) | δ 1.0 and 1.4 | On the film's bounds, δ 0.6 / 1.0 / 1.4 | A density's effect or a second phase's |
|---|---|---|---|---|---|
| α read (page's vapour share) | 0.896 | 0.560 | 0 | 0.972 / 0.500 / 0.028 | shown as a gas share; it is a density |
| head share `M(α)` (the law) | 0.596 | 0.913 | 1 | 0.516 / 0.948 / 1.000 | density: a pump losing its grip on a light fluid |
| gas ratio r = α / α_T (page reading) | 1.45 | 0.89 | 0 | 1.58 / 0.78 / 0.04 | second phase (Turpin's onset is for bubbles) |
| churn loudness `GasEntrainment.churn(r)` | 0.999 (255/255) | 0.624 (159/255) | 0 | 231 / 117 / 0 of 255 | second phase |
| `GAS` status word (r > 0.5) | yes | yes | no | yes / yes / no | second phase |
| gas lock at no flow (the DRY fault, α > α_T) | yes | no | no | yes / no / no | density: a pump that passes nothing on a gas-like fluid runs dry |
| a dry screw's liquid share `1 − α` | 0.104 | 0.440 | 1 | 0.028 / 0.500 / 0.972 | second phase (a liquid slug) |
| its LIQUID fault intensity | 1 | 1 | 1 | 0.28 / 1 / 1 | second phase |
| its `LIQUID` word (above 0.01) | yes | yes | yes | yes / yes / yes | second phase |

The churn, the `GAS` word, the gas ratio and the dry screw's LIQUID fault and word all describe bubbles or slugs that
one dense fluid does not have. The head share and the gas lock describe a pump losing its grip on a light fluid, which
the density does cause.

## 5. The compressor's head at low compressibility (step 5, measured, no change)

- The law: `r = (1 + H_p ρ_s/(σ P_s))^σ` with `σ = η k/(k − 1)`, where `k` is the ideal-gas heat capacity ratio
  (`heatCapacityRatio`, which S2b extended to a dense fluid). Turned round, the head is `σ (P_s/ρ_s)(r^(1/σ) − 1)` on the
  suction's real `P/ρ`.
- The power `ṁ H_p/η_p` goes into the discharge as enthalpy. The engine then flashes the discharge at that enthalpy, so
  the world's discharge temperature comes from the real (PR) enthalpy.
- The simulator's sizing estimates the discharge as `T r^((k−1)/(kη))` (`CompressorSizing`).

The measurement is at η = 1, the isentropic step, against CoolProp's Helmholtz equation at s₁
(`tmp/supercritical-s4/probe/CompressorLawProbe`, `compressor-law.txt`):

| Suction, ratio 2 | δ | law head, J/kg | reference Δh_s, J/kg | head error | engine T₂ | reference T₂ | sizing T₂ |
|---|---|---|---|---|---|---|---|
| N2 300 K, 1 MPa | 0.038 | 68,086 | 68,368 | −0.4 % | 365.9 K | 366.1 K | 365.6 K |
| CO2 320 K, 1 MPa | 0.041 | 43,257 | 43,309 | −0.1 % | 373.9 K | 372.8 K | 372.5 K |
| N2 300 K, 10 MPa | 0.385 | 68,588 | 71,407 | −3.9 % | 364.5 K | 367.7 K | 365.6 K |
| CO2 320 K, 8 MPa | 0.570 | 25,354 | 26,900 | −5.7 % | 373.6 K | 373.6 K | 372.5 K |
| CO2 310 K, 8.3 MPa | 0.981 | 15,380 | 16,513 | −6.9 % | 345.0 K | 343.9 K | 361.5 K |
| CO2 320 K, 10 MPa | 1.016 | 17,899 | 19,205 | −6.8 % | 356.7 K | 355.4 K | 372.5 K |
| CO2 320 K, 15 MPa | 1.643 | 16,746 | 19,565 | −14.4 % | 342.3 K | 340.2 K | 372.5 K |

- In the world, the head error passes 10 % only on a liquid-like suction. Read the other way round, the reference's
  isentropic head asks for a ratio of 2.23 there, against 2. Part of the error is PR's suction density, which is 5 to 8 %
  under the reference's on dense CO2 (404 against 427 kg/m³ at 310 K). The engine's discharge temperature stays within
  2.1 K.
- The sizing's discharge temperature does not see the density at all. It is 17 to 32 K high on a dense suction (a
  temperature rise 48 to 160 % too large).
- **Option for the owner (not done):**
  - in the world, the real isentropic volume exponent `n_s = −(v/P)(∂P/∂v)_s` at the suction in place of the ideal-gas
    `k` in `σ` (one derivative of the suction's phase per solve);
  - in the sizing, the discharge from the engine at `h₁ + H_p/η` at the outlet pressure, as the world does. S4b takes
    this for one dense fluid, blended by `w` (section 6).

## 6. The simulator (step 6; corrected in S4b)

Every place in `science/flowsheet` that reads a phase label, a vapour share or a condensed share to choose a law:

| Reader | What it chose by | S4 | S4b |
|---|---|---|---|
| `MoverUnit`: refusal | `InletPhase.bulk`: dry suction and solids only | none needed | none |
| `MoverUnit`: `pump.bubble_point` warning | `FlowThermo.boils` 0.1 % under the pressure, which for a dense fluid only said "vapour slot" | `denseAtEdge` for a dense fluid | none for a dense fluid |
| `PumpSizing`: p_v, E | `FlowThermo.vapourPressure`, `zoneEdge` on the sheet's TP trial (`ReleasePressure.vapourVolumeShare`) | E read `1 − w` | E none for a dense stream; p_v unchanged |
| `Service.of`: gas or liquid class | vapour mass share above 95 %, i.e. the slot | unchanged: the slot is the weight's midpoint (w 0.5 at δ 1) | unchanged |
| `Service.of`: a pump suction boiling or cold | spare over `bubblePressure`, which for a dense fluid is the slot-line pressure | `denseAtEdge` | `SUCTION_COLD` for a dense fluid |
| `CompressorSizing`: `COMPRESSOR_LIQUID` | `100 − vapour mass share`, i.e. the slot | `100 w` inside the window | kept |
| `CompressorSizing`: discharge | `T r^((k−1)/(kη))`, the ideal-gas exponent | unchanged | blended to the solved streams' by `w` |
| `ExchangerSizing`: vapour share in and out | exchanger service | not a machine; the film is S2b's | |
| `TankSizing` and `VesselUnit`: bubble and storage pressure | vessels | not in S4 | storage none for a dense stream; no reading moves |
| `FlowThermo.releaseShare`: a trial all gas reads 1 | sheet trial | the dense trial read `1 − w` first | the dense trial reads 0 first |

**As first implemented (S4).** A dense line into a pump was `SUCTION_BOILING`, and its pump warned `pump.bubble_point`,
where its `1 − w` reached the clean share (to δ about 1.1 at 310 K). Its sheet E was a density above Pc (8.5132 MPa at
310 K δ 1.2, 10.3582 MPa at 320 K 15 MPa) or its own pressure.

**Corrected in S4b.** The sheet's trial is isothermal (`FlowThermo.releaseShare`, a TP flash at the stream's
temperature), and one dense fluid at its own temperature never splits. So every trial of a dense stream reads 0, and
the search gives no edge and no bubble point.

- `FlowThermo.denseAtEdge` is removed. `releaseShare`'s "a trial all gas reads 1" comes after the dense 0, so it does not
  apply to a dense trial.
- `MoverUnit`: no `pump.bubble_point` warning for a dense inlet, in either slot. Elsewhere the flash test 0.1 % under
  the pressure stands, as before S4.
- `Service.of`: a dense line into a pump is `SUCTION_COLD`.
- `CompressorSizing`: the liquid share stays `100 w`. One dense fluid with `w > 0` blends the discharge exponent:
  `ideal + w (solved − ideal)`, with `solved = ln(T_out/T_in)/ln(ratio)` from the sheet's solved streams. `out` is the
  compressor tile's own solved outlet, from `MoverUnit`, at the same polytropic efficiency (0.78) the ideal exponent
  uses. With `ratio ≤ 1` or temperatures that are not finite, the ideal exponent is used. At `w = 0` it is the ideal
  exponent to the bit.
- `FlowThermo.vapourPressure` is unchanged. It reads no state, and knowing the stream dense would cost a TP flash it
  does not make today. So the dense natural gas keeps a p_v on the sheet (its ethane and propane), where the world reads
  none.

Sized numbers (`after/S4SheetProbe.txt`, `after/S4bSheetProbe.txt`):

- The pump's E on the sheet is none for every dense stream:
  - CO2 at 310 K, δ 0.6 to 1.6, and at 320 K and 15 MPa;
  - nitrogen at 300 K and 5 MPa, which read its own pressure before S4 and in S4. The world looks for no edge there
    (w = 0, `Drawn.condensed` false), so the two now agree.
- A dense line into a pump is `SUCTION_COLD` at δ 1.0005, 1.05, 1.1, 1.2 and 1.6. S4 had it `SUCTION_BOILING` to δ 1.1.
  Before S4 it was boiling at δ 1.0005, on the slot line's spare.
- **The world and the sheet differ** for the same stream. The world's isenthalpic E can exist where the sheet's
  isothermal one does not: CO2 at 320 K and 15 MPa has E 6.5198 MPa in the world and none on the sheet. No PH trial is
  added to the sheet.
- The compressor's discharge, ratio 2, one stage (`aCompressorsDischargeOnADenseSuctionFollowsTheSolvedOutlet`):

  | Suction | w | ideal estimate | sized (S4b) | solved outlet |
  |---|---|---|---|---|
  | N2 300 K, 1 MPa | 0 | 386.63 K | 386.63 K (exponent to the bit) | 386.35 K |
  | N2 300 K, 10 MPa | 0 | 386.63 K | 386.63 K (exponent to the bit) | 382.67 K |
  | CO2 320 K, 8 MPa | 0.014 | 388.82 K | 388.66 K | 377.54 K |
  | CO2 310 K, δ 0.9995 | 0.499 | 377.51 K | 361.39 K | 345.92 K |
  | CO2 310 K, δ 1.0005 | 0.501 | 377.51 K | 361.33 K | 345.88 K |
  | CO2 320 K, 10 MPa | 0.523 | 388.82 K | 372.88 K | 358.94 K |
  | CO2 320 K, 15 MPa | 1 | 388.82 K | 344.81 K | 344.81 K |
  | CO2 280 K, 3 MPa (not dense) | | 343.46 K | 343.46 K | 343.49 K |

  So the reading is the solved outlet on a liquid-like suction. It is continuous across the slot line, and halfway
  there: 15.5 K high at the slot line, where the ideal estimate was 31.6 K high. Below δ 0.5 it keeps the ideal
  estimate's real-gas error: 4 K on nitrogen at 10 MPa, 11 K on CO2 at 320 K and 8 MPa (section 8).
- The storage pressure (V/L = 1, `FlowThermo.storagePressure`) of a dense stream is none now. As first implemented it
  was the slot line's pressure: N2 300 K 200 bar, CH4 300 K 190.3 bar, CO2 310 K 83.3 bar, the natural gas 167.6 bar.
  Its readers take the smaller of it and the bubble pressure (`TankSizing`) or compare it with 76.6 kPa (`VesselUnit`,
  the floating roof), and the bubble pressure is the same number. So no tank or roof reading moved.
- No sample pin moved (section 10).

Tests: `FlowsheetUnitsTest.aPumpAndACompressorOnADenseFluidSeeNoSlotLine` (the 0.2 % agreement across the slot line; no warning on either side), `FlowsheetUnitsTest.aCompressorsDischargeOnADenseSuctionFollowsTheSolvedOutlet`, `ServiceTest.aDenseSuctionIsColdAndHasNoEdgeOnTheSheet`.

## 7. Identity

`FluidThermodynamics.revision` holds the token `dense-movers-v1` beside `line-cap-saturation-v1`, so saved solved state
rebuilds once. There is no facts field and no corpus image. S4b keeps the token: no world has been saved with it (the
branch is not merged).

## 8. Owner options

Settled by S4b (owner, 2026-11-16: "ok but use a new thread"): S4's options 1 (the pump's lock band) and 5 (wet gas
wear). One dense fluid has no edge from its density, no vapour pressure and no droplet wear (sections 3 and 4).

Still open:

1. **The compressor's head law** (section 5): use the real isentropic exponent in `σ`. Not done.

(S4's option 1, the machines' bounds, is decided, owner 2026-11-16, "Use 0.5 to 1.0", option 1 B of
[DENSE_PUMP_RESEARCH_REVIEW.md](DENSE_PUMP_RESEARCH_REVIEW.md): the pump on 0.5 and 1.0, the compressor and the film on 0.5
and 1.5; section 1.)
2. **The seam at the critical isenthalp** (section 3): E goes from none to about Pc at δ 1.067 (310 K), 1.181 (320 K)
   and 1.311 (330 K). Past it the suction stands 1.0 to 6.5 MPa above E. It is the physics' own boundary, between the
   dew-side and the bubble-side entry into the dome. Leave it (implemented).
3. **The pump's readers on a dense fluid**, for S5 (the table in section 4): the churn, the `GAS` word, the gas ratio,
   and a dry screw's LIQUID fault and word read `1 − w_pump` as if it were bubbles.
4. **The compressor sizing's discharge between w = 0 and 1** (section 6): the blend is exact at `w = 1` and halves the
   error at the slot line (15.5 K high). Reading the solved outlet at every `w` would remove the rest. Below δ 0.5 it
   would also move ordinary gases: 4 K on nitrogen at 10 MPa. So it is an owner choice, not done.
5. **The sheet's p_v of a dense mixture** (section 6): the world gives none; the sheet keeps the bubble pressure of its
   compounds below their Tc. Closing this costs one TP flash per pump sizing. Not done.
6. S4's options 2 and 3 (the seam at Tc on the isenthalp, the seam at δ 0.5) are gone with the density crossing: no
   dense trial reads `1 − w`.

## 9. Left for S5, and what S4 met outside its scope

- Texts that name a phase for a dense fluid:
  - `pump.bubble_point` (S4b: it no longer fires on a dense fluid; a text for a pump on a gas-like dense fluid is S5's);
  - `compressor.phase` / `pump.phase` (the refusal texts);
  - "ERROR: compressor inlet not gas" (`InletPhase.reason`: solids only);
  - the pump page's vapour share and gas ratio readings, which show `1 − w_pump` for a dense fluid, and the other readers
    of it (section 4's table: the churn, the `GAS` word, a dry screw's LIQUID fault and word).
- Not in S4 and not met: the LIQUID port's head seam; a vent or blanket on a dense vessel; the engine's labels; the
  machines' maps.

## 10. Tests and runs

- Tests: `DenseMachineSharesTest` (new, 6 tests), `DenseFluidSuctionTest` (3), `FlowsheetUnitsTest`
  (`aPumpAndACompressorOnADenseFluidSeeNoSlotLine`) and `ServiceTest` (`aDenseSuctionIsBoilingByItsDensity`).
- Measurement probes are in `tmp/supercritical-s4/probe/`:
  - `S4SharesProbe`: `shares-before.txt`, `shares-after.txt` and `shares-compare.txt` (100 states, 0 states that are not
    dense moved);
  - `S4SeamProbe` (`seam.txt`);
  - `S4SheetProbe` (`sheet-before.txt`, `sheet-after.txt`);
  - `CompressorLawProbe` (`compressor-law.txt`).
- One filtered Gradle invocation per gate group, `--max-workers 4`, with the classes from
  `node tools/merge-lane/affected.mjs select --since 8bac3e6e7`:

  | Group | Classes | Tests | Failed |
  |---|---|---|---|
  | test | 254 | 1,702 | 0 |
  | holdGates | 10 (all) | 136 | 0 |
  | equipmentGates | 2 (`CrossflowEquipmentTest`, `HeatSidesTest`) | 13 | 0 |
  | fluidSolverRegression | chain-100 | 1 | 0, every deviation 0 |

- The dense GameTests alone (`-PfluidGameTestNamespaces=icheme_dense_test`), run `s4-dense1`: 3 of 3 passed, with
  every ledger within 6e-16:
  - the letdown, from 403.83 to 325.23 K;
  - the two nozzles, with the same fluid and head;
  - the loop: heater outlet 342.77 K at δ 0.679 (vapour slot), cooler outlet 302.41 K at δ 1.794 (liquid slot),
    4.99 kg/s, no slot change.
- The final lane must still run what `affected.mjs` marks due: the regression step and the GameTest step, since
  `FlowThermo` and `HeatCurve` changed.

### S4b (2026-11-16)

- Commits: `588f7da31` (one dense fluid has no second phase at a machine: E only at a real flash, no p_v, no droplets,
  the sheet), `6e6f928aa` (its tests), `c69ff5303` (a pump's own weight, 0.5 to 1.0, and its test), then the documents.
- Tests: `DenseFluidSuctionTest` (4: the gas side has no edge, the bubble-side flash, the seam on the critical
  isenthalp), `DenseMachineSharesTest` (10: a pump through a line not locked, no droplets at a compressor, no p_v for
  the dense natural gas, a pump's full head from the critical density), `ServiceTest.aDenseSuctionIsColdAndHasNoEdgeOnTheSheet`,
  `FlowsheetUnitsTest` (no warning; `aCompressorsDischargeOnADenseSuctionFollowsTheSolvedOutlet`).
- Probes (`tmp/supercritical-s4b/`): S4's three probes recompiled and run before (`before/`, equal to S4's own outputs
  to the byte) and after (`after/`, `after-pump/`); `S4bProbe` (the seam, the pump readers, the droplet share),
  `S4bSidesProbe` (the isenthalps on each side of the seam), `S4bSheetProbe` (the compressor's discharge, the storage
  pressure), `S4bPumpProbe` (the pump's weight).
- One filtered Gradle invocation per gate group, `--max-workers 4`, with the classes of
  `node tools/merge-lane/affected.mjs select`, after each code change:

  | Run | Since | Group | Classes | Tests | Failed |
  |---|---|---|---|---|---|
  | `runs1` | `c0596cff3` | test | 190 | 1,347 | 0 |
  | | | holdGates | 10 (all) | 136 | 0 |
  | | | fluidSolverRegression | chain-100 | 1 | 0, every deviation 0 |
  | `runs2` (the pump's weight) | `6e6f928aa` | test | 235 | 1,533 | 0 |
  | | | holdGates | 10 (all) | 136 | 0 |
  | | | fluidSolverRegression | chain-100 | 1 | 0, every deviation 0 |

  columnGates and equipmentGates had no affected class. `FlowsheetExamplesTest` was not selected: its recorded load
  set holds none of the changed files. The sample runs that pin the examples' numbers (`SmallCrudeUnitTest`,
  `CrudeUnitSampleTest`, `SmallUnitRecordingTest` and the other `client.gui.flowsheet` classes) were, and passed.
- The dense GameTests alone, on the final code (run `s4b-dense1`): 3 of 3 passed, every ledger within 6.7e-16. The
  letdown and the two nozzles read as before. The loop circulates as in `s4-dense1` and `s2c-dense2`: hottest
  342.77 K, 10.721 MPa, δ 0.679; coldest 302.41 K, δ 1.794; 4.99 kg/s; no slot change. Its pump draws a subcooled
  liquid. Interval-level values differ in the fourth digit, as they did between `s2c-dense2` and `s4-dense1`
  (interval 21's hottest pressure 10.7206, 10.7161 and 10.7181 MPa).
- The final lane must still run what `affected.mjs` marks due: the regression step and the GameTest step.
