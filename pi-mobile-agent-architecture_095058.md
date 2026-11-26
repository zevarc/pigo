# 手机端与家庭 Pi Agent 实时互通技术架构与落地方案

> **技术选型总览：** Android 原生客户端（Kotlin + Jetpack Compose） ↔ Tailscale 点对点加密组网 ↔ 家庭 Bridge 服务端（Node.js + TypeScript） ↔ 多工作区 `pi --mode rpc` 进程池

---

## 一、 方案背景与技术选型对比

在移动场景下与家中运行的本地 Agent（如 Pi Agent）进行随时随地的双向交互，需要同时解决 **交互体验（流式渲染、工具调用状态、中断控制）** 与 **内网穿透安全性** 两个核心问题。

### 1.1 前期接入路线对比

| 方案路线 | 接入方式 | 优势 | 局限 | 适用场景 |
| :--- | :--- | :--- | :--- | :--- |
| **IM 机器人外壳** | Telegram Bot / 飞书长连接 | 零客户端开发，无需公网 IP | 受限于聊天软件气泡格式，难以展示复杂终端日志与工具折叠卡片 | 快速验证、轻量对话 |
| **内网穿透 + 开源 WebUI** | Tailscale + Open WebUI / LobeChat | 界面成熟、支持 PWA | 偏向通用大模型聊天，对 Coding/执行型 Agent 的进程控制与工作区切换支持较弱 | 通用模型交互 |
| **自研原生 App + 协议桥接（最终选定）** | Kotlin App + Tailscale + Bridge + `pi --mode rpc` | 深度定制工具卡片、中止控制、多会话切换、断线增量同步 | 需要开发和维护 Bridge 与客户端 | **追求极客体验、高可控性与多工作区管理** |

### 1.2 最终确定技术栈

* **Agent 核心层：** `pi --mode rpc`（通过标准输入/输出进行 JSONL 流式事件通信）。
* **家庭网关/桥接层（Bridge）：** Node.js + TypeScript（管理多工作区子进程生命周期、事件缓冲与 WebSocket 路由）。
* **网络穿透层：** Tailscale（启用 MagicDNS，实现手机与家庭主机零端口暴露的加密点对点通信）。
* **手机客户端：** Android 原生开发（Kotlin + Jetpack Compose + Material 3 + OkHttp WebSocket + Kotlinx Serialization）。

---

## 二、 整体系统架构设计

系统采用分层解耦架构，不让手机端直接连接裸 `pi` 进程，而是通过家庭 Bridge 承接状态管理、进程回收与断线重连补偿：

```text
+-----------------------------------+
|      Android 手机 App (Kotlin)     |
|  - Jetpack Compose UI             |
|  - Active Session 状态管理         |
|  - OkHttp WebSocket (指数退避重连)  |
+-----------------+-----------------+
                  |
                  |  Tailscale 加密虚拟局域网 (ws://home-pc:8787/ws?token=...)
                  v
+-----------------------------------+
|   家庭 Bridge 服务 (Node.js / TS)  |
|  - Token 鉴权 & 工作区路径白名单     |
|  - SessionManager (多会话路由)      |
|  - 环形事件缓冲 & seq 增量同步      |
+--------+-----------------+--------+
         |                 |
   stdin/stdout      stdin/stdout
   (JSONL 流)        (JSONL 流)
         v                 v
+-----------------+ +---------------+
| pi --mode rpc   | | pi --mode rpc |
| (Session A:     | | (Session B:   |
|  /projects/api) | |  /notes)      |
+-----------------+ +---------------+
```

### 关键架构设计原则

1. **按需懒启动与闲置回收（Lazy Spawn & Idle Eviction）：**
   不为每个历史会话常驻子进程。仅当用户向某 `sessionId` 发送指令时按其工作目录 `cwd` 拉起 `pi --mode rpc`；空闲超过阈值（如 20 分钟）且无任务运行时自动发送 `SIGTERM` 释放内存。
2. **会话级序列号与断线增量同步（`seq` + `sync`）：**
   移动网络在 Wi-Fi、蜂窝和锁屏后台之间切换时容易断开 WebSocket。Bridge 为每个会话维护独立递增的 `seq` 和最近 500 条事件环形缓冲；客户端重连或切回前台时发送 `sync(sessionId, lastSeq)` 即可无缝补齐错过的输出。
3. **严格 JSONL 字节流分帧：**
   Pi RPC 协议严格按 `\n`（`0x0a`）分隔 JSON 记录。Bridge 在解析 `stdout` 时直接按字节缓冲区查找 `0x0a` 切分，避免部分文本按行读取器将 Unicode 行分隔符（`U+2028`/`U+2029`）误判为换行而导致 JSON 解析崩溃。
