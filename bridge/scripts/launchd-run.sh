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

# Node must be pinned. nvm rewrites its version directories on every install,
# so a PATH captured at plist-generation time turns into an infinite
# "exec: node: not found" (exit 127) restart loop under KeepAlive. Resolve one
# absolute path at startup and fail loudly with a readable message if it moves.
#
# Resolution order matters: the highest installed version is NOT the same thing
# as the version nvm would select. `nvm alias default` is authoritative and is
# usually an alias chain (default -> lts/* -> lts/lithium -> v26.13.0), so it is
# followed explicitly rather than guessed at.
resolve_nvm_alias() {
	alias_name=$1
	alias_file="${HOME}/.nvm/alias/${alias_name}"
	[ -f "$alias_file" ] || return 1

	hops=0
	while [ -f "$alias_file" ] && [ "$hops" -lt 10 ]; do
		value=$(tr -d '[:space:]' < "$alias_file")
		case "$value" in
			"" | "N/A") return 1 ;;
			v[0-9]*)
				printf '%s\n' "$value"
				return 0
				;;
			*)
				alias_file="${HOME}/.nvm/alias/${value}"
				;;
		esac
		hops=$((hops + 1))
	done
	return 1
}

node_for_version() {
	[ -n "${1:-}" ] && [ -x "${HOME}/.nvm/versions/node/${1}/bin/node" ] \
		&& printf '%s\n' "${HOME}/.nvm/versions/node/${1}/bin/node"
}

resolve_node() {
	if [ -n "${BRIDGE_NODE:-}" ] && [ -x "${BRIDGE_NODE}" ]; then
		printf '%s\n' "${BRIDGE_NODE}"
		return 0
	fi

	# What nvm itself would pick, following the alias chain.
	if default_version=$(resolve_nvm_alias default); then
		if resolved=$(node_for_version "$default_version"); then
			printf '%s\n' "$resolved"
			return 0
		fi
	fi

	# Fall back to the newest installed version, then non-nvm installs.
	for candidate in \
		$(ls -1d "${HOME}"/.nvm/versions/node/*/bin/node 2>/dev/null | sort -rV) \
		"${HOME}/.local/share/pi-node/current/bin/node" \
		/opt/homebrew/bin/node \
		/usr/local/bin/node
	do
		if [ -x "$candidate" ]; then
			printf '%s\n' "$candidate"
			return 0
		fi
	done

	if NODE_ON_PATH=$(command -v node 2>/dev/null); then
		printf '%s\n' "$NODE_ON_PATH"
		return 0
	fi
	return 1
}

if ! NODE_BIN=$(resolve_node); then
	echo "[launchd-run] cannot find a node binary." >&2
	echo "[launchd-run] set BRIDGE_NODE=/abs/path/to/node in bridge/.env" >&2
	echo "[launchd-run] PATH was: ${PATH:-<unset>}" >&2
	exit 1
fi

# Logged on every start so a stale BRIDGE_NODE or a drifted nvm version is
# visible without guessing which interpreter actually ran.
echo "[launchd-run] node ${NODE_BIN} ($("$NODE_BIN" --version 2>/dev/null || echo '?')), BRIDGE_HOME=${BRIDGE_HOME}"

# Tells server.ts's SIGUSR2 handler to re-exec this launcher instead of the raw
# node binary, so a reload re-sources .env and picks up a changed BRIDGE_NODE.
BRIDGE_LAUNCHER="${BRIDGE_HOME}/scripts/launchd-run.sh"
export BRIDGE_LAUNCHER

exec "$NODE_BIN" dist/server.js
