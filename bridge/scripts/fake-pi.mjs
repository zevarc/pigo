#!/usr/bin/env node
/**
 * Minimal stand-in for `pi --mode rpc`, used by the Bridge tests.
 *
 * Behaviour mirrors the documented protocol (docs/rpc.md, docs/json.md,
 * docs/rpc-commands.md) closely enough to exercise framing, streaming,
 * tool lifecycles, prompt rejection and aborts without a model call.
 *
 * Env:
 *   FAKE_PI_RECORD  append every received command to this file as JSONL
 *   FAKE_PI_UNICODE emit a U+2028/U+2029/CRLF torture record on prompt
 */
import { appendFileSync } from "node:fs";

const RECORD = process.env.FAKE_PI_RECORD;

let buffer = Buffer.alloc(0);
let seq = 0;

function out(record) {
	seq++;
	process.stdout.write(`${JSON.stringify(record)}\n`);
}

function record(command) {
	if (!RECORD) return;
	appendFileSync(RECORD, `${JSON.stringify(command)}\n`);
}

let streaming = false;
let abortRequested = false;
let pendingDialog = null;

function respond(command, data, success = true, error) {
	out({
		id: command.id,
		type: "response",
		command: command.type,
		success,
		...(data === undefined ? {} : { data }),
		...(error ? { error } : {})
	});
}

function sleep(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

const script = {
	async prompt(command) {
		if (String(command.message ?? "").startsWith("/dialog")) {
			respond(command, { disposition: "handled" });
			const id = `dlg-${Date.now()}`;
			out({
				type: "extension_ui_request",
				id,
				method: "select",
				title: "Pick one",
				options: ["first", "second"]
			});
			pendingDialog = id;
			return;
		}
		if (String(command.message ?? "").startsWith("REJECT")) {
			respond(command, undefined, false, "rejected by test double");
			return;
		}
		if (streaming) {
			if (!command.streamingBehavior) {
				respond(command, undefined, false, "Agent is streaming; specify streamingBehavior");
				return;
			}
			respond(command, { disposition: "queued" });
			return;
		}
		respond(command, { disposition: "started" });

		streaming = true;
		abortRequested = false;
		const message = String(command.message ?? "");

		out({ type: "agent_start" });
		out({ type: "turn_start" });
		out({
			type: "message_start",
			message: { role: "user", content: message, timestamp: Date.now() }
		});
		out({
			type: "message_end",
			message: { role: "user", content: message, timestamp: Date.now() }
		});
		out({
			type: "message_start",
			message: { role: "assistant", content: [], stopReason: "pending", id: "a1" }
		});

		const chunks = ["Hel", "lo", " from", " fake", " pi"];
		for (const delta of chunks) {
			if (abortRequested) break;
			out({
				type: "message_update",
				assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta }
			});
			await sleep(15);
		}

		if (abortRequested) {
			out({
				type: "message_end",
				message: {
					role: "assistant",
					id: "a1",
					stopReason: "aborted",
					content: [{ type: "text", text: "Hello from fake pi" }]
				}
			});
			out({ type: "turn_end", message: {}, toolResults: [] });
			out({ type: "agent_end", messages: [], willRetry: false });
			out({ type: "agent_settled", aborted: true });
			streaming = false;
			return;
		}

		out({ type: "turn_end", message: {}, toolResults: [] });

		// Tool lifecycle, including a partial result update.
		const toolCallId = `call_${seq}`;
		out({
			type: "tool_execution_start",
			toolCallId,
			toolName: "bash",
			args: { command: "git status --short" }
		});
		out({
			type: "tool_execution_update",
			toolCallId,
			toolName: "bash",
			args: { command: "git status --short" },
			partialResult: { content: [{ type: "text", text: "partial" }] }
		});
		await sleep(20);
		if (abortRequested) {
			out({ type: "agent_end", messages: [], willRetry: false });
			out({ type: "agent_settled", aborted: true });
			streaming = false;
			return;
		}
		out({
			type: "tool_execution_end",
			toolCallId,
			toolName: "bash",
			result: {
				content: [
					{ type: "text", text: "line with \u2028 separator and \u2029 paragraph" },
					{ type: "text", text: "second\u2028chunk" }
				],
				details: {}
			},
			isError: false,
			durationMs: 42
		});

		out({
			type: "message_end",
			message: {
				role: "assistant",
				id: "a1",
				stopReason: "stop",
				content: [{ type: "text", text: "Hello from fake pi" }]
			}
		});

		// A retry-ish second low-level run to prove agent_end != settled.
		out({ type: "agent_end", messages: [], willRetry: false });
		await sleep(10);
		out({ type: "agent_start" });
		out({ type: "turn_start" });
		out({
			type: "message_start",
			message: { role: "assistant", content: [], stopReason: "pending", id: "a2" }
		});
		out({
			type: "message_update",
			assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: " second run" }
		});
		out({
			type: "message_end",
			message: {
				role: "assistant",
				id: "a2",
				stopReason: "stop",
				content: [{ type: "text", text: " second run" }]
			}
		});
		out({ type: "turn_end", message: {}, toolResults: [] });
		out({ type: "agent_end", messages: [], willRetry: false });
		out({ type: "agent_settled", aborted: false });
		streaming = false;
	},

	async abort(command) {
		abortRequested = true;
		respond(command, {});
	},

	async get_state(command) {
		respond(command, {
			sessionId: "fake",
			model: { id: "fake/model" },
			isStreaming: streaming,
			messageCount: 2
		});
	},

	async get_messages(command) {
		respond(command, {
			messages: [
				{ role: "user", content: "earlier question", timestamp: 1 },
				{
					role: "assistant",
					content: [{ type: "text", text: "earlier answer" }],
					timestamp: 2
				}
			]
		});
	},

	async clear_queue(command) {
		respond(command, { steering: [], followUp: [] });
	},

	async extension_ui_response(command) {
		if (command.id !== pendingDialog) {
			out({ type: "notice", kind: "stale_ui_response", id: command.id });
			return;
		}
		pendingDialog = null;
		const answered = command.cancelled ? "cancelled" : String(command.value ?? command.confirmed);
		process.stderr.write(`[fake-pi] dialog answered: ${answered}\n`);
		out({
			type: "extension_ui_request",
			id: "note-1",
			method: "notify",
			message: `dialog answered: ${answered}`
		});
	},

	async set_session_name(command) {
		respond(command, {});
		out({ type: "session_info_changed", name: command.name });
	}
};

async function main() {
	process.stdin.on("data", (chunk) => {
		buffer = Buffer.concat([buffer, chunk]);
		let index;
		while ((index = buffer.indexOf(0x0a)) !== -1) {
			const line = buffer.subarray(0, index).toString("utf8").trim();
			buffer = buffer.subarray(index + 1);
			if (!line) continue;
			let command;
			try {
				command = JSON.parse(line);
			} catch {
				out({ type: "response", command: "parse", success: false, error: "bad json" });
				continue;
			}
			record(command);
			const handler = script[command.type];
			if (!handler) {
				respond(command, undefined, false, `unknown command: ${command.type}`);
				continue;
			}
			void handler(command);
		}
	});

	process.stdin.on("end", () => {
		process.exit(0);
	});

	// Startup chatter, like the real extensions do.
	out({ type: "extension_ui_request", id: "ui-1", method: "setStatus", statusKey: "fake" });
}

void main();
