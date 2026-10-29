# Vacuum-column fix and retest — 2026-10-29

Branch `codex/vacuum-column-test`; production/test commit `dba4ba45`; baseline `79b853e0` (0.17.1). Not merged.

## 1. Expected behaviour

- Owner's operating constraint: zero external reflux, with an exactly dry top contact stage. A dry gas stage transports components and enthalpy; it has no liquid unknowns or liquid/vapor equilibrium equations. Cooling below it can generate internal liquid traffic.
- Feed is the complete accepted default Tia Juana CDU bottoms: 763.4858996 kmol/h, 339.6424258 t/h, every component and trace preserved.
- [Penn State](https://courses.ems.psu.edu/fsc432/node/534) supports reheating atmospheric residue before vacuum recovery. [Sulzer](https://www.sulzer.com/en/shared/applications/refining-crude-vacuum-distillation) describes low-pressure-drop packing; the present sieve-tray model remains a capability test, not a refinery design qualification.

## 2. What the code did instead

- Every interior tray required positive logarithmic liquid flow, so a genuinely dry top could not be represented.
- The 150 kPa startup anchor could publish the same dense liquid as both phases: x=y and the same PR root passed the old equilibrium audit. Interpolation/projection then amplified an energy defect from 1.285 to 2.884 GW.
- A package-fit boundary at 900 K rejected trial steps. That data range was acting as a numerical wall.

## 3. Implemented fix and results

- The resolver derives the dry prefix at exact zero reflux, ending at the first feed or cooling return. The existing ledger removes absent-phase unknowns/VLE rows; material and energy equations remain. Initializers, seed transfer, material TDMA and hydraulics now respect that phase layout; dry trays carry exactly zero liquid and only gas pressure drop.
- Cold starts try the authored dry layout/pressure before auxiliary anchors; pots also try their authored topology. Steam-bearing bases can retain their actual steam during coupled cooling/draw ramps. Bad same-phase projection seeds are not amplified; dry-layout projection must improve frozen-scale merit without worsening energy closure.
- A fresh `INTERIOR_PHASE_IDENTITY` check rejects collapsed same-composition/same-root/same-enthalpy splits unless independent TP flash supports two phases. The two original captured startup states now fail the audit; two genuine two-phase controls remain valid.
- Column properties continue within the numerical box; caloric fits continue with edge heat capacity, continuous in value/slope. Accepted extrapolation gets an advisory. This does not change the shared fluid-network kernel or the separate legacy package-pressure admission rule.
- Publication identities add `-phase-r1`, before any closure suffix. Old trained models remain eligible for unchanged positive-reflux seed equations; old models cannot claim the new dry layout. No saved fact, material value or revision was changed.
- The empirical wet-column liquid-supply screen does not apply to exact zero reflux; its exact necessary liquid-supply bound remains enforced. No acceptance tolerance was loosened for this fix.

| Retest | Result |
|---|---|
| Zero reflux, same feed/steam, no cuts or cooling | Accepted; seven upper stages have L=0 exactly; maximum scaled residual 1.24e-14 |
| Zero reflux, two original side rates, 10+15 MW cooling | Accepted; top contact stage L=0 exactly; fresh dry-gas flash beta=1; maximum scaled residual 4.37277e-9 |
| Same side rates, earlier 1+2 or 3+5 MW cooling | Did not converge; these outcomes do not prove physical infeasibility |

Full accepted settings: 10 interior stages/feed 8; top 2 kPa(a); diameter 15 m; inlet 355.429 C; condenser 60 C; reflux 0; reboiler 0; bottom steam 3.3964 t/h at 260 C; cuts at stages 3/6 of 76.3486/190.8715 kmol/h; cooling returns/draws 2/3 and 5/6, 10/15 MW.
Hydrocarbon products (t/h): distillate 89.3641; side 3 26.2506; side 6 84.0082; residue 140.0195. Steam overhead 3.3964 t/h. Condenser duty -14.7879 MW; pumparounds -25 MW. Every VDU component closes to <7.11e-15 mol/s.
Published hydraulics: bottom 5.54968 kPa(a), total drop 3.54968 kPa, maximum flood 35.258%, bounded correction mismatch 3.4713%. AGGRESSIVE certificate closure is 0.001, despite the smaller actual residual.

## Validation and limits

- Final `test slowGates` passes: 1810 quick + 188 slow, zero failures/errors/skips (`--no-parallel --max-workers=1 -PtestForks=1`); registered `tmp/vacuum-gates/final-full.log` and `gates-summary.json`. Focused dry-stage, phase, pot, hot-start and learned-recovery checks pass; no dev client was running.
- The old 551 K accepted binary is now a negative phase-admission regression. Original numerical roots/certificates are frozen, freshly re-evaluated, and cannot bypass the audit; positive recovery fixtures use independently accepted live-boilup states.
- Tools: `tools/vacuum-column-test/` contains the final recipe, exports, historical trace/analyzer and fixture probe. All one-off runs stay in indexed `tmp/`; no new product instrumentation/Gradle switch remains. Guides: column-v3 and verification updated.
- The inlet temperature is fixed after a nominal 2 kPa heater-letdown calculation. Actual feed-stage pressure is 4.603 kPa; an outer heater-enthalpy iteration was not performed. Pumparounds are stage heat sinks; packing, arbitrary dry middle sections and experimental product-quality validation remain open.
- Loadable mixture/column JSON and verification are under `tmp/vacuum-test/`; exported/checked offline, with no GUI/world import claim. Main and `mod_version` remain unchanged pending the owner's merge/version choice.
