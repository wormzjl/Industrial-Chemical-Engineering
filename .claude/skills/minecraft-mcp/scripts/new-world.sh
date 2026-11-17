#!/usr/bin/env bash
# From the title screen of the runMcpClient lane: create a creative superflat world without structures, then run
# the optional command file (one slash command per line). Run from the worktree root:
#   bash .claude/skills/minecraft-mcp/scripts/new-world.sh [setup.txt] [shot-folder under tmp/]
# Index map: references/screen-map.md. Adapted from tools/custom-equipment-rendering-poc/new-world.sh (bridge 0.4.18).
set -euo pipefail
S="$(cd "$(dirname "$0")" && pwd)"
B="node $S/bridge.js"
SETUP="${1:-}"
SHOTS="${2:-tmp/mcp-shots}"
export MSYS_NO_PATHCONV=1
$B wait-ready 300 >/dev/null
$B enter_control_mode                                # needs -Dmcp.remote_control=true (set by the lane)
$B click_button_index '{"index":0}' >/dev/null      # Singleplayer
sleep 2
if [ -n "$(ls run/mcp-client/saves 2>/dev/null)" ]; then
  $B click_button_index '{"index":2}' >/dev/null    # SelectWorldScreen: Create New World
  sleep 2
fi
$B click_button_index '{"index":5}' >/dev/null      # Game Mode: Survival -> Hardcore
sleep 1
$B click_button_index '{"index":5}' >/dev/null      # -> Creative (commands on)
$B switch_tab '{"index":1}' >/dev/null
$B click_button_index '{"index":3}' >/dev/null      # World Type -> Superflat
$B click_button_index '{"index":8}' >/dev/null      # Generate Structures off
$B switch_tab '{"index":0}' >/dev/null
sleep 2
$B shot "$SHOTS/last-create-world.png" >/dev/null   # check Creative before Create
$B click_button_index '{"index":1}' >/dev/null      # Create New World
$B wait-world 120
if [ -n "$SETUP" ]; then $B cmds "$SETUP" >/dev/null; fi
echo "world ready"
