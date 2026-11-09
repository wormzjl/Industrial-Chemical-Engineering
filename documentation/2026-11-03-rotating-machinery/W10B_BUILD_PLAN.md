# W10b health and repair (simple form): build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9`. Revision 2, 2026-11-04. The owner: "Hold on, keep this simple", then picked "Cavitation + dry only" (decision D34, which replaces D33 and readings A109 to A128). Revision 1, with six wear sources, gradual loss, a knock trip and two parts, is in git history (`1a2f6dfdb`).

## 1. Behaviour

- Every pump, constructed or in-line (D19), has a health from 1 (new) to 0 (failed).
- **Wear has two sources:**
  - **Cavitation:** `I_c(σ) / 2 h`, with `σ = NPSHA/NPSH3` and `I_c = (σ/2.06)³ ((4 − σ)/1.94)^2.83` for 0 < σ < 4, else 0 (Gülich; plan 3.7 without the gas cushion). Lives in online time: 2.0 h at σ 2.06, 5.1 h at NPSH3, 18.9 h at σ 3.5, none above 4.
  - **Running dry:** `1 / 10 min` while the driver is on and the pump is gas-locked or vapour-locked.
- **Effect.** None until health reaches 0. Then the pump stands closed, and the message display or status line says `FAILED`.
- **Compressors** have no wear source in this form, so they carry no health.
- **Repair.** One crafted `repair_kit` (4 iron ingots, 1 copper ingot), used on any cell of a pump casing or on an in-line pump's pipe block, restores health 1. It is consumed on acceptance (none in creative mode) and queued as an engine event. A pump at full health refuses it with a chat line.

## 2. Engine

- **Facts.** Health is a double per pump device id, in fluid facts schema 12, with corpus image `compat/fluid/schema-12/`. The default 1.0 matches the behaviour before the field.
- **Booking.** At each slice commit and replay, a pure `science/machine/Wear.rate(σ, dry)` reads the committed suction state, with NPSH3 from `Npsh.required(Q)` (D21), and books `rate × Δt`.
  - A resting island is booked on the 100-tick cadence at its last rate, so a steady cavitating pump still wears.
  - One `bookedTick` per pump prevents double booking (W5's pattern).
- **To the solver.** Only a failed flag, as a dispatch input (W3's `ShaftPlan` pattern), using the closed-mover path. A failure or a repair wakes the island. Wear above 0 wakes nothing, and no new mode is added.
- **Config.** `machines.wear.enabled` (true), `cavitationHours` (2), `dryMinutes` (10).
- **Shown.** `HEALTH 73 %` on the pump's status line and on its machine page's nameplate. Readings and a Repair key wait for W10d.

## 3. Build and tests

1. `Wear` with `WearTest`: section 1's lives, and zero above σ = 4.
2. Schema 12 and its image. Older images read health 1.
3. Booking, the cadence, the failed flag and the waking. `PumpHealthTest`: a cavitating pump wears to `FAILED` and stands closed, a clean pump keeps 1, a repair restores it.
4. The `repair_kit` item: recipe, model, a flat 16 × 16 texture drawn by `tools/machine-parts/`, and its use. GameTest in its own batch: a cavitating pump loses health and a kit restores it.
5. en_us lines only.

- Affected tests by `--tests`, then one campaign on the final code, alone.
