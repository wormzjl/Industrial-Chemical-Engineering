# W10c: pump cavitation crackle and gas churn, review

Commit `975a718e2` (`W10C_BUILD_PLAN.md`, owner decision D35). Built by an Opus medium subagent and reviewed here.

## 1. What was built

- **Loops.** Two loops by `tools/machine-sounds/make_loops.py`, both under their mean step at the seam, so they loop without a click:
  - `pump_cavitation.ogg`: about 140 random bright clicks a second over a faint hiss, limited by tanh. RMS 0.101, peak 0.47, seam step 0.054 against a mean step of 0.092.
  - `pump_gas.ogg`: a 50 to 200 Hz band under a random 1 to 4 Hz swell. RMS 0.093, peak 0.57, seam step 0.0007 against 0.0021.
- **Bytes.** `MoverState` carries two bytes (registrar `mover-3`):
  - crackle `255 I_c(σ)`, W10b's wear curve;
  - churn `255 GasEntrainment.churn(α/α_T)`.
  
  They are set at commit from each pump's committed observation (`PipeObservation.gasRatio`, beside σ) and sent only on the engine's existing mover publication. They are 0 for a compressor, a stopped pump or a failed pump.
- **Client.** `MoverSounds` layers both on the casing loop at `byte/255 × soundGain`, easing 0.05 per tick (A137). A silent layer stops.

## 2. Tests

- `GasEntrainmentTest` 4: churn 0, 0.76, 1.0, 0.51 and 0.13 at r = 0.5, 1, 1.42, 2 and 3.
- `FluidPacketCodecTest` 22: the bytes round-trip, and a byte out of range is refused.
- `PumpHealthTest` 2: a cavitating pump's crackle is `round(255 I_c)`; a clean or failed pump is silent.
- Affected `test` 613 and hold gates 58; fluid GameTests 43.
- No campaign: the solver change is one more read-only observation value, as W10b's σ, which left the campaign unchanged. The final lane runs it.

## 3. For the owner

- **In-line pumps make no sound.** W4's sound watch covered constructed movers only. Follow-up done (D36, 69c1c8aa0): in-line pumps play the two layers, and every machine loop's pitch follows rpm.
- **A set-flow in-line pump has no gas churn:** it has no head factor M.
- **The listening check (D26) is due:** the casing, driver, crackle and churn loops, with volume and pitch following speed and size. You start it; no agent starts a dev client.

Code guide: no change yet. The client sound and fluid network chapters change at the merge refresh.
