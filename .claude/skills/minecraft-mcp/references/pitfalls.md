# Pitfalls: what went wrong in earlier sessions and what fixed it

From the 2026-09-24 transcript mining (bridge 0.3.0), the 2026-10 lanes on 0.4.18 and the v0.4.28
release notes. Desktop-input fixes (`robot.ps1`, `java.awt.Robot`) are left out: they are forbidden
unless the owner is watching. Read this before improvising.

## Transport and setup

| pitfall | symptom | fix |
|---|---|---|
| POST without `Content-Type: application/json` (0.4.27+) | `{"error":"content type must be application/json"}` | `bridge.js` sends it; a hand-written `curl` needs `-H 'Content-Type: application/json'` |
| `enter_control_mode` on a client started without consent (0.4.27+) | `control mode entry not consented` | the lane's `mcp.remote_control=true`; a `runClient` launch does not have it |
| Input command before `enter_control_mode` | `not in control mode` | `enter_control_mode` first, also after every client restart; it works on the title screen |
| `screenshot_to_file` outside the jail (0.4.28) | `path resolves outside the screenshot directory` | a path inside `<worktree>/tmp/` (the lane's `mcp.screenshot.dir`); a worktree whose `build.gradle` predates this has the default `run/mcp-client/screenshots/` |
| Raw stdio JSON-RPC into `npx minecraft-mod-mcp mcp` | answers out of order, or never | HTTP through `bridge.js`. Subagent sessions never have the MCP tools; HTTP always works |
| Bridge jar missing | no endpoint on 9876; `prepareMcpControlMod` fails | copy `tools/minecraft-mcp-bridge/minecraft-mcp-1.21.1-neoforge-v0.4.28.jar` into `<worktree>/run/mcp-client-mods/` |
| Port 9876 already bound (another client, the owner's game) | `Address already in use`; commands reach the wrong game | `bridge.js status` must fail before launch; check its `pid` after launch |
| Compat mod not loaded | typing and `use_item` misbehave | `runMcpClient`, not `runClient`; `icheme_mcp_compat` must be in the log's mod list |
| `execute_command` | `"sent":true` yet "Unknown or incomplete command", even `/help` | chat: `bridge.js chat` / `bridge.js cmds` |
| Git Bash path mangling | `/tp ...` arrives as `C:/Program Files/Git/tp ...` | `MSYS_NO_PATHCONV=1` (`new-world.sh` sets it); then script paths for Windows `node` must be in `pwd -W` form, or `node` cannot find `/d/...` |
| Shell-built JSON | `MalformedJsonException` | pass the params as one quoted JSON argument, or `bridge.js batch` |

## Client state

| pitfall | symptom | fix |
|---|---|---|
| `pauseOnLostFocus:true` (the default) | the pause screen reopens every frame; `close_screen` and Escape cannot beat it; clicks fail | stop the client, write `pauseOnLostFocus:false` into `run/mcp-client/options.txt`, start again |
| `open_chat` before `enter_control_mode` | a wedged chat that swallows every mouse event | control mode first; `close_screen` clears it |
| Window resized in a world | the world quit to the title screen once | change the size by `-PmcpClientWidth/-PmcpClientHeight` at launch |
| Sodium or Iris in `run/mcp-client/mods` | 0.4.x crashed in `glfw.dll` on `enter_control_mode` | fixed by compat `WindowHandleMixin`; the Sodium lanes run unattended through the autopilot (`tools/custom-equipment-rendering-poc/README.md`) |
| Old save format | the integrated server stops, `level.dat` already rewritten | a fresh world; no compatibility work on old saves |
| Unexpected screen open | input lands on the wrong screen | `get_screen_buttons` before acting |
| Fresh `run/mcp-client/` | the accessibility onboarding screen comes first | `onboardAccessibility:false` in `options.txt` before the first launch |
| Tutorial toasts | "Move with W, A, S and D" covers the screen's top right | ignore, or crop; it stays in the first minutes of a new world |

## Menus and world creation

| pitfall | symptom | fix |
|---|---|---|
| `click_button_index` on a CycleButton | `"via":"manual_cycle"`; evidence split: world type and game mode usually take, Allow Commands did not | screenshot before Create; Creative turns commands on by itself, so leave Allow Commands alone; `/gamemode creative` in the world if needed |
| Two quick clicks on one CycleButton | count as one | click, wait 1 s, click |
| `switch_tab {"tab":1}` | switched to tab 0 | `index` (or `tab_index`) |
| `select_list_item` on the world list | `could not select on WorldSelectionList` | `click` on the entry, then index 1 |
| Coordinate `click` on vanilla menus | nothing happens at GUI scale 2 | `click_button_index`; see `screen-map.md` |

## Commands

| pitfall | symptom | fix |
|---|---|---|
| Helper prints `sent /cmd` on failure | the command never ran | read `run/mcp-client/logs/latest.log`: failures say `Unknown or incomplete command`, `You do not have permission` |
| Chat text never arrives | seen under the Sodium/Iris autopilot lane and once on 0.4.18 over stdio | 0.4.18 fallback: `open_chat`, `paste_text {"text":"/..."}`, `hotkey {"keys":"enter"}`; `press_key key.keyboard.enter` did not submit there |
| `/save-all` | unknown in singleplayer | Save and Quit from the pause screen; the integrated server autosaves every 5 min |
| `gamemode spectator` then `creative` | the player keeps flying | `gamemode survival` to make it fall |

## Blocks, aiming, GUIs

| pitfall | symptom | fix |
|---|---|---|
| Opening a block GUI from eye level 2 blocks away | reply true, `no screen` | the aiming placements in `SKILL.md` |
| `tp ... facing` | aims wrong in one session | explicit yaw and pitch |
| `tp` sets the feet | the eye is 1.62 higher than the `tp` y | aim from the eye height |
| `click` with no screen open | breaks the block at the crosshair | only click with a screen open |
| Neighbouring block in the line of sight | the wrong GUI opens | approach from a side with no neighbour |
| Teleport into mid-air | the player falls, the crosshair moves | stand on a block (`/fill` a glass column), creative flight off |
| `click_button_index` on an `EditBox` | does not focus it | `click {x,y}` at the field's framebuffer pixel (GUI x scale), backspaces, `paste_text` or `type_text` (compat) |
| `press_key tab` | moves focus by two widgets | click the field instead |
| Clearing a field with backspaces only | the caret sits at the click point, so the digits after it stay (`101.3` became `2503`) | after the `click`, alternate `press_key delete` and `press_key backspace` about 8 times each; `press_key end` does not move the caret |
| Hover tooltips | do not render at a `click` point | judge tooltip texts by tests |
| Screenshot right after a click | the old frame | wait 2-3 s; a background client repaints slowly: shots less than ~2 s apart are byte-identical |

## Timing and the engine

| pitfall | symptom | fix |
|---|---|---|
| Screenshot right after Apply | old values or `Waiting for the engine` | the engine publishes about every 5 s: wait 6-8 s, or close and reopen the GUI |
| A HELD island | Apply gives no acknowledgement | `grep -c "status=HELD" run/mcp-client/logs/latest.log` before spending GUI time; build elsewhere or a fresh world |
| Foreground `sleep` loops | blocked by the harness | `sleep N` inside one script (`new-world.sh`), or a background wait |

## Shutdown

| pitfall | symptom | fix |
|---|---|---|
| `press_key escape` in the world | nothing | `pause_game` |
| Killing the JVM | an unsaved world (autosave usually rescues it) | `pause_game`, index 8, wait for the title screen, index 6 |
| A stopped background Bash | leaves the Gradle client running | stop the java process whose command line contains `mcpClientRunProgramArgs` |
| NeoForge config persists between starts | `run/mcp-client/config/` keeps the last value | set it before each start |
