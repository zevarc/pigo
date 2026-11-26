import assert from "node:assert/strict";
import test from "node:test";
import { JsonlFramer } from "../src/jsonl.js";

test("splits records on LF only", () => {
	const framer = new JsonlFramer();
	const records = framer.push(Buffer.from('{"a":1}\n{"b":2}\n'));
	assert.deepEqual(records, ['{"a":1}', '{"b":2}']);
});

test("keeps a partial record buffered until its LF arrives", () => {
	const framer = new JsonlFramer();
	assert.deepEqual(framer.push(Buffer.from('{"a":')), []);
	assert.deepEqual(framer.push(Buffer.from("1}")), []);
	assert.deepEqual(framer.push(Buffer.from("\n")), ['{"a":1}']);
});

test("does not split on U+2028 / U+2029 inside JSON strings", () => {
	const framer = new JsonlFramer();
	const payload = JSON.stringify({ text: "a\u2028b\u2029c" });
	const records = framer.push(Buffer.from(`${payload}\n`));
	assert.equal(records.length, 1);
	assert.equal(JSON.parse(records[0]!).text, "a\u2028b\u2029c");
});

test("does not split on multi-byte UTF-8 boundaries", () => {
	const framer = new JsonlFramer();
	const payload = Buffer.from(`${JSON.stringify({ text: "家庭 Pi Agent 🚀" })}\n`, "utf8");
	// Feed one byte at a time.
	let records: string[] = [];
	for (const byte of payload) {
		records.push(...framer.push(Buffer.from([byte])));
	}
	assert.equal(records.length, 1);
	assert.equal(JSON.parse(records[0]!).text, "家庭 Pi Agent 🚀");
});

test("strips CRLF and splits a CR/LF pair across chunks", () => {
	const framer = new JsonlFramer();
	assert.deepEqual(framer.push(Buffer.from('{"a":1}\r')), []);
	assert.deepEqual(framer.push(Buffer.from('\n{"b":2}\r\n')), ['{"a":1}', '{"b":2}']);
});

test("ignores blank lines", () => {
	const framer = new JsonlFramer();
	assert.deepEqual(framer.push(Buffer.from('\n\n{"a":1}\n\n')), ['{"a":1}']);
});
