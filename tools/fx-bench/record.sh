#!/usr/bin/env bash
# Effects recording run (batch 2026-11-10-custom-shader-system; owner 2026-11-10: "Record these animations"). Not a timing run.
# Usage: [SHOTS=<shot file>] record.sh <worktree> <out dir> [config]   (config: auto (default), compatible, off: a tools/fx-bench/config toml;
#   SHOTS: a shot list in the format of record.txt to record instead of it)
# The probe's mode=fx builds the bench scene and feeds the synthetic states; FxRecorder (attached beside it) flies to each
# shot of record.txt and captures frame sequences into <out dir>/<shot>/. It takes the fx lane lock, so no bench lane
# starts meanwhile, and refuses to start while a test or game JVM runs. Then: record_sheets.py <out dir> for GIFs and sheets.
set -euo pipefail
tree=$(cd "$1" && pwd); out=$2; toml=${3:-auto}
here=$(cd "$(dirname "$0")" && pwd)
probe="$here/../custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java"
bridge="$here/../minecraft-mcp-bridge/minecraft-mcp-1.21.1-neoforge-v0.4.18.jar"
offline=--offline; [ "${ONLINE:-0}" = 1 ] && offline=

lock="$tree/tmp/fx-bench/lane.lock"
mkdir -p "$tree/tmp/fx-bench" "$out"
if ! mkdir "$lock" 2>/dev/null; then echo "record.sh: $lock exists: an fx lane runs (or a stopped one left it)" >&2; exit 2; fi
busy=$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|fml.modFolders' }).Count" | tr -d '\r')
if [ "$busy" != "0" ]; then rmdir "$lock"; echo "record.sh: $busy other test or game JVMs run" >&2; exit 2; fi

game="$tree/run/mcp-client"
src="$tree/src/mcpCompat/java/com/wormzjl/icheme/mcpcompat"
save="fx-record"
config="$game/config/icheme-client.toml"
cleanup() {
  rm -f "$src/FrameTimeProbe.java" "$src/FxRecorder.java"
  rm -rf "$game/fps-autopilot" "$game/saves/$save" "$game/fx-record"
  if [ -f "$out/client-config-before.toml" ]; then cp "$out/client-config-before.toml" "$config"; else rm -f "$config"; fi
  if [ -f "$out/options-before.txt" ]; then cp "$out/options-before.txt" "$game/options.txt"; fi
  rmdir "$lock" 2>/dev/null || true
}
trap cleanup EXIT

cp "$probe" "$src/FrameTimeProbe.java"
cp "$here/FxRecorder.java" "$src/FxRecorder.java"
mkdir -p "$tree/run/mcp-client-mods" "$game/fps-autopilot" "$game/config"
cp "$bridge" "$tree/run/mcp-client-mods/"
[ -f "$game/options.txt" ] && cp "$game/options.txt" "$out/options-before.txt"
cp "$here/options.txt" "$game/options.txt"
[ -f "$config" ] && cp "$config" "$out/client-config-before.toml"
cp "$here/config/icheme-client-$toml.toml" "$config"
for f in scene view away far record; do cp "$here/$f.txt" "$game/fps-autopilot/$f.txt"; done
[ -n "${SHOTS:-}" ] && cp "$SHOTS" "$game/fps-autopilot/record.txt"
rm -rf "$game/saves/$save" "$game/fx-record"
cat > "$game/fps-autopilot/autopilot.properties" <<EOF
mode=fx
save=$save
label=record
warmup=60
measure=60
windows=view
feed=true
noise=false
install=true
server=false
jfr=false
drums.x=-27
drums.y=-60
drums.z=10
drums.nx=10
drums.nz=5
drums.step=6
noise.x=0.5
noise.y=-40
noise.z=-30
EOF

(cd "$tree" && JAVA_OPTS=-Xshare:off ./gradlew runMcpClient $offline --console=plain -PmcpClientWidth=1920 -PmcpClientHeight=1080) > "$out/client.log" 2>&1 || true
grep -h "FX_RECORD\|FX_FEED\|FPS_AUTOPILOT" "$out/client.log" || true
if [ -d "$game/fx-record" ]; then cp -r "$game/fx-record/." "$out/"; fi
ls "$out"
