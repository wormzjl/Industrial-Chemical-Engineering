# Minecraft MCP bridge skill (2026-09-24)

Batch that distilled every session's experience with the langyo/minecraft-mod-mcp bridge into a
tracked agent skill, `.claude/skills/minecraft-mcp/` (branch `claude/minecraft-mcp-skill-c3f0f0`,
commits `20b1587`, `1287d6d` and the mining follow-up; `.gitignore` re-includes `/.claude/skills/`).

## What the skill holds

- `SKILL.md`: launch lanes (`runMcpClient` with the tracked `mcpCompat` mixins, `runClient` with the
  jar in `run/mods`), machine rules, the HTTP transport, world creation, scenario placement with
  chat commands, the three aiming placements that open a block screen, text entry, engine waits,
  screenshots, save and quit.
- `scripts/bridge.js` (sequential HTTP driver: `status`, `wait-ready`, `wait-world`, `shot`, `chat`,
  `cmds`, `batch`, any tool), `scripts/robot.ps1` (real input through `java.awt.Robot`, foreground by
  `AttachThreadInput`, GUI-to-screen mapping with the window DPI) and the Java helpers `Field`,
  `KeyTap`, `Poke`, `Desk`, `Flatten` (same sources as `tools/mcp-gui-helpers/` and the rig).
- `references/screen-map.md` (widget indices and click points), `references/bridge-tools.md`
  (every 0.3.0 tool and what works in this client), `references/pitfalls.md` (failure catalogue).

## Transcript mining (2026-09-24)

Four opus subagents mined the JSONL transcripts of about 15 agents that drove the bridge between
2026-09-07 and 2026-09-24 (V3 Heat tab pass, solid-phase GUI pass and its seven probe agents,
fluid scheduling WP3 to WP5 and follow-ups, Codex's V3 handoff review). Their reports are in
`transcript-mining/part-A.md` to `part-D.md`, each with numbered difficulties (goal, symptom,
cost, fix, evidence line), techniques, verbatim recipes and open problems. The skill's pitfalls
catalogue and screen map are the consolidation.

Headline findings: `execute_command` never reaches the server (chat is the only path); the bridge
cannot type into a container screen's `EditBox` (Robot paste, `/data merge block`, or the
`mcpCompat` lane); `pauseOnLostFocus:false` must be set before launch; `click_button_index` on
Create World cycle buttons sometimes moves the label without the value; `press_key escape` does
nothing in the world (`pause_game`); block screens open from `tp @s x.5 -58 z.5 0 89` + `use_item`,
`tp @s x.5 -59 z+2.2 180 22` + `right_click`, or `tp ... facing ...`; `get_player_info`,
`debug_fields`, `select_list_item`, `kill_minecraft` are unusable; every screenshot has alpha 0.

## Status

Implemented 2026-11-17 (0.34.4): rebuilt for bridge v0.4.28 on `claude/mcp-skill-status-632852` and merged to `main`. The 2026-09-24
skill was lost: its branch was deleted and its commits are gone from the object store. The rebuild
starts from the copies kept in `tools/p6-pilot-acceptance/mcp/` (driver, two references) and the
2026-10 lanes' gotchas. It drops `robot.ps1` and the `java.awt.Robot` helpers (desktop input is
forbidden unless the owner watches). The owner's in-game check on v0.4.28 passed on 2026-11-17 (below).
Nothing open.

## v0.4.28 update (2026-11-17)

- Jar: official release v0.4.28, SHA-256 equal to the published digest; `tools/minecraft-mcp-bridge/`
  (README lists the changes since v0.4.18). `build.gradle` pins it; the compat range is `[0.4.28,0.5.0)`.
