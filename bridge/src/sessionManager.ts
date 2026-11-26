import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { JsonlFramer } from "./jsonl.js";
import { normalizePiEvent, type NormalizeContext } from "./normalize.js";
import type { BridgeEvent, SessionSummary } from "./protocol.js";

export interface BridgeOptions {
	/** Command used for the child process; tests inject a fake pi. */
	cliPath: string;
	/** Extra CLI arguments appended after the mode flag. */
	cliArgs: string[];
	allowedRoot: string;
	dataDir: string;
	sessionDir: string;
	idleTimeoutMs: number;
	/** How many events a session keeps for reconnect replay. */
	historyLimit: number;
	/** Grace period between stdin-close and SIGTERM when evicting. */
	shutdownGraceMs: number;
	env: NodeJS.ProcessEnv;
	logger: Logger;
}

export interface Logger {
	info(message: string): void;
	warn(message: string): void;
	error(message: string): void;
}

export interface SessionMeta {
	id: string;
	title: string;
	cwd: string;
	createdAt: number;
	lastActiveAt: number;
}

/** Anything that can receive bridge events; WebSocket in production, a stub in tests. */
export interface ClientSink {
	readyState: number;
	send(raw: string): void;
	on?(event: string, listener: (...args: unknown[]) => void): unknown;
}

interface BufferedEvent {
	seq: number;
	payload: BridgeEvent;
}

interface PendingCommand {
	resolve: (data: Record<string, unknown>) => void;
	reject: (error: Error) => void;
	timer: NodeJS.Timeout;
}

interface SessionRuntime {
	meta: SessionMeta;
	proc: ChildProcessWithoutNullStreams | null;
	framer: JsonlFramer;
	stderrTail: string;
	seqCounter: number;
	eventHistory: BufferedEvent[];
	isAgentRunning: boolean;
	idleTimer: NodeJS.Timeout | null;
	normalizeContext: NormalizeContext;
	pending: Map<string, PendingCommand>;
	/** Prompt command ids awaiting a rejection notice. */
	promptRequests: Map<string, { clientMessageId?: string }>;
	exited: boolean;
}

const MAX_HISTORY_FILES = 200;

function safeId(raw: string): string | null {
	const trimmed = raw.trim();
	if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(trimmed)) return null;
	if (!/[A-Za-z0-9]$/.test(trimmed)) return null;
	return trimmed;
}

export class MultiSessionPiBridge {
	private sessions = new Map<string, SessionRuntime>();
	private clients = new Set<ClientSink>();
	private options: BridgeOptions;
	private sessionsFile: string;
	private shuttingDown = false;

	constructor(options: BridgeOptions) {
		this.options = options;
		mkdirSync(options.dataDir, { recursive: true });
		mkdirSync(options.sessionDir, { recursive: true });
		this.sessionsFile = path.join(options.dataDir, "sessions.json");
		this.loadSessions();
	}

	// ---------------------------------------------------------------- persistence

	private loadSessions() {
		if (!existsSync(this.sessionsFile)) return;
		try {
			const list = JSON.parse(readFileSync(this.sessionsFile, "utf8")) as SessionMeta[];
			for (const meta of list) {
				if (!meta?.id || !meta.cwd) continue;
				this.sessions.set(meta.id, this.newRuntime(meta));
			}
			this.options.logger.info(`[Bridge] loaded ${this.sessions.size} session(s)`);
		} catch (error) {
			this.options.logger.error(`[Bridge] failed to load sessions.json: ${(error as Error).message}`);
		}
	}

	private saveSessions() {
		const list = Array.from(this.sessions.values()).map((runtime) => runtime.meta);
		writeFileSync(this.sessionsFile, JSON.stringify(list, null, 2), "utf8");
	}

	private newRuntime(meta: SessionMeta): SessionRuntime {
		return {
			meta,
			proc: null,
			framer: new JsonlFramer(),
			stderrTail: "",
			seqCounter: 0,
			eventHistory: [],
			isAgentRunning: false,
			idleTimer: null,
			normalizeContext: { toolCalls: new Map() },
			pending: new Map(),
			promptRequests: new Map(),
			exited: false
		};
	}

	// ------------------------------------------------------------------- queries

