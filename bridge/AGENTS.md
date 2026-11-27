# bridge/AGENTS.md

家庭 Bridge 服务：把 `pi --mode rpc` 会话暴露给手机端。只做协议翻译、会话路由、
进程生命周期与断线补偿，不含业务逻辑。

## 验证命令

```bash
npm install          # 首次
npm run typecheck    # tsc --noEmit
npm test             # node:test + tsx，26 个用例，不需要模型凭据
npm run smoke        # 需要 npm run build，且本机 pi 已登录；会真跑一次 prompt
```

`npm test` 全绿是唯一硬门槛。改 `normalize.ts` 或 `sessionManager.ts` 的协议形状时，
必须同步改 `android/app/src/test/.../TranscriptReducerTest.kt`。

## 分层

```
server.ts         HTTP/WS 入口、Bearer/Query 鉴权、消息路由
  sessionManager.ts  会话表、懒启动、空闲回收、seq 环形缓冲、cwd 白名单
    normalize.ts     pi 原始事件 → 手机端线协议
      jsonl.ts       LF 字节分帧（不切 U+2028/U+2029）
```

## 硬规则

- **线格式以 `docs/json.md` 为准**，不是以设计文档为准。pi 发的是 `message_update`
  与 `tool_execution_*`，`message_delta`/`tool_start` 是本层翻译出来的。
- **`agent_end` ≠ 空闲**。只有 `agent_settled` 才代表没有重试/压缩/排队消息。
- **`stdin.write()` 返回值是背压标志**，不是成功标志，不要用它判断发送成败。
- **停子进程先关 stdin**，等宽限期再 `SIGTERM`，最后 `SIGKILL`；直接 kill 会丢会话。
- 任何路径都要过 `resolveSafeCwd`；新增从客户端来的路径参数时必须复用它。
- 事件流里塞不进的东西（二进制、图片流）不要偷偷塞 JSON，走单独通道。
- **`extension_ui_request` 不能丢**。对话框方法（select/confirm/input/editor）要转成
  `ui_request` 并登记在 `runtime.uiRequests`，客户端回 `ui_response` 才解开；客户端
  全断开时必须 `cancelPendingUi`，否则 pi 卡在等回答上，会话永远不 settle。

## 关于 resume

- 列表数据源是 pi 的 `~/.pi/agent/sessions`，**不要**改成 Bridge 私有目录，否则手机端
  看不到用户真实的会话。
- 会话文件巨大（最大 54 MB），只能读头部：`sessionIndex.ts` 用 `readSync` 取前 N KB，
  且保证第一个 header 行完整。
- 目录名 `--Users-me-Labs-pigo--` 是把 `/`、`:`、`\\` 都压成 `-` 的结果，**不可逆**，
  权威 `cwd` 一律取文件 header 里的字段。
- 恢复时用 `--session <file>`，不要用 `--session-id`：后者会在 bridge 自己的目录里另建
  一个空会话。

## 常驻部署

- macOS 用 `scripts/install-launchd.sh` 安装两个 LaunchAgent（bridge + watchdog）。
  **不要把 token 写进 plist**：`scripts/launchd-run.sh` 负责 `source .env` 再 `exec node`，
  `.env` 是唯一配置源。
- 改完 `.env` 生效：`kill -USR2 $(pgrep -f dist/server.js)` —— 原地 `execve` 重启，
  **PID 不变、launchd 注册不丢**，会重新 source `.env` 并重跑 `launchd-run.sh`。
- 只重启不重读配置：`launchctl kickstart -k gui/$(id -u)/dev.zevarc.pigo-bridge`。
- **禁止在任何脚本或文档里用 `launchctl unload` / `bootout` 来重启 bridge。**
  unload 会删除注册并 SIGTERM 进程，而执行它的 shell 通常是 bridge 的 pi 子进程，
  于是命令在跑到 `load` 之前就被杀了 —— 这是历史上反复掉线的直接原因。
  `install-launchd.sh` 里因此只用 `bootout`+`bootstrap`（紧跟重试），并带
  "是否在 bridge 进程树内" 的自检会拒绝执行。
- **看门狗（`dev.zevarc.pigo-bridge.watchdog`）是必需的一层，不是可选优化。**
  `KeepAlive` 只能拉起 launchd 还认识的进程；服务被 bootout 后它无能为力。
  pi 子进程与 bridge 同 UID，随时能执行 bootout，所以监管者必须在被监管树之外。
  它每 60s 检查：未注册则 `bootstrap`，`/health` 不通则 `kickstart -k`。
  日志 `data/watchdog.log`；暂停监督：`touch data/.bridge-disabled`。
- **node 路径必须固定。** nvm 每次安装都会重写版本目录，把 plist 生成时的 `$PATH`
  抄进去，迟早变成 `exec: node: not found`（exit 127）+ KeepAlive 无限重启。
  `launchd-run.sh` 按 `nvm alias default` 的别名链（`default -> lts/* -> lts/lithium -> v26.13.0`）
  解析出绝对路径，失败则回退到最新已安装版本 / 系统 node，并把实际用的解释器打到日志首行。
  要强制指定：`.env` 里设 `BRIDGE_NODE=/abs/path/to/node`。

## 排查顺序

1. `curl localhost:8787/health`
2. 浏览器打开 `http://<host>:8787/?token=<token>` 调测台
3. `journalctl -u pi-home-bridge@$USER -f`
4. 事件形状对不上时，直接 `pi --mode rpc` 手工喂一行 `{"type":"prompt","message":"hi"}` 看原始输出
