import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { MultiSessionPiBridge, type Logger } from "../src/sessionManager.js";

const fakePi = path.resolve(import.meta.dirname, "../scripts/fake-pi");

const quiet: Logger = { info: () => {}, warn: () => {}, error: () => {} };

function makeBridge(overrides: Partial<Parameters<typeof MultiSessionPiBridge>[0]> = {}) {
	const root = mkdtempSync(path.join(tmpdir(), "bridge-root-"));
	const data = mkdtempSync(path.join(tmpdir(), "bridge-data-"));
	const recordFile = path.join(data, "commands.jsonl");
	writeFileSync(recordFile, "");

	const bridge = new MultiSessionPiBridge({
		cliPath: fakePi,
		cliArgs: [],
		allowedRoot: root,
		dataDir: data,
		piSessionsRoot: path.join(data, "pi-sessions"),
		extraWorkspaces: [],
		idleTimeoutMs: 0,
		historyLimit: 500,
		shutdownGraceMs: 1000,
		env: { ...process.env, FAKE_PI_RECORD: recordFile },
		logger: quiet,
		...overrides
	});

	return { bridge, root, data, recordFile };
}

function readCommands(file: string): Record<string, unknown>[] {
	try {
		return readFileSync(file, "utf8")
			.split("\n")
			.filter(Boolean)
			.map((line: string) => JSON.parse(line) as Record<string, unknown>);
	} catch {
		return [];
	}
}

async function waitFor(predicate: () => boolean, timeoutMs = 4000): Promise<void> {
	const started = Date.now();
	while (!predicate()) {
		if (Date.now() - started > timeoutMs) throw new Error("waitFor timed out");
		await new Promise((resolve) => setTimeout(resolve, 20));
	}
}

test("creates sessions only inside the allowed root", async () => {
	const { bridge, root, data } = makeBridge();
	try {
		const meta = bridge.createSession(".", "root session");
		assert.equal(meta.cwd, root);

		const nested = path.join(root, "projects", "api");
		mkdirSync(nested, { recursive: true });
		const nestedMeta = bridge.createSession("projects/api");
		assert.equal(nestedMeta.cwd, nested);
		assert.equal(nestedMeta.title, "api");

		assert.throws(() => bridge.createSession("../"), /outside allowed root/);
		assert.throws(() => bridge.createSession("/etc"), /outside allowed root/);
		assert.throws(() => bridge.createSession("does-not-exist"), /does not exist/);

		// Persisted for the next boot.
		const saved = JSON.parse(readFileSync(path.join(data, "sessions.json"), "utf8"));
		assert.equal(saved.length, 2);
	} finally {
		await bridge.shutdown();
	}
});

test("streams a full run: deltas, tool lifecycle, and settle after retries", async () => {
	const { bridge } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		const events: Record<string, unknown>[] = [];
		const fakeClient = {
			readyState: 1,
			send: (raw: string) => events.push(JSON.parse(raw))
		};
		bridge.attachClient(fakeClient as never);

		assert.equal(bridge.sendPrompt(meta.id, "hello"), true);

		await waitFor(() => bridge.getSession(meta.id)?.isAgentRunning === true);
		await waitFor(() => bridge.getSession(meta.id)?.isAgentRunning === false, 6000);

		const types = events.map((event) => event.type);
		assert.ok(types.includes("agent_start"));
		assert.ok(types.includes("message_delta"), "text deltas must be translated");
		assert.ok(types.includes("tool_start"));
		assert.ok(types.includes("tool_progress"));
		assert.ok(types.includes("tool_end"));
		assert.ok(types.includes("assistant_message_end"));

		// The first agent_end must not clear the running flag: a second run follows.
		const settled = events.filter((event) => event.type === "agent_settled");
		assert.equal(settled.length, 1, "only agent_settled ends the run");
		assert.equal(settled[0]!.aborted, false);

		const deltas = events.filter((event) => event.type === "message_delta").map((event) => event.delta);
		assert.deepEqual(deltas.filter((delta) => delta !== ""), ["Hel", "lo", " from", " fake", " pi", " second run"]);

		const toolEnd = events.find((event) => event.type === "tool_end")!;
		assert.equal(toolEnd.isError, false);
		assert.match(String(toolEnd.output), /line with \u2028 separator/);

		// seq is strictly increasing so clients can dedupe/replay.
		const seqs = events.map((event) => Number(event.seq));
		assert.deepEqual(seqs, seqs.slice().sort((a, b) => a - b));
		assert.equal(new Set(seqs).size, seqs.length);
	} finally {
		await bridge.shutdown();
	}
});

