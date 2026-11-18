# Furnace support and fuel header review

The owner’s screenshots showed steel flickering through the concrete leg and a bright, square-ended pipe joint under the burners. Both came from the furnace mesh.

- **Cabin supports.** The two side girders ended flush with the outer concrete faces. Each steel cap put a second surface on the same plane. The transverse girders also ended against concrete. The tops of the longitudinal girders overlapped the tops of the legs where they supported the floor. The casings now supply the joint faces and the original solid boxes still define the supports.
- **Cabin fuel pipe.** The two outer rows meet the front cross header through tangent elbows. Inner rows branch at tees. Each row turns up into its terminal burner, including the single-row design. The bends and straight pipes share their end vertices.
- **Vertical fuel pipe.** The header closes through one common ring of vertices at each burner. Its original chord route still meets the fuel nozzle and every burner takeoff.
- **Finish.** The headers and risers use the mounted pipe’s existing texture, paint and ordinary lighting. They carry the same pipe-run marker as other equipment piping, which excludes them from the custom reflection pass.

The mesh regressions cover normal and low detail. They check for coplanar steel on concrete, verify that underfloor pipe facets are excluded from reflections, and require each edge of a pipe elbow or ring corner to be shared by exactly two faces.

## Native mesh checks

| Drawing | Steelwork faces before → after | Pipe faces before → after |
|---|---:|---:|
| CABIN_HEATER_SMALLEST | 400 → 390 | 264 → 318 |
| CABIN_HEATER_SINGLE | 1,016 → 1,006 | 436 → 662 |
| CABIN_HEATER | 1,112 → 1,092 | 1,080 → 1,414 |
| CABIN_HEATER_LARGEST | 1,984 → 1,964 | 1,382 → 1,716 |
| VERTICAL_HEATER_SMALLEST | 252 → 252 | 196 → 238 |
| VERTICAL_HEATER | 652 → 652 | 358 → 470 |
| VERTICAL_HEATER_LARGEST | 1,168 → 1,168 | 418 → 558 |

Only the two listed material counts changed. Equipment dimensions and exported nozzle, shaft, service and instrument data matched the original meshes in all seven scenes.

## Verification

The affected run passed 1,635 JUnit cases, 90 hold-gate cases and 214 equipment-gate cases. All 140 required headless GameTests passed. After the first textured close-up exposed the overlap on top of the concrete, all 214 affected equipment gates passed again with the three-axis overlap check.

Inspected the front, side and isometric views of four cabin and three vertical heaters, plus textured close-ups of the cabin leg and its outer fuel elbow. Visual verification used the software renderer. The regression checks the same pipe-run flag the in-game reflection pass reads.
