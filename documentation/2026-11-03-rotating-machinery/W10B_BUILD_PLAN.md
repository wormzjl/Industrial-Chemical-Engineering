# W10b health, wear and repair: build plan

Batch `2026-11-03-rotating-machinery`, branch `claude/realistic-pump-compressor-6fdfd9` at `1c1c74b39`. Binds: plan 3.6, 3.7 and 10; D19, D20 (Q14 a, Q15, Q16), D21; W10a's laws (`4536849bb`); W3's machine registrations and trips; W5's cadence booking (A60, A78, A82); W7's machine pages (D31, D32). Readings A109 to A128 (section 7) wait on review. Nothing is built before the go.

## 1. Expected behaviour (plan 3.7's lives, checked)

- Every pump and compressor, constructed or in-line (D19), has a health D from 1 (new) to 0 (failed). Clean running never wears it: only a fault the player can see and fix does.
- **Cavitation** wears at `I_c(σ) g(α) / 2 h`, with:
  - `σ = NPSHA/NPSH3` and `I_c = (σ/2.06)³ ((4 − σ)/1.94)^2.83` for 0 < σ < 4, else 0;
  - the gas cushion `g = exp(−α/0.02)`.
  
  | σ | 1.0 | 2.06 | 3.5 | ≥ 4 |
  |---|---|---|---|---|
  | life, online time | 5.1 h | 2.0 h | 18.9 h | none |
- **Gas surging** wears at `clamp(α/α_T − 1, 0, 1) / 20 h`. A pump at 2 × the Turpin onset lives 20 h.
- **Dry running** wears at `1 / 10 min`: power on, with the pump gas-locked or vapour-locked (D15, D16).
- **Low flow** wears at `1 / 50 h` below 30 % of rated flow; an in-line mover's rated flow is its set flow.
- **Centrifugal compressor:** droplet erosion `(w_l/0.01) / 100 h`.
- **Reciprocating compressor:**
  - valve wear `(LVF/0.001) / 2 h`;
  - a *liquid knock*: `LVF ≥ 0.5 × clearance` costs 0.5 at once and trips the machine, one event per slice.
- **Effect.** Head and efficiency × `(0.9 + 0.1 D)`. A pump at D = 0.5 gives 95 % head at 95 % efficiency, so its shaft power is unchanged and its flow on a flat system curve falls by about 3 %.
  - At D = 0 the machine stands closed: `IMPELLER FAILED` for a centrifugal pump or compressor, `VALVES FAILED` for a reciprocating compressor or an in-line mover.
- **Repair (Q14 a).** A crafted spare part, used on the casing or on the in-line mover's pipe block, restores D = 1. It is queued as an engine event and acknowledged on the engine schedule.

## 2. Facts, config and status

- **Health fact.** Health is a double per pump or compressor device, in fluid facts schema 12, with corpus image `compat/fluid/schema-12/`. The default 1.0 is the behaviour before the field (A109).
  - It is keyed by the fluid device id, so it covers constructed casings and in-line movers alike.
  - A mover rebuilt from the same registration keeps its health.
- **Config** in a new `machines.wear` section of the common config:
  - `enabled`, default true;
  - the six τ values: 2 h, 20 h, 10 min, 50 h, 100 h, 2 h;
  - the cushion width, 0.02.
  
  Disabled means no wear and no knock. Health still reads, and parts still repair.
- **Status.** Until W10d's screens, health shows on the device page's status line (`HEALTH 73 %`) and on the machine page's nameplate (A110). The failure words go on the message display.

## 3. Engine (AGENTS.md: commits and cadence, never the tick loop)

- **Rates at commit** (A111). At each slice commit and replay, a pure `science/machine/Wear.rate(...)` reads each mover's committed suction state, flow, vapour lock and design point:
  - NPSHA from the committed suction pressure, vapour pressure and liquid density;
  - NPSH3 from `Npsh.required(Q)`, so the in-line speed is D21's;
  - α and α_T as W10a reads them; `w_l` and LVF from the compressor's committed suction.
  
  It books `ΔD = rate × Δt_slice`. One `bookedTick` per mover prevents double booking, as W5's stores do (A78).
- **At rest** (A112). A resting island has no commits. Its movers are booked on the presentation cadence at their last rate, so a pump cavitating at a steady state still wears. A rate of 0 books nothing.
- **To the solver** (A113). The factor `0.9 + 0.1 D` and the failed flag reach the solver as a dispatch input, as W3's `ShaftPlan` and W5's fan limit do: health never edits the mover's settings.
  - The factor multiplies W10a's `withHeadShare(M)` on the head and efficiency.
  - A failed mover stands closed (the D58 closed-mover path), so no new mode.
- **Waking** (A114). A factor change above 1e-3 since the island's last dispatch, a failure, a knock or a repair ends the island's certificate. Health that drifts by less leaves it at rest.
- **Knock** (A115).
  - On a constructed reciprocating compressor: the train's driver trips with a new latched `Trip.KNOCK`, `TRIPPED: LIQUID KNOCK`, reset on W7's machine page or with `/icheme shaft reset`.
  - On an in-line compressor: the mover disables itself (its `enabled` set by the engine as a state flag, not a settings edit), and the player's Off then On clears it.