test("replays only the events a client missed", async () => {
	const { bridge } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		bridge.sendPrompt(meta.id, "hello");
		await waitFor(() => (bridge.getSession(meta.id)?.latestSeq ?? 0) > 5);
		await waitFor(() => bridge.getSession(meta.id)?.isAgentRunning === false, 6000);

		const cursor = 4;
		const synced = bridge.getMissedEvents(meta.id, cursor) as {
			events: { seq: number }[];
			latestSeq: number;
		};
		assert.ok(synced.events.length > 0);
		assert.ok(synced.events.every((event) => event.seq > cursor));
		assert.equal(synced.events.at(-1)!.seq, synced.latestSeq);

		const empty = bridge.getMissedEvents(meta.id, synced.latestSeq) as { events: unknown[] };
		assert.equal(empty.events.length, 0);

		const unknown = bridge.getMissedEvents("nope", 0) as { latestSeq: number; events: unknown[] };
		assert.deepEqual(unknown, {
			sessionId: "nope",
			latestSeq: 0,
			isAgentRunning: false,
			events: []
		});
	} finally {
		await bridge.shutdown();
	}
});

test("a dialog request is forwarded and the answer reaches pi", async () => {
	const { bridge } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		const events: Record<string, unknown>[] = [];
		bridge.attachClient({
			readyState: 1,
			send: (raw: string) => events.push(JSON.parse(raw))
		} as never);

		bridge.sendPrompt(meta.id, "/dialog please");
		await waitFor(() => events.some((event) => event.type === "ui_request"));

		const request = events.find((event) => event.type === "ui_request")!;
		assert.equal(request.method, "select");
		assert.deepEqual(request.options, ["first", "second"]);

		assert.equal(bridge.respondToUi(meta.id, String(request.requestId), "second"), true);
		await waitFor(() => events.some((event) => event.kind === "extension_notify"));
		assert.match(String(events.find((event) => event.kind === "extension_notify")!.message), /second/);

		// Replaying a stale id must be rejected, not silently accepted.
		assert.equal(bridge.respondToUi(meta.id, String(request.requestId), "second"), false);
	} finally {
		await bridge.shutdown();
	}
});

test("a dialog left hanging by a disconnecting client is cancelled", async () => {
	const { bridge, recordFile } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		const events: Record<string, unknown>[] = [];
		const client = {
			readyState: 1,
			send: (raw: string) => events.push(JSON.parse(raw))
		};
		bridge.attachClient(client as never);

		bridge.sendPrompt(meta.id, "/dialog please");
		await waitFor(() => events.some((event) => event.type === "ui_request"));

		// The last client is gone: nobody can answer, so the dialog must be
		// cancelled on pi's side, otherwise it blocks until its own timeout and
		// the session never settles.
		bridge.detachClientAndCancelUi(client as never);
		await waitFor(() =>
			readCommands(recordFile).some(
				(command) => command.type === "extension_ui_response" && command.cancelled === true
			)
		);
	} finally {
		await bridge.shutdown();
	}
});

test("a cursor from a previous bridge lifetime asks for a history reload", async () => {
	const { bridge } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		// No events ever produced: any positive cursor is unvouchable.
		assert.equal(bridge.needsHistoryReload(meta.id, 7), true);
		assert.equal(bridge.needsHistoryReload(meta.id, 0), false, "a fresh client needs no reload");

		bridge.sendPrompt(meta.id, "hello");
		await waitFor(() => (bridge.getSession(meta.id)?.latestSeq ?? 0) > 3);
		const latest = bridge.getSession(meta.id)!.latestSeq;
		// Cursor inside the buffer and behind the counter: plain replay is enough.
		assert.equal(bridge.needsHistoryReload(meta.id, latest - 1), false);
		// Cursor ahead of anything we produced: belongs to another lifetime.
		assert.equal(bridge.needsHistoryReload(meta.id, latest + 50), true);
	} finally {
		await bridge.shutdown();
	}
});

test("evicted window asks for a history reload", async () => {
	const { bridge } = makeBridge({ historyLimit: 4 });
	try {
		const meta = bridge.createSession(".");
		bridge.sendPrompt(meta.id, "hello");
		await waitFor(() => (bridge.getSession(meta.id)?.latestSeq ?? 0) > 6);
		// seq 1..2 have been evicted by now, so a cursor of 1 has a hole after it.
		assert.equal(bridge.needsHistoryReload(meta.id, 1), true);
		const latest = bridge.getSession(meta.id)!.latestSeq;
		assert.equal(bridge.needsHistoryReload(meta.id, latest), false);
	} finally {
		await bridge.shutdown();
	}
});

test("a prompt queued during a run is acknowledged", async () => {
	const { bridge } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		const events: Record<string, unknown>[] = [];
		bridge.attachClient({
			readyState: 1,
			send: (raw: string) => events.push(JSON.parse(raw))
		} as never);

		bridge.sendPrompt(meta.id, "first", "msg-1");
		await waitFor(() => bridge.getSession(meta.id)?.isAgentRunning === true);
		bridge.sendPrompt(meta.id, "second", "msg-2", "followUp");

		await waitFor(() => events.some((event) => event.kind === "prompt_accepted" && event.disposition === "queued"));
		const accepted = events.find((event) => event.kind === "prompt_accepted" && event.clientMessageId === "msg-2");
		assert.ok(accepted);
		assert.equal(accepted!.clientMessageId, "msg-2");
	} finally {
		await bridge.shutdown();
	}
});