4. **安全边界隔离：**
   * 网络层依赖 Tailscale 身份认证与加密隧道，不向公网暴露任何端口。
   * 应用层校验 `Bearer Token` 或 Query `token`。
   * 新建会话时通过 `path.resolve` + `path.relative` 严格限制 `cwd` 必须位于 `PI_ALLOWED_ROOT` 白名单目录内，防止目录穿越。

---

## 三、 通信协议规范（Bridge ↔ Android App）

### 3.1 客户端上行消息（Phone → Bridge）

| 消息类型 (`type`) | 关键字段 | 说明 |
| :--- | :--- | :--- |
| `list_sessions` | — | 请求获取所有会话元数据及运行状态快照 |
| `create_session` | `cwd`, `title` | 在白名单目录下创建新工作区会话 |
| `sync` | `sessionId`, `lastSeq` | 重连或切换会话时，拉取 `seq > lastSeq` 的增量事件 |
| `prompt` | `sessionId`, `message` | 向指定会话发送用户输入指令（若进程未启动则自动拉起） |
| `abort` | `sessionId` | 中止指定会话当前正在运行的 Agent 任务或工具调用 |

### 3.2 服务端下行消息（Bridge → Phone）

| 消息类型 (`type`) | 关键字段 | 说明 |
| :--- | :--- | :--- |
| `sessions_snapshot` / `sessions_updated` | `sessions[]` | 返回会话列表（含 `id`、`title`、`cwd`、`isAgentRunning`、`processAlive`、`latestSeq`） |
| `sync_response` | `sessionId`, `latestSeq`, `isAgentRunning`, `events[]` | 响应客户端增量同步请求，补发遗漏事件 |
| `agent_start` / `agent_end` | `sessionId`, `seq` | 标记整轮任务开始/结束，驱动客户端发送/停止按钮状态切换 |
| `message_delta` | `sessionId`, `seq`, `delta` | 助手文本流式增量片段 |
| `tool_start` / `tool_end` | `sessionId`, `seq`, `toolCallId`, `name`, ... | 工具调用开始与执行结果，客户端渲染为可折叠状态卡片 |
| `session_process_exit` | `sessionId`, `seq`, `code`, `signal` | 通知客户端某会话底层 `pi` 进程已退出或回收 |

---

## 四、 家庭 Bridge 服务端核心实现（Node.js / TypeScript）

### 4.1 环境初始化

```bash
mkdir pi-home-bridge && cd pi-home-bridge
npm init -y
npm install ws uuid
npm install -D typescript @types/node @types/ws @types/uuid tsx
npx tsc --init
```

### 4.2 多会话管理与进程调度（`src/sessionManager.ts`）