	getSessionSummaries(): SessionSummary[] {
		return Array.from(this.sessions.values())
			.map((runtime) => ({
				id: runtime.meta.id,
				title: runtime.meta.title,
				cwd: runtime.meta.cwd,
				createdAt: runtime.meta.createdAt,
				lastActiveAt: runtime.meta.lastActiveAt,
				isAgentRunning: runtime.isAgentRunning,
				processAlive: runtime.proc !== null && !runtime.exited,
				latestSeq: runtime.seqCounter
			}))
			.sort((a, b) => b.lastActiveAt - a.lastActiveAt);
	}

	getSession(id: string): SessionSummary | null {
		return this.getSessionSummaries().find((session) => session.id === id) ?? null;
	}

	/** Directories under the allowed root that a client may use as a workspace. */
	listWorkspaces(maxDepth = 2): { path: string; name: string }[] {
		const results: { path: string; name: string }[] = [];
		const walk = (dir: string, depth: number) => {
			if (depth > maxDepth) return;
			let entries: string[];
			try {
				entries = readdirSync(dir);
			} catch {
				return;
			}
			for (const entry of entries) {
				if (entry.startsWith(".") || entry === "node_modules") continue;
				const full = path.join(dir, entry);
				try {
					if (!statSync(full).isDirectory()) continue;
				} catch {
					continue;
				}
				results.push({ path: path.relative(this.options.allowedRoot, full), name: entry });
				walk(full, depth + 1);
			}
		};
		walk(this.options.allowedRoot, 1);
		return results.slice(0, MAX_HISTORY_FILES);
	}

	// ------------------------------------------------------------------ lifecycle

	createSession(rawCwd?: string, title?: string): SessionMeta {
		const cwd = this.resolveSafeCwd(rawCwd);
		const id = `s-${randomUUID().replace(/-/g, "").slice(0, 12)}`;
		const meta: SessionMeta = {
			id,
			title: title?.trim() || path.basename(cwd) || id,
			cwd,
			createdAt: Date.now(),
			lastActiveAt: Date.now()
		};
		this.sessions.set(id, this.newRuntime(meta));
		this.saveSessions();
		this.broadcast({ type: "sessions_updated", sessions: this.getSessionSummaries() });
		return meta;
	}

	deleteSession(id: string): boolean {
		const runtime = this.sessions.get(id);
		if (!runtime) return false;
		this.stopProcess(runtime, "session_deleted");
		this.sessions.delete(id);
		this.saveSessions();
		this.broadcast({
			type: "session_deleted",
			sessionId: id,
			sessions: this.getSessionSummaries()
		});
		return true;
	}

	private resolveSafeCwd(rawCwd?: string): string {
		const root = this.options.allowedRoot;
		if (!rawCwd || rawCwd.trim() === "" || rawCwd.trim() === ".") return root;
		const candidate = path.resolve(root, rawCwd);
		const relative = path.relative(root, candidate);
		const inside = relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
		if (!inside) {
			throw new Error(`Workspace outside allowed root: ${rawCwd}`);
		}
		if (!existsSync(candidate) || !statSync(candidate).isDirectory()) {
			throw new Error(`Workspace directory does not exist: ${rawCwd}`);
		}
		return candidate;
	}

	// --------------------------------------------------------------- pi process

