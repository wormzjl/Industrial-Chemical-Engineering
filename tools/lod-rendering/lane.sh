#!/usr/bin/env bash
# One LOD frame-time lane (batch 2026-11-09-lod-rendering): the probe's mode=lod on one worktree's build.
# Usage: lane.sh <label> <worktree> <log dir>
# A timing run: it refuses to start while another Gradle test worker, GameTest server or client runs on the machine.
set -euo pipefail
label=$1; tree=$(cd "$2" && pwd); logs=$3
here=$(cd "$(dirname "$0")" && pwd)
probe="$here/../custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java"
bridge="$here/../minecraft-mcp-bridge/minecraft-mcp-1.21.1-neoforge-v0.4.18.jar"
mkdir -p "$logs"

# Test executors, GameTest servers and clients count; an idle compiler worker daemon (a build's leftover) does not, but
# the machine's CPU over 5 s must also be low (16 threads: one busy Gradle worker is about 6 %).
busy=$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|fml.modFolders' }).Count" | tr -d '\r')
if [ "$busy" != "0" ]; then echo "lane.sh: $busy other test or game JVMs run; a timing lane runs alone" >&2; exit 2; fi
cpu=$(powershell -NoProfile -Command "\$s=1..5|%{ (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter \"Name='_Total'\").PercentProcessorTime; Start-Sleep 1 }; [math]::Round((\$s|Measure-Object -Average).Average)" | tr -d '\r')
if [ "${cpu:-100}" -gt 25 ]; then echo "lane.sh: the machine's CPU is at ${cpu} %; a timing lane runs alone" >&2; exit 2; fi
free=$(powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,1)" | tr -d '\r')
echo "lane.sh: $label on $tree, free RAM ${free} GB"

game="$tree/run/mcp-client"
attached="$tree/src/mcpCompat/java/com/wormzjl/icheme/mcpcompat/FrameTimeProbe.java"
save="lod-fps-$label"
cleanup() {
  rm -f "$attached"
  rm -rf "$game/fps-autopilot" "$game/saves/$save"
}
trap cleanup EXIT

cp "$probe" "$attached"
mkdir -p "$tree/run/mcp-client-mods" "$game/fps-autopilot"
cp "$bridge" "$tree/run/mcp-client-mods/"
[ -f "$game/options.txt" ] && cp "$game/options.txt" "$logs/$label-options-before.txt"
cp "$here/options.txt" "$game/options.txt"
cp "$here/scene.txt" "$here/view.txt" "$here/away.txt" "$game/fps-autopilot/"
rm -rf "$game/saves/$save"
cat > "$game/fps-autopilot/autopilot.properties" <<EOF
mode=lod
save=$save
label=$label
warmup=60
measure=60
grid.x=0
grid.y=-58
grid.z=0
grid.nx=10
grid.nz=10
grid.step=3
EOF

(cd "$tree" && JAVA_OPTS=-Xshare:off ./gradlew runMcpClient --offline --console=plain -PmcpClientWidth=1920 -PmcpClientHeight=1080) > "$logs/$label-client.log" 2>&1 || true
grep -h "FPS_PROBE\|FPS_LOAD\|FPS_AUTOPILOT" "$logs/$label-client.log" > "$logs/$label-summary.txt" || true
cp "$game/screenshots/fps-$label-view.png" "$logs/" 2>/dev/null || true
cat "$logs/$label-summary.txt"
