# Implementation and verification

Branch: `codex/water-cooling-tower`, based on `79b853e0d`. Implementation remains unmerged. This review records actual code and qualification; it does not designate the batch merge-ready.

## Owning code

`CoolingTower` is the validated geometry/control specification. `TowerBank` names the fan-cell arrangement separately from the dry cooler's existing `ProcessUnit.Bank`. `CoolingTowerDesign` repeats bounded fan modules or builds the natural tower's thin hyperbolic bands; `ProcessUnit.Solid.hollowCone` and sparse `ShapeRasterizer` layout keep the chimney empty. The fan bank's shared pan is metal and its support is steel. Existing placement/refresh paths supply world-height, occupancy and chunk restoration checks.

`TowerTransfer` solves eight implicit water-contact segments with relaxed air sweeps and solves signed draft against resistance. Its immutable calibration pair is published atomically and tied to the thermodynamic model identity. `TowerExchange` mixes incoming water enthalpy, computes representative-cell contact and scales extensive quantities, adds wet-basin surface exchange and controls leaving-water temperature before basin mixing. Water-only capability closure uses the existing hydraulic active set. There is one upper inlet shared by return and makeup, one bottom basin outlet, no pickup riser, no float valve and no droplet drift. Atmospheric discharge uses nozzle elevation and the incoming water's density; basin outlets retain hydrostatic head.

`PassiveStepSolver` includes tower exchange in its water and energy rows. `ConservativeTransport` reconstructs the same sink/source and emits an `ATMOSPHERE` boundary. The interval result carries accepted tower readings through event integration and certificate replay. Latent duty is a diagnostic split; booked energy is sensible transfer plus vapor enthalpy , using the existing water reference. No separate latent sink is applied.

`FluidWorldAuthority` publishes the accepted worker's cached readings. `FluidDeviceScreen` reads those snapshots and edits only operating controls; `EquipmentConstructorScreen` edits dimensions and bank counts. Opening either GUI performs no transfer solve. Controls are applied through the existing queued server edit path.

## Formats

| Format | Before | After | Compatibility evidence |
|---|---:|---:|---|
| Fluid state | 29 | 30 | Codec round trip and historical facts rebuilds |
| Fluid facts | 5 | 6 | `compat/fluid/schema-6/` adds nondefault bank/control facts |
| Equipment shape fields | 3 | 4 | `compat/equipment/shapes-4.snbt` includes a 24-cell bank |
| Fluid protocol | 16 | 17 | Tower controls and cached readings; packet tests |
| Equipment protocol | 14 | 15 | Constructor bank dimensions |
| Certificate graph identity | 8 | 9 | Tower dimensions/controls are part of fixed graph identity |

Equipment kinds, nozzle roles and atmospheric boundary kind are appended. Old constructors default the new optional specification to absent and the tower bank to one cell. Historical compatibility images are retained unchanged. Tower geometry and controls, basin inventories and integrated boundary totals are facts; transfer results can be rebuilt from those facts.

## Verification record

Latest owner revision, 2026-10-30: both tower outlets moved to 0.5 m above the base and the pickup risers were removed. No tests or builds were run for this relocation, as requested. Earlier verification and layout goldens predate this geometry change; refresh the tower golden rows when verification resumes.

Screenshot repairs, 2026-10-30: nozzle tubes and collision now stop inside their walls; the natural inlet's weld follows the same sampled shell band. External flange positions remain unchanged. Both designs share one bottom-outlet builder. Tower fans now have an open metal cowl instead of the motor fan's backing disk; the rotor's pivot is inside its hub, so the sparse layout contains the cell that registers its animation. Source and whitespace review only; no tests, builds or client checks were run. Earlier layout goldens also predate these repairs.

Water transparency, 2026-10-30: `LiquidRenderer` now supplies the floating tank gauge and tower basin with one texture/layer/vertex implementation. The basin no longer clamps opacity to at least 160/255: it draws the published water material alpha (64/255, approximately 75% transparent). The tank gauge retains its existing opacity minimum and marker appearance. Source and whitespace review only; no tests, builds or client checks were run under the owner's standing instruction.

Focused physics and geometry tests cover calibration, low/high loading, cold water and condensation, passive and reverse draft, 24-cell linear scaling, water-only refusal, atmospheric flashing, supercooled liquid, target-temperature control independent of basin holdup, accepted conservation, empty startup, through-flow, starvation, shutdown and overflow. A populated-basin checkpoint and saved-certificate arithmetic have dedicated tests. Existing equipment gates exercise nozzle clearance, accessible ladders, moving parts and layout compatibility.

Final lane results will be recorded here after the run. The reproducible command is `./tools/cooling-towers/verify.ps1`; outputs belong to `tmp/tower-verification/`. Compare the 1000-seed campaigns with the established clean counts of 975 cold random, 994 bundled random and 1000 defaults in [the previous merge lane](../2026-10-27-heat-exchanger-design/MERGE_REVIEW.md), including the actual held seed classes.

The owner’s second revision removes makeup/overflow nozzles, the float valve and drift; natural height follows diameter at 2:1. A basin level-gauge mount is present, with control linking explicitly deferred. Water levels and accepted fan speed use scheduled open-basin display packets. Natural chimneys upload one cached GPU mesh; chunk surfaces omit that shell and previews use whole coarse facets. Collision and layout occupancy remain independent of render batching.

Native verification is pending completion of the local bridge lane. Direct tools are not exposed in this chat, so the repository’s installed MCP CLI is used over one persistent stdio session, with one Minecraft client. Logs and images stay in `tmp/tower-native/`. No merge or release-version bump has been performed.
