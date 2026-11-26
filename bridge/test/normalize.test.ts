import assert from "node:assert/strict";
import test from "node:test";
import { normalizePiEvent, summarizeToolCall, toolResultText } from "../src/normalize.js";

const context = () => ({ toolCalls: new Map() });

test("agent_start marks the run as started", () => {
	const result = normalizePiEvent({ type: "agent_start" }, context());
	assert.equal(result.runStarted, true);
	assert.deepEqual(result.event, { type: "agent_start" });
});

test("agent_end does not settle the run", () => {
	const result = normalizePiEvent({ type: "agent_end", willRetry: false }, context());
	assert.equal(result.runSettled, undefined);
	assert.equal(result.event?.type, "agent_end");
});

test("agent_settled settles the run", () => {
	const result = normalizePiEvent({ type: "agent_settled", aborted: false }, context());
	assert.equal(result.runSettled, true);
	assert.deepEqual(result.event, { type: "agent_settled", aborted: false });
});

test("text_delta becomes message_delta", () => {
	const result = normalizePiEvent(
		{ type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "hi" } },
		context()
	);
	assert.deepEqual(result.event, { type: "message_delta", contentIndex: 0, delta: "hi" });
});

test("thinking_delta stays distinct from text", () => {
	const result = normalizePiEvent(
		{ type: "message_update", assistantMessageEvent: { type: "thinking_delta", contentIndex: 1, delta: "hm" } },
		context()
	);
	assert.deepEqual(result.event, { type: "thinking_delta", contentIndex: 1, delta: "hm" });
});

test("tool lifecycle produces start, progress and end with a summary", () => {
	const ctx = context();
	const start = normalizePiEvent(
		{ type: "tool_execution_start", toolCallId: "c1", toolName: "bash", args: { command: "git status" } },
		ctx
	);
	assert.deepEqual(start.event, {
		type: "tool_start",
		toolCallId: "c1",
		name: "bash",
		summary: "git status",
		args: { command: "git status" }
	});

	const progress = normalizePiEvent(
		{
			type: "tool_execution_update",
			toolCallId: "c1",
			toolName: "bash",
			partialResult: { content: [{ type: "text", text: "partial" }] }
		},
		ctx
	);
	assert.equal(progress.event?.type, "tool_progress");
	assert.equal(progress.event?.output, "partial");

	const end = normalizePiEvent(
		{
			type: "tool_execution_end",
			toolCallId: "c1",
			toolName: "bash",
			result: { content: [{ type: "text", text: "done" }] },
			isError: false,
			durationMs: 12
		},
		ctx
	);
	assert.equal(end.event?.type, "tool_end");
	assert.equal(end.event?.summary, "git status");
	assert.equal(end.event?.output, "done");
	assert.equal(end.event?.durationMs, 12);
	assert.equal(ctx.toolCalls.get("c1")?.status, "ok");
});

test("tool errors are flagged", () => {
	const ctx = context();
	normalizePiEvent({ type: "tool_execution_start", toolCallId: "c2", toolName: "read", args: { path: "a.txt" } }, ctx);
	const end = normalizePiEvent(
		{
			type: "tool_execution_end",
			toolCallId: "c2",
			toolName: "read",
			result: { content: [{ type: "text", text: "ENOENT" }] },
			isError: true
		},
		ctx
	);
	assert.equal(end.event?.isError, true);
	assert.equal(ctx.toolCalls.get("c2")?.status, "error");
});

test("command responses and extension UI records are not forwarded as session events", () => {
	assert.equal(normalizePiEvent({ type: "response", success: true }, context()).event, null);
	assert.equal(
		normalizePiEvent({ type: "extension_ui_request", method: "setStatus" }, context()).event,
		null
	);
});

test("assistant message_end carries the final text so the client can replace deltas", () => {
	const result = normalizePiEvent(
		{
			type: "message_end",
			message: { role: "assistant", id: "a1", stopReason: "stop", content: [{ type: "text", text: "final" }] }
		},
		context()
	);
	assert.deepEqual(result.event, {
		type: "assistant_message_end",
		messageId: "a1",
		stopReason: "stop",
		text: "final"
	});
});

test("user message_end is not forwarded", () => {
	const result = normalizePiEvent(
		{ type: "message_end", message: { role: "user", content: "hi" } },
		context()
	);
	assert.equal(result.event, null);
});

test("tool summaries stay one line and are truncated", () => {
	assert.equal(summarizeToolCall("bash", { command: "ls\n-la" }), "ls -la");
	const long = summarizeToolCall("bash", { command: "x".repeat(500) });
	assert.ok(long.length <= 201);
	assert.ok(long.endsWith("…"));
});

test("tool result text joins content blocks", () => {
	assert.equal(
		toolResultText({ content: [{ type: "text", text: "a" }, { type: "text", text: "b" }] }),
		"a\nb"
	);
});
