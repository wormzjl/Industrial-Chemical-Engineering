# pipe-laying-ingame

Purpose: the batch's one in-game check of the pipe laying tool, in the dev client through the MCP bridge: the rack's look, the port highlight, the start cell, the route ghost, a lay through a rack, and the transition pipes at the rack's ends.

Batch: `documentation/2026-10-26-pipe-laying-system/`. No code in a tracked path.

## How to run

1. In the worktree: copy the pinned jar from `tools/minecraft-mcp-bridge/` to `run/mcp-client-mods/`. Set `pauseOnLostFocus:false` in `run/mcp-client/options.txt`, or the pause menu reopens every time the window loses focus. Then start `./gradlew runMcpClient --offline` in the background (no other Gradle run at the same time).
2. `node bridge.js wait-ready`. For a new world: `batch create.jsonl`, then press Create. For the saved world: `batch play.jsonl` (Singleplayer, select the first world, Play). Then `wait-world`.
3. `cmds scene.txt` builds the scene: two tanks at (4, −58, 4) and (14, −58, 4), a rack run x = 6..11 along x and z = 5..8 along z, a drum, and the tool in the main hand.
4. `cmds aim-start.txt`, `batch use.jsonl` (start); `cmds aim-end.txt`, `batch use.jsonl` (lay). Before the second use, `shot` shows the ghost.
5. `cmds view-low.txt` / `view-west.txt` / `view-east.txt`, then `shot <png>`.
6. Stop the client: kill the `java` process whose command line contains `mcpClientRunProgramArgs`. The Gradle task then ends with exit code 1, which is expected.

Bridge gotchas: `click_button_index` does not use the order that `get_screen_buttons` prints. On the world list, index 2 opens Create New World, so `play.jsonl` clicks by framebuffer position (854 × 480). `wait-world` reports "still on a screen" when a screen is still open, for example when the click hit Create New World.

## Entry points

- `bridge.js`, `Flatten.java`: copied from `tools/custom-equipment-rendering-poc/` (HTTP driver of the MCP mod; alpha flatten of its screenshots).
- `scene.txt`, `create.jsonl`, `play.jsonl`, `aim-*.txt`, `use.jsonl`, `view-*.txt`: the steps above.
- Screenshots: `s03`/`s04` the scene (rack, drum port highlight), `s05` the start cell, `s06` the ghost, `s11` the laid line, `s13`/`s14` the transition pipes at the rack's two ends.
- Layered rack check: `scene-layers.txt`, `aim-layers-start.txt`, `aim-layers-end.txt`, `view-layers-overview.txt` / `-corner` / `-start` / `-end` (each starts in spectator mode); shots `l01-scene`, `l02-ghost`, `l03-laid`, `l04-corner`, `l05-west-end`, `l06-south-end`.
- `logs/pipe-laying-ingame-logs-2026-10-26.tar.gz`: the two `runMcpClient` logs.

## History

- 2026-10-26: the check, run after W4 (commit 031e855d) rather than after W3, so that it also covers the transition pipes. Pass: the rack frame is drawn and the lane is visible; the port squares show on the tank and drum nozzles; the ghost draws the S-jog to the lane's slot; the lay places one line through the rack; both rack-end cells bend from the lane's off-centre point back to the centre line.
- 2026-10-26 (layered rack, R1-R4): the task's one in-game check, in a fresh superflat world (the earlier world, whose racks and facts use the branch's previous formats, was kept as `run/mcp-client/saves/pre-layers-2026-10-26`). Steps: `scene-layers.txt` (a run facing east x = 6..10 at z = 4, a corner at x = 11 turning south, a run facing south z = 5..8, tanks at (4, −58, 4) and (11, −58, 10)); `aim-layers-start.txt` and `use.jsonl`; `aim-layers-end.txt`, a shot of the ghost, `use.jsonl`; then `view-layers-*.txt` (spectator, so the camera does not fall). Pass: the frames draw by facing, the corner with posts at its three outer corners; the ghost shows the 12 m route with its one elbow; the lay runs through the straight cells and round the corner bend; both rack ends have transition pipes from the tanks' flanges to the resting lane; the aimed rack shows three free-position marks. No errors in the client log. Shots `l01` to `l06`; logs `logs/pipe-laying-ingame-layers-logs-2026-10-26.tar.gz`. The client was closed by `pause_game`, a click on Save and Quit to Title (426, 383) and one on Quit Game (525, 415), with no process kill.

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 13 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/pipe-laying-ingame/`, at the same relative paths.
