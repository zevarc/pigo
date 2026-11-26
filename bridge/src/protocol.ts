/** Shared wire-protocol types for the Bridge <-> client link. */

export interface SessionSummary {
  id: string;
  title: string;
  cwd: string;
  createdAt: number;
  lastActiveAt: number;
  isAgentRunning: boolean;
  processAlive: boolean;
  latestSeq: number;
}

export interface BridgeEvent {
  type: string;
  sessionId: string;
  seq: number;
  [key: string]: unknown;
}

export interface ClientMessage {
  type: string;
  sessionId?: string;
  cwd?: string;
  title?: string;
  message?: string;
  lastSeq?: number;
  id?: string;
  streamingBehavior?: string;
  clearQueue?: boolean;
  /** `/resume` picker. */
  query?: string;
  limit?: number;
  /** Extension UI dialog reply. */
  requestId?: string;
  value?: string;
  confirmed?: boolean;
  cancelled?: boolean;
}

export interface ToolSnapshot {
  toolCallId: string;
  toolName: string;
  summary: string;
  status: "running" | "ok" | "error";
  output?: string;
  durationMs?: number;
}
