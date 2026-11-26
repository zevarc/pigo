/**
 * End-to-end smoke test against the real `pi` binary over the Bridge WebSocket.
 *
 *   BRIDGE_TOKEN=dev token=dev npm run build && npm run smoke
 *
 * Starts a throwaway bridge on an ephemeral port inside a temp workspace, then
 * drives one prompt through it and asserts the normalized event stream.
 */
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { WebSocket } from "ws";

const port = 18000 + Math.floor(Math.random() * 1000);
const token = "smoke-token-123456";
const root = mkdtempSync(path.join(tmpdir(), "bridge-smoke-"));
const data = path.join(root, ".bridge");

const child = spawn(process.execPath, ["dist/server.js"], {
	env: {
		...process.env,
		BRIDGE_PORT: String(port),
		BRIDGE_HOST: "127.0.0.1",
		BRIDGE_TOKEN: token,
		PI_ALLOWED_ROOT: root,
		BRIDGE_DATA_DIR: data,
		IDLE_TIMEOUT_MS: "60000"
	},
	stdio: ["ignore", "pipe", "pipe"]
});

child.stdout.on("data", (chunk: Buffer) => process.stdout.write(`[bridge] ${chunk}`));
child.stderr.on("data", (chunk: Buffer) => process.stderr.write(`[bridge:err] ${chunk}`));

const timeout = setTimeout(() => {
	console.error("smoke: timed out");
	child.kill("SIGKILL");
	process.exit(1);
}, 180_000);

function fail(message: string): never {
	console.error(`smoke: ${message}`);
	child.kill("SIGKILL");
	process.exit(1);
}

async function waitForHealth(deadlineMs = 20_000): Promise<void> {
	const started = Date.now();
	while (Date.now() - started < deadlineMs) {
		try {
			const response = await fetch(`http://127.0.0.1:${port}/health`);
			if (response.ok) return;
		} catch {
			/* not up yet */
		}
		await new Promise((resolve) => setTimeout(resolve, 100));
	}
	fail("bridge did not become healthy");
}

await waitForHealth();
const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${token}`);
const seen: string[] = [];
let sessionId = "";
let settled = false;

ws.on("open", () => {
	console.log("smoke: connected");
	ws.send(JSON.stringify({ type: "create_session", cwd: ".", title: "smoke" }));
});

ws.on("message", (raw) => {
	const message = JSON.parse(raw.toString("utf8")) as Record<string, unknown>;
	const type = String(message.type);
	if (type !== "sessions_snapshot") seen.push(type);
	if (type === "session_created") {
		sessionId = String((message.session as Record<string, unknown>).id);
		console.log(`smoke: session ${sessionId}`);
		ws.send(JSON.stringify({ type: "prompt", sessionId, message: "Reply with exactly: PONG" }));
	}
	if (type === "message_delta") process.stdout.write(String(message.delta ?? ""));
	if (type === "tool_start") console.log(`\nsmoke: tool ${String(message.name)} → ${String(message.summary)}`);
	if (type === "agent_settled" && !settled) {
		settled = true;
		console.log("");
		finish();
	}
	if (type === "error") fail(String(message.message));
});

function finish() {
	const required = ["agent_start", "message_delta", "agent_settled"];
	const missing = required.filter((type) => !seen.includes(type));
	if (missing.length > 0) fail(`missing events: ${missing.join(", ")} (saw ${seen.join(", ")})`);
	console.log(`smoke: ok — ${seen.length} events, session ${sessionId}`);
	ws.close();
	clearTimeout(timeout);
	child.kill("SIGTERM");
	setTimeout(() => process.exit(0), 500);
}

ws.on("error", (error) => fail(`websocket error: ${error.message}`));
