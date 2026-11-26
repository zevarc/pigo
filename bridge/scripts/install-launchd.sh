#!/bin/sh
# Installs (or reinstalls) the macOS LaunchAgent that keeps the bridge running.
#
#   ./scripts/install-launchd.sh            # install / update
#   ./scripts/install-launchd.sh --uninstall
#
# Configuration is NOT copied into the plist: launchd-run.sh sources bridge/.env
# at startup, so there is exactly one place to edit.
set -eu

BRIDGE_HOME="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="dev.zevarc.pigo-bridge"
TARGET="$HOME/Library/LaunchAgents/$LABEL.plist"
TEMPLATE="$BRIDGE_HOME/scripts/dev.zevarc.pigo-bridge.plist.template"

if [ "${1:-}" = "--uninstall" ]; then
	if [ -f "$TARGET" ]; then
		launchctl unload "$TARGET" 2>/dev/null || true
		rm -f "$TARGET"
		echo "uninstalled $LABEL"
	else
		echo "$LABEL is not installed"
	fi
	exit 0
fi

if [ ! -f "$BRIDGE_HOME/.env" ]; then
	echo "missing $BRIDGE_HOME/.env — copy .env.example and fill it in first" >&2
	exit 1
fi
if [ ! -f "$BRIDGE_HOME/dist/server.js" ]; then
	echo "missing dist/server.js — run 'npm run build' first" >&2
	exit 1
fi

chmod +x "$BRIDGE_HOME/scripts/launchd-run.sh"
mkdir -p "$HOME/Library/LaunchAgents"

sed \
	-e "s|__BRIDGE_HOME__|$BRIDGE_HOME|g" \
	-e "s|__HOME__|$HOME|g" \
	-e "s|__PATH__|$PATH|g" \
	"$TEMPLATE" > "$TARGET"
chmod 600 "$TARGET"

launchctl unload "$TARGET" 2>/dev/null || true
launchctl load "$TARGET"

sleep 2
if launchctl list | grep -q "$LABEL"; then
	echo "installed $LABEL"
	echo "  logs:   $BRIDGE_HOME/data/bridge.err.log"
	echo "  health: curl -s http://127.0.0.1:${BRIDGE_PORT:-8787}/health"
else
	echo "launchd did not report $LABEL; check 'launchctl list | grep pigo'" >&2
	exit 1
fi