```ts
import { spawn, ChildProcessWithoutNullStreams } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { WebSocket } from "ws";

const ALLOWED_ROOT = path.resolve(process.env.PI_ALLOWED_ROOT ?? process.cwd());
const DATA_DIR = path.resolve(process.env.BRIDGE_DATA_DIR ?? "./data");
const SESSIONS_FILE = path.join(DATA_DIR, "sessions.json");
const IDLE_TIMEOUT_MS = 20 * 60 * 1000; // 20 分钟空闲自动回收子进程

export interface SessionMeta {
  id: string;
  title: string;
  cwd: string;
  createdAt: number;
  lastActiveAt: number;
}

interface BufferedEvent {
  seq: number;
  payload: Record<string, unknown>;
}

interface SessionRuntime {
  meta: SessionMeta;
  proc: ChildProcessWithoutNullStreams | null;
  stdoutBuffer: Buffer;
  seqCounter: number;
  eventHistory: BufferedEvent[];
  isAgentRunning: boolean;
  idleTimer: NodeJS.Timeout | null;
}

function resolveSafeCwd(rawCwd?: string): string {
  if (!rawCwd) return ALLOWED_ROOT;
  const candidate = path.resolve(ALLOWED_ROOT, rawCwd);
  const relative = path.relative(ALLOWED_ROOT, candidate);
  const isInside = relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
  if (candidate !== ALLOWED_ROOT && !isInside) {
    throw new Error(`Workspace outside allowed root: ${rawCwd}`);
  }
  if (!existsSync(candidate) || !statSync(candidate).isDirectory()) {
    throw new Error(`Workspace directory does not exist: ${candidate}`);
  }
  return candidate;
}

export class MultiSessionPiBridge {
  private sessions = new Map<string, SessionRuntime>();
  private clients = new Set<WebSocket>();

  constructor() {
    mkdirSync(DATA_DIR, { recursive: true });
    this.loadSessions();
  }

  private loadSessions() {
    if (!existsSync(SESSIONS_FILE)) return;
    try {
      const list = JSON.parse(readFileSync(SESSIONS_FILE, "utf8")) as SessionMeta[];
      for (const meta of list) {
        this.sessions.set(meta.id, {
          meta,
          proc: null,
          stdoutBuffer: Buffer.alloc(0),
          seqCounter: 0,
          eventHistory: [],
          isAgentRunning: false,
          idleTimer: null
        });
      }
    } catch (err) {
      console.error("[Bridge] Failed to load sessions.json:", err);
    }
  }

  private saveSessions() {
    const list = Array.from(this.sessions.values()).map((s) => s.meta);
    writeFileSync(SESSIONS_FILE, JSON.stringify(list, null, 2), "utf8");
  }

  getSessionSummaries() {
    return Array.from(this.sessions.values()).map((s) => ({
      id: s.meta.id,
      title: s.meta.title,
      cwd: s.meta.cwd,
      lastActiveAt: s.meta.lastActiveAt,
      isAgentRunning: s.isAgentRunning,
      processAlive: s.proc !== null,
      latestSeq: s.seqCounter
    }));
  }

  createSession(rawCwd?: string, title?: string): SessionMeta {
    const cwd = resolveSafeCwd(rawCwd);
    const id = `sess-${randomUUID().slice(0, 8)}`;
    const meta: SessionMeta = {
      id,
      title: title?.trim() || path.basename(cwd) || id,
      cwd,
      createdAt: Date.now(),
      lastActiveAt: Date.now()
    };

    this.sessions.set(id, {
      meta,
      proc: null,
      stdoutBuffer: Buffer.alloc(0),
      seqCounter: 0,
      eventHistory: [],
      isAgentRunning: false,
      idleTimer: null
    });
    this.saveSessions();
    this.broadcast({ type: "sessions_updated", sessions: this.getSessionSummaries() });
    return meta;
  }

  private ensureProcess(rt: SessionRuntime): ChildProcessWithoutNullStreams {
    this.resetIdleTimer(rt);
    if (rt.proc) return rt.proc;

    const proc = spawn("pi", ["--mode", "rpc"], {
      cwd: rt.meta.cwd,
      stdio: ["pipe", "pipe", "pipe"],
      env: { ...process.env }
    });

    rt.proc = proc;
    console.log(`[Session ${rt.meta.id}] Spawned pi in ${rt.meta.cwd} (pid=${proc.pid})`);

    proc.stdout.on("data", (chunk: Buffer) => this.handleStdout(rt, chunk));
    proc.stderr.on("data", (chunk: Buffer) => {
      console.error(`[Session ${rt.meta.id} stderr] ${chunk.toString("utf8").trimEnd()}`);
    });

    proc.on("exit", (code, signal) => {
      console.log(`[Session ${rt.meta.id}] pi exited (code=${code}, signal=${signal})`);
      rt.proc = null;
      rt.isAgentRunning = false;
      this.emitSessionEvent(rt, {
        type: "session_process_exit",
        code,
        signal
      });
    });

    return proc;
  }

  private resetIdleTimer(rt: SessionRuntime) {
    if (rt.idleTimer) {
      clearTimeout(rt.idleTimer);
      rt.idleTimer = null;
    }
    rt.idleTimer = setTimeout(() => {
      if (!rt.isAgentRunning && rt.proc) {
        console.log(`[Session ${rt.meta.id}] Idle timeout, stopping pi process`);
        rt.proc.kill("SIGTERM");
      }
    }, IDLE_TIMEOUT_MS);
  }

  private handleStdout(rt: SessionRuntime, chunk: Buffer) {
    rt.stdoutBuffer = Buffer.concat([rt.stdoutBuffer, chunk]);
    let idx: number;
    while ((idx = rt.stdoutBuffer.indexOf(0x0a)) !== -1) {
      let lineBuf = rt.stdoutBuffer.subarray(0, idx);
      rt.stdoutBuffer = rt.stdoutBuffer.subarray(idx + 1);
      if (lineBuf.length > 0 && lineBuf[lineBuf.length - 1] === 0x0d) {
        lineBuf = lineBuf.subarray(0, lineBuf.length - 1);
      }
      const line = lineBuf.toString("utf8").trim();
      if (!line) continue;
      try {
        const parsed = JSON.parse(line) as Record<string, unknown>;
        const eventType = String(parsed.type ?? "");
        if (eventType === "agent_start") rt.isAgentRunning = true;
        if (eventType === "agent_end") {
          rt.isAgentRunning = false;
          this.resetIdleTimer(rt);
        }
        this.emitSessionEvent(rt, parsed);
      } catch (err) {
        console.error(`[Session ${rt.meta.id}] Invalid JSONL:`, err);
      }
    }
  }

  private emitSessionEvent(rt: SessionRuntime, event: Record<string, unknown>) {
    const seq = ++rt.seqCounter;
    const payload = {
      ...event,
      sessionId: rt.meta.id,
      seq
    };
    rt.eventHistory.push({ seq, payload });
    if (rt.eventHistory.length > 500) {
      rt.eventHistory.shift();
    }
    this.broadcast(payload);
  }

  private broadcast(data: Record<string, unknown>) {
    const raw = JSON.stringify(data);
    for (const ws of this.clients) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(raw);
      }
    }
  }

  sendPrompt(sessionId: string, message: string): boolean {
    const rt = this.sessions.get(sessionId);
    if (!rt) return false;
    rt.meta.lastActiveAt = Date.now();
    this.saveSessions();

    const proc = this.ensureProcess(rt);
    if (!proc.stdin.writable) return false;

    const line = JSON.stringify({ id: `cmd-${randomUUID()}`, type: "prompt", message }) + "\n";
    return proc.stdin.write(line, "utf8");
  }

  abortSession(sessionId: string): boolean {
    const rt = this.sessions.get(sessionId);
    if (!rt?.proc?.stdin.writable) return false;
    const line = JSON.stringify({ id: `cmd-${randomUUID()}`, type: "abort" }) + "\n";
    return rt.proc.stdin.write(line, "utf8");
  }

  getMissedEvents(sessionId: string, lastSeq: number) {
    const rt = this.sessions.get(sessionId);
    if (!rt) return { latestSeq: 0, isAgentRunning: false, events: [] };
    return {
      latestSeq: rt.seqCounter,
      isAgentRunning: rt.isAgentRunning,
      events: rt.eventHistory.filter((e) => e.seq > lastSeq).map((e) => e.payload)
    };
  }

  attachClient(ws: WebSocket) {
    this.clients.add(ws);
    ws.send(
      JSON.stringify({
        type: "sessions_snapshot",
        sessions: this.getSessionSummaries()
      })
    );
    ws.on("close", () => this.clients.delete(ws));
    ws.on("error", () => this.clients.delete(ws));
  }
}
```

