# Prime movers: combustion engines, gas turbines, generators to 300 MW

Status: **In progress since 2026-11-10** (phase 2: owner answers in, building). Branch `claude/prime-movers-4b7224`, cut from `claude/custom-shader-system-caff9e` at `841e765f2`.

## Scope

Owner, 2026-11-10: "Add combustion engine which worked on liquid fuels, and gas turbine for gas fuels; should support MW scale size (also make bigger generators if their size doesn't match)". Fixed: "To 300 MW"; exhaust "Own stack + steam coil"; fuel gas "Real supply pressure".

## Documents

- [PRIME_MOVERS_PLAN.md](PRIME_MOVERS_PLAN.md): Willans-line efficiency, fuel from LHV, air and exhaust, the radiator, GT pressure ratio and fuel supply rule, models, governor, generators to 300 MW and the FE arithmetic, the pinch-designed steam coil, saves; decisions P1 to P14.

## History

- 2026-11-10: plan written (revision 1), sent to the owner.
- 2026-11-10: owner "It shall not overspeed, and keep the model simple. Others as recommended" (plan 12.1): P11 no overspeed at all (governor caps at the set speed, set speed at most rated, no trip); all others as recommended.

## Open items

- Build (phase 2).
