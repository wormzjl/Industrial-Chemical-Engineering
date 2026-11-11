# Gas-vent setup hold of the level-loop probe (2026-11-11, owner "Diagnose it")

Diagnosis only; nothing fixed. The runs were made with `LEVEL_LOOP_OPTS="-Dicheme.levelLoop.generatorVent=true -Dicheme.probe.failures=true" bash tools/minecraft-time-delta/level-loop/run.sh 4`, then `run.sh 1`. The probe `tools/minecraft-time-delta/patches/probe-feed-step.patch`, plus an ATTEMPTS dump before the substep-limit throw, was applied for the runs and then reverted. Each run used a fresh review world. Lines: `tools/minecraft-time-delta/results/feed-step-hold.txt`, last section.

## 0. First attempt: a crash that measured nothing
- At 4, on the reused `run/review-world/world` (online tick 147499, 15 restored certificates), the server died before the rig was built. The error was a NullPointerException in `IslandCoordinator.certifiedStatus`: "certificate" is null. The world is kept as `run/review-world/world-crash-147499`, with the crash report in `crash-reports/crash-2026-11-11_13.56.53-server.txt`. This is its own defect; see the README's open items. With owner choice A, the run was repeated on a fresh world.

## 1. Expected behaviour
- The rig: a 7.95 m3 drum, a 1 atm nitrogen generator on the 0.25 m vent line, a feed valve, and a 6 m leg down to the regulated outlet valve and a void. With the drum empty (or holding nitrogen at 1 atm), there is no flow at rest. With feed: liquid comes in at the feed rate and gas leaves through the vent at the same volume. At the first feed of 19.81 kg/s (0.020 m3/s), about 0.022 kg/s of nitrogen goes out. The outlet passes nothing until liquid reaches it.

## 2. What the code did (same at both factors)
- **When:** on the island's first slices after the last setup step ("feed law and 3 bar charge", ticks 38 at 4 and 43 at 1). The loops never controlled.
- **Held islands:**

| Case (feed) | Factor 4 | Factor 1 |
|---|---|---|
| step, 198.1 | worker failure | worker failure |
| step at the rule feed (198.1 at 4, 792.4 at 1) | substep limit, 0.113 of 0.4 s, 502 accepted / 522 rejected | substep limit, 0.0369 of 0.1 s, 503 / 521 |
| empty, 198.1 | worker failure | worker failure |
| empty at the rule feed | 198.1: worker failure | 792.4: clean, 38.95 to 39.28 % |

- **The status line drops the cause.** "Round deadline or worker failure" came less than 1 s after dispatch, far inside the 45 s deadline, so the worker failed. The coordinator's status leaves out `completion.failure()`, so the log has no exception for these islands.
- **Rows at the end of a failed Newton** (substep-limit island; the same at 4 and 1):
  - Pass 0 (drum as a vessel, size 20 to 22): row 2 of the drum (node 0, 6 rows) carries the residual, 0.64 to 4.8. Next is row 1 of the outlet leg's foot junction (node 3), then the leg (pipe 1) and the outlet valve (pipe 4).
  - Pass 1 (drum as a junction, size 19): row 2 of the drum carries 91 to 195 at 4 and 616 to 1690 at 1, with a line search at constant residual. Steps halve to 1e-7 s.
- **State at the step's start:** the drum is at 1 atm exactly, holding 7.4e-5 kg (at 4) or 1.9e-4 kg (at 1) of gas, no liquid; phase 14 in pass 0, 10 in pass 1. The leg's foot junction (node 3, 1 L) is at 70.7 kPa (at 4) and 71.3 kPa (at 1), about 30 kPa below 1 atm, holding 1.0 kg.
- **Modes and flows:**
  - The outlet valve (pipe 4) is VALVE_OPEN at every pass, though its target is 0.01 m3/s. This is option 1's starved rule: the drum's port holds nothing.
  - Newton tries 470 to 500 kg/s down the leg and through the open valve from a drum that holds 1e-4 kg.
  - The vent line (pipe 0, drum to generator) carries -1.40 kg/s: nitrogen flows into the drum from the generator, about 1.2 m3/s. Expected is 0.022 kg/s out.
- **The suite body's residual 121 692 does not reproduce.** On a fresh world at 4, the suite body (void vent, fill and ramp) ran clean: 24.90 to 76.01 %, 50.00 % at the end, residual 1e-9 mol. The 121 692 came from the reused world, so no row can be given. At 1 the suite body stops on the rig's factor-1 sizing. The outlet reads at most 0.789 m3/s against the 0.80 m3/s rule feed, and the level was still at 53 % rising when it timed out "waiting for the drum to hold 25.0 % on the design feed". The probe cases reached the same setup step at both factors.

## 3. Candidates
- Present, together: a generator and a void joined through one empty drum. The starved rule opens the outlet valve (candidate 4, read from the modes), so the void sees the drum through the 6 m leg. The leg's foot sits about 30 kPa under 1 atm (candidate 2), while the drum holds nothing (candidate 3). Section 5: the starved rule is not needed for the hold.
  - The solve then asks the empty drum for liquid at the leg's draw. A higher first feed covers the leg in time: the factor-1 empty case at 792 kg/s ran clean.
- Ruled out as the cause: the time factor. The same cases hold the same way at 1 and at 4, with the same feeds. The failing steps are 1e-7 s, far below the 4 s first step, the 0.2 s junction buffer and the slice (2 ticks: 0.4 s at 4, 0.1 s at 1). W1 to W5 change none of these rows. The rejection counts are nearly equal: 522 against 521.

## 4. Answer and proposal
**The time factor does not cause this hold.** It joins finding 3 and the drop-leg cold start in their own batch. Options for that batch, for the owner:
- (a) Charge the leg and its foot junction from the vessel above them: the gas at the vessel's pressure when the vessel holds no liquid.
- (b) A starved regulating valve whose supply is gas-only stays at its target (or shut) instead of VALVE_OPEN. Option 1 then acts only where liquid runs out within a pass. (c) Both (a) and (b). Section 5: (b) alone does not remove the hold.

In every case, the "worker failure" status should name the failure's message (a diagnostic line, no physics). Recommended: (a) plus the status line, because the 30 kPa deficit at the leg's foot is the state no real plant has.

## 5. Without the starved-valve rule (owner "Yes, run it", factor 1, one run)
- The run: `run.sh 1` with the same options on a fresh world. `InletPhase.starved` was made to return false, which turns off both the carried start mode and `openStarved`, as in the `StarvedRegulatingValveTest` proof. The probe was on. Both were reverted.
- Step, 198.1: held, "round deadline or worker failure" (island 23, tick 39). Step at the rule feed, 792.4: held, worker failure (island 46; with the rule it was the substep limit). Empty, 198.1: held, worker failure (island 69). Empty at the rule feed, 792.4: clean, 33.19 to 39.96 % (a filling drum, not finding 3's full one). The suite body stops on the factor-1 sizing, as before.
- No failed Newton printed rows: a worker failure leaves no probe dump, so the outlet valve's mode and the vent's flow at the failing step are not in the log. What the log does show: the last readings before each hold, from a drum holding 0.0005 to 0.003 m3, were outlet flows of 1.500, 2.018 and 0.8006 m3/s, equal to the last digit to the readings with the rule. So gas already passes from the generator through the empty drum and out of the outlet to the void, before the starved rule changes anything.
- **It holds without the rule too.** The defect is on `main` as it stands at factor 1, and option 1 only changes its form (the substep-limit hold becomes a worker failure). Option (a) stays the recommendation, with the status line.