- Breaking for the lane: a remote `enter_control_mode` needs `-Dmcp.remote_control=true`, and
  `screenshot_to_file` writes only inside `mcp.screenshot.dir`. The `mcpClient` run now sets both
  (the folder is the worktree's `tmp/`). `Content-Type: application/json` was already sent by every driver.
- The `src/mcpCompat` mixin targets have the same signatures in v0.4.28 (`javap`).
- Skill: `.claude/skills/minecraft-mcp/` (`SKILL.md`, `scripts/bridge.js` with the bearer token and
  the screenshot folder, `scripts/new-world.sh`, `scripts/Flatten.java`, `references/bridge-tools.md`
  from the jar's command whitelist, `pitfalls.md`, `screen-map.md`), `.agents/skills/minecraft-mcp`
  symlink, `.gitignore` re-includes `/.claude/skills/**/scripts/`. AGENTS.md and
  `rules/in-game-checks.md` name the skill.

## In-game check on v0.4.28 (2026-11-17, owner asked)

`runMcpClient` from this worktree, fresh `run/mcp-client/`, zero desktop input. Confirmed:

- `/api/status` reports `mcpmod` 0.4.28; `icheme_mcp_compat` loads with the range `[0.4.28,0.5.0)`.
- Before `enter_control_mode` an input command answers `not in control mode`; the remote
  `enter_control_mode` succeeds with the lane's consent (`"hook":true`).
- `new-world.sh` built a creative superflat world (screenshot of the Create World screen: Creative,
  commands on); five chat commands all reached the server (`latest.log`).
- `use_item` standing on a fluid generator opened `FluidDeviceScreen`.
- Text entry: `click` on the pressure field, `delete`/`backspace`, `paste_text`, Apply
  (`click_button_index 11`); reopened, the set point read 300.3 then 400 as typed. Backspaces
  alone leave the digits after the caret, and `press_key end` does not move the caret: the skill now
  says to alternate `delete` and `backspace`.
- `screenshot_to_file` wrote into `tmp/mcp-check/` (the jailed folder).
- `press_key escape` in the world does nothing; `pause_game`, index 8, title screen after ~8 s, index 6;
  Gradle ended with exit 0.
- Fix found: `new-world.sh` set `MSYS_NO_PATHCONV=1` and then passed a `/d/...` script path to Windows
  `node`; it now uses `pwd -W`. `get_screen_buttons` lists every widget twice on 0.4.28.

## Index record (moved from INDEX.md on 2026-10-26)

Purpose: Tracked agent skill `.claude/skills/minecraft-mcp/` distilling all dev-client bridge experience; transcript mining of about 15 agents (four opus reports) into a pitfalls catalogue and screen map

Status: Implemented 2026-11-17 (0.34.4). Index: [../INDEX.md](../INDEX.md).

### Documents

- [transcript-mining/part-A.md](transcript-mining/part-A.md) to `part-D.md`

### Code areas

`.claude/skills/minecraft-mcp/`, `.agents/skills/minecraft-mcp`, `.gitignore`, `build.gradle` (`mcpClient` run, `prepareMcpControlMod`), `src/mcpCompat/resources/META-INF/neoforge.mods.toml`, `tools/minecraft-mcp-bridge/`, `AGENTS.md`, `documentation/reference/rules/in-game-checks.md`; read-only: `src/mcpCompat`, `tools/mcp-gui-helpers/`

### History

- In progress since 2026-09-24 on `claude/minecraft-mcp-skill-c3f0f0` (skill written, mining consolidated; eval runs skipped; `runMcpClient` typing lane unconfirmed)
- 2026-11-17: skill lost with its branch; rebuilt for bridge v0.4.28 on `claude/mcp-skill-status-632852` (jar updated, lane consent and screenshot folder set, robot helpers dropped); in-game check open
- 2026-11-17: merged to `main` as 0.34.4 (owner: patch bump; no final lane: no test class loads `src/mcpCompat` or the `mcpClient` run settings; code guide `client-kit.md` updated; tooling cleanup: nothing created outside `tmp/`)
- 2026-11-17: owner's in-game check on v0.4.28 passed; text-field clearing recipe and the `new-world.sh` path fix added to the skill

### Notes

- 2026-10-26: `.claude/skills/minecraft-mcp/` is not on `main`; no branch holds the skill commits `20b1587` and `1287d6d` (branch `claude/minecraft-mcp-skill-c3f0f0` is gone), which are still in the object store.
- 2026-11-17: the two commits are no longer in the object store either.
