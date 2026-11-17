---
name: minecraft-mcp
description: How to drive the IChemE dev client in game through the langyo/minecraft-mod-mcp bridge (v0.4.28) on the runMcpClient lane - launch, control mode, world creation, chat commands, aiming at a block to open its GUI, text entry, screenshots, engine waits, save and quit - with the known pitfalls. Use before any in-game check the owner asks for, before writing or reviewing a brief for one, when updating the pinned bridge jar, and whenever a bridge call fails (not in control mode, content type, screenshot path refused, unknown command, nothing happens).
---

# Minecraft MCP bridge (IChemE)

The bridge is the official langyo/minecraft-mod-mcp mod, pinned in `tools/minecraft-mcp-bridge/` (jar,
digest, what changed per version). Agents drive it over its own HTTP endpoint with `scripts/bridge.js`;
no MCP tools need to be loaded.

## Rules first

- An in-game check runs only when the owner asks for it; at most one per task (`documentation/reference/rules/in-game-checks.md`). Otherwise judge GUI work by the gates, the compile and the software renders (`gui-creation` skill).
- No real desktop keyboard or mouse input (`robot.ps1`, SendKeys, `java.awt.Robot`) unless the owner is watching. If a check needs input only the desktop can give, stop and report.
- The client is a Gradle invocation: it counts against the two-lane limit, and no test suite runs while it is up.
- A check waits on the client, so it runs in the main thread or a sub-thread, never in a subagent.

## Lane setup (once per worktree)

1. Copy `tools/minecraft-mcp-bridge/minecraft-mcp-1.21.1-neoforge-v0.4.28.jar` into `<worktree>/run/mcp-client-mods/`. `prepareMcpControlMod` checks the SHA-256 and copies it into `run/mcp-client/mods/`.
2. With the client stopped, set `pauseOnLostFocus:false` and `onboardAccessibility:false` in `run/mcp-client/options.txt` (create the file with those lines if it is missing). Optional: `guiScale:1` makes GUI units equal framebuffer pixels.
3. The `mcpClient` run in `build.gradle` already sets `mcp.background_input=true` (the OS cursor is never touched), `mcp.remote_control=true` (a remote `enter_control_mode` is allowed) and `mcp.screenshot.dir=<worktree>/tmp` (screenshots may only go there).
4. Make sure nothing answers on port 9876: `node .claude/skills/minecraft-mcp/scripts/bridge.js status` must fail.

## Launch and connect

```bash
JAVA_OPTS=-Xshare:off ./gradlew.bat --no-configuration-cache runMcpClient --offline --console=plain
```

Run it in the background. Then, from the worktree root:

```bash
node .claude/skills/minecraft-mcp/scripts/bridge.js wait-ready 300
```

Check `run/mcp-client/logs/latest.log` lists `icheme_mcp_compat` and `mcpmod` 0.4.28. Then
`bridge.js enter_control_mode` (again after every client restart). Window size: `-PmcpClientWidth=…
-PmcpClientHeight=…` at launch, never a resize in the world.

## World and scenario

- `bash .claude/skills/minecraft-mcp/scripts/new-world.sh [setup.txt] [tmp/<name>]` creates a creative superflat world without structures from the title screen, screenshots the Create World screen, waits for the world and runs the command file. Indices: `references/screen-map.md`.
- Server commands go through chat only: `bridge.js chat "/setblock …"` or `bridge.js cmds file.txt` (one command per line). `execute_command` never reaches the server. Confirm each command in `latest.log`.
- Build the scenario with `/setblock`, `/fill`, `/give` and the placement defaults; prefer block NBT (`/data merge block`) over typed GUI values when the GUI is not what is under test.

## Open a block GUI

Aim with an explicit position and angle, then `use_item` (empty hand works; `right_click` routes to the
control-mode screen):

- stand on the block looking down: `/tp @s X.5 Y+1 Z.5 0 89`, then `use_item`;
- stand 2.2 blocks south at foot level facing north: `/tp @s X.5 Y Z+2.2 180 22`, then `use_item`.

Then `get_screen_buttons` must name the expected screen. Never `click` with no screen open: it breaks the block.

## Inside a screen

- Buttons: `click_button_index {"index":N}` (indices from `get_screen_buttons`). Click, wait 1-2 s, then the next.
- Text fields: `click {"x":…,"y":…}` at the field's framebuffer pixel (GUI units x GUI scale; centre of the field's `get_screen_buttons` box), then alternate `press_key {"key":"delete"}` and `press_key {"key":"backspace"}` about 8 times each (the caret sits where you clicked), then `paste_text {"text":"…"}`. Screenshot before Apply. Needs the compat mixins (this lane only).
- Close: `close_screen` or `press_key {"key":"escape"}`.

## Screenshots and engine waits

- `bridge.js shot tmp/<name>/<file>.png` (path inside the worktree's `tmp/`; add a `tmp/INDEX.md` row for a new folder). It drops the alpha channel the bridge leaves at 0.
- The engine publishes about every 5 s of online time: wait 6-8 s after an input before a screenshot, or close and reopen the GUI to replay the last snapshot. A background client repaints slowly; captures less than ~2 s apart are identical.
- Before spending GUI time, `grep -c "status=HELD" run/mcp-client/logs/latest.log`.

## Stop

`pause_game`, `click_button_index {"index":8}` (Save and Quit to Title), wait for the title screen,
`click_button_index {"index":6}` (Quit Game). If the client hangs, stop the java process whose command
line contains `mcpClientRunProgramArgs` (a stopped background Bash leaves it running).

## References

- Last confirmed end to end on v0.4.28: 2026-11-17 (launch, consent, world, chat commands, `use_item`, text entry, Apply, screenshots into `tmp/`, Save and Quit, Quit Game).
- `references/bridge-tools.md`: every v0.4.28 command, its parameters, the control-mode gate and what worked here.
- `references/pitfalls.md`: failure catalogue with fixes. Read it before improvising.
- `references/screen-map.md`: vanilla widget indices.
- `tools/minecraft-mcp-bridge/README.md`: the pinned jar, the version changes and how to update.
- History and the transcript-mining reports: `documentation/2026-09-24-minecraft-mcp-skill/`.
