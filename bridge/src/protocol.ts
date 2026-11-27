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
  /**
   * Absolute path of the pi session file, once pi has reported it.
   * Undefined for a session that has never been spawned, and for the window
   * between spawn and the `get_state` reply.
   */
  sessionFile?: string;
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
  /** Tool result to fetch in full. */
  toolCallId?: string;
  /** Extension UI dialog reply. */
  requestId?: string;
  value?: string;
  confirmed?: boolean;
  cancelled?: boolean;
  /** Image placeholder tapped in the transcript; see `get_image`. */
  imageId?: string;
}

export interface ToolSnapshot {
  toolCallId: string;
  toolName: string;
  summary: string;
  status: "running" | "ok" | "error";
  output?: string;
  durationMs?: number;
}
