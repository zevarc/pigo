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

## 排查顺序

1. `curl localhost:8787/health`
2. 浏览器打开 `http://<host>:8787/?token=<token>` 调测台
3. `journalctl -u pi-home-bridge@$USER -f`
4. 事件形状对不上时，直接 `pi --mode rpc` 手工喂一行 `{"type":"prompt","message":"hi"}` 看原始输出
