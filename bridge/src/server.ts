import { createServer, type IncomingMessage } from "node:http";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { timingSafeEqual } from "node:crypto";
import { WebSocketServer, type WebSocket } from "ws";
import { MultiSessionPiBridge, type Logger } from "./sessionManager.js";
import type { ClientMessage } from "./protocol.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.resolve(here, "..", "public");

const logger: Logger = {
	info: (message) => console.log(`${stamp()} ${message}`),
	warn: (message) => console.warn(`${stamp()} ${message}`),
	error: (message) => console.error(`${stamp()} ${message}`)
};

function stamp() {
	return new Date().toISOString().slice(11, 19);
}

function envInt(name: string, fallback: number): number {
	const raw = process.env[name];
	if (!raw) return fallback;
	const value = Number(raw);
	return Number.isFinite(value) && value >= 0 ? value : fallback;
}

const PORT = envInt("BRIDGE_PORT", 8787);
const HOST = process.env.BRIDGE_HOST ?? "0.0.0.0";
const BRIDGE_TOKEN = process.env.BRIDGE_TOKEN ?? "";
const ALLOWED_ROOT = path.resolve(process.env.PI_ALLOWED_ROOT ?? process.cwd());
const DATA_DIR = path.resolve(process.env.BRIDGE_DATA_DIR ?? "./data");
const SESSION_DIR = path.resolve(process.env.PI_SESSION_DIR ?? path.join(DATA_DIR, "pi-sessions"));
// pi's own store. This is what /resume lists, so it must be the real one.
const SERVICES_ROOT = path.resolve(
	process.env.PI_SESSIONS_ROOT ?? path.join(process.env.PI_AGENT_DIR ?? path.join(process.env.HOME ?? "~", ".pi", "agent"), "sessions")
);

if (!BRIDGE_TOKEN) {
	logger.error("[Bridge] BRIDGE_TOKEN is not set; refusing to start with an open socket");
	process.exit(1);
}
if (BRIDGE_TOKEN.length < 12) {
	logger.warn("[Bridge] BRIDGE_TOKEN is shorter than 12 chars; use a stronger secret");
}

const bridge = new MultiSessionPiBridge({
	cliPath: process.env.PI_CLI ?? "pi",
	cliArgs: (process.env.PI_ARGS ?? "").split(/\s+/).filter(Boolean),
	allowedRoot: ALLOWED_ROOT,
	dataDir: DATA_DIR,
	sessionDir: SESSION_DIR,
	piSessionsRoot: SERVICES_ROOT,
	extraWorkspaces: (process.env.PI_EXTRA_WORKSPACES ?? process.env.PI_ALLOWED_ROOT ?? "")
		.split(",")
		.map((entry) => entry.trim())
		.filter(Boolean)
		.map((entry) => path.resolve(entry)),
	idleTimeoutMs: envInt("IDLE_TIMEOUT_MS", 20 * 60 * 1000),
	historyLimit: envInt("BRIDGE_HISTORY_LIMIT", 500),
	shutdownGraceMs: envInt("SHUTDOWN_GRACE_MS", 3000),
	env: process.env,
	logger
});

function tokenMatches(candidate: string | null): boolean {
	if (!candidate) return false;
	const a = Buffer.from(candidate);
	const b = Buffer.from(BRIDGE_TOKEN);
	return a.length === b.length && timingSafeEqual(a, b);
}

function parseUrl(req: IncomingMessage): URL {
	return new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
}

function isAuthorized(req: IncomingMessage): boolean {
	const header = req.headers.authorization;
	if (typeof header === "string" && header.startsWith("Bearer ")) {
		if (tokenMatches(header.slice("Bearer ".length).trim())) return true;
	}
	return tokenMatches(parseUrl(req).searchParams.get("token"));
}

const server = createServer((req, res) => {
	const url = parseUrl(req);

	if (url.pathname === "/health") {
		res.writeHead(200, { "Content-Type": "application/json" });
		res.end(JSON.stringify({ status: "ok", sessions: bridge.getSessionSummaries().length }));
		return;
	}

	if (url.pathname === "/" || url.pathname === "/index.html") {
		try {
			const body = readFileSync(path.join(publicDir, "index.html"));
			res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
			res.end(body);
		} catch {
			res.writeHead(404);
			res.end();
		}
		return;
	}

	res.writeHead(404);
	res.end();
});

const wss = new WebSocketServer({ noServer: true });

function send(ws: WebSocket, payload: Record<string, unknown>) {
	if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
}

