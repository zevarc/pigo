import type { ToolSnapshot } from "./protocol.js";

/**
 * Translates raw pi RPC session events (docs/json.md) into the Bridge wire
 * protocol the Android client consumes.
 *
 * The design doc's table assumed pi already speaks `message_delta` / `tool_start`
 * / `tool_end`; it does not. Everything below is that missing translation layer.
 */
export interface NormalizeContext {
	/** Assistant text accumulated per content block, so `message_delta` is not needed twice. */
	toolCalls: Map<string, ToolSnapshot>;
}

export interface Normalized {
	event: ({ type: string } & Record<string, unknown>) | null;
	/** Set when the run finished and the session should be considered idle. */
	runSettled?: boolean;
	runStarted?: boolean;
}

function truncate(text: string, max = 400): string {
	const single = text.replace(/\s+/g, " ").trim();
	return single.length > max ? `${single.slice(0, max)}…` : single;
}

/** One-line human summary for the collapsed tool card. */
export function summarizeToolCall(toolName: string, args: unknown): string {
	if (args && typeof args === "object") {
		const record = args as Record<string, unknown>;
		if (typeof record.command === "string") return truncate(record.command, 200);
		if (typeof record.path === "string") return truncate(record.path, 200);
		if (typeof record.pattern === "string") return truncate(record.pattern, 200);
		if (typeof record.query === "string") return truncate(record.query, 200);
	}
	try {
		return truncate(JSON.stringify(args ?? {}), 200);
	} catch {
		return toolName;
	}
}

/** Flattens tool result content blocks into displayable text. */
export function toolResultText(result: unknown): string {
	if (result == null) return "";
	if (typeof result === "string") return result;
	if (typeof result === "object") {
		const record = result as Record<string, unknown>;
		const content = record.content;
		if (Array.isArray(content)) {
			const parts: string[] = [];
			for (const block of content) {
				if (block && typeof block === "object") {
					const entry = block as Record<string, unknown>;
					if (typeof entry.text === "string") parts.push(entry.text);
					else if (entry.type) parts.push(`[${String(entry.type)}]`);
				}
			}
			if (parts.length > 0) return parts.join("\n");
		}
		try {
			return JSON.stringify(result, null, 2);
		} catch {
			return String(result);
		}
	}
	return String(result);
}

export function normalizePiEvent(raw: Record<string, unknown>, ctx: NormalizeContext): Normalized {
	const type = String(raw.type ?? "");

	switch (type) {
		case "agent_start":
			return { event: { type: "agent_start" }, runStarted: true };

		case "agent_end":
			// Low-level run boundary only: retries, compaction recovery, steering and
			// queued messages may still follow, so this does NOT mean "idle".
			return {
				event: { type: "agent_end", willRetry: raw.willRetry === true }
			};

		case "agent_settled":
			return {
				event: { type: "agent_settled", aborted: raw.aborted === true },
				runSettled: true
			};

		case "message_start": {
			const message = raw.message as Record<string, unknown> | undefined;
			if (message?.role === "assistant") {
				return { event: { type: "assistant_message_start", messageId: message.id } };
			}
			return { event: null };
		}

		case "message_end": {
			const message = raw.message as Record<string, unknown> | undefined;
			if (message?.role !== "assistant") return { event: null };
			return {
				event: {
					type: "assistant_message_end",
					messageId: message.id,
					stopReason: message.stopReason,
					text: assistantText(message)
				}
			};
		}

		case "message_update": {
			const update = raw.assistantMessageEvent as Record<string, unknown> | undefined;
			if (!update) return { event: null };
			const kind = String(update.type ?? "");
			const contentIndex = Number(update.contentIndex ?? 0);
			if (kind === "text_delta") {
				return {
					event: { type: "message_delta", contentIndex, delta: String(update.delta ?? "") }
				};
			}
			if (kind === "thinking_delta") {
				return {
					event: { type: "thinking_delta", contentIndex, delta: String(update.delta ?? "") }
				};
			}
			if (kind === "text_end") {
				return {
					event: { type: "message_delta", contentIndex, delta: "", content: String(update.content ?? "") }
				};
			}
			if (kind === "toolcall_end") {
				const call = update.toolCall as Record<string, unknown> | undefined;
				if (!call) return { event: null };
				const id = String(call.id ?? "");
				return {
					event: {
						type: "tool_card",
						toolCallId: id,
						name: String(call.name ?? ""),
						summary: summarizeToolCall(String(call.name ?? ""), call.arguments)
					}
				};
			}
			return { event: null };
		}

		case "tool_execution_start": {
			const toolCallId = String(raw.toolCallId ?? "");
			const name = String(raw.toolName ?? "");
			const summary = summarizeToolCall(name, raw.args);
			ctx.toolCalls.set(toolCallId, { toolCallId, toolName: name, summary, status: "running" });
			return {
				event: {
					type: "tool_start",
					toolCallId,
					name,
					summary,
					args: raw.args ?? null
				}
			};
		}

		case "tool_execution_update": {
			const toolCallId = String(raw.toolCallId ?? "");
			const snapshot = ctx.toolCalls.get(toolCallId);
			const partial = toolResultText(raw.partialResult);
			if (snapshot) snapshot.output = partial;
			return {
				event: {
					type: "tool_progress",
					toolCallId,
					name: String(raw.toolName ?? snapshot?.toolName ?? ""),
					output: partial
				}
			};
		}

		case "tool_execution_end": {
			const toolCallId = String(raw.toolCallId ?? "");
			const isError = raw.isError === true;
			const output = toolResultText(raw.result);
			const snapshot = ctx.toolCalls.get(toolCallId);
			if (snapshot) {
				snapshot.status = isError ? "error" : "ok";
				snapshot.output = output;
			}
			const durationMs = typeof raw.durationMs === "number" ? raw.durationMs : undefined;
			return {
				event: {
					type: "tool_end",
					toolCallId,
					name: String(raw.toolName ?? snapshot?.toolName ?? ""),
					summary: snapshot?.summary ?? "",
					isError,
					output,
					durationMs: durationMs ?? null
				}
			};
		}

		case "session_info_changed":
			return { event: { type: "session_name_changed", name: raw.name ?? null } };

		case "compaction_start":
			return { event: { type: "notice", kind: "compaction_start", reason: raw.reason } };

		case "compaction_end":
			return {
				event: {
					type: "notice",
					kind: "compaction_end",
					aborted: raw.aborted === true,
					errorMessage: raw.errorMessage ?? null
				}
			};

		case "auto_retry_start":
			return {
				event: {
					type: "notice",
					kind: "auto_retry_start",
					attempt: raw.attempt,
					maxAttempts: raw.maxAttempts,
					errorMessage: raw.errorMessage ?? null
				}
			};

		case "auto_retry_end":
			return {
				event: {
					type: "notice",
					kind: "auto_retry_end",
					success: raw.success === true,
					attempt: raw.attempt,
					finalError: raw.finalError ?? null
				}
			};

		case "extension_error":
			return {
				event: {
					type: "notice",
					kind: "extension_error",
					extensionPath: raw.extensionPath,
					error: raw.error
				}
			};

		case "response":
			// Command responses are correlated by id in the session runtime.
			return { event: null };

		case "extension_ui_request":
			return normalizeExtensionUi(raw);

		default:
			return { event: null };
	}
}

