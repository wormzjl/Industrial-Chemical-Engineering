# Batch still: process simulator (W7)

Status: written 2026-11-09 on `claude/still-simulator-6e9d92`; not committed, not run through Gradle. Hand-over in the
session's `tmp/still-simulator/HANDOFF.md`.

## Unit (`batch_still`, MODELLED)

- `StillBatch` (Minecraft-free) integrates one batch with the W1 law: `BatchStill.step` on `StillColumn`, bounded at
  2,000 steps. A step distils about 1 % of the charge or warms about 5 K; the head moves at most 2 K a step.
- `BatchStillUnit`: the charge is the inlet's flow times the cycle (default 2 h). The charge is let down to the pot at its
  enthalpy, at most its bubble point. Cuts end at a head temperature or at a time (1 to 6, defaults 60 °C and 120 °C).
  Each cut leaves by `cut/<id>`, the residue by `residue`, the uncondensed vapour by `vent` (worm tub only). Flows are
  cycle averages at 101.325 kPa. The unit's duty is the law's ledger: heat in − condenser duty + the charge's let-down.
- Refused: solids, free water, no bubble point, a charge over the pot, a batch that does not finish (`still.unfinished`).
  Warned: hot charge, fill over 75 %, dry, overheat, batch longer than its cycle, vented vapour, flooding, column failure.

## Curve

`Report.Series` holds time-series readings (time, named channels). The still writes `batch`: pot and head temperature,
distillate rate (kg/s), cut number, and the mole fractions of up to five main components, at most 240 points.
`SimulatorResults` carries it (state format 1 → 2). `StillCurve` draws it on the door's third tab, Batch.

## Sizing

`StillSizing`: pots of `BatchStillDesign` (100 to 2,000 L). Limits STILL_VOLUME (MUST 100 %), STILL_FILL (SHOULD 70 %),
STILL_BATCH (MUST, batch within the cycle), STILL_BATCH_SPARE (SHOULD 20 %), STILL_COOLING, STILL_FLOOD (MUST, F 1.8),
STILL_F_FACTOR (SHOULD, F 1.5). Beside them: heater rating, worm area, column diameter. Auto: the smallest pot that holds
the charge at 70 % and the cycle's 20 % spare. The batch time on another pot scales with 1 / heater rating.

## Catalog and drawing

`BATCH_STILL` is MODELLED. `Symbol.STILL` (pot on its setting, helmet, swan neck, worm tub) replaces the kettle
placeholder (owner O4). The diverter valve (`TopologyCompiler.Kind.DIVERTER_VALVE`, from still-engine) maps to the
splitter: the chosen outlet takes the rest, the other two a share of none. That case is a separate patch, applied after
still-engine's.

## Owner decisions

1. `network/SimulatorResults` changed (format 2): outside this thread's boundary, needed for the curve to replay.
2. Water in the charge: refused (A, built). Or boiled off first to the first cut (B).
3. A hot charge: let down to its bubble point, the flash heat booked as the still's duty (A, built). Or its flash vapour
   goes to the first cut (B).
4. Condenser water is a setting (inlet temperature at the law's design flow), not a port pair; its duty is a boundary duty.
5. A head cut is decided at the start of a step: the cut boundary is late by at most one step (about 1 % of the charge).
6. Default cycle 2 h, water 25 °C. Isopentane boils at 28 °C, so a light naphtha may vent part of it at 25 °C water.

## Law gaps

None blocking. `BatchStill.Step` gives the head temperature of the step's vapour only; a cut ends on the next step.
