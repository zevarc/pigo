#!/bin/sh
# Installs (or reinstalls) the macOS LaunchAgents that keep the bridge running.
#
#   ./scripts/install-launchd.sh            # install / update both jobs
#   ./scripts/install-launchd.sh --uninstall
#
# Configuration is NOT copied into the plist: launchd-run.sh sources bridge/.env
# at startup, so there is exactly one place to edit.
#
# Restart semantics are deliberate. This script never runs `launchctl unload`:
# unload deletes the registration and SIGTERMs the running bridge, which kills
# the shell mid-script (it is usually a pi child of that very bridge), so the
# following load never executes and the service stays down. bootstrap/kickstart
# hand the intent to launchd first and survive their caller dying.
set -eu

BRIDGE_HOME="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="dev.zevarc.pigo-bridge"
WATCHDOG_LABEL="${LABEL}.watchdog"
TARGET="$HOME/Library/LaunchAgents/$LABEL.plist"
WATCHDOG_TARGET="$HOME/Library/LaunchAgents/$WATCHDOG_LABEL.plist"
TEMPLATE="$BRIDGE_HOME/scripts/dev.zevarc.pigo-bridge.plist.template"
WATCHDOG_TEMPLATE="$BRIDGE_HOME/scripts/dev.zevarc.pigo-bridge.watchdog.plist.template"
DOMAIN="gui/$(id -u)"

die() {
	printf '%s\n' "$1" >&2
	exit 1
}

# Guard against the self-defeating case: running this while inside the tree it
# is about to restart. Refuse instead of producing a mystery outage.
assert_not_inside_bridge() {
	pid=$PPID
	hops=0
	while [ -n "$pid" ] && [ "$pid" != "1" ] && [ "$hops" -lt 20 ]; do
		cmd=$(ps -o command= -p "$pid" 2>/dev/null || true)
		case "$cmd" in
			*"dist/server.js"*)
				die "refusing to run: this shell is inside the bridge process tree (pid $pid: $cmd).
Restarting from here would kill the very command that is doing the restart.
Run it from a separate terminal, or use:
  launchctl kickstart -k $DOMAIN/$LABEL"
				;;
		esac
		pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
		hops=$((hops + 1))
	done
}

install_job() {
	template=$1
	target=$2
	label=$3

	[ -f "$template" ] || die "missing template $template"

	sed \
		-e "s|__BRIDGE_HOME__|$BRIDGE_HOME|g" \
		-e "s|__HOME__|$HOME|g" \
		-e "s|__PATH__|$PATH|g" \
		"$template" > "$target"
	chmod 600 "$target"

	# bootout is required to replace a live definition, but unlike unload it is
	# immediately followed by bootstrap and launchd owns the restart intent.
	# Failure here is expected when the job was never loaded.
	launchctl bootout "$DOMAIN/$label" >/dev/null 2>&1 || true

	# bootout is asynchronous: the job stays in the domain for a few seconds
	# after the call returns, and a bootstrap that lands inside that window is
	# rejected with "Bootstrap failed: 5: Input/output error" or a bare failure.
	# Retry rather than reporting a failure that a second run would not reproduce.
	attempt=0
	while [ "$attempt" -lt 10 ]; do
		if launchctl bootstrap "$DOMAIN" "$target" >/dev/null 2>&1; then
			return 0
		fi
		# Already loaded (bootout lost the race the other way): nothing to do.
		if launchctl print "$DOMAIN/$label" >/dev/null 2>&1; then
			return 0
		fi
		attempt=$((attempt + 1))
		sleep 1
	done

	launchctl print "$DOMAIN/$label" 2>&1 | head -5 >&2
	die "launchctl bootstrap failed for $label after ${attempt} attempts"
}

if [ "${1:-}" = "--uninstall" ]; then
	launchctl bootout "$DOMAIN/$WATCHDOG_LABEL" >/dev/null 2>&1 || true
	launchctl bootout "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
	rm -f "$WATCHDOG_TARGET" "$TARGET"
	echo "uninstalled $LABEL and $WATCHDOG_LABEL"
	exit 0
fi

if [ ! -f "$BRIDGE_HOME/.env" ]; then
	die "missing $BRIDGE_HOME/.env — copy .env.example and fill it in first"
fi
if [ ! -f "$BRIDGE_HOME/dist/server.js" ]; then
	die "missing dist/server.js — run 'npm run build' first"
fi

assert_not_inside_bridge

chmod +x "$BRIDGE_HOME/scripts/launchd-run.sh" "$BRIDGE_HOME/scripts/watchdog.sh"
mkdir -p "$HOME/Library/LaunchAgents"

install_job "$TEMPLATE" "$TARGET" "$LABEL"
# Watchdog last: it bootstraps the bridge back if anything above went sideways.
install_job "$WATCHDOG_TEMPLATE" "$WATCHDOG_TARGET" "$WATCHDOG_LABEL"

sleep 2
if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
	echo "installed $LABEL"
	echo "  logs:   $BRIDGE_HOME/data/bridge.out.log"
	echo "  health: curl -s http://127.0.0.1:${BRIDGE_PORT:-8787}/health"
else
	echo "launchd did not report $LABEL; check 'launchctl print $DOMAIN/$LABEL'" >&2
	exit 1
fi

if launchctl print "$DOMAIN/$WATCHDOG_LABEL" >/dev/null 2>&1; then
	echo "installed $WATCHDOG_LABEL (checks every 60s)"
	echo "  logs:   $BRIDGE_HOME/data/watchdog.log"
	echo "  pause:  touch $BRIDGE_HOME/data/.bridge-disabled"
else
	echo "launchd did not report $WATCHDOG_LABEL; bridge runs unsupervised" >&2
	exit 1
fi
