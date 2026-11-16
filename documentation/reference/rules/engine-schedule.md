# Rules: Engine schedule and player-facing updates

The full rules of the `AGENTS.md` section "Engine schedule and player-facing updates", with the same force. `AGENTS.md` keeps the core and says when to read this file.

- The engine publishes presentation snapshots, acknowledgements and status changes on its own schedule, about every 5 s of online time.
- A client gets GUI and process data only on its own request. Opening a GUI replays the last published snapshot at once and subscribes the client to scheduled updates; closing it ends the subscription.
- A replay reads cached data. It does not run a solver, create process state, apply pending input or advance time. With no snapshot yet, wait for the first scheduled publication.
- Player inputs and network changes are queued engine events, acknowledged on the engine schedule. Packet handlers, block-entity loads and menu opens never force a process update.
- Do not calculate process state on Minecraft's tick loop. Due deadlines, dependency changes and worker completions drive the work. The tick hook only checks what is due, drains completions and flushes a presentation bucket.
- Simulated time is online ticks at a nominal 20 TPS, times the time factor (`fluid.timeScale`, default 4). No wall-clock or offline advancement.
- The process simulator's run is a client tool outside this schedule: it reads and writes no process state. The server only stores the sheet and its results, and the block opens them as a replay.
