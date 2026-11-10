#!/usr/bin/env bash
# One effects bench run (batch 2026-11-10-custom-shader-system, FX_BENCH_PLAN.md): the probe's mode=fx on one worktree's build.
# Usage: lane.sh <run> <worktree> <log dir>
#   runs (plan lanes): 1-off 1-auto (lane 1), 2-compat (lane 2), 3-off 3-auto (lane 3, Iris + the pack), 4-sil 4-nosil
#   (lane 4, Distant Horizons, the far window), 5-sound (lane 5, the noise feed). README.md gives the order.
# Environment: JFR=0 records no JFR; PACK=<zip> another shader pack for lane 3; ONLINE=1 lets Gradle resolve (first -PwithIris
# or -PwithDh run on a machine whose cache lacks Iris, Sodium or Distant Horizons).
# A timing run: it refuses to start while another fx lane, Gradle test worker, GameTest server or client runs on the machine.
set -euo pipefail
run=$1; tree=$(cd "$2" && pwd); logs=$3
here=$(cd "$(dirname "$0")" && pwd)
probe="$here/../custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java"
bridge="$here/../minecraft-mcp-bridge/minecraft-mcp-1.21.1-neoforge-v0.4.18.jar"
pack=${PACK:-$here/../../tmp/fx-bench/shaderpacks/ComplementaryReimagined_r5.9.3.zip}
jfr=true; [ "${JFR:-1}" = 0 ] && jfr=false
offline=--offline; [ "${ONLINE:-0}" = 1 ] && offline=

# The run's client config, Gradle switches, windows and feed.
props=""; windows="view,away"; noise=false; iris=false; solo=false
case "$run" in
  1-off) toml=off ;;
  1-auto) toml=auto ;;
  2-compat) toml=compatible ;;
  3-off) toml=off; props="-PwithIris"; iris=true ;;
  3-auto) toml=auto; props="-PwithIris"; iris=true ;;
  4-sil) toml=auto; props="-PwithDh"; windows=far ;;
  4-nosil) toml=auto-nosilhouettes; props="-PwithDh"; windows=far ;;
  5-sound) toml=auto; windows=view; noise=true ;;
  6-solo) toml=auto; windows=view; solo=true; jfr=false ;;  # a listening run: each sound alone, its name on screen
  *) echo "lane.sh: unknown run $run (1-off 1-auto 2-compat 3-off 3-auto 4-sil 4-nosil 5-sound 6-solo)" >&2; exit 2 ;;
esac
if [ "$iris" = true ] && [ ! -f "$pack" ]; then echo "lane.sh: shader pack $pack missing" >&2; exit 1; fi

# One fx lane at a time on the machine (the lock is in the worktree's tmp/; another worktree's lane shows as a client JVM).
lock="$tree/tmp/fx-bench/lane.lock"
mkdir -p "$tree/tmp/fx-bench"
if ! mkdir "$lock" 2>/dev/null; then echo "lane.sh: $lock exists: another fx lane runs (or a stopped one left it)" >&2; exit 2; fi
mkdir -p "$logs"

# Test executors, GameTest servers and clients count; an idle compiler worker daemon (a build's leftover) does not, but
# the machine's CPU over 5 s must also be low (16 threads: one busy Gradle worker is about 6 %).
busy=$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'Gradle Test Executor|fml.modFolders' }).Count" | tr -d '\r')
if [ "$busy" != "0" ]; then rmdir "$lock"; echo "lane.sh: $busy other test or game JVMs run; a timing lane runs alone" >&2; exit 2; fi
cpu=$(powershell -NoProfile -Command "\$s=1..5|%{ (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter \"Name='_Total'\").PercentProcessorTime; Start-Sleep 1 }; [math]::Round((\$s|Measure-Object -Average).Average)" | tr -d '\r')
if [ "${CPU_OK:-0}" = 1 ]; then echo "lane.sh: CPU_OK=1: the CPU check is skipped (the owner's choice); the load is ${cpu} %"; fi
if [ "${CPU_OK:-0}" != 1 ] && [ "$solo" != true ] && [ "${cpu:-100}" -gt 25 ]; then rmdir "$lock"; echo "lane.sh: the machine's CPU is at ${cpu} %; a timing lane runs alone" >&2; exit 2; fi
free=$(powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,1)" | tr -d '\r')
echo "lane.sh: $run on $tree, config $toml, ${props:-no extra mods}, windows $windows, CPU ${cpu} %, free RAM ${free} GB"

