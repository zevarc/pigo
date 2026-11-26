import { readdirSync, openSync, readSync, closeSync, statSync } from "node:fs";
import path from "node:path";

/**
 * Read-only index over pi's own session store.
 *
 * Resume is pi's feature, so the source of truth is `~/.pi/agent/sessions/`,
 * not the bridge's bookkeeping:
 *
 *   <sessionsRoot>/--<path with / : \\ -> ->--/<timestamp>_<id>.jsonl
 *
 * The store can be hundreds of megabytes (one of ours is 54 MB), so listing
 * never reads a whole file: the header and the first user message are pulled
 * from the first few kilobytes only.
 */

export interface SessionFileInfo {
	/** pi session id (the `<id>` part of the file name). */
	id: string;
	path: string;
	cwd: string;
	/** Last write time, used for sorting. */
	updatedAt: number;
	createdAt: number;
	/** Display name set via `/name`, when present. */
	name: string | null;
	/** First user message, trimmed for a picker row. */
	preview: string | null;
	sizeBytes: number;
}

export interface SessionIndexOptions {
	root: string;
	/** Give up on a file after reading this many bytes when scanning. */
	scanBytes: number;
	/** Cap the number of rows returned. */
	limit: number;
}

export function decodeSessionDir(dirName: string): string | null {
	if (!dirName.startsWith("--") || !dirName.endsWith("--")) return null;
	const body = dirName.slice(2, -2);
	if (body.length === 0) return null;
	// pi replaces the path separator, `:` and `\` with `-`, so a doubled
	// separator is the only unambiguous marker we have.
	return `/${body.replace(/-/g, "/")}`.replace(/\/+/g, "/");
}

/**
 * Reads at most `maxBytes` and returns only the lines it completed.
 *
 * The first line is always completed even when `maxBytes` cuts through it: a
 * session header is the only mandatory record, and a picker row without it is
 * useless.
 */
function readHeadLines(file: string, maxBytes: number): string[] {
	const fd = openSync(file, "r");
	try {
		let size = maxBytes;
		let buffer = Buffer.alloc(size);
		let read = readSync(fd, buffer, 0, size, 0);

		// Extend until the header line is complete, capped so one pathological
		// file cannot turn a listing into a full read.
		const headerCap = Math.min(Math.max(maxBytes, 64 * 1024), 1024 * 1024);
		while (read === size && size < headerCap && buffer.subarray(0, read).indexOf(0x0a) === -1) {
			size = Math.min(size * 2, headerCap);
			buffer = Buffer.alloc(size);
			read = readSync(fd, buffer, 0, size, 0);
		}

		const text = buffer.subarray(0, read).toString("utf8");
		const lines = text.split("\n");
		// The last line is cut mid-object unless the file ended on a newline.
		if (read === size) lines.pop();
		return lines.filter((line) => line.trim().length > 0);
	} finally {
		closeSync(fd);
	}
}

function firstUserText(message: Record<string, unknown>): string | null {
	const content = message.content;
	const parts: string[] = [];
	if (typeof content === "string") {
		parts.push(content);
	} else if (Array.isArray(content)) {
		for (const block of content) {
			if (block && typeof block === "object") {
				const entry = block as Record<string, unknown>;
				if (entry.type === "text" && typeof entry.text === "string") parts.push(entry.text);
			}
		}
	}
	const text = parts.join(" ").replace(/\s+/g, " ").trim();
	if (!text) return null;
	return text.length > 120 ? `${text.slice(0, 120)}…` : text;
}

export function describeSessionFile(
	file: string,
	id: string,
	updatedAt: number,
	scanBytes: number
): SessionFileInfo | null {
	let lines: string[];
	try {
		lines = readHeadLines(file, scanBytes);
	} catch {
		return null;
	}

	let cwd = "";
	let createdAt = updatedAt;
	let name: string | null = null;
	let preview: string | null = null;

	for (const line of lines) {
		let record: Record<string, unknown>;
		try {
			record = JSON.parse(line) as Record<string, unknown>;
		} catch {
			continue;
		}
		const type = String(record.type ?? "");
		if (type === "session") {
			cwd = String(record.cwd ?? "");
			name = typeof record.name === "string" ? record.name : null;
			const stamp = Date.parse(String(record.timestamp ?? ""));
			if (!Number.isNaN(stamp)) createdAt = stamp;
			continue;
		}
		if (type === "session_info_changed" && typeof record.name === "string") {
			name = record.name;
			continue;
		}
		if (type === "message" && preview === null) {
			const message = record.message as Record<string, unknown> | undefined;
			if (message?.role === "user") preview = firstUserText(message);
		}
	}

	if (!cwd) return null;
	let sizeBytes = 0;
	try {
		sizeBytes = statSync(file).size;
	} catch {
		/* keep zero */
	}
	return { id, path: file, cwd, updatedAt, createdAt, name, preview, sizeBytes };
}

export function listSessionFiles(options: SessionIndexOptions): SessionFileInfo[] {
	const { root, scanBytes, limit } = options;
	let dirs: string[];
	try {
		dirs = readdirSync(root);
	} catch {
		return [];
	}

	const results: SessionFileInfo[] = [];
	for (const dir of dirs) {
		if (!dir.startsWith("--")) continue;
		const full = path.join(root, dir);
		let entries: string[];
		try {
			entries = readdirSync(full);
		} catch {
			continue;
		}
		for (const entry of entries) {
			if (!entry.endsWith(".jsonl")) continue;
			const match = /^(.+)_([^_]+)\.jsonl$/.exec(entry);
			const id = match?.[2] ?? entry.replace(/\.jsonl$/, "");
			const file = path.join(full, entry);
			let updatedAt = 0;
			try {
				// mtimeMs is fractional; clients parse this as an integer.
				updatedAt = Math.round(statSync(file).mtimeMs);
			} catch {
				continue;
			}
			const described = describeSessionFile(file, id, updatedAt, scanBytes);
			if (described) results.push(described);
		}
	}

	results.sort((a, b) => b.updatedAt - a.updatedAt);
	return results.slice(0, limit);
}
