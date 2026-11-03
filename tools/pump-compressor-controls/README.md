# Constructed mover audio authoring and build evidence

Batch: `documentation/2026-10-23-pump-compressor-switch-sound/`. Feature source: `4b4634f1`, branch `codex/pump-compressor-switch-sound`.

`make_loops.py` authors the two original runtime Ogg assets from periodic motor harmonics and periodic filtered noise. Pump: a smooth 60 Hz hum and water wash; compressor: 48 Hz hum and six-pulse-per-second modulation. Three seconds, mono, 24 kHz; low peaks leave mixing headroom. No recordings or external audio were used.

`python-deps/` is an ignored local authoring environment: soundfile 0.14.0, numpy 2.5.4, cffi 2.1.1, pycparser 3.12 and typing-extensions 4.16.0. These are not runtime dependencies of the mod. The original assets are tracked; this tool was never tracked or detached, so there is no removing commit/re-attach patch.

Run from the feature worktree:

```powershell
$moverPython='C:\Users\wormz\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $moverPython 'D:\Minecraft\Modding\1.21\CreateChemE\tools\pump-compressor-controls\make_loops.py' 'src/main/resources/assets/icheme/sounds'
.\gradlew.bat jar --max-workers=4
```

For a missing local authoring environment, install those pinned packages with pip --target into this tool's python-deps directory. The script prints decoded duration/rate/RMS/peak/seam-step metrics. Generated assets are assets/icheme/sounds/pump_running.ogg and compressor_running.ogg; SoundEvent and subtitle definitions are in the product resources.

Evidence: `logs/mover-controls-logs-2026-10-23.tar.gz` contains dependency-install output, authoring metrics and compile/package logs. `mover-controls-build-final.log` is the final constructed-only build. No tests or automated in-game/GUI checks were run. Subsequent owner-requested integration fast-forwarded target to ab35ef47, preserving all ten local changes; target jar passed and runClient was left running.

Integration evidence (2026-10-23): logs/merge-preservation-logs-2026-10-23.tar.gz contains the target diff, status, hash manifest, untracked-file backup and verification log. logs/target-integration-logs-2026-10-23.tar.gz contains target jar and startup snapshots plus responsive-client process evidence. The untracked diagnostic backup is archived only and was never staged; the original target file remains untouched. Fresh worlds required (checkpoint 15 / unit 8).

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Original periodic motor audio authoring, local codec dependencies and compressed build/asset/target-merge evidence

Batch: 2026-10-23-pump-compressor-switch-sound

Status: Archived 2026-10-23. Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `make_loops.py`, `python-deps/`, `logs/*-logs-2026-10-23.tar.gz`

### History

- Archived 2026-10-23 (target integrated at `ab35ef47`; ten local changes preserved; target jar passed, client running; no tests)
- 2026-11-04: `pump_running.ogg` and `compressor_running.ogg` re-authored without the motor hum by `tools/machine-sounds/` (batch 2026-11-03-rotating-machinery, D13); this script makes the 2026-10-23 versions. Its `python-deps/` stays the authoring environment of both.
