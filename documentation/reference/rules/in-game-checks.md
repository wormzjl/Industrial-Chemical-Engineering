# Rules: GUI and in-game checks

The full rules of the `AGENTS.md` section "GUI and in-game checks", with the same force. `AGENTS.md` keeps the core and says when to read this file.

- Before any GUI work, read the `gui-creation` skill and the reference it names for the job.
- Do not start a dev-client check on your own. Judge GUI work by the gates, the compile and the software renders of `tools/heat-gui-renders/`. Where nothing else can judge (rendering, lighting, chunk sync), offer a check and let the owner start it.
- A check the owner asks for runs through the langyo/minecraft-mod-mcp bridge on the `runMcpClient` lane: copy the pinned jar from `tools/minecraft-mcp-bridge/` into `<worktree>/run/mcp-client-mods/`, and follow the `minecraft-mcp` skill (it drives the bridge over HTTP; `.mcp.json` is optional).
- Never send real desktop keyboard or mouse input (`robot.ps1`, SendKeys) unless the owner is watching. If a check needs typed values that only desktop input can supply, stop and report.
- At most one in-game check per task. Judge drawing-only changes by gates, sweeps and software renders.
