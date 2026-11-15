# S4: a dense fluid at a pump and a compressor

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

**What changed** (four commits; no state that is not dense moved, measured on 100 states):

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
- E is a density above Pc: 320 K at δ 1.25 gives 8.361 MPa (it was 7.309 MPa, on the slot line). A fluid that is
  already gas-like enough has E at its own pressure: below δ about 1.12 at 310 K, 1.10 at 320 K, 1.09 at 330 K.
- E keeps two seams (section 3), each an owner option:
  - at δ 0.5, E goes from none to the suction's pressure;
  - where the isenthalp crosses Tc above Pc, the weight stops applying, and E drops by 0.36 to 0.65 MPa.
- Compressor law (step 5, measured, not changed):
  - isentropic head error −4 to −7 % up to δ about 1, and −14.4 % at δ 1.64 (CO2 320 K, 15 to 30 MPa);
  - the engine's discharge temperature at the law's enthalpy is within 2.1 K;
  - the simulator sizing's ideal-gas discharge estimate is 17 to 32 K high on a dense suction.
- Wear: a pump on a dense fluid has no p_v, so no cavitation read and no cavitation wear (pure CO2; unchanged). A
  centrifugal compressor's wet gas wear now reads `w`: it erodes fully from δ about 0.65, where before it eroded only
  in the liquid slot.
- Runs:
  - the affected classes since the merge (`8bac3e6e7`), all passed: test 254 classes, 1,702 tests; holdGates 136
    tests; equipmentGates 13 tests; chain-100 deviation 0;
  - the three dense GameTests alone, run `s4-dense1`: 3 of 3 passed. The loop's pump draws δ 1.79, so `w = 1`, and the
    loop runs at the same states as before S4.

## 1. The shares

`InletPhase.measure`: for one dense fluid (`State.dense()`, with some fluid measured), `vapourVolumeShare = 1 − w` and
`condensedMassShare = w`. The smoothstep gives exactly 0 at or below δ 0.5 and exactly 1 from δ 1.5. So outside the
window the shares are the slots' own values to the bit: 1 and 0 in the vapour slot, 0 and 1 in the liquid slot.

- A port's stream drawn from a dense state is that fluid (the state has one fluid slot).
- A dense state may also carry solids. The two shares are of the fluid only, so solids do not enter them; the largest
  solid share is read as before.
- An ordinary gas above its Tc is `dense()` too (nitrogen at 300 K). Its δ is below 0.5 at the network's pressures
  (0.385 at 10 MPa), so it reads exactly what it read before (`DenseMachineSharesTest`).