	private ensureProcess(runtime: SessionRuntime): ChildProcessWithoutNullStreams {
		this.touch(runtime);
		if (runtime.proc && !runtime.exited) return runtime.proc;

		runtime.exited = false;
		runtime.framer = new JsonlFramer();
		runtime.normalizeContext = { toolCalls: new Map() };

		const args = ["--mode", "rpc", ...this.options.cliArgs];
		// Persist and resume the conversation across process evictions.
		if (!args.includes("--no-session")) {
			args.push("--session-id", runtime.meta.id, "--session-dir", this.options.sessionDir);
		}

		const proc = spawn(this.options.cliPath, args, {
			cwd: runtime.meta.cwd,
			stdio: ["pipe", "pipe", "pipe"],
			env: this.options.env
		});

		runtime.proc = proc;
		this.options.logger.info(
			`[${runtime.meta.id}] spawned ${this.options.cliPath} in ${runtime.meta.cwd} (pid=${proc.pid})`
		);

		proc.stdout.on("data", (chunk: Buffer) => this.handleStdout(runtime, chunk));
		proc.stderr.on("data", (chunk: Buffer) => {
			const text = chunk.toString("utf8");
			runtime.stderrTail = (runtime.stderrTail + text).slice(-4000);
			for (const line of text.split("\n")) {
				if (line.trim()) this.options.logger.warn(`[${runtime.meta.id} stderr] ${line.trimEnd()}`);
			}
		});
		proc.stdin.on("error", (error) => {
			this.options.logger.error(`[${runtime.meta.id}] stdin error: ${error.message}`);
		});
		proc.on("error", (error) => {
			this.options.logger.error(`[${runtime.meta.id}] process error: ${error.message}`);
			this.failPending(runtime, new Error(`pi process error: ${error.message}`));
		});
		proc.on("exit", (code, signal) => {
			this.options.logger.info(`[${runtime.meta.id}] pi exited (code=${code}, signal=${signal})`);
			const wasRunning = runtime.isAgentRunning;
			runtime.proc = null;
			runtime.exited = true;
			runtime.isAgentRunning = false;
			this.failPending(runtime, new Error(`pi process exited (code=${code}, signal=${signal})`));
			if (wasRunning) {
				// The client is waiting on a run that will never settle.
				this.emitSessionEvent(runtime, { type: "agent_settled", aborted: true });
			}
			this.emitSessionEvent(runtime, {
				type: "session_process_exit",
				code,
				signal,
				stderrTail: runtime.stderrTail.slice(-1500)
			});
			this.clearIdleTimer(runtime);
		});

		this.resetIdleTimer(runtime);
		return proc;
	}

	/** Orderly shutdown per docs/rpc.md: close stdin, wait, then signal. */
	private stopProcess(runtime: SessionRuntime, reason: string) {
		const proc = runtime.proc;
		if (!proc) return;
		this.options.logger.info(`[${runtime.meta.id}] stopping pi (${reason})`);
		runtime.proc = null;
		runtime.exited = true;
		this.clearIdleTimer(runtime);

		try {
			proc.stdin.end();
		} catch {
			/* already closed */
		}

		const killTimer = setTimeout(() => {
			if (!proc.killed) proc.kill("SIGTERM");
		}, this.options.shutdownGraceMs);
		const hardTimer = setTimeout(() => {
			if (!proc.killed) proc.kill("SIGKILL");
		}, this.options.shutdownGraceMs + 5000);

		proc.once("exit", () => {
			clearTimeout(killTimer);
			clearTimeout(hardTimer);
		});
	}

	private touch(runtime: SessionRuntime) {
		runtime.meta.lastActiveAt = Date.now();
	}

	private resetIdleTimer(runtime: SessionRuntime) {
		this.clearIdleTimer(runtime);
		if (this.options.idleTimeoutMs <= 0) return;
		runtime.idleTimer = setTimeout(() => {
			if (runtime.isAgentRunning || !runtime.proc) {
				this.resetIdleTimer(runtime);
				return;
			}
			this.options.logger.info(
				`[${runtime.meta.id}] idle for ${this.options.idleTimeoutMs}ms, evicting pi process`
			);
			this.stopProcess(runtime, "idle_timeout");
		}, this.options.idleTimeoutMs);
		runtime.idleTimer.unref?.();
	}

	private clearIdleTimer(runtime: SessionRuntime) {
		if (runtime.idleTimer) {
			clearTimeout(runtime.idleTimer);
			runtime.idleTimer = null;
		}
	}

	// ------------------------------------------------------------ stdout handling

	private handleStdout(runtime: SessionRuntime, chunk: Buffer) {
		for (const line of runtime.framer.push(chunk)) {
			let record: Record<string, unknown>;
			try {
				record = JSON.parse(line) as Record<string, unknown>;
			} catch {
				this.options.logger.warn(`[${runtime.meta.id}] invalid JSONL: ${line.slice(0, 200)}`);
				continue;
			}
			this.handleRecord(runtime, record);
		}
	}

	private handleRecord(runtime: SessionRuntime, record: Record<string, unknown>) {
		const type = String(record.type ?? "");

		if (type === "response") {
			this.handleResponse(runtime, record);
			return;
		}

		const normalized = normalizePiEvent(record, runtime.normalizeContext);
		if (normalized.runStarted) runtime.isAgentRunning = true;
		if (normalized.runSettled) {
			runtime.isAgentRunning = false;
			this.resetIdleTimer(runtime);
		}
		if (normalized.event) {
			this.emitSessionEvent(runtime, normalized.event);
		}
	}

