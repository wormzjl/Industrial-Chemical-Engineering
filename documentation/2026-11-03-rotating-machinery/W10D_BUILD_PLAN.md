# W10d pump readings and curve: build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9`. The owner picked "Readings + pump curve" (decision D37) over the readings only and plan 9 in full. Binds: plan 9's two-phase readings, A93 (the curve moved here), D34 (W10b's simple form), D36, the `gui-creation` skill. Prototype first; the owner looks; then build.

## 1. What the pump page shows

- **Suction group** (a meter each): NPSH available (m), NPSH required (m, the NPSH3 at the present flow and speed), and gas (% of the suction by volume).
  - For an in-line pump, NPSH required is NPSH3 at its D21 duty speed.
- **Health meter** (%), from W10b's fact.
- **Status words** on the message display, said once:
  - `CAVITATION` while σ < 4, that is while the pump crackles;
  - `GAS` while the gas share is above half the Turpin onset, that is while it churns;
  - `FAILED` at health 0, which takes precedence.
  
  `HEALTH n %` leaves the status line, because the meter shows it.
- **The curve on the glass,** for a constructed centrifugal pump only:
  - Its head–flow curve H(Q) at the present speed, from its `CentrifugalPump` design point, and the NPSH3(Q) curve on a second axis.
  - The operating point (present flow and head) and NPSH available as a horizontal line. Where it crosses NPSH3, the pump starves.
  - It is a Minecraft-free drawing class with its own hit-free layout, drawn inside the glass (skill recipe 7).
- **In-line pumps** run the set-flow law and have no head–flow curve, so their glass keeps today's tables. They get the readings only.
- **Not built:** no compressor readings (no wear source; D34), no head factor M, no rate of loss, no Repair key (the kit is used on the block).

## 2. Data (send on request only)

- The readings ride the device page's existing view (`FluidView`), from the pump's last committed observation (W10b's σ, W10c's gas ratio) and W10b's health. This is a wire change, with no compatibility work.
- The curve is drawn on the client from the casing's static design data (its shape's duty: rated flow, head and speed), with the present speed share and operating point from the view. The client computes no process state.

## 3. Steps

1. Prototype sheets (`GuiPrototypes`, both themes, opened and scrolled), the owner looks:
   - the constructed pump page: Suction and Health groups, the curve on the glass, `CAVITATION` on the display;
   - the in-line pump page: readings only.
2. After the owner's look: the builder rows (`DeviceColumn`), the drawing class with its gates (`DeviceColumnTest`, a drawing test like `HeatDiagramTest`), the view fields with a codec test, en_us lines only, and the fluid GameTests once.