### 4.3 HTTP / WebSocket 鉴权入口（`src/server.ts`）

```ts
import { createServer, IncomingMessage } from "node:http";
import { WebSocketServer } from "ws";
import { MultiSessionPiBridge } from "./sessionManager.js";

const PORT = Number(process.env.BRIDGE_PORT ?? 8787);
const BRIDGE_TOKEN = process.env.BRIDGE_TOKEN ?? "change-me-to-a-strong-secret";

const bridge = new MultiSessionPiBridge();

function isAuthorized(req: IncomingMessage): boolean {
  if (req.headers.authorization === `Bearer ${BRIDGE_TOKEN}`) return true;
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
  return url.searchParams.get("token") === BRIDGE_TOKEN;
}

const server = createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "ok" }));
    return;
  }
  res.writeHead(404);
  res.end();
});

const wss = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  const pathname = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`).pathname;
  if (pathname !== "/ws") {
    socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
    socket.destroy();
    return;
  }
  if (!isAuthorized(req)) {
    socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    bridge.attachClient(ws);
    ws.on("message", (data) => {
      try {
        const msg = JSON.parse(data.toString("utf8")) as Record<string, unknown>;
        const type = String(msg.type ?? "");
        const sessionId = String(msg.sessionId ?? "");

        switch (type) {
          case "list_sessions":
            ws.send(JSON.stringify({ type: "sessions_snapshot", sessions: bridge.getSessionSummaries() }));
            break;
          case "create_session": {
            const meta = bridge.createSession(
              typeof msg.cwd === "string" ? msg.cwd : undefined,
              typeof msg.title === "string" ? msg.title : undefined
            );
            ws.send(JSON.stringify({ type: "session_created", session: meta }));
            break;
          }
          case "sync": {
            const lastSeq = Number(msg.lastSeq ?? 0);
            const syncData = bridge.getMissedEvents(sessionId, lastSeq);
            ws.send(JSON.stringify({ type: "sync_response", sessionId, ...syncData }));
            break;
          }
          case "prompt": {
            const ok = bridge.sendPrompt(sessionId, String(msg.message ?? ""));
            if (!ok) ws.send(JSON.stringify({ type: "error", sessionId, message: "Failed to send prompt" }));
            break;
          }
          case "abort":
            bridge.abortSession(sessionId);
            break;
        }
      } catch (err) {
        ws.send(JSON.stringify({ type: "error", message: (err as Error).message }));
      }
    });
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`[Bridge] Multi-session Pi Bridge listening on ws://0.0.0.0:${PORT}/ws`);
});
```

---

## 五、 Android 客户端（Kotlin + Jetpack Compose）核心实现

### 5.1 领域状态模型（`PiUiState.kt`）

```kotlin
import kotlinx.serialization.Serializable

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED
}

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val cwd: String,
    val isAgentRunning: Boolean = false,
    val processAlive: Boolean = false,
    val latestSeq: Long = 0L
)