## 4. Parts and repair

- **Items** (A116): `impeller`, for the centrifugal pump and compressor, in-line pumps included (an in-line pump is centrifugal under W10a's law); and `valve_set`, for reciprocating and in-line compressors.
  - Q14 named a third, `seal_kit`. Under plan 3.7 no fault wears a seal, so it is left out (A117).
- **Recipes** (A118): vanilla ingots, so the parts craft in any pack.
  - impeller: 4 iron ingots, 1 copper ingot, 1 redstone;
  - valve set: 3 iron ingots, 2 copper ingots.
- **Textures** (A119): 16 × 16 flat silhouettes in the mod logo's direction (no lighting), drawn by a script in `tools/machine-parts/`. Rendered for the owner.
- **Use** (A120). Right-clicking the casing, any of its cells, or an in-line mover's pipe block with the matching part consumes one part (none in creative mode). A repair edit is queued with the usual checks (may build, reach). The engine sets D = 1 at its next bucket, clears a failure and a knock latch, and wakes the island. A wrong part or full health refuses with a chat line and consumes nothing.

## 5. Build order and tests

1. `science/machine/Wear` (pure): rates, the lives table, the knock test. `WearTest` checks section 1's numbers: 2.0 h, 5.1 h, 18.9 h, 20 h, 10 min, the gas cushion halving at α = 1.4 %.
2. Health facts in schema 12, with the corpus image (`FluidFactsCompatTest` writes it). Older images read health 1.
3. The commit and cadence booking (`runtime/fluid`, beside `ElectricStores`), the dispatch input, the wake threshold and the knock trip. Tests:
   - `MoverHealthTest`: a cavitating pump wears to failure and stands closed; a clean pump keeps 1.0; repair restores it.
   - `ShaftTrainTest`: `KNOCK` latches and resets.
4. Items, recipes, textures and the repair use and event. GameTest `MachineWearGameTests`, in its own batch:
   - a pump from a near-boiling tank cavitates, its health falls, an impeller restores it;
   - a wrong part refuses.
5. Config; status line and nameplate; en_us only.

- **Affected tests** run by `--tests`. One campaign on the final code, alone (A121): W10b changes what a pump delivers once it wears, so the campaign is a real check. The campaign's horizon is 120 slices, so only cavitating seeds can move.

## 6. What it does not do

- No screens, readings or Repair key (W10d), no sounds (W10c).
- No wear from clean running, start counts or hours.
- No item durability on parts.
- Nothing for steam turbines, motors or gearboxes: request 11 named pumps and compressors.

## 7. Readings (recommended answer: reason)

- **A109.** Health is keyed by the fluid device id, in schema 12, default 1.0. In-line and constructed movers share the key, and the facts rule needs a default equal to before.
- **A110.** Until W10d, health shows on the status line and the nameplate. The player must see wear before a part is needed.
- **A111.** Rates are read at commit from committed states, with NPSH3 from `Npsh.required`. These are plan 3.7's "committed states" and W10a's design point.
- **A112.** Resting movers are booked on the cadence at their last rate. A steady cavitating pump is the main case.
- **A113.** The health factor is a dispatch input multiplying W10a's M. It is the W3/W5 pattern, with no settings edit and no recompile per slice.
- **A114.** An island wakes on a factor change above 1e-3. Health then moves the plant in steps of at most 0.1 % without waking every slice.
- **A115.** A constructed machine's knock trips its driver (`KNOCK`, latched). An in-line compressor disables itself and Off/On clears it. A trip needs a reset path, and in-line movers have no driver.
- **A116.** Two parts: the impeller for centrifugal machines, the valve set for reciprocating and in-line compressors. These are the parts plan 3.7's failures name.
- **A117.** No seal kit; no modelled fault wears a seal. Q14 listed it, and it can join if a fault does.
- **A118.** Recipes from vanilla ingots, copper and redstone. Parts craft in a pack without other mods.
- **A119.** Flat-silhouette textures from a script, for the owner to see. They follow the logo's direction.
- **A120.** The part is used on any cell of the casing or on the in-line pipe block, consumed on acceptance. It is the casing that is repaired; any cell is easier to reach.
- **A121.** One campaign on the final code. Wear moves only cavitating or gas-locked seeds within 120 slices.
- **A122.** A replay books wear but wakes nothing; a failure found at replay acts at the next cadence. A replay can be a view's read (W5's A82).
- **A123.** The τ values live in the config in hours and minutes, as plan 3.7 writes them. They are server-tunable.
- **A124.** The dry-running rate needs the driver on: a stopped train or a disabled in-line mover does not wear. A stopped pump does not run dry.
- **A125.** Low flow below 30 % of rated, with rated = the design point's flow, or the set flow in-line. This is plan 3.7's rule.
- **A126.** Several faults at once add their rates. They are independent wear paths.
- **A127.** Health is clamped to [0, 1]. A knock at D < 0.5 fails the machine.
- **A128.** No sound or screen hooks beyond the status line. W10c and W10d own those.
