# minecraft-mcp-bridge

Purpose: the Minecraft MCP control mod (langyo/minecraft-mod-mcp) that the `runMcpClient` lane pins. Batches: `documentation/2026-10-16-minecraft-mcp-mouse-input/` (background input), `documentation/2026-09-24-minecraft-mcp-skill/` (the `minecraft-mcp` skill, update to v0.4.28).

- `minecraft-mcp-1.21.1-neoforge-v0.4.28.jar`: the official release v0.4.28 (published 2026-11-07), unmodified, downloaded 2026-11-17 from the GitHub release. 931639 bytes, SHA-256 `0da719532a643d35378acd3aad26dbc07c23a36c04a9fe24f2056f70e800073b`, identical to the release asset's published digest. This is the file `build.gradle` (`prepareMcpControlMod`) names. The v0.4.18 jar it replaces (SHA-256 `e9359fce…25f0`) is in git history.
- The lane runs the bridge in its background input mode. `build.gradle` sets `mcp.background_input=true` on the `mcpClient` run (upstream issue #54, PR #57, since v0.4.9). In that mode:
  - `setCursorPos` edits only Minecraft's own cursor fields;
  - the `java.awt.Robot` fallbacks are skipped;
  - `exit_control_mode` does not lock the cursor;
  - each client tick sets a vanilla `GLFW_CURSOR_DISABLED` back to NORMAL.
- Vanilla's own grab still ran in that mode (world join, screen close: cursor to the window centre, confined for up to one tick; `cursor3.log`). The compat mixin `BackgroundCursorMixin` (`src/mcpCompat`, commit ce6923d9) skips it while the game window is not the foreground window; checked 2026-10-26 (`cursor4.log`), the cursor stays put.

## What changed from v0.4.18 to v0.4.28 (upstream issue #81)

- The HTTP server binds `127.0.0.1` only and rejects a non-loopback `Host` header.
- `POST /api/cmd` needs `Content-Type: application/json` (every driver in `tools/` already sends it).
- A bearer token is required only when one is configured (`-Dmcp.token` / `MC_MCP_TOKEN`). The lane sets none.
- Only known commands are dispatched; any other name answers `unknown command: <name>`.
- A remote `enter_control_mode` is refused unless the game runs with `-Dmcp.remote_control=true`. `build.gradle` sets it on the `mcpClient` run.
- `screenshot_to_file` writes only inside the screenshot folder (`-Dmcp.screenshot.dir`, default `<game dir>/screenshots`; symbolic links refused). `build.gradle` sets it to the worktree's `tmp/`, so a capture path is `tmp/<name>/<file>.png`, absolute or relative to `tmp/`.
- Screenshots read the live framebuffer size (v0.4.24).
- The mixin targets of `src/mcpCompat` have the same signatures as in v0.4.18 (checked with `javap`, 2026-11-17).

## Install and update

Install: nothing to do. The jar is tracked here, so every worktree has it; `prepareMcpControlMod` copies it into `run/mcp-client/mods/` and deletes any other `minecraft-mcp-*.jar` there. The build checks no digest (the tracked file is the pin); the digest is compared once, when a release is downloaded. A leftover `run/mcp-client-mods/` folder from before 0.34.4 is unused. The compat mod (`src/mcpCompat`) accepts `mcpmod` `[0.4.28,0.5.0)`.

Update to a newer release: `gh release download vX.Y.Z --repo langyo/minecraft-mod-mcp --pattern 'minecraft-mcp-1.21.1-neoforge-vX.Y.Z.jar'` into an empty folder under `tmp/`. Compare the SHA-256 with the release asset's digest (`gh api repos/langyo/minecraft-mod-mcp/releases/tags/vX.Y.Z`). Read the release notes for changes to the HTTP API, the command list or the system properties. Then replace the jar here, change the jar name in `build.gradle` and the range in `src/mcpCompat/resources/META-INF/neoforge.mods.toml`, check that every `src/mcpCompat` mixin target still exists with the same signature (`ReflectedInputHandler.hotkey`, `ScreenInteractionHelper.guiClick`/`guiCharType`/`pasteText`, `PlayerWorldHelper.doUseItem`, `WindowHelper.getWindowHandle`; `javap -p` on the extracted `xyz/langyo/minecraft/mcp/common/` classes), and update the `minecraft-mcp` skill's `references/bridge-tools.md`.

`superseded-0.4.7-osguard/` holds the local build used 2026-10-16..26 (upstream 0.4.7 plus `OsInputGuard`, SHA-256 `d71b35aa…c4e7`, source commit 70bafcf on branch `fix/background-os-input` of the clone `D:/Minecraft/Modding/1.21/mcp`), with its patch. It prevented vanilla's grab while the window was in the background (focus withheld through the game's own GLFW focus callback), which the official mode does not do.
