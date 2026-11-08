# Connected control, terminal mounts and offset junctions

1. Expected: a cable body reaches every built face point, a terminal box sits against its host casing, and a panel offers only devices on its data bus. A level loop at 25% acts on an inlet or outlet control valve.
2. Defects: the shared pipe mesher ignored offsets once a line gained a third/fourth arm; drum data mounts fell back to shifted instrument taps; cached panel views and dormant selections could expose disconnected IDs. The generic Action selector explained neither valve location nor its effect.
3. Implemented in `76ad7889`, translations `f6d02add`, allocation follow-up `68294ece`, 2026-11-07; prepared for 0.22.0, not merged.

- `LineGeometry.offsetJunction` reuses fitted pipe sweeps and a closed hub for each offset branch. Centred pipes keep their existing path. Neither placement nor endpoints, connectivity, diameter or fluid laws change.
- Regression probe at the same offset tee: old body misses 8/8 connector-rim vertices; current body misses 0/8. Source-mesh before/after figures retained.
- Drum sockets use independent shell pads, away from the front taps/rear access on standing drums, or on the side of lying drums. The box back meets the shell; broad spans use block-face centres.
- Wired drums keep their existing socket UUID, entry cell/face and offsets; the refreshed casing mount stays on that built side. Large incompatible moves retain the prior port. No nozzle block moves.
- `DataBus.hosts` owns connected-host projection. Controller/DCS choices use it; a topology-obsolete cached view is not replayed. Disconnected dormant selections retain their facts but show no remote ID/reading. Enabled Apply is refused without both data links.
- Control output choices omit movers that local loops cannot actuate. The existing VFD demand-control path is unchanged.
- The GUI says Controlled device and Valve location; level/pressure show the inlet/outlet choices and guidance. Other measurements hide that selector. New level selections start in percent; saved selections retain their units.
- For 25%: select the drum LT ID, choose %, enter 25, select the connected CV ID, choose Inlet valve (fills below target) or Outlet valve (drains above), switch On and Apply. Percent means instrument height span, not vessel volume fraction.
- Device/machinery GUIs show synced sensor IDs with their measurement labels; valve GUI includes its CV ID. This reads static metadata even before a process sample exists.
- No numeric control law, conservation, engine cadence, fact format, wire format, layout stamp or version change. Settings remain named INLET/OUTLET; old selections are retained.

## Verification

- 65 affected checks passed first; after the last ID presentation and locale edits, 14 filtered GUI/language checks passed. The resolver-based host projection then passed ControlSystemTest 12/12 without copying the global port map per panel. Combined coverage is 68 distinct checks: 23 geometry, 12 bus/facts, 14 instruments, 16 GUI, 3 language/unit checks.
- No full suite, final lane, campaign or in-game client was replayed. Initial compile/import failure and cache-access refusal are retained with successful follow-ups.
- Normal production/test compilation and jar packaging pass. All five locales have 2940 ordered matching keys/arguments; width and stale audits pass except eight historical Chinese wording notices.
- Light/dark local/DCS forms, opened/end scroll, IDs, standing/lying drum pads and the repaired offset tee were inspected in software.
- Jar: `build/libs/icheme-0.22.0.jar`, 8,579,630 bytes; SHA-256 `c9c0e21d880e3b77cb2a5a8fc104eb8a565c601dcc579838534b31fad4ba1e4d`. Eleven cabinet/sensor PNGs match source; no task/probe classes are packaged.
- Evidence and jar: main `research/2026-11-06-simulator-sizing/evidence/control-rework/`; logs: main `tools/simulator-sizing/logs/simulator-sizing-control-rework-logs-2026-11-07.tar.gz`. Originals and previous qualified jars remain retained.
- Tools: `tools/simulator-sizing/ControlPortProbe.java`, `PowerLookRender.java`, `export-model-faces.py`, retained diagnostic sources; `tools/pump-sprites/ControlAssets.java`/`probes/SensorSheet.java`; `tools/heat-gui-renders/GuiPrototypes.java`.
- Owner's in-game acceptance and merge instruction remain open. No merge, push or dev-client launch.
