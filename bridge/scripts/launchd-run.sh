#!/bin/sh
# Launcher used by the macOS LaunchAgent.
#
# launchd cannot read a .env file, and putting the token and paths into the
# plist creates a second source of truth that silently drifts from bridge/.env.
# Instead launchd runs this script, which loads the env file and execs node.
#
# Override the project location with BRIDGE_HOME when the checkout is elsewhere.
set -eu

BRIDGE_HOME="${BRIDGE_HOME:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$BRIDGE_HOME"

if [ -f .env ]; then
	set -a
	. ./.env
	set +a
fi

if [ -z "${BRIDGE_TOKEN:-}" ]; then
	echo "[launchd-run] BRIDGE_TOKEN is not set; refusing to start" >&2
	exit 1
fi

if [ ! -f dist/server.js ]; then
	echo "[launchd-run] dist/server.js is missing; run 'npm run build' first" >&2
	exit 1
fi

exec node dist/server.js
