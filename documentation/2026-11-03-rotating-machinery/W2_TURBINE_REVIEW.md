# W2: the steam turbine connection, review

Commit `18c6f304b` (plan section 3.4, owner decision D22). Built by an Opus medium subagent and reviewed here.

## 1. What was built

- **D22.** The turbine is a driver separate from the pump or compressor head it drives; a shaft train joins them (W3). Control systems are separate components. So `FlowControl.Turbine(SteamTurbine)` has no governor and no set flow.
  - It stands `VALVE_OPEN` for the whole solve and passes what its own resistance passes. No mode decision is ever re-read (the D16 lesson).
  - A mass-target control valve upstream is its control component for now.
- **Work out.** `W = ṁ η_is Δh_is`, on the inlet's real P/ρ and the inlet's start-state k, down to the exhaust node's pressure. It goes through the one `moverPower` function that both the Newton's energy rows and the transport's booking read, negative for a turbine.
  - It leaves the island as a `SHAFT` boundary transfer at the connection, which W3 will read as delivered power. `pumpWorkJoule` keeps its meaning (work in, never negative).
- **Format.** Checkpoint state format 34 (control tag 6, the `SHAFT` kind). No facts field, block, screen or lang.

## 2. Tests

- `SteamTurbineConnectionTest`:
  - (a) open: 530 kJ/kg drop (k 1.288), 2.67 kg/s, W = ṁηΔh_is, exhaust `h_in − W/ṁ`, dry at 4 bar;
  - (b) governed at 0.5 kg/s: the valve holds it, the junction at 7.5 bar, power = ṁηΔh_is on the junction's state to 1e-15;
  - (c) closed: no flow, no work;
  - (d) six slices conserve components and energy, with the shaft work leaving;
  - (e) 10 bar to 1 bar leaves 4.6 % liquid at 395 K.
- **Affected set:** 1132 tests; hold gates 131; fluid GameTests 37; the turbine test 5. `DeterministicMathSourceTest` caught a `Math.log` in the k ≤ 1 guard, now `DeterministicMath.log`.
- **Campaign** (1000 seeds, alone): 992, 994 and 1000 clean, the same held seeds as W10a on 0.20.0.
  - Bundled defaults' counters are identical.
  - The random distributions' counters move by under 0.4 %, the same size as the pre- and post-rebase pair. No world places a turbine.

## 3. Found on the way: a closed steam pocket that does not solve

1. **Expected.** A junction between a closed valve and a 4 bar void, holding 450 K steam at 4 bar, stands still. No flow, a trivial solution.
2. **What the code does.** The Newton stalls at residual 0.9702121578183048 (active-set pass 1). It does the same with a passive line in the turbine's place, and on main 0.20.0 (`84f739baa`) to the last digit. With 673 K steam in the pocket, or any flow through the valve, it solves.
   - This branch did not cause it. The turbine test's pocket now holds throttled supply steam (673 K), which is what such a pocket holds after closing.
3. **Proposal.** Trace the 450 K case: a pocket 33 K above saturation at zero flow. It looks like a seed or phase-layout problem, not a law. The trace would run on main's code, so its fix is a batch of its own.

## 4. Options for the owner

- **T6, reverse flow.** A reverse drive passes through the turbine as through an open line, with no work. A real turbine has a trip valve and a non-return valve. The option: close it on reverse flow, as a mover's check valve does (D13).
- **T7, a liquid inlet.** The ideal-gas drop on a liquid's P/ρ (k 1.4 for a liquid) gives 7.5 kJ/kg from 40 to 4 bar, against `v ΔP` ≈ 4 kJ/kg, so 1.9 times too much. No refusal was added; wet steam reads the mixture density, a small effect.
- **Status line.** A turbine shows `VALVE_OPEN`. A turbine status word comes with W7's screens.

Code guide: no change yet. The fluid network chapter (the turbine connection, the `SHAFT` transfer, format 34) changes at the merge refresh.
