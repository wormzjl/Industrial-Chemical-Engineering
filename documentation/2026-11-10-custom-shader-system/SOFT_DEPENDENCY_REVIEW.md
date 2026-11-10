# Soft dependencies for the effects system: review

2026-11-10, owner request: "built with performance in mind; check if soft dependency on related mods can make it run smoother and be compatible with them". Sources: section 9 of the plan and the links below.

## Finding

Soft dependencies buy compatibility, not much speed. The speed comes from the system's own design: no particles, effects animated on the GPU, budgets, and level-of-detail tiers. Only the Iris API is needed for correctness. Distant Horizons is added for looks (owner, 2026-11-10). One candidate, Flywheel, would hurt compatibility. Every call into an optional mod is guarded (plan section 4.8), so a change in its code turns the integration off instead of crashing the game.

## Candidates

| Mod | What its API offers | Speed | Compatibility | Verdict |
|---|---|---|---|---|
| Iris (shader loader) | `IrisApi`: `isShaderPackInUse`, `isRenderingShadowPass` | indirect: skips effect work in a shadow pass, picks the cheaper texture path under a pack | needed: our own shaders break under a pack | **Use now**: compileOnly, optional dependency with a version range |
| Sodium | a fast bulk vertex writer; a config API for an options page | small: the per-frame vertex writes under a pack (at most 2,000 quads, about 8,000 vertices) | none needed. Our chunk models already mesh under it | **No** for now; add the writer only if W0 measures over 0.1 ms per frame on vertex writes |
| Embeddium | events to add chunk geometry, options pages | none | none needed | **No** |
| Flywheel (comes with Create) | GPU instancing, effect visuals | high for many repeated rigid meshes | its own shaders stop under a shader pack unless the player installs the third-party "Iris & Oculus Flywheel Compat" | **No** for effects: two paths for one look, and broken under packs without the add-on. It may suit `MachineAnimation`'s fans and rotors in a later batch |
| Distant Horizons | generic rendering API 3.0+: box groups drawn in DH's terrain pass | none (DH draws them) | equipment already shows in DH as solid voxels, all one grey (the plate sprite), whatever its paint. Correction: an earlier draft of this review said it vanished | **Use (X11 A, owner 2026-11-10)**: painted silhouettes covering those voxels, plan section 4.7 |
| LambDynamicLights 4.x (NeoForge 1.21.1) | custom light sources | costs a little: chunk sections relit when a light changes; ours change at most every 5 s | none needed | **Later (X12)**: flare, still and heater fires light their surroundings without a pack |
| ImmediatelyFast, ModernFix, FerriteCore, Entity Culling | — | — | fine as long as we draw through `MultiBufferSource` and our own batch | No dependency |
| Sound Physics Remastered | — | — | applies reverb and occlusion to our sound loops by itself | No dependency |
| Veil, Lodestone, Photon (effect libraries) | post-processing, particles, an effects editor | — | each would be a hard dependency, with its own shader-pack add-ons | **No** |

On NeoForge 1.21.1, Iris needs Sodium, so the COMPATIBLE path (a pack in use) always runs with Sodium present. Oculus is a Forge 1.20.1 port and OptiFine does not run on NeoForge.

## Where the speed comes from (plan changes)

1. **SHADER path, GPU animation.** The CPU writes an emitter's vertices only when a snapshot arrives, about every 5 s. Each vertex carries the old and new values and the glide's start time, and the vertex shader eases between them, faces the camera and flickers. The result is a cached vertex buffer, with no per-frame vertex writes.
2. **COMPATIBLE path.** Iris's programs cannot run our vertex animation, so vertices are written every frame within the 2,000-quad budget. Density drops to half under a pack and heat haze is off, because the pack's frame is already heavy.
3. **Instrument wiring.** The wiring stays an immediate entity draw under a pack, the path that works today. Without a pack it becomes a cached vertex buffer per structure in our own shader.
4. **Shadow pass.** No effect work runs while `isRenderingShadowPass()` is true.

## How to wire an optional mod (the project's Create pattern)

- `compileOnly` against the API jar, plus `optionalModRuntime` for dev runs, with a `-PwithoutIris` switch like `-PwithoutCreate`.
- `neoforge.mods.toml`: `type = "optional"`, `side = "CLIENT"`, and no version range, so no version of the other mod stops the game from starting. The version is checked at runtime instead, and a mismatch turns the integration off (plan section 4.8).
- `ModList.isLoaded` is read once at client setup. Every API reference lives in `client/fx/compat/<mod>/`, loaded by name inside a try/catch only when that mod is present. Every call catches `LinkageError` and `RuntimeException`; the first failure logs once and disables that integration for the session.
- Gates: the effects core loads without the optional mods on the classpath, and stub APIs that throw or report an old version leave the integration off without an exception.

Sources: [Iris & Oculus Flywheel Compat](https://skinmc.net/index.php/project/iris-flw-compat), [Colorwheel](https://modrinth.com/project/BzHgFoGz/version/JwDFI3aj), [DH generic rendering API](https://distant-horizons-f74d0f.gitlab.io/com/seibel/distanthorizons/api/interfaces/render/IDhApiRenderableBoxGroup.html), [Sodium Options API](https://modrinth.com/mod/sodium-options-api/version/neoforge-1.21.1-1.0.10?dep=1), [LambDynamicLights 4.1.0 for 1.21.1](https://modrinth.com/mod/lambdynamiclights/version/4.1.0+1.21.1?dep=1).
