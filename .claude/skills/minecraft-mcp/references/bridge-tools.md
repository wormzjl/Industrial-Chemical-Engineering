# minecraft-mod-mcp v0.4.28: in-game commands and what works here

The command set is the mod's own whitelist (`McpMessageHandler.KNOWN_COMMANDS`, read from the v0.4.28
jar on 2026-11-17). A name outside it answers `unknown command: <name>`. The "status" column is what
IChemE sessions observed with v0.3.0 (to 2026-09-24) and v0.4.18 (2026-10-15 to 2026-10-27), on the
`runMcpClient` lane unless noted. Nothing in this table was re-observed on v0.4.28; the first check on
v0.4.28 confirms or corrects it. "compat" means it works only with the `icheme_mcp_compat` mixins
(`src/mcpCompat`), which only `runMcpClient` loads.

Every command is one HTTP call: `POST http://127.0.0.1:9876/api/cmd`, header
`Content-Type: application/json` (required since 0.4.27), body `{"cmd":"<name>", ...params}`.
`scripts/bridge.js <name> '<json>'` sends exactly that. `GET /api/status` needs no token and answers
the port, the pid and the mod version.

## Always allowed (no control mode)

| command | params | status |
|---|---|---|
| `ping` | | `pong` |
| `screenshot` | | base64 PNG; too large for a tool result, use `screenshot_to_file` |
| `get_screen_buttons` | | ids for `click_button_id`, index order for `click_button_index`; `{"error":"no screen"}` in the world |
| `enumerate_widgets` | | full widget tree, GUI-scaled coordinates and messages |
| `get_world_info` | | untested |
| `get_player_info` | | unreliable (game mode wrong, name empty); read `/data get entity @s` in the log instead |
| `debug_fields` | | unusable; F3 cannot be toggled through the bridge |
| `overlay_click` | `x`, `y` | the mod's own overlay button; not needed |
| `enter_control_mode` | | needed once per client start before any input command; refused remotely without `-Dmcp.remote_control=true` (the lane sets it) |
| `exit_control_mode` | | works |
| `set_gamemode` | `mode` | replies true without proof; use `/gamemode` through chat |
| `release_mouse` | | works |
| `pause_game` | | opens the pause screen; the only way to it in background mode |
| `close_screen` | | closes the open screen (not a pause screen forced by `pauseOnLostFocus`) |
| `open_chat` | | opens chat; its text argument is dropped; wait ~400 ms before typing |

## Control mode required

| command | params | status |
|---|---|---|
| `screenshot_to_file` | `path` | since 0.4.28 only inside `mcp.screenshot.dir` (the lane: `<worktree>/tmp`); relative paths resolve against it; PNG alpha is 0 on every pixel, flatten it (`bridge.js shot` does) |
| `click` | `x`, `y` (framebuffer pixels), `button` | buttons; an `EditBox` focuses with compat; with no screen open it attacks (breaks) the block at the crosshair |
| `click_button_id` | `id` | works |
| `click_button_index` | `index` | fires `onPress`; on a `CycleButton` replies `"via":"manual_cycle"` (see pitfalls); does not focus an `EditBox`; two back-to-back clicks can land as one: click, wait, screenshot, click |
| `right_click` | | in the world routes to the control-mode screen on 0.4.18; prefer `use_item` |
| `use_item` | | opens a block GUI at the crosshair (empty hand works); compat `UseItemMixin` runs it on the render thread, without it a client-side screen can crash before init |
| `place_block` | | works |
| `set_view_angle` | `yaw`, `pitch` | works |
| `look_delta` | `delta_yaw`, `delta_pitch` | works |
| `press_key` / `key` | `key` (`enter`, `escape`, `tab`, `key.keyboard.w`), `hold_seconds` | `enter` submits chat, `escape` closes a GUI; `escape` in the world does nothing; `hold_seconds` does not walk; `tab` moves focus by two widgets |
| `press_enter` / `enter` | | alias of `press_key enter` (new since 0.4.18; untested) |
| `type_text` | `text`, `press_enter` | chat and vanilla screens; a container `EditBox` with compat |
| `paste_text` | `text`, `press_enter` | inserts into the focused `EditBox` with compat |
| `hotkey` | `keys` comma-separated, e.g. `key.keyboard.left control,key.keyboard.a` or `enter` | compat routes it; Ctrl+A did not select on one 0.4.18 run, use backspaces |
| `scroll` | `clicks` | in the world it changes the hotbar slot between ticks |
| `scroll_at` / `direct_scroll` | `x`, `y`, `clicks` | untested |
| `mouse_drag` / `drag` | `x_start`/`x1`, `y_start`/`y1`, `x_end`/`x2`, `y_end`/`y2`, `button` | untested |
| `switch_tab` | `index` (also `tab_index`) | Create World tabs; a wrong name is ignored; mod tab rows are ordinary buttons |
| `select_list_item` | `index` | fails on the world list; click the entry instead |
| `call_screen_method` | `methodName` | untested |
| `execute_command` | `command` | **broken here**: runs through KubeJS's client dispatcher, every server command is "Unknown or incomplete command". Use chat |

## npm-side tools

`get_minecraft_status`, `wait`, `launch_minecraft`, `kill_minecraft`, `install_version`,
`launch_server`, `serve`, `detect_java`, accounts: these live in the npm package, not in the mod, and
are not reachable over `/api/cmd`. The game always starts from Gradle (the bridge's launcher installed
a wrong NeoForge); stop it as `SKILL.md` says, never with `kill_minecraft`.

## Coordinates

`--width 854 --height 480` (the lane default; `-PmcpClientWidth/-PmcpClientHeight` change it) gives an
854x480 framebuffer. At GUI scale 2 `enumerate_widgets` reports 427x240 GUI units; `click` takes
framebuffer pixels (GUI x scale). At `guiScale:1` in `run/mcp-client/options.txt` the frames coincide.
Since 0.4.24 screenshots read the live framebuffer size; the window should still stay at its launch size.