	private handleResponse(runtime: SessionRuntime, record: Record<string, unknown>) {
		const id = typeof record.id === "string" ? record.id : "";
		const command = String(record.command ?? "");
		const success = record.success === true;
		const data = (record.data ?? {}) as Record<string, unknown>;
		const request = runtime.promptRequests.get(id);

		if (request) {
			runtime.promptRequests.delete(id);
			if (!success) {
				this.emitSessionEvent(runtime, {
					type: "error",
					code: "prompt_rejected",
					message: String(record.error ?? "prompt rejected"),
					clientMessageId: request.clientMessageId ?? null
				});
			} else {
				const disposition = String(data.disposition ?? "");
				this.emitSessionEvent(runtime, {
					type: "notice",
					kind: "prompt_accepted",
					disposition,
					clientMessageId: request.clientMessageId ?? null
				});
			}
			return;
		}

		const pending = runtime.pending.get(id);
		if (pending) {
			runtime.pending.delete(id);
			clearTimeout(pending.timer);
			if (success) pending.resolve(data);
			else pending.reject(new Error(String(record.error ?? `${command} failed`)));
		}
	}

	private failPending(runtime: SessionRuntime, error: Error) {
		for (const [id, pending] of runtime.pending) {
			clearTimeout(pending.timer);
			pending.reject(error);
			runtime.pending.delete(id);
		}
		runtime.promptRequests.clear();
	}

	private emitSessionEvent(runtime: SessionRuntime, event: { type: string } & Record<string, unknown>) {
		const seq = ++runtime.seqCounter;
		const payload: BridgeEvent = {
			...event,
			sessionId: runtime.meta.id,
			seq,
			ts: Date.now()
		};
		runtime.eventHistory.push({ seq, payload });
		while (runtime.eventHistory.length > this.options.historyLimit) {
			runtime.eventHistory.shift();
		}
		this.broadcast(payload);
	}

	private broadcast(data: Record<string, unknown>) {
		const raw = JSON.stringify(data);
		for (const client of this.clients) {
			if (client.readyState === 1 /* WebSocket.OPEN */) client.send(raw);
		}
	}

	// ------------------------------------------------------------- command plumbing

	private sendCommand(runtime: SessionRuntime, command: Record<string, unknown>): boolean {
		const proc = runtime.proc;
		if (!proc || runtime.exited || !proc.stdin.writable) return false;
		const line = `${JSON.stringify(command)}\n`;
		proc.stdin.write(line, "utf8");
		return true;
	}

	private request(
		runtime: SessionRuntime,
		command: Record<string, unknown>,
		timeoutMs = 15_000
	): Promise<Record<string, unknown>> {
		const proc = this.ensureProcess(runtime);
		if (!proc.stdin.writable) {
			return Promise.reject(new Error("pi stdin is not writable"));
		}
		const id = `cmd-${randomUUID().slice(0, 8)}`;
		return new Promise<Record<string, unknown>>((resolve, reject) => {
			const timer = setTimeout(() => {
				runtime.pending.delete(id);
				reject(new Error(`command ${String(command.type)} timed out`));
			}, timeoutMs);
			timer.unref?.();
			runtime.pending.set(id, { resolve, reject, timer });
			const line = `${JSON.stringify({ ...command, id })}\n`;
			proc.stdin.write(line, "utf8");
		});
	}

	sendPrompt(
		sessionId: string,
		message: string,
		clientMessageId?: string,
		streamingBehavior?: string
	): boolean {
		const runtime = this.sessions.get(sessionId);
		if (!runtime) return false;
		this.touch(runtime);
		this.saveSessions();

		const proc = this.ensureProcess(runtime);
		if (!proc.stdin.writable) return false;

		const behavior =
			streamingBehavior === "steer" || streamingBehavior === "followUp"
				? streamingBehavior
				: runtime.isAgentRunning
					? "steer"
					: undefined;

		const id = `cmd-${randomUUID().slice(0, 8)}`;
		const command: Record<string, unknown> = { id, type: "prompt", message };
		if (behavior) command.streamingBehavior = behavior;

		runtime.promptRequests.set(id, { clientMessageId });
		proc.stdin.write(`${JSON.stringify(command)}\n`, "utf8");
		this.resetIdleTimer(runtime);
		return true;
	}

