# 2026-11-18-pollution-propagation

Status: in progress since 2026-11-18, on `claude/water-intake-outfall-0e90c8` (not merged).

Purpose: the air pollution field drifts upward (the horizontal spread unchanged), and its step is benched and made faster with the same bits.

## The owner's request (2026-11-18)

"change the air pollution pollution propagation so it tends to propagate more upwards. horizontal propagation rate stays the same. Also try bench and optimize its performance".

## Documents

- [POLLUTION_PROPAGATION_PLAN.md](POLLUTION_PROPAGATION_PLAN.md): the drift's physics and numbers, its rules, the bench and the optimisation's terms.
- [POLLUTION_PROPAGATION_REVIEW.md](POLLUTION_PROPAGATION_REVIEW.md): what was built, the moved references, the before and after table, the owner items.

## Tools

- [tools/pollution-bench/](../../tools/pollution-bench/README.md): the bench and the digest.

## History

- 2026-11-18: P1 (`4841ec5cb`) the upward drift, `riseShare` 0.12 (config `pollution.riseShare`); riseShare 0 bit for bit as before.
- 2026-11-18: P2 harness (`72993755a`, `a92f7d91f`) `tools/pollution-bench/`.
- 2026-11-18: P2 optimisation (`1e64ee2fd`) the step bit-identical, 2 to 3 times faster at 4 096 to 8 192 cubes; review written.
- 2026-11-18: three GameTest seeds for the drift (`4ebf4ee66`).
- 2026-11-18: P2 memory and the B items (`4ee803129`, `41cf4a697`; bench `d9b274413`): sorted key sets, array caches, the ground step on arrays; worst case retained 19.8 → 3.8 MB, per pass 60 → 16 MB; the ground step 2.9 to 5.1 times faster, the server's sets 12 times.
