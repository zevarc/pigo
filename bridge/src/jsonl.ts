/**
 * Strict JSONL framing for the pi RPC protocol.
 *
 * Splits only on LF (0x0a) at the byte level: Node's `readline` also treats
 * U+2028 / U+2029 as line breaks, and those are legal inside JSON strings.
 */
export class JsonlFramer {
	private buffer: Buffer = Buffer.alloc(0);

	/** Appends a chunk and returns every record completed by it. */
	push(chunk: Buffer): string[] {
		const records: string[] = [];
		let buffer = this.buffer.length > 0 ? Buffer.concat([this.buffer, chunk]) : chunk;

		let index: number;
		while ((index = buffer.indexOf(0x0a)) !== -1) {
			let end = index;
			// Strip an optional CR of a CRLF pair.
			if (end > 0 && buffer[end - 1] === 0x0d) {
				end -= 1;
			}
			const line = buffer.subarray(0, end).toString("utf8").trim();
			if (line.length > 0) {
				records.push(line);
			}
			buffer = buffer.subarray(index + 1);
		}

		this.buffer = Buffer.from(buffer);
		return records;
	}

	/** Unsent remainder, for diagnostics. */
	get rest(): string {
		return this.buffer.toString("utf8");
	}
}
