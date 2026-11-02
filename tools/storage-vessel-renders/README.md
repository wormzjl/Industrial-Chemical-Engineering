# storage-vessel-renders

2026-10-30: `StorageRender.java` also accepts designed process units, including both cooling tower kinds. Optional `[columns rows]` after `[views]` selects a fan bank. Put compiled classes and PNGs under the owning worktree’s indexed `tmp/`; see [cooling-towers](../cooling-towers/README.md).

Software renders of the floating-roof tank (`Kind.TANK`) and the storage sphere (`Kind.SPHERE`), batch
`2026-10-21-floating-roof-tanks`, work package WP3 (geometry and placement), branch `claude/wonderful-banach-1f1152`,
commit `a16977ab`. Offline only; nothing here is in a tracked path.

`StorageRender.java` is adapted from `tools/new-units/UnitRender.java` (package `com.wormzjl.icheme`). It draws every
shell cell's polygons from `ShapeRasterizer.surface` with the mod's sprites, culls back faces as the chunk renderer
does, and places the tank's client-drawn roof and rolling ladder (`ShapeRasterizer.floating`) for a given liquid level.
Orthographic views to PNG.

## Run

From a worktree root that holds the WP3 code (or a later commit):

```bash
./gradlew compileJava processResources
mkdir -p tools/storage-vessel-renders && cp <this folder>/StorageRender.java <this folder>/render.sh tools/storage-vessel-renders/
bash tools/storage-vessel-renders/render.sh            # writes build/tmp/storage-render/*.png
```

One vessel: `java -cp "<classes>;build/classes/java/main;build/resources/main" com.wormzjl.icheme.science.equipment.StorageRender
kind size length support level outPrefix [views]`; views `front, back, left, right, top, iso, iso2, iso3, low`.

## Renders (`renders/`, 2026-10-21, at `a16977ab`)

| File prefix | Vessel | Liquid level (roof) |
|---|---|---|
| `tank-4x4` | least tank, D 4 m, shell 4 m | 1.8 m |
| `tank-20x12` | mid tank, D 20 m, shell 12 m | 6.6 m |
| `tank-40x24` | largest tank, D 40 m, shell 24 m | 13.8 m |
| `sphere-4-on-8` | least sphere on its highest legs | - |
| `sphere-14-on-2` | the concept sheet's sphere | - |
| `sphere-24-on-2` | largest sphere | - |

Each in four views: `iso`, `front`, `top`, `iso3`.

## Ladder forms (2026-10-21, at `768fc93d`: `documentation/2026-10-21-floating-roof-tanks/figures/wp3-ladders/`)

Owner change 2026-10-21, "only put round ladders on them when they are big enough": the wound stair from tank D 6 m and
sphere D 10 m; smaller vessels climb caged ladders. The tank's ladders have part-ring rests; the sphere's ladder has a
rest on posts and a bridge onto the crown ring. Rendered from the working tree committed as `768fc93d`.

| File prefix | Vessel |
|---|---|
| `tank-4x4`, `tank-5x22` | ladder tanks (5×22: two rests) |
| `tank-6x8` | the smallest tank with the wound stair (`iso`, `iso3`) |
| `tank-40x24` | the largest tank (unchanged form) |
| `sphere-4-on-8`, `sphere-9-on-8` | ladder spheres (both with a rest on posts) |
| `sphere-24-on-2` | the largest sphere (unchanged form) |

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: WP3 software renders of the floating-roof tank and the storage sphere: `StorageRender.java` (adapted from `new-units/UnitRender`, draws the client-drawn roof and rolling ladder at a liquid level), `render.sh` (three tanks, three spheres, four views each) and the 24 PNGs at `a16977ab`; the ladder forms (owner change 2026-10-21, `768fc93d`) rendered to the batch's `figures/wp3-ladders/`

Batch: 2026-10-21-floating-roof-tanks

Status: Concluded 2026-10-26 (batch in 0.12.0). Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `render.sh`, `renders/`

### History

- In progress since 2026-10-21 (renders 2026-10-21, branch `claude/wonderful-banach-1f1152`, awaiting the owner's approval)
- nothing tracked

### Notes

- status corrected 2026-10-26: the batch reached `main`; the folder is kept as its evidence.