Carbon dioxide at 310 K (head share at the state's pressure; `tmp/supercritical-s4/probe/shares-compare.txt`):

| δ | slot | w | vapour share before → after | head share before → after | wet efficiency before → after |
|---|---|---|---|---|---|
| 0.30–0.50 | V | 0 | 1 | 0.340–0.459 (unchanged) | ×1 |
| 0.60 | V | 0.028 | 1 → 0.972 | 0.487 → 0.516 | ×1 → ×0.986 |
| 0.80 | V | 0.216 | 1 → 0.784 | 0.517 → 0.739 | ×1 → ×0.892 |
| 0.9995 | V | 0.4993 | 1 → 0.5008 | 0.530 → 0.947 | ×1 → ×0.750 |
| 1.0005 | L | 0.5008 | 0 → 0.4993 | 1 → 0.948 | ×0.5 → ×0.750 |
| 1.20 | L | 0.784 | 0 → 0.216 | 1 → 0.998 | ×0.5 → ×0.608 |
| 1.40 | L | 0.972 | 0 → 0.028 | 1 → 1.000 | ×0.5 → ×0.514 |
| 1.50–1.80 | L | 1 | 0 | 1 | ×0.5 |

Nitrogen at 150 K across the line: head share 0.468 / 1 before, 0.933 / 0.934 after.

**The refusal across the window.** The refusal (`InletPhase.decide` through `Mover.refuses`) reads only a dry suction
and, at a compressor, solids above the trace. Neither share enters it. So a dense fluid is refused nowhere in the
window, as before (`DenseMachineSharesTest.neitherMachineIsRefusedAcrossTheWindow`).

**Densities.**

- A set-flow pump's rise (`MoverSuction.riseDensity`, from `InletPhase.density`) and a machine's rise (the suction
  stream) read the dense fluid's own density in either slot, before and after. No reader defect there.
- The NPSH density (`InletPhase.liquidDensity`) read NaN in the vapour slot. It now reads the fluid's own density
  wherever `w > 0`. It feeds the breakdown NPSH, which adds to p_v only, so it changes nothing for pure CO2 (no p_v).
  It also feeds the pump page's NPSH3 reading, which was blank in the vapour slot.

## 2. Where E is evaluated

- E is read once per solve per pump, on the step's start states (`suctionFloors`, then `zoneEdge`, cached by state).
  Its trials are full PH equilibria (`equilibriumPH`), not tangent-built states, so `dense()` there is each trial's own.
- The mode tests and the suction-limit row read the stored E, not a trial (`cavitationMargin`, `suctionLimit`).
- The supply shares are read on the committed graph's states (`readSuctions`), which are full states.

## 3. The suction limit E, before and after

`ReleasePressure.vapourVolumeShare` of a dense trial state is now `1 − w`:

- 0 from δ 1.5, as the liquid slot gave;
- 1 at or below δ 0.5, where the vapour slot gave NaN;
- `Double.MIN_VALUE` if `1 − w` should round to 0 while `w < 1`, which never happens in doubles.

So above Pc, E is the pressure at which the stream, at its own enthalpy, thins to the clean share: a density. Below Pc,
the dome's real vapour share is unchanged.

| State | E before | E after |
|---|---|---|
| CO2 320 K, 15 MPa (δ 1.643) | 6.5198 MPa (a flash) | 6.5198 MPa |
| CO2 310 K, δ 1.0005 (8.3265 MPa) | 8.3178 MPa (the slot line) | 8.3265 MPa (its own pressure) |
| CO2 310 K, δ 0.9995 (8.3251 MPa) | none | 8.3251 MPa (its own pressure) |
| N2 150 K, δ 1.0005 (7.0593 MPa) | 7.0535 MPa | 7.0593 MPa (its own pressure) |
| CO2 320 K, δ 1.25 (11.079 MPa) | 7.3093 MPa (the slot line) | 8.3612 MPa (a density) |
| CO2 330 K, δ 1.30 (13.771 MPa) | 7.4262 MPa | 9.4241 MPa |
| natural gas 300 K, δ 1.6 (30.61 MPa) | 14.249 MPa | 15.811 MPa |

**Where E is the suction's own pressure.** The world's rule (`MoverSuction.zoneEdge`) puts E at the supplier's
pressure when the stream's vapour share is already at or past the clean share there, as for any stream that gassy. For
a dense fluid that is `1 − w ≥ cleanShare(P)`: below δ about 1.12 at 310 K (clean share 0.32 at 8.5 MPa). A pump drawing
such a fluid through a line is vapour-locked; one drawing straight from its vessel holds its suction at E.

Before S4 the same states had either no E or an E well under the suction:

- in the vapour slot (δ 0.5 to 1) there was no E, and the pump ran on its head share, 0.46 to 0.53;
- in the liquid slot, just above the line, E was the slot line (9 kPa under the suction at δ 1.0005);
- from δ 1.05, E was a flash below Pc at 310 K (7.27 to 7.54 MPa, 0.86 to 1.3 MPa under the suction), or the slot
  line at 320 and 330 K (0.95 to 1.0 MPa under it).

So a pump that draws dense CO2 at δ 0.5 to about 1.1 through a line is now vapour-locked, where before it ran. This is
the largest behaviour change of S4, and the owner should look at it (section 8, option 1).

**Is E continuous?** It is continuous across the slot line: it is the suction's pressure on both sides. Two seams
remain:

1. **At δ 0.5** E goes from none (`w = 0`: no liquid) to the suction's pressure (`w > 0`, vapour share near 1). This is
   the jump the world already has between a gas and a gas with a trace of liquid. The pump's head share is continuous
   there (0.459 at 310 K).
2. **Where the isenthalp crosses Tc above Pc.** Past Tc the stream is a subcritical one-root fluid, not `dense()`. It
   reads vapour share 0 there, then the dome's share once it flashes. So the trial falls from `1 − w` (about 0.33) to 0
   at Tc, and the bisection lands on either crossing.
   - E drops by 0.65 MPa at 320 K between δ 1.2875 and 1.30 (7.875 → 7.226 MPa), by 0.36 MPa at 310 K (δ 1.1875 →
     1.20) and by 0.35 MPa at 330 K (δ 1.4625 → 1.475) (`probe/seam.txt`).
   - Before S4 the same seam sat at the slot line, with a kink.
   - This is the film's seam (S2b): the film weights every one-fluid state, but the machines weight only `dense()`
     ones, as the brief asks. To close it, weight every one-fluid state at a machine too. That would move subcritical
     near-critical liquids (δ below 1.5), so it is an owner option, not done.

Mixtures: a dense natural gas (CH4 0.90, C2H6 0.06, C3H8 0.03, N2 0.01) at 300 K keeps its p_v in the liquid slot. It
is 2.66 MPa, because ethane and propane stay after the light gases are left out, and it is none in the vapour slot, as
before. Its limit is max(E, p_v + breakdown), and E (15.8 to 17.8 MPa) is the larger of the two. So the p_v step does
not reach the limit at the states measured.

## 4. Wear (step 4)

- Cavitation wear reads σ = NPSHA / NPSH3 against p_v. A pure dense fluid has no p_v (`leaveOutGases`), so σ is NaN
  and the intensity is 0. This is unchanged and confirmed by `DenseMachineSharesTest.aDenseFluidGivesNoCavitationWear`.
- A dense mixture whose heavier compounds stay below their Tc keeps a p_v in the liquid slot, so it has a σ. This is
  unchanged.
- The gas lock (the DRY fault) reads the same vapour share as the head share.
- A centrifugal compressor's wet gas wear (`Wear.wetGas` of the condensed share) now reads `w`. Its onset is 0.5 % and
  its full intensity is reached from 5.5 %, so it erodes fully from δ about 0.65. Before S4 it was zero in the vapour
  slot and full in the liquid slot. Physically, a dense fluid has no droplets to erode an impeller, so this is an owner
  option (section 8).

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
  - in the sizing, the discharge from the engine at `h₁ + H_p/η` at the outlet pressure, as the world does.

## 6. The simulator (step 6)

Every place in `science/flowsheet` that reads a phase label, a vapour share or a condensed share to choose a law:

| Reader | What it chose by | S4 |
|---|---|---|
| `MoverUnit`: refusal | `InletPhase.bulk`: dry suction and solids only | none needed |
| `MoverUnit`: `pump.bubble_point` warning | `FlowThermo.boils` 0.1 % under the pressure, which for a dense fluid only said "vapour slot" | `denseAtEdge` (`1 − w` at or past the clean share) for a dense fluid |
| `PumpSizing`: p_v, E | `FlowThermo.vapourPressure`, `zoneEdge` on the sheet's TP trial (`ReleasePressure.vapourVolumeShare`) | reached by step 3 |
| `Service.of`: gas or liquid class | vapour mass share above 95 %, i.e. the slot | unchanged: the slot is the weight's midpoint (w 0.5 at δ 1) |
| `Service.of`: a pump suction boiling or cold | spare over `bubblePressure`, which for a dense fluid is the slot-line pressure | `denseAtEdge` for a dense fluid |
| `CompressorSizing`: `COMPRESSOR_LIQUID` | `100 − vapour mass share`, i.e. the slot | `100 w` inside the window |
| `ExchangerSizing`: vapour share in and out | exchanger service | not a machine; the film is S2b's |
| `TankSizing` and `VesselUnit`: bubble and storage pressure | vessels | not in S4 |
| `FlowThermo.releaseShare`: a trial all gas reads 1 | sheet trial | the dense trial reads `1 − w` first |

Sized numbers at carbon dioxide, 310 K (`probe/sheet-before.txt`, `sheet-after.txt`):

- The pump's E on the sheet's TP trial:
  - at δ 1.2 (8.680 MPa) it was 8.3262 MPa, the slot line, and is now 8.5132 MPa (where `1 − w` meets the clean share at
    310 K);
  - at δ 1.6 it was 8.3285 MPa and is now 8.5134 MPa;
  - at 320 K and 15 MPa it was 9.9411 MPa and is now 10.3582 MPa;
  - at δ 0.9995 and 1.0005 it is the suction's own pressure, before and after.
- A line into a pump, at δ 1.05 and 1.1: `SUCTION_COLD` before (1.5 m/s under a 0.25 m bore, 1.8 above), `SUCTION_BOILING`
  after (1 m/s under 0.25 m, 1.25 m/s to 0.5 m, 1.5 above). At δ 1.0005 and 1.2 the service did not change.
- The compressor's liquid share:
  - at δ 0.9995 it was 0 % and is now 49.9 %; at δ 1.0005 it was 100 % and is now 50.1 %;
  - at δ 1.2 it was 100 % and is now 78.4 %;
  - so `COMPRESSOR_LIQUID` (a SHOULD row, at most 1 %) still lights from δ about 0.56.
- The pump warning, at δ 1.05 and 1.1: none before, now `pump.bubble_point` (the fluid is gas-like enough that its edge is
  its pressure).

`FlowsheetUnitsTest.aPumpAndACompressorOnADenseFluidSeeNoSlotLine`:

- at 8.32509 MPa (vapour slot) and 8.32648 MPa (liquid slot), the pump's and the compressor's power, head and rise agree
  within 0.2 %;
- the pump warns alike on both sides.

`ServiceTest.aDenseSuctionIsBoilingByItsDensity` covers the suction class and the sheet's E at five states. No sample
pin moved (`FlowsheetExamplesTest` is among the affected classes run).

## 7. Identity

`FluidThermodynamics.revision` holds the token `dense-movers-v1` beside `line-cap-saturation-v1`, so saved solved state
rebuilds once. There is no facts field and no corpus image.

## 8. Owner options

1. **The machines' bounds, and the pump's lock.** The film's 0.5 and 1.5 (implemented), or bounds of their own. With
   these bounds the pump's E is the suction's own pressure below δ about 1.1, where `1 − w` passes the clean share.
   So a pump drawing through a line is vapour-locked from δ 0.5 to about 1.1. Before S4 it ran there: on a head share
   of 0.46 to 0.53 in the vapour slot, and with E 0.9 to 1.3 MPa under the suction in the liquid slot from δ 1.05.
   Bounds nearer the line (0.75 and 1.25) would narrow that band to about 0.75 to 1.06.
2. **The seam at Tc on the isenthalp** (section 3): weight every one-fluid state at a machine, as the film does. That
   would close the 0.35 to 0.65 MPa drop in E, but it would move subcritical near-critical liquids at δ below 1.5.
   Alternatively, leave it (implemented).
3. **The seam at δ 0.5** (none → the suction's pressure). It is the world's gas-against-trace rule, and it is left as
   it is.
4. **The compressor law** (section 5): the real isentropic exponent in `σ`, and the sizing's discharge from the engine.
   Not done.
5. **Wet gas wear on a dense fluid** (section 4): read `w` (implemented, full from δ about 0.65), or none for a dense
   fluid, since it has no droplets.

## 9. Left for S5, and what S4 met outside its scope

- Texts that name a phase for a dense fluid:
  - `pump.bubble_point` (the warning now fires on a dense fluid at its edge, but the word is "bubble point");
  - `compressor.phase` / `pump.phase` (the refusal texts);
  - "ERROR: compressor inlet not gas" (`InletPhase.reason`: solids only);
  - the pump page's vapour share and gas ratio readings, which show `1 − w` for a dense fluid.
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