@Serializable
data class ToolCallItem(
    val id: String,
    val name: String,
    val summary: String,
    val isRunning: Boolean = true,
    val outputPreview: String? = null,
    val isExpanded: Boolean = false
)

data class ChatTurn(
    val id: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val toolCalls: List<ToolCallItem> = emptyList(),
    val isStreaming: Boolean = false
)

data class PiUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val isAgentRunning: Boolean = false,
    val turns: List<ChatTurn> = emptyList(),
    val inputText: String = "",
    val errorBanner: String? = null
)
```

### 5.2 带指数退避的 WebSocket 客户端（`BridgeSocketClient.kt`）

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.*
import java.util.concurrent.TimeUnit

class BridgeSocketClient(
    private val scope: CoroutineScope,
    private val wsUrlProvider: () -> String, // 如 "ws://home-pc:8787/ws?token=..."
    private val onConnectedSync: () -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val incomingMessages: SharedFlow<String> = _incomingMessages.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var manuallyClosed = false
    private var retryAttempt = 0

    fun connect() {
        manuallyClosed = false
        reconnectJob?.cancel()
        openSocket()
    }

    fun disconnect() {
        manuallyClosed = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client closed")
        webSocket = null
        _status.value = ConnectionStatus.DISCONNECTED
    }

    fun send(rawJson: String): Boolean = webSocket?.send(rawJson) ?: false

    private fun openSocket() {
        _status.value = ConnectionStatus.CONNECTING
        val request = Request.Builder().url(wsUrlProvider()).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                retryAttempt = 0
                _status.value = ConnectionStatus.CONNECTED
                onConnectedSync()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                _incomingMessages.tryEmit(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _status.value = ConnectionStatus.DISCONNECTED
                if (!manuallyClosed) scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _status.value = ConnectionStatus.DISCONNECTED
                if (!manuallyClosed) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = minOf(30_000L, 1_000L * (1L shl retryAttempt.coerceAtMost(4)))
            retryAttempt++
            delay(delayMs)
            if (!manuallyClosed) openSocket()
        }
    }
}
```

### 5.3 移动端交互设计要点（Jetpack Compose）

1. **顶部状态与会话切换器：** 显示 Tailscale 连接状态（`CONNECTED` / `CONNECTING`）及当前会话名称，点击可拉起 `ModalBottomSheet` 切换不同工作区或新建会话，后台正在运行任务的会话显示运行中角标。
2. **工具调用可折叠卡片：** 移动端屏幕空间有限，将 `toolCalls` 默认折叠为单行摘要（如 `▶ shell: git status`），用户点击后再展开查看详细输出。
3. **发送与中止动态按钮：** 当 `isAgentRunning == true` 时，输入框右侧按钮自动切换为红色 **停止（Abort）** 按钮，随时叫停远程执行的命令。

---

## 六、 部署与落地建议

1. **Tailscale MagicDNS：** 在 Tailscale 控制台开启 MagicDNS，手机 App 内直接配置主机名（如 `ws://home-server:8787/ws`），避免虚拟内网 IP 变化带来的重配成本。
2. **进程守护（systemd）：** 在家庭 Linux 主机/NAS 上将 Bridge 配置为 `systemd` 服务（设置 `Restart=always`），确保主机重启或异常退出后服务自动恢复。
3. **后台任务完成通知（可选增强）：** 鉴于 Android 后台会限制长连接保活，切回前台时依靠 `sync` 补偿状态；若希望长耗时任务完成时主动弹窗提醒，可在 Bridge 监听到非活跃会话的 `agent_end` 时，调用自建或轻量推送服务（如 `ntfy`）向手机发送通知并附带 `sessionId` 跳转参数。
