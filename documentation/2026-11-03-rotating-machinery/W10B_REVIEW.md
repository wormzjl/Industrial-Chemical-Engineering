# W10b: pump health and repair (simple form), review

Commit `6074e7aa0` (`W10B_BUILD_PLAN.md` revision 2, owner decision D34). Built by an Opus medium subagent and reviewed here.

## 1. What was built

- **Wear rates.** `science/machine/Wear` (pure): Gülich's cavitation intensity `I_c(σ)` (using `DeterministicMath.pow`), the cavitation and dry-running rates, and `Settings` from the config `machines.wear.enabled`, `cavitationHours` (2) and `dryMinutes` (10).
- **Booking.** `runtime/fluid/PumpHealth` keeps health per pump device id.
  - It books at commit and replay from the solver's observation, and on the 100-tick cadence while at rest, with one `bookedTick` per pump.
  - Fluid facts schema 12 saves only pumps below 1. The corpus image is `compat/fluid/schema-12/`, and older images read every pump at 1.
- **Observation.** `PassiveStepSolver.PipeObservation` gains σ and a dry flag for running liquid movers. It is read only: the solve itself is unchanged.
  - `σ = (p_end − vapour floor)/(ρ_l g)/NPSH3(Q)`.
  - Dry means vapour-locked, a wrong inlet phase, or a machine passing nothing with gas above its onset.
- **Failure.** `ShaftPlan.Kind.FAILED` is a dispatch input. A failed pump stands closed (a machine at speed 0, a set-flow pump switched off) with its settings kept. The flag is set on the cadence, because a replay may be a view's read (A132).
- **Repair.** The `repair_kit` item: four iron ingots around one copper ingot, a flat navy and gold icon drawn by `tools/machine-parts/MachineParts.java`.
  - It is used on a pump's casing or an in-line pump's pipe block and queued as an engine event.
  - It restores health 1, takes one kit (none in creative), and refuses at full health or on a non-pump with a chat line.
- **Status.** `HEALTH n %`, or `FAILED: use a repair kit`, on the pump's status line.

## 2. Tests

- **`WearTest` (3):** lives of 2.0 h at σ 2.06, 5.1 h at NPSH3 and 18.9 h at σ 3.5; zero at σ ≥ 4; dry life 600 s; the switch and the config scaling.
- **`PumpHealthTest` (2):**
  - A cavitating in-line pump wears to `FAILED`, stands closed with its settings kept, and a kit restores it.
  - A pump fed at 1 atm keeps 1; the refusals take no kit.
- **`PumpHealthGameTests`:** a constructed pump on a creative motor at about 2 × NPSH3 loses health; a survival player's kit on its casing restores it.
- **Affected set:** `test` 625 and hold gates 58 pass. Fluid GameTests 43 of 43 (run `w10b-3`; runs 1 and 2 crashed in the new test's mock player, fixed).

## 3. Campaign

1000 seeds, run alone, on `6074e7aa0`, against W5's run:
- cold random: 992 clean, the same held seeds;
- bundled random: 994 clean, the same held seeds; seed 6 (main's baseline wall hold) clears again, as under W3;
- bundled defaults: 1000 clean, identical counters.

Counters move by under 0.35 %. No campaign pump wears to failure within 120 slices.

## 4. Departures and readings (A129 to A136)

- **No nameplate line.** A pump casing has no machine page (W7 gives pages to drivers), so health shows on the status line only.
- **In-line pumps after repair.** An island keeps the controls its last slice solved with, so a repaired in-line pump gets a reopen plan.
- **Mock player in the GameTest.** The GameTest's player is not joined to the player list: joining a mock player crashed the server in KubeJS's data sync.
- **Readings:**
  - A129, A130: wear at the slice's rate; dry and cavitation wear add.
  - A131: a set-flow pump with no NPSH design wears only when dry.
  - A133: disabled wear keeps saved health and closes no pump.
  - A134: σ is not saved with the checkpoint, so after a load a pump books 0 until its next commit.
  - A135: the kit stacks to 16 and is taken from any inventory slot.
  - A136: the status wording.

Code guide: no change yet. The fluid network and runtime chapters change at the merge refresh.
