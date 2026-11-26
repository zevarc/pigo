import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { decodeSessionDir, describeSessionFile, listSessionFiles } from "../src/sessionIndex.js";

function makeStore() {
	const root = mkdtempSync(path.join(tmpdir(), "pi-sessions-"));
	const dir = path.join(root, "--Users-me-projects-api--");
	mkdirSync(dir, { recursive: true });

	const header = {
		type: "session",
		version: 3,
		id: "01a215bb",
		timestamp: "2026-11-26T02:41:58.114Z",
		cwd: "/Users/me/projects/api"
	};
	const body = [
		JSON.stringify(header),
		JSON.stringify({ type: "message", id: "a1", parentId: null, message: { role: "system", content: "" } }),
		JSON.stringify({
			type: "message",
			id: "a2",
			parentId: "a1",
			message: { role: "user", content: [{ type: "text", text: "  fix the   flaky test  " }] }
		})
	].join("\n");
	const file = path.join(dir, "2026-11-26T02-41-58-114Z_01a215bb.jsonl");
	writeFileSync(file, `${body}\n`);
	return { root, file };
}

test("decodes pi's session directory names", () => {
	assert.equal(decodeSessionDir("--Users-me-projects-api--"), "/Users/me/projects/api");
	// pi collapses every separator into one dash, so this is inherently lossy.
	assert.equal(decodeSessionDir("--Users-me-Labs-pigo--"), "/Users/me/Labs/pigo");
	assert.equal(decodeSessionDir("not-a-session-dir"), null);
	assert.equal(decodeSessionDir("----"), null);
});

test("reads the header and the first user message without the full file", () => {
	const { file } = makeStore();
	const info = describeSessionFile(file, "01a215bb", 1, 64 * 1024)!;
	assert.equal(info.id, "01a215bb");
	assert.equal(info.cwd, "/Users/me/projects/api");
	// Whitespace in the preview is collapsed so a picker row stays one line.
	assert.equal(info.preview, "fix the flaky test");
	assert.ok(info.createdAt > 0);
});

test("lists only .jsonl files and sorts newest first", () => {
	const { root } = makeStore();
	const rows = listSessionFiles({ root, scanBytes: 64 * 1024, limit: 10 });
	assert.equal(rows.length, 1);
	assert.equal(rows[0]!.id, "01a215bb");
});

test("a truncated head still yields the header", () => {
	const { file } = makeStore();
	// 40 bytes only reaches into the header; the entry must still be listed.
	const info = describeSessionFile(file, "01a215bb", 1, 40);
	assert.equal(info?.id, "01a215bb");
});

test("a file without a session header is skipped", () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-sessions-"));
	const dir = path.join(root, "--Users-me-x--");
	mkdirSync(dir, { recursive: true });
	writeFileSync(path.join(dir, "2026-01-01T00-00-00-000Z_broken.jsonl"), '{"type":"message"}\n');
	assert.deepEqual(listSessionFiles({ root, scanBytes: 4096, limit: 10 }), []);
});

test("timestamps are integers, not fractional mtimes", () => {
	const { file } = makeStore();
	const info = describeSessionFile(file, "01a215bb", Math.round(Date.now()), 64 * 1024)!;
	// mtimeMs is fractional; a client parsing this as an integer would get 0.
	assert.ok(Number.isInteger(info.updatedAt), `updatedAt must be an integer: ${info.updatedAt}`);
});

test("a missing store is not fatal", () => {
	assert.deepEqual(
		listSessionFiles({ root: path.join(tmpdir(), "does-not-exist-xyz"), scanBytes: 4096, limit: 10 }),
		[]
	);
});
