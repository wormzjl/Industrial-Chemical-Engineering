# Simulator sizing, wired control and equipment integration

Status: **In progress since 2026-11-06** on `codex/simulator-sizing`; merge preparation for owner-approved **0.22.0**. Do not merge without the owner’s instruction.

## Scope

Scale-up-first Auto sizing, constructor-style Manual sizing, Standard/Low-dP crossflow exchangers, industrial equipment/access models, shared rotation, wired local controllers and DCS, shared pipe/data/power laying, standard FE power, protected equipment removal, optional client low polygon rendering and user-facing fixes.

## Documents and evidence

- [MERGE_REVIEW.md](MERGE_REVIEW.md): scope, compatibility, verification and remaining limits.
- [CONTROL_FINISH_REVIEW.md](CONTROL_FINISH_REVIEW.md): shared cabinet/sensor finish, unchanged envelopes and inspected assets.
- [CONTROL_REWORK_REVIEW.md](CONTROL_REWORK_REVIEW.md): offset junction gaps, drum terminal pads, connected visibility, IDs and the 25% level workflow.
- [LOW_POLY_REVIEW.md](LOW_POLY_REVIEW.md): restored casing nuts, client-only detail mode, geometry inventory and measured reductions.
- [EQUIPMENT_PROTECTION_REVIEW.md](EQUIPMENT_PROTECTION_REVIEW.md): Creative-only breaking and constructor deconstruction, validation and updated jar.
- [IMPLEMENTATION_REVIEW.md](IMPLEMENTATION_REVIEW.md): the implemented contracts and modelling assumptions.
- [Code guide](../reference/code-guide/README.md): current subsystem maps and formats.
- [Retained tools](../../tools/simulator-sizing/README.md): source inventory and reproducible entry points.
- Local figures/tables: main checkout `research/2026-11-06-simulator-sizing/evidence/`; compressed logs: main checkout `tools/simulator-sizing/logs/`. Scratch originals are retained while unmerged.

## History

- 2026-11-06: `6402aeb64` sizing prefers larger units before parallel units and exposes Manual dimensions/build requirements; `622e35bde` adds catalogued crossflow thermal/hydraulic routing; `72e733cb4` separates Equipment and Sizing method and corrects exchanger appearance; `e46c024ac` balances nozzle projections.
- 2026-11-06–07: `0c5ce5224` incorporates the optional tank gauge (`b1477c090`); tank access/seal/grating revisions culminate in `0f0cce03f`; `658127385` repairs pump housing ends; `c08c4d83c` revises manways; `1df22fabc` completes the compact compressor; `75186e607` fixes shared text alignment/font scale; `67b8ca687` adds the turbine’s internal governor; `3d6da0512` consolidates equipment nozzle finish/bolts.
- 2026-11-07: `47abf7bcb` ports/extends local controller and DCS work; `2260953dd` adds VFD demand control, native Create support, powered inline movers and creative RF; `bdce652a3` caps motors at 3000 rpm; `b871843d6` makes sensor installation/removal immediate.
- 2026-11-07: `a839f2c38` completes shared laying/model updates; `8b4ac02ee` makes creative RF six-sided; `0f39d2f71` increases nonlinear visual speed; `b7a90872e` stops disconnected motors, reduces their buffer and caps cable ends; `7a6301da8` unifies 125 mm conduits with sparse blue/orange symbols; `c45cea075` completes industrial workstation models.
- 2026-11-07: `8a768d9d` updates all four translations; `db322f04f` integrates conduit/RF GameTests into the normal gate, supplies column-gate dependencies and retains investigation tools. Owner chooses 0.22.0. Final-lane results are recorded in MERGE_REVIEW.

- 2026-11-07: final lane at `891c8acf5`; focused corrections `3515b1ff` resolve all gate failures, including fresh-world FE/sensor fixtures; 2865 checks covered, chain exact and campaign holds match baseline. Guide, artefact and evidence inventories complete.

- 2026-11-07: `efa22cfe` protects constructed cells at the chunk write boundary and adds physical-target constructor deconstruction; `f13019d1` translates it in four locales. Combined filtered runs resolve 58 world paths, 23 constructor/codec checks and five language/source checks; light/dark renders and updated 0.22.0 jar inspected. Existing science lane stands.

- 2026-11-07: `238c136b` restores raised casing nuts and adds default-off client Low polygon mode, compatible with the normal server and mixed clients. Golden layouts/ports remain identical; 146 affected equipment/four quick checks and 16 dedicated-server paths resolve through filtered runs. Static mesh counts and comparison sheets are in LOW_POLY_REVIEW.

- 2026-11-07: `4682156e` unifies controller/DCS/sensor industrial finishes; `76ad7889` repairs shared offset junctions, places drum shell pads and clarifies connected level control/IDs; `f6d02add` updates all four translations; `68294ece` avoids full registry copies per controller view. Combined 68 affected checks pass through filtered runs; all source/assets are in the refreshed 0.22.0 jar.

- 2026-11-07–08: `417d70417`/`349099167`/`3d09e6188` consolidate line session, commands, admission and derived collision; `1edbc055f` builds the two-block DCS; `5b84b9ec6` preserves GUI scale; `c15dfcecc` adds ID/location hover, main-door nozzles and RPM controls. Initially compiled/rendered while owner tested; fresh-server/JUnit qualification completed in this readiness pass.
- 2026-11-08: `616fe7a1c` implements the reviewed native service/probe plans with matching equipment paint; `fd5603249` corrects total-feed probe mass basis. Native route/extreme/heading/Normal-Low and saved-probe gates pass. Current drawing workflow is tracked under `tools/equipment-drawings/`.
- 2026-11-08: translations `429fe26b1` bring four locales to 2949 keys. Readiness code `050cd1098` fixes crossed-cell cursor handling, direct cable items and cabinet collision. Combined 206 focused JUnit and 25 dedicated-server methods pass; native drawings, guide, archive inventories and the 0.22.0 package refreshed. See [MERGE_REVIEW](MERGE_REVIEW.md) and [PIPE_FRAMEWORK_REVIEW](PIPE_FRAMEWORK_REVIEW.md).

## Code areas and open items

`science/flowsheet`, `science/equipment`, `science/fluid/network`, `science/machine`, `runtime/{fluid,control,power,instrument}`, equipment/pipe/rack blocks, shared laying/client models, GUI and network payloads. The column operation’s V3 duty/energy contract is unchanged.

Open: owner’s in-game acceptance and later merge instruction. The once-run final lane and affected later follow-ups are recorded separately; current merge preparation and the release jar are complete. No dev client is launched for merge preparation. No unmerged output is deleted.