test("a rejected prompt surfaces as an error event", async () => {
	const { bridge } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		const events: Record<string, unknown>[] = [];
		bridge.attachClient({
			readyState: 1,
			send: (raw: string) => events.push(JSON.parse(raw))
		} as never);

		bridge.sendPrompt(meta.id, "REJECT please", "msg-9");
		await waitFor(() => events.some((event) => event.code === "prompt_rejected"));
		const failure = events.find((event) => event.code === "prompt_rejected")!;
		assert.match(String(failure.message), /rejected by test double/);
		assert.equal(failure.clientMessageId, "msg-9");
	} finally {
		await bridge.shutdown();
	}
});

test("abort settles the run and drains the queue", async () => {
	const { bridge, recordFile } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		bridge.sendPrompt(meta.id, "long task");
		await waitFor(() => bridge.getSession(meta.id)?.isAgentRunning === true);

		assert.equal(bridge.abortSession(meta.id), true);
		await waitFor(() => bridge.getSession(meta.id)?.isAgentRunning === false, 6000);

		const commands = readCommands(recordFile).map((command) => command.type);
		assert.ok(commands.includes("abort"));
		assert.ok(commands.includes("clear_queue"), "abort should drain queued messages like Esc");
	} finally {
		await bridge.shutdown();
	}
});

test("idle eviction stops the process and revives it on the next prompt", async () => {
	const { bridge } = makeBridge({ idleTimeoutMs: 120 });
	try {
		const meta = bridge.createSession(".");
		bridge.sendPrompt(meta.id, "hello");
		await waitFor(() => bridge.getSession(meta.id)?.processAlive === true);
		await waitFor(() => bridge.getSession(meta.id)?.isAgentRunning === false, 6000);

		await waitFor(() => bridge.getSession(meta.id)?.processAlive === false, 6000);
		assert.equal(bridge.getSession(meta.id)?.processAlive, false);

		// A later prompt must lazily respawn the child.
		assert.equal(bridge.sendPrompt(meta.id, "again"), true);
		await waitFor(() => bridge.getSession(meta.id)?.processAlive === true);
	} finally {
		await bridge.shutdown();
	}
});

test("get_messages proxies pi state", async () => {
	const { bridge } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		const messages = await bridge.getMessages(meta.id);
		assert.equal(messages.length, 2);
		assert.equal(messages[1]!.role, "assistant");
	} finally {
		await bridge.shutdown();
	}
});
test("a new session does not pin a bridge-private session directory", async () => {
	// `--session-dir` used to send every phone-created session into
	// bridge/data/pi-sessions, where pi's own /resume could not see it. That made
	// the phone and desktop disagree about which sessions exist.
	//
	// Asserted against the spawn decision itself rather than fake-pi's argv, since
	// the fixture never sees how it was launched.
	const { bridge, data } = makeBridge();
	try {
		const meta = bridge.createSession(".");
		const privateDir = path.join(data, "sessions");

		bridge.sendPrompt(meta.id, "hi");
		await waitFor(() => bridge.getSession(meta.id)?.processAlive === true);

		// A session file appearing in bridge's own data dir is the exact bug being
		// fixed: that copy is invisible to pi's `/resume` on the desktop.
		const leaked = existsSync(privateDir)
			? readdirSync(privateDir, { recursive: true }).filter((f) => String(f).endsWith(".jsonl"))
			: [];
		assert.deepEqual(leaked, [], "bridge must not create its own session store");
	} finally {
		await bridge.shutdown();
	}
});

test("the session file pi reports is recorded for later resumes", async () => {
	const { bridge, data } = makeBridge();
	const piFile = path.join(data, "pi-store", "2026-01-01T00-00-00-000Z_s-abc.jsonl");
	bridge.options.env.FAKE_PI_SESSION_FILE = piFile;
	try {
		const meta = bridge.createSession(".");
		bridge.sendPrompt(meta.id, "hi");

		// Without this the next eviction would spawn a brand-new empty session
		// instead of reopening the conversation.
		await waitFor(() => bridge.getSession(meta.id)?.sessionFile === piFile, 6000);
		assert.equal(bridge.getSession(meta.id)?.sessionFile, piFile);

		const persisted = JSON.parse(readFileSync(path.join(data, "sessions.json"), "utf8")) as Record<string, unknown>[];
		assert.equal(persisted.find((s) => s.id === meta.id)?.sessionFile, piFile);
	} finally {
		await bridge.shutdown();
	}
});

