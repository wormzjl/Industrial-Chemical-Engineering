# Pump, coupling and motor sprites

Batch: `documentation/2026-11-03-rotating-machinery/` (pump and motor rework, 2026-11-04).

Purpose: bake the sprites that carry the detail of the centrifugal pumps (`ProcessUnit.pump`, `betweenBearingsPump`), the coupling guards and shafts of every machine (`ProcessUnit.coupling`, `guard`, `shaft`) and the TEFC and large motors (`ProcessUnit.motor`, `largeMotor`, `MotorDesign`). This follows the cooling tower's way (`tools/cooling-towers/relief/TowerSprites.java`): the block shader reads no normals, so fins, ribs, seams, nuts, rims, panels and nameplates are painted into greyscale or coloured sprites and shaded with the game's directional formula relative to a flat wall. Painted sprites stay light and neutral (about 0.93), because the paint tint multiplies them. The motor sprites are painted too (Paint.BODY; owner request 2026-11-04). Only the guard (safety yellow) and the shaft (bare steel) keep their own colours.

Every sprite but the casting (tiled by 2 m), the motor's plain and panel sprites and the shaft is stretched once over its part, so the sprite's edges are the part's edges: `Mesh.band` (a frustum's side, round and along), `Mesh.disc` (a flat face, across its diameter), `ProcessUnit.baseplate` (a deck's top and its channel sides) and `ProcessUnit.guard` (one panel to a flank or the round). Tiled sprites tile by the metre, so their tiles meet at block faces and cut no extra polygons.

| Sprite (texture name) | Material | Part |
|---|---|---|
| `pump_casting` | `PUMP_CASTING` | coated cast surface: an even tone, a very soft 2 m variation, tiled |
| `pump_volute` | `PUMP_VOLUTE` | volute crown: fillets and the cast rib |
| `pump_fins` | `PUMP_FINS` | finned bearing housing |
| `pump_joint` | `PUMP_JOINT` | bolted joint's rim: two flanges and the gasket |
| `pump_disc` | `PUMP_DISC` | bolted face: twelve nuts, spigot |
| `pump_base`, `pump_channel` | `PUMP_BASE`, `PUMP_CHANNEL` | drain-rim deck with anchors, drain and grout holes; channel sides with gussets |
| `coupling_guard` | `COUPLING_GUARD` | framed expanded-metal panel, safety yellow; its plain band makes the end plates |
| `shaft_steel` | `SHAFT` | machined steel of every turning shaft and hub, and of the shaft block models |
| `motor_frame` | `MOTOR_FRAME` | TEFC finned frame: 32 axial fins, end spigots, a lighter nameplate at the frame's angle 0 |
| `motor_shield` | `MOTOR_SHIELD` | end shield: rim, eight bolts, six ribs, bearing cap |
| `motor_panel`, `motor_plain` | `MOTOR_PANEL`, `MOTOR_PLAIN` | large motor's bolted panels; plain cast and pressed parts |

## How to run

From the worktree root:

```bash
java -Djava.awt.headless=true tools/pump-sprites/PumpSprites.java src/main/resources/assets/icheme/textures/block
```

It writes the fifteen PNGs above. `probes/PumpRender` takes `-Dpaint=RRGGBB` to render another body paint. The output is the same on every run (no random numbers; the grain is a fixed hash).

`probes/` holds the checks used for the rework. They are in package `com.wormzjl.icheme.science.equipment`: compile them with `javac -proc:none -d <worktree>/tmp/<folder>` against `build/classes/java/main` and the runtime class path, and run them with that folder, the main classes and `build/resources/main` on the class path, from the worktree root.

- `PumpProbe [all]`: each pump size's box, pipe blocks and shaft block at 2 and 4 poles and 5, 100 and 300 m. The `2p 100m` lines (the design point that older saves take) must not change.
- `MotorProbe`: every motor and generator size's box, shaft block and terminal cell, at grade and on a 3 m table.
- `Spools`: each pump size's nozzle runs at the default point, and the run one block inward.
- `UnitCount "KIND size length support"...`: drawn polygons (chunk and moving).
- `PumpRender KIND size length support outPrefix views`: software renders. `-Dground=0 -Dcap=300` for a close view, `-Dfocus=x,y,z,radius` to frame one joint.

## Entry points

- `PumpSprites.main(folder)`: bakes every sprite.
- `PumpSprites.relief(nx, ny, nz)`: the game's shade of a normal relative to a wall facing +z, as in `TowerSprites`.

## History

- 2026-11-07, batch `2026-11-06-simulator-sizing`: `ConstructorAssets.java` and `SimulatorAssets.java` now author the industrial fabrication bench and operator cabinet as vanilla JSON models, using existing steel, panel, grating and display textures. From the worktree root, run `java tools/pump-sprites/ConstructorAssets.java src/main/resources/assets/icheme` and the corresponding `SimulatorAssets.java` command to regenerate their block/item models and facing variants. Compile both classes with `tools/simulator-sizing/StationRender.java` for software views; `tools/simulator-sizing/check_models.py` checks collision coverage, UVs, resources and facing. The batch's other one-off renderers and texture sources are retained under `tools/simulator-sizing/archive/`.

- 2026-11-04: created for the pump rework (OH2 and BB2 drawn with baked castings, fins, joints and drain-rim baseplate), the coupling guard (API 686 expanded-metal hood with end plates and a shaft opening, its own material so `GUARD`'s plain yellow stays as it is for valve handles, labels and the derrick), steel shafts that match the shaft block, and the motor rework (finned TEFC frame, end shields, large motor's panels).