game="$tree/run/mcp-client"
attached="$tree/src/mcpCompat/java/com/wormzjl/icheme/mcpcompat/FrameTimeProbe.java"
save="fx-bench-$run"
config="$game/config/icheme-client.toml"
irisConfig="$game/config/iris.properties"
packCopy="$game/shaderpacks/$(basename "$pack")"
cleanup() {
  rm -f "$attached"
  rm -rf "$game/fps-autopilot" "$game/saves/$save"
  rm -f "$game"/fps-*.jfr
  if [ -f "$logs/$run-client-config-before.toml" ]; then cp "$logs/$run-client-config-before.toml" "$config"; else rm -f "$config"; fi
  if [ "$iris" = true ]; then
    rm -f "$packCopy"
    if [ -f "$logs/$run-iris-before.properties" ]; then cp "$logs/$run-iris-before.properties" "$irisConfig"; else rm -f "$irisConfig"; fi
  fi
  rmdir "$lock" 2>/dev/null || true
}
trap cleanup EXIT

cp "$probe" "$attached"
mkdir -p "$tree/run/mcp-client-mods" "$game/fps-autopilot" "$game/config"
cp "$bridge" "$tree/run/mcp-client-mods/"
[ -f "$game/options.txt" ] && cp "$game/options.txt" "$logs/$run-options-before.txt"
cp "$here/options.txt" "$game/options.txt"
[ -f "$config" ] && cp "$config" "$logs/$run-client-config-before.toml"
cp "$here/config/icheme-client-$toml.toml" "$config"
if [ "$iris" = true ]; then
  mkdir -p "$game/shaderpacks"
  cp "$pack" "$packCopy"
  [ -f "$irisConfig" ] && cp "$irisConfig" "$logs/$run-iris-before.properties"
  sed "s/^shaderPack=.*/shaderPack=$(basename "$pack")/" "$here/config/iris.properties" > "$irisConfig"
fi
for f in scene view away far; do cp "$here/$f.txt" "$game/fps-autopilot/$f.txt"; done
rm -rf "$game/saves/$save"
cat > "$game/fps-autopilot/autopilot.properties" <<EOF
mode=fx
save=$save
label=$run
warmup=60
measure=60
windows=$windows
feed=true
noise=$noise
install=$([ "$solo" = true ] && echo false || echo true)
server=$([ "$solo" = true ] && echo false || echo true)
solo=$solo
server.calls=200
jfr=$jfr
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

# runMcpClient takes no extra JVM arguments (build.gradle's mcpClient run sets none); the probe starts JFR itself (jdk.jfr).
(cd "$tree" && JAVA_OPTS=-Xshare:off ./gradlew runMcpClient $offline --console=plain -PmcpClientWidth=1920 -PmcpClientHeight=1080 $props) > "$logs/$run-client.log" 2>&1 || true
grep -h "FPS_PROBE\|FPS_LOAD\|FPS_AUTOPILOT\|FX_PROBE\|FX_JFR\|FX_FEED\|FX_SERVER\|FX_MEMORY" "$logs/$run-client.log" > "$logs/$run-summary.txt" || true
for f in "$game"/fps-"$run"-*.jfr "$game"/screenshots/fps-"$run"-*.png; do if [ -f "$f" ]; then cp "$f" "$logs/"; fi; done
cat "$logs/$run-summary.txt"
