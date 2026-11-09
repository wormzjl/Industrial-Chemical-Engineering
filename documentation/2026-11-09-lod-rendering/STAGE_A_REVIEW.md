# Stage A: level of detail for the per-frame renderers

- Status: implemented 2026-11-09 on `claude/lod-rendering-optimization-1ad1f9`, not merged. Owner approved Stage A (L1) as planned.
- Expected: past the distance where a detail covers a pixel, the client stops spending CPU on it; near the player nothing changes.
- Before: `InstrumentRenderer` drew every gauge, transmitter and wiring run within 120 m, in view or not, with a light lookup per vertex and six text strings per dial. Body, moving-part and tank-roof meshes were dropped all at once (or the oldest each frame) when a cache filled.

## What changed

| Area | Rule (k = 771 px at 1080 lines, 70°; `lod_scale` divides k) |
|---|---|
| `RenderLod` (new, Minecraft-free) | k from the projection's m11 and the framebuffer height each frame, so zoom and resolution count. `tier` loses detail at a threshold, gains it past ×1.15. `Tiers` remembers each object's last tier. |
| `ClientConfig` | `[equipment] lod_scale = 1.0` (0.25–4) in `icheme-client.toml`. No lang key: config comments only. |
| Dials (0.34 m face) | ≥ 48 px (≤ 5.5 m): unchanged, 261 quads and text. 12–48 px (≤ 22 m): `housingPlain`, 24 sides, no ticks or text, connectors kept. 3–12 px (≤ 87 m): `housingFar`, 12 sides, needle, no connector. Under 3 px: not drawn. |
| Transmitters (0.23 m head) | ≥ 44 px (≤ 4 m): symbol and ID. ≥ 14 px (≤ 13 m): symbol. ≥ 3 px (≤ 59 m): head. Connector while its 28 mm stem covers a pixel (≤ 22 m). |
| Equipment wiring (25 mm ribbon) | While the ribbon covers a pixel at the structure box's nearest point (≤ 19 m) and the box is in view. Baked quads kept per structure while shape, mounts, nozzle codes, detail and atlas hold. Factory heads follow the transmitter rule on their own. |
| All instruments | Frustum test per mount (centre and tap, 0.2 m margin). One light lookup per instrument at its centre (the per-vertex lookups read the same point). Hosts gathered every 5 ticks unless a placing item or the wrench is held; placement ghosts are unchanged. |
| Cables (`DataControlRenderer`) | Frustum test for glands, leads and rack cables. Glands while 0.15 m covers 2 px (≤ 58 m); leads and rack cables while the conduit's 0.125 m covers a pixel (96 m, the old range). |
| Sight glasses | Liquid and bubbles while the window covers 12 px; liquid alone to 2 px; then not drawn. Old 128 m range and frustum test kept. |
| Mesh caches (`VertexBufferCache`, new) | Body (soft 64), moving parts (256) and tank parts (64) close only meshes not drawn for a second; hard caps 1024 / 4096 / 1024. |

Each renderer keeps its old range as the upper bound, so no tier draws more than before. Placement, collision, layouts, packets and saves are untouched.

## Deviations from the plan

- Plan A2 dropped dial connectors at 48 px. They are kept to 12 px instead: the 28 mm stem still covers about 4 px at 5.5 m and reaches 1 px at 22 m, the 12 px switch. The plan's own 1 px rule decides.
- A4 also covers the tank roof, ladder and gauge meshes: the same drop-all-when-full cache, so more than about 20 tanks in view rebuilt meshes each frame.

## Verification

- `RenderLodTest` (new, quick suite): reference k, the plan's switch distances, scale and its bounds, hysteresis, per-object memory, box distance.
- `GaugeAccessoriesTest.housingIsClosedAtObliqueAnglesAndEveryConnectorRemainsFinite`: the plain and far housings are closed envelopes inside the full radius; the near housing keeps 261 quads.
- Not run: equipmentGates (no test there reads the housings; `InstrumentGeometry`'s static init is covered by `GaugeAccessoriesTest`), GameTests (server only; no client renderer loads there).
- Not measured: frame time. The 10–20 ms per 100 dials remains an estimate until the owner's FPS pair (plan L4).
- Not seen in game: the switch points and any popping. Offered to the owner (AGENTS.md: one check per task, owner-started).

Code guide: not updated (merge preparation waits for the owner).
