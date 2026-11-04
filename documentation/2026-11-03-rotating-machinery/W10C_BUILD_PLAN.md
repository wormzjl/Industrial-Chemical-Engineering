# W10c cavitation sound: build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9`. Binds: plan 3.8, D26 (W4's loops, volume and pitch from size and speed), D34 (W10b's simple form: σ and dry read at commit). The scope (section 3) is the owner's pick. Nothing is built before it.

## 1. Simple form (recommended)

- **One new loop, `pump_cavitation.ogg`:** the crackle of collapsing bubbles, broadband with sharp random transients ("gravel in the pump").
  - It is authored by `tools/machine-sounds/make_loops.py` beside W4's loops, 3 s long, with a seamless seam.
  - It plays at a pump's casing or in-line block, on top of W4's casing loop.
- **Volume:** `I_c(σ)` from W10b's `Wear.intensity`, the same curve that wears the pump. It is silent above σ = 4 (four times NPSH3), loudest near twice NPSH3, and softer below NPSH3. It is scaled by W4's `soundGain` for the pump's size.
- **Transport:** one byte added to `FluidNetwork.MoverState`, σ's intensity 0 to 255, set from the pump's last committed σ.
  - The engine already sends `MoverState` on its schedule to clients that watch movers in range. The client eases the volume and computes no process state.
  - This is a wire format, so it needs no compatibility work.
- **No compressor sounds, no gas churn.** D34 left compressors without a wear source; the gas churn can come later.
- **Check:** the loop's numbers (RMS, peak, seam step) in the tool's README; `MoverState`'s codec test; a test that the byte follows `I_c`. Then your one listening check (D26), which you start.

## 2. What the plan's full form adds (plan 3.8)

- A gas churn loop (50 to 200 Hz surges, rising with α/α_T from 0.5 to 1.5, fading toward gas lock) and a second byte.
- A compressor droplet hiss and a reciprocating knock, which D34 removed the causes for.

## 3. Owner pick: B (decision D35)

The owner picked B, the crackle and the gas churn. The churn:

- **Loop:** `pump_gas.ogg`, low irregular surges, 50 to 200 Hz, authored beside the crackle.
- **Volume:** `v(r) = clamp(r − 0.5, 0, 1) · M(r) / 0.568`, with `r = α/α_T` (W10a's Turpin onset) and `M(r) = 1/(1 + (r/1.6)^4)`, W10a's head factor. It is silent below r = 0.5, at 0.76 at the onset, at its peak 1.0 at r = 1.42, and at 0.51 at 2 and 0.13 at 3: it fades as the head collapses toward gas lock.
- **Data:** a second `MoverState` byte. The solver's observation adds `r` for running liquid movers, beside W10b's σ.


- **A (recommended):** the simple form, the crackle only.
- **B:** the crackle and the gas churn.
- **C:** plan 3.8 in full.