	abortSession(sessionId: string, clearQueue = true): boolean {
		const runtime = this.sessions.get(sessionId);
		if (!runtime?.proc) return false;
		this.touch(runtime);
		if (clearQueue) {
			// Mirrors interactive Esc: drain the queue so the session really stops.
			void this.request(runtime, { type: "clear_queue" }, 5_000)
				.then((data) => {
					this.emitSessionEvent(runtime, {
						type: "notice",
						kind: "queue_cleared",
						steering: data.steering ?? [],
						followUp: data.followUp ?? []
					});
				})
				.catch(() => undefined);
		}
		this.resetIdleTimer(runtime);
		return this.sendCommand(runtime, { type: "abort" });
	}

	async getMessages(sessionId: string): Promise<Record<string, unknown>[]> {
		const runtime = this.sessions.get(sessionId);
		if (!runtime) throw new Error(`Unknown session: ${sessionId}`);
		this.touch(runtime);
		const data = await this.request(runtime, { type: "get_messages" }, 30_000);
		const messages = Array.isArray(data.messages) ? (data.messages as Record<string, unknown>[]) : [];
		return messages;
	}

	getMissedEvents(sessionId: string, lastSeq: number): Record<string, unknown> {
		const runtime = this.sessions.get(sessionId);
		if (!runtime) {
			return { sessionId, latestSeq: 0, isAgentRunning: false, events: [] };
		}
		const events = runtime.eventHistory.filter((entry) => entry.seq > lastSeq).map((entry) => entry.payload);
		return {
			sessionId,
			latestSeq: runtime.seqCounter,
			isAgentRunning: runtime.isAgentRunning,
			processAlive: runtime.proc !== null && !runtime.exited,
			events
		};
	}

	/** Oldest seq still replayable; a client below this must reload messages. */
	getOldestReplaySeq(sessionId: string): number {
		const runtime = this.sessions.get(sessionId);
		const first = runtime?.eventHistory[0];
		return first?.seq ?? 0;
	}

	/**
	 * True when the replay buffer cannot vouch for the gap after `lastSeq`.
	 *
	 * Two cases, both invisible to a naive `oldestSeq > lastSeq` check:
	 *  - the "buffer empty" case (`oldestSeq === 0` after a bridge restart), and
	 *  - the "buffer never dropped anything but started later" case.
	 * Seq counters are per-process and restart at 0, so a cursor from a previous
	 * bridge lifetime is meaningless: replay from scratch instead of silently
	 * returning nothing.
	 */
	needsHistoryReload(sessionId: string, lastSeq: number): boolean {
		if (lastSeq <= 0) return false;
		const runtime = this.sessions.get(sessionId);
		if (!runtime) return true;
		const oldest = runtime.eventHistory[0]?.seq ?? 0;
		if (oldest === 0) return true;
		// `oldest > lastSeq + 1` means the events in between were evicted.
		if (oldest > lastSeq + 1) return true;
		// A cursor ahead of what we ever produced belongs to another lifetime.
		return lastSeq > runtime.seqCounter;
	}

	// ------------------------------------------------------------------- clients

	attachClient(ws: ClientSink) {
		this.clients.add(ws);
		ws.send(JSON.stringify({ type: "sessions_snapshot", sessions: this.getSessionSummaries() }));
		ws.on?.("close", () => this.clients.delete(ws));
		ws.on?.("error", () => this.clients.delete(ws));
	}

	detachClient(ws: ClientSink) {
		this.clients.delete(ws);
	}

	/** Kill every child process; used by tests and by shutdown handlers. */
	async shutdown(): Promise<void> {
		if (this.shuttingDown) return;
		this.shuttingDown = true;
		const exits: Promise<void>[] = [];
		for (const runtime of this.sessions.values()) {
			const proc = runtime.proc;
			if (!proc) continue;
			exits.push(
				new Promise<void>((resolve) => {
					proc.once("exit", () => resolve());
					this.stopProcess(runtime, "bridge_shutdown");
					setTimeout(resolve, this.options.shutdownGraceMs + 6000).unref?.();
				})
			);
		}
		await Promise.all(exits);
	}
}