async function handleClientMessage(ws: WebSocket, raw: string) {
	let msg: ClientMessage;
	try {
		msg = JSON.parse(raw) as ClientMessage;
	} catch {
		send(ws, { type: "error", message: "invalid JSON" });
		return;
	}

	const type = String(msg.type ?? "");
	const sessionId = String(msg.sessionId ?? "");

	try {
		switch (type) {
			case "list_sessions":
				send(ws, { type: "sessions_snapshot", sessions: bridge.getSessionSummaries() });
				return;

			case "list_workspaces":
				send(ws, {
					type: "workspaces_snapshot",
					root: ALLOWED_ROOT,
					workspaces: bridge.listWorkspaces()
				});
				return;

			case "list_pi_sessions": {
				send(ws, {
					type: "pi_sessions_snapshot",
					sessions: bridge.listPiSessions(
						typeof msg.query === "string" ? msg.query : undefined,
						typeof msg.limit === "number" ? msg.limit : 50
					)
				});
				return;
			}

			case "resume_session": {
				const meta = bridge.resumePiSession(sessionId);
				send(ws, { type: "session_created", session: meta });
				return;
			}

			case "create_session": {
				const meta = bridge.createSession(msg.cwd, msg.title);
				send(ws, { type: "session_created", session: meta });
				return;
			}

			case "delete_session":
				if (!bridge.deleteSession(sessionId)) {
					send(ws, { type: "error", sessionId, message: "unknown session" });
				}
				return;

			case "sync": {
				const lastSeq = Number(msg.lastSeq ?? 0);
				const synced = bridge.getMissedEvents(sessionId, lastSeq);
				const oldestSeq = bridge.getOldestReplaySeq(sessionId);
				// The buffer cannot cover the gap: the client must reload messages.
				const needsHistoryReload = bridge.needsHistoryReload(sessionId, lastSeq);
				send(ws, {
					type: "sync_response",
					...synced,
					oldestSeq,
					needsHistoryReload
				});
				return;
			}

			case "prompt": {
				const ok = bridge.sendPrompt(
					sessionId,
					String(msg.message ?? ""),
					typeof msg.id === "string" ? msg.id : undefined,
					typeof msg.streamingBehavior === "string" ? msg.streamingBehavior : undefined
				);
				if (!ok) send(ws, { type: "error", sessionId, message: "failed to send prompt" });
				return;
			}

			case "abort":
				bridge.abortSession(sessionId, msg.clearQueue !== false);
				return;

			case "ui_response": {
				const ok = bridge.respondToUi(
					sessionId,
					String(msg.requestId ?? ""),
					msg.value,
					msg.confirmed,
					msg.cancelled === true
				);
				if (!ok) {
					send(ws, { type: "error", sessionId, message: "unknown or stale ui_request id" });
				}
				return;
			}

			case "get_commands": {
				const commands = await bridge.getCommands(sessionId);
				send(ws, { type: "commands_snapshot", sessionId, commands });
				return;
			}

			case "get_messages": {
				const messages = await bridge.getMessages(sessionId);
				send(ws, { type: "messages_snapshot", sessionId, messages });
				return;
			}

			default:
				send(ws, { type: "error", sessionId, message: `unknown message type: ${type}` });
		}
	} catch (error) {
		send(ws, { type: "error", sessionId, message: (error as Error).message });
	}
}

server.on("upgrade", (req, socket, head) => {
	const pathname = parseUrl(req).pathname;
	if (pathname !== "/ws") {
		socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
		socket.destroy();
		return;
	}
	if (!isAuthorized(req)) {
		logger.warn(`[Bridge] rejected unauthorized socket from ${req.socket.remoteAddress ?? "?"}`);
		socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
		socket.destroy();
		return;
	}

	wss.handleUpgrade(req, socket, head, (ws) => {
		logger.info(`[Bridge] client connected from ${req.socket.remoteAddress ?? "?"}`);
		bridge.attachClient(ws);
		ws.on("message", (data) => {
			void handleClientMessage(ws, data.toString("utf8"));
		});
		ws.on("close", () => {
			logger.info("[Bridge] client disconnected");
			bridge.detachClientAndCancelUi(ws);
		});
	});
});

server.listen(PORT, HOST, () => {
	logger.info(`[Bridge] listening on ws://${HOST}:${PORT}/ws (root=${ALLOWED_ROOT})`);
});

let stopping = false;
async function stop(signal: string) {
	if (stopping) return;
	stopping = true;
	logger.info(`[Bridge] ${signal} received, stopping sessions`);
	server.close();
	await bridge.shutdown();
	process.exit(0);
}

process.on("SIGTERM", () => void stop("SIGTERM"));
process.on("SIGINT", () => void stop("SIGINT"));

/**
 * Restart in place, without leaving the launchd domain.
 *
 * The only supported reload used to be `launchctl unload && launchctl load`.
 * That pair is self-defeating: unload deletes the registration and SIGTERMs the
 * process, so the shell that was mid-command is killed before it reaches load.
 * Worse, that shell is normally a pi child of this very process, which makes
 * every reload a guaranteed outage.
 *
 * `process.execve` replaces the image and keeps the PID, so launchd never sees
 * the job leave: registration, KeepAlive state, and the pid it tracks all
 * survive. Sessions are stopped cleanly first, exactly like SIGTERM, and new
 * ones can be resumed/woken lazily by the next client.
 *
 * Triggered by SIGUSR2 (`launchctl kickstart -k` is the plain restart, this is
 * the config-reload path that also re-sources .env through launchd-run.sh).
 */
async function restartInPlace() {
	if (stopping) return;
	stopping = true;
	logger.info(`[Bridge] SIGUSR2 received, re-executing in place (pid=${process.pid})`);
	server.close();
	await bridge.shutdown();

	if (typeof process.execve !== "function") {
		logger.error("[Bridge] process.execve unavailable; exiting for launchd to restart");
		process.exit(0);
	}

	try {
		// Re-exec the launcher, not this file, so .env is sourced again and a
		// changed BRIDGE_NODE takes effect on reload.
		const launcher = process.env.BRIDGE_LAUNCHER;
		if (launcher) {
			process.execve(launcher, [launcher], process.env);
		} else {
			process.execve(process.execPath, [process.execPath, ...process.argv.slice(1)], process.env);
		}
	} catch (error) {
		logger.error(`[Bridge] re-exec failed: ${(error as Error).message}`);
		process.exit(0);
	}
}

process.on("SIGUSR2", () => void restartInPlace());
