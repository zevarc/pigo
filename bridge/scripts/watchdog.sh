#!/bin/sh
# Out-of-band supervisor for the bridge LaunchAgent.
#
# KeepAlive can only restart a process that launchd still knows about. It does
# nothing when the service has been booted out of the domain entirely — which is
# exactly what happens when bridge's own pi child runs `launchctl unload` (pi
# executes with the same UID, so it can always reach the launchd switch that
# manages its parent). That failure mode is invisible to launchd itself, so
# something outside the managed tree has to notice it.
#
# This script is that something. It runs on its own StartInterval, and:
#   - service missing from the domain  -> bootstrap it back
#   - /health not answering           -> kickstart -k it
#   - data/.bridge-disabled exists     -> do nothing at all
#
# Exit codes are informational only; launchd does not act on them.
set -u

BRIDGE_HOME="${BRIDGE_HOME:-$(cd "$(dirname "$0")/.." && pwd)}"
LABEL="dev.zevarc.pigo-bridge"
PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"
DOMAIN="gui/$(id -u)"
DISABLE_FLAG="${BRIDGE_HOME}/data/.bridge-disabled"
LOG_PREFIX="[watchdog]"

log() {
	printf '%s %s %s\n' "$(date -u '+%H:%M:%S')" "$LOG_PREFIX" "$1"
}

# Respect an operator's deliberate stop. `touch data/.bridge-disabled` pauses
# supervision; removing the file resumes it on the next tick.
if [ -f "$DISABLE_FLAG" ]; then
	exit 0
fi

if [ ! -f "$PLIST" ]; then
	log "plist missing at ${PLIST}; nothing to supervise (run install-launchd.sh)"
	exit 0
fi

health_url="http://127.0.0.1:${BRIDGE_PORT:-8787}/health"

# Layer 1: is the job still registered with launchd?
if ! launchctl print "${DOMAIN}/${LABEL}" >/dev/null 2>&1; then
	log "service not registered with launchd; bootstrapping"
	launchctl bootstrap "${DOMAIN}" "$PLIST" >/dev/null 2>&1 \
		|| log "bootstrap failed; will retry on next tick"
	exit 0
fi

# Layer 2: is the registered job actually serving? The service is alive but
# wedged, or KeepAlive is in its 10s throttle after a hard crash.
if ! curl -sf --max-time 5 --noproxy '*' "$health_url" >/dev/null 2>&1; then
	log "registered but ${health_url} is not answering; kickstarting"
	launchctl kickstart -k "${DOMAIN}/${LABEL}" >/dev/null 2>&1 \
		|| log "kickstart failed; will retry on next tick"
	exit 0
fi

exit 0