/**
 * Translates the extension UI sub-protocol (docs/rpc-extension-ui.md).
 *
 * Slash commands such as `/model` or `/compact` are handled by extensions, and
 * whatever they need from the user arrives here. Dropping these records (the
 * original behaviour) makes every interactive command look like a no-op on the
 * phone: pi blocks on a dialog nobody answers.
 */
function normalizeExtensionUi(raw: Record<string, unknown>): Normalized {
	const method = String(raw.method ?? "");
	const requestId = String(raw.id ?? "");

	switch (method) {
		// Dialog methods: the client must answer, or pi waits until its timeout.
		case "select":
			return {
				event: {
					type: "ui_request",
					requestId,
					method,
					title: raw.title ?? null,
					message: raw.message ?? null,
					options: Array.isArray(raw.options) ? raw.options : [],
					timeout: typeof raw.timeout === "number" ? raw.timeout : null
				}
			};

		case "confirm":
			return {
				event: {
					type: "ui_request",
					requestId,
					method,
					title: raw.title ?? null,
					message: raw.message ?? null,
					timeout: typeof raw.timeout === "number" ? raw.timeout : null
				}
			};

		case "input":
			return {
				event: {
					type: "ui_request",
					requestId,
					method,
					title: raw.title ?? null,
					placeholder: raw.placeholder ?? null,
					timeout: typeof raw.timeout === "number" ? raw.timeout : null
				}
			};

		case "editor":
			return {
				event: {
					type: "ui_request",
					requestId,
					method,
					title: raw.title ?? null,
					prefill: raw.prefill ?? "",
					timeout: typeof raw.timeout === "number" ? raw.timeout : null
				}
			};

		// Fire-and-forget notifications.
		case "notify":
			return {
				event: {
					type: "notice",
					kind: "extension_notify",
					message: raw.message ?? "",
					notifyType: raw.notifyType ?? "info"
				}
			};

		case "setStatus":
			return {
				event: {
					type: "status",
					statusKey: raw.statusKey ?? null,
					statusText: raw.statusText ?? null
				}
			};

		case "setWidget":
			return {
				event: {
					type: "widget",
					widgetKey: raw.widgetKey ?? null,
					widgetLines: Array.isArray(raw.widgetLines) ? raw.widgetLines : null,
					widgetPlacement: raw.widgetPlacement ?? "aboveEditor"
				}
			};

		case "setTitle":
			return { event: { type: "session_title", title: raw.title ?? null } };

		case "set_editor_text":
			return { event: { type: "editor_text", text: raw.text ?? "" } };

		default:
			return { event: null };
	}
}

function assistantText(message: Record<string, unknown>): string {
	const content = message.content;
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	const parts: string[] = [];
	for (const block of content) {
		if (block && typeof block === "object") {
			const entry = block as Record<string, unknown>;
			if (entry.type === "text" && typeof entry.text === "string") parts.push(entry.text);
		}
	}
	return parts.join("");
}
