# PiGo — 手机端与家庭 Pi Agent 实时互通

把家里运行的 [Pi](https://pi.dev) Agent 接到手机上：原生 Android 客户端（Kotlin + Compose）
经 Tailscale 加密 tailnet 连到家庭 Bridge 服务，Bridge 再按工作区拉起/回收 `pi --mode rpc` 子进程。

```
Android App (Kotlin/Compose)
        │  ws://home-pc:8787/ws?token=…  (Tailscale 加密，零端口暴露)
        ▼
Bridge (Node.js/TS)  ── 鉴权 / 工作区白名单 / seq 环形缓冲 / 进程生命周期
        │ stdin/stdout JSONL
        ├── pi --mode rpc  (工作区 A)
        └── pi --mode rpc  (工作区 B)
```

## 目录

| 路径 | 内容 |
| --- | --- |
| `bridge/` | 家庭 Bridge 服务（Node 22+ / TypeScript） |
| `bridge/src/sessionManager.ts` | 多会话、懒启动、空闲回收、seq 缓冲、路径白名单 |
| `bridge/src/normalize.ts` | **协议翻译层**：pi 原始事件 → 手机端线协议 |
| `bridge/src/jsonl.ts` | 严格 LF 分帧（不误切 `U+2028/U+2029`） |
| `bridge/scripts/fake-pi.mjs` | 符合 RPC 规范的 pi 替身，测试用 |
| `bridge/public/index.html` | 局域网自测台（浏览器里直接对话，排查 Bridge 问题） |
| `android/` | Android 客户端（compileSdk 37 / minSdk 26） |
| `pi-mobile-agent-architecture_095058.md` | 原始设计文档 |

## 快速开始

### 1. Bridge

```bash
cd bridge
npm install
npm run build

export BRIDGE_TOKEN=$(openssl rand -hex 32)
export PI_ALLOWED_ROOT=$HOME/projects     # 手机能打开的工作区白名单根
export BRIDGE_DATA_DIR=$PWD/data
node dist/server.js
# [Bridge] listening on ws://0.0.0.0:8787/ws (root=/Users/you/projects)
```

验证：`npm test`（47 个用例，含伪 pi 端到端）、`npm run smoke`（真 pi，需已构建且 pi 已登录）。

`pi` 必须在 `PATH` 里。若你的 pi 装在别处，用 `PI_CLI=/abs/path/to/pi` 指定。
`BRIDGE_TOKEN` 也接受 `bridge/.env` 文件：

```bash
cp .env.example .env      # 填 BRIDGE_TOKEN 与 PI_ALLOWED_ROOT
set -a; . ./.env; set +a
node dist/server.js
```

### 2. Android

```bash
cd android
ANDROID_HOME=$HOME/Library/Android/sdk \
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew :app:installDebug
```

打开 App → 填 Bridge 地址与 Token → 保存。右上角 ⇄ 新建/切换工作区会话。

### 3. 自动构建（CI）

推送到 `main` 或开 PR 时，`.github/workflows/ci.yml` 会跑：

| 任务 | 内容 |
| --- | --- |
| `bridge` | Node 22 与 26 上分别 `typecheck` + `test` + `build` |
| `android` | 单测 + `assembleDebug`，产物 `app-debug-apk` |
| `android`（配了密钥时） | 额外构建**签名**的 `app-release.apk`，产物 `app-release-apk` |

#### release 包签名

**未签名的 release APK 装不上手机**，所以 CI 需要一份 keystore；keystore 本身不进仓库，
而是通过 GitHub Secrets 注入。配置一次即可：

```bash
cd android
keytool -genkeypair -v -keystore pigo-release.jks \
  -alias pigo -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=PiGo, O=Personal, C=CN"

# 转成一行 base64，粘进 PIGO_KEYSTORE_BASE64
base64 -i pigo-release.jks | tr -d '\n'
```

然后在仓库 Settings → Secrets and variables → Actions 里加四个 secret：

| Secret | 内容 |
| --- | --- |
| `PIGO_KEYSTORE_BASE64` | 上面输出的 base64 串 |
| `PIGO_KEYSTORE_PASSWORD` | keystore 密码 |
| `PIGO_KEY_ALIAS` | `pigo` |
| `PIGO_KEY_PASSWORD` | key 密码（通常与 keystore 相同） |

**`pigo-release.jks` 务必自己留一份备份。** 丢了它，新包将无法覆盖安装旧的
（Android 要求签名一致），只能先卸载再装，而卸载会清掉 App 设置里的地址与 token。

没配 secret 也能正常构建：workflow 会跳过 release 任务，只出 debug 包。

想拿一个可长期保存的 APK：打 tag 即可，工作流会把它挂到 GitHub Release
（有签名版就附签名版，否则附 debug 包）。

```bash
git tag v0.1.0 && git push origin v0.1.0
# Release 里出现 pigo-v0.1.0.apk
```

本地出签名包：

```bash
cd android
PIGO_KEYSTORE_FILE=/abs/path/pigo-release.jks \
PIGO_KEYSTORE_PASSWORD=... PIGO_KEY_ALIAS=pigo PIGO_KEY_PASSWORD=... \
ANDROID_HOME=$HOME/Library/Android/sdk \
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

`compileSdk = 37` 比 runner 自带的平台新，工作流里显式 `sdkmanager --install "platforms;android-37.0"`，
不要删掉那一步。

地址填法（只填 `host:port`，协议前缀会自动补）：

| 场景 | 填什么 |
| --- | --- |
| 手机在同一个 tailnet 里 | `home-pc:8787`（MagicDNS 主机名）或 `100.x.y.z:8787` |
| 用 Android **模拟器** 连本机 | `10.0.2.2:8787`（模拟器里访问宿主机 loopback 的特例地址） |
| 想用浏览器排查（不用装 App） | 打开 `http://127.0.0.1:8787/?token=<你的token>` |

### 4. 常驻运行

**macOS**（LaunchAgent，登录后自动启动，退出一律重启）：

```bash
cd bridge && npm run build
./scripts/install-launchd.sh          # 装/更新；--uninstall 卸载
launchctl list | grep pigo            # 应有一行，退出码 0
```

plist 里**不含任何密钥**：launchd 只调用 `scripts/launchd-run.sh`，
由它在启动时 `source bridge/.env`。改配置只改 `.env`，然后
`launchctl unload/load ~/Library/LaunchAgents/dev.zevarc.pigo-bridge.plist`。

**Linux/NAS**：

```bash
sudo cp bridge/systemd/pi-home-bridge@.service /etc/systemd/system/
sudo cp bridge/systemd/pi-home-bridge.env.example ~/.config/pi-home-bridge.env
chmod 600 ~/.config/pi-home-bridge.env   # 填 BRIDGE_TOKEN / PI_ALLOWED_ROOT
sudo systemctl daemon-reload
sudo systemctl enable --now pi-home-bridge@$USER
```

## 客户端 ↔ Bridge 协议

上行（手机 → Bridge）：`list_sessions` `list_workspaces` `create_session` `delete_session`
`sync(sessionId,lastSeq)` `prompt(sessionId,message,id)` `abort(sessionId)` `get_messages`
`get_commands` `ui_response(requestId,value|confirmed|cancelled)`
`list_pi_sessions(query,limit)` `resume_session(sessionId)`

斜杠命令走的是 `prompt`（pi 把 `/model`、`/compact`、`/skill:xxx` 当普通输入展开），
扩展需要的交互经 `ui_request` / `ui_response` 往返。客户端断开时 Bridge 会把挂起的
对话框全部 `cancelled`，否则 pi 会一直等到自己的超时，`agent_settled` 永不出现。

下行（Bridge → 手机）：除 `sessions_snapshot/sessions_updated/session_created/session_deleted/
sync_response/workspaces_snapshot/messages_snapshot/error` 等控制消息外，会话事件统一带
`sessionId` + 单调递增 `seq`：

| 事件 | 含义 |
| --- | --- |
| `agent_start` | 一轮任务开始（按钮切成停止） |
| `agent_end` | 一次底层 run 结束，**不代表空闲**（可能还有重试/压缩/排队消息） |
| `agent_settled` | 该会话确实空闲（按钮切回发送）|
| `assistant_message_start` / `message_delta` / `assistant_message_end` | 流式文本；`end` 携带权威全文 |
| `thinking_delta` | 推理过程（与正文分开渲染）|
| `tool_card` / `tool_start` / `tool_progress` / `tool_end` | 工具调用生命周期，`summary` 一行摘要 |
| `notice` | 压缩、重试、排队、队列清空等提示 |
| `session_process_exit` | 子进程退出/被回收（下条消息自动拉起） |
| `ui_request` | **扩展对话框**（select/confirm/input/editor），pi 正阻塞等客户端回答 |
| `status` / `widget` / `editor_text` | 扩展推来的状态行、文本块、输入框预填 |
| `commands_snapshot` | `/` 命令列表（扩展命令、prompt 模板、skill）|
| `pi_sessions_snapshot` | `/resume` 列表：pi 自己磁盘上的历史会话 |

重连时客户端发 `sync(sessionId, lastSeq)`，Bridge 回放 `seq > lastSeq` 的事件；
若游标已滑出 500 条环形缓冲，`sync_response.needsHistoryReload=true`，客户端改用
`get_messages` 重建整段对话。

## 恢复历史会话（/resume）

客户端列出的是 **pi 自己** 的会话库，不是 Bridge 的私有记录：

```
~/.pi/agent/sessions/--<cwd 编码>--/<timestamp>_<session-id>.jsonl
```

会话数据可以有几百 MB（我们这台 73 个会话 / 559 MB，单个最大 54 MB），所以列表只
读取每个文件的前 64 KB：从中取 header 的 `cwd`、`/name` 和第一条用户消息做预览。
会话抽屉打开即列出**全部**历史会话，按工作目录分组（组内按最近使用排序），
顶部有搜索框，行内显示相对时间、大小、短 id 与首条消息；已在运行的会话带「运行中」角标，
点一下直接切过去，否则按需拉起进程。

点开某个会话时 Bridge 用 `pi --session <file>` 在原工作区拉起进程，历史原样接续。

工作区白名单仍生效：session header 里的 `cwd` 必须落在 `PI_ALLOWED_ROOT` 内。
新建会话的工作区候选项也来自这个会话库（用户真正工作过的目录），而不是遍历家目录。

## 相对设计文档的修正

实现时按 `pi` 的实际 RPC 契约（`pi docs` → `rpc.md` / `json.md` / `rpc-commands.md`）改了几处：

1. **补协议翻译层**：文档假设 pi 直接发 `message_delta`/`tool_start`，实际是
   `message_update{assistantMessageEvent}` 与 `tool_execution_*`，由 `normalize.ts` 转换。
2. **`isAgentRunning` 以 `agent_settled` 为准**：`agent_end` 之后仍可能有重试、溢出恢复、
   压缩重试和排队消息；用 `agent_end` 复位会让按钮提前变回"发送"。
3. **`prompt` 的 response 不再丢弃**：被拒（streaming 中未带 `streamingBehavior`）时回
   `error{code:"prompt_rejected"}`；排队时回 `notice{kind:"prompt_accepted",disposition}`。
   同时 Bridge 在 agent 运行中自动补 `streamingBehavior:"steer"`。
4. **`stdin.write()` 的返回值是背压标志**，不是成败；改写路径一律返回"已写入"。
5. **优雅停进程**：先关 stdin（pi 会自行 dispose runtime），宽限期后再 `SIGTERM`/`SIGKILL`。
6. **会话持久化**：子进程用 `--session-id <sessionId> --session-dir …`，进程被回收后再唤起
   仍接着原来的对话，而不是从零开始。
7. **abort 先 `clear_queue`**：等价于交互式 Esc，否则排队消息会让会话"停不下来"。
8. **鉴权失败不静默**：`BRIDGE_TOKEN` 未设置时拒绝启动；比较用 `timingSafeEqual`。

## 已知限制

- 后台长连接受 Android 限制，靠切回前台 `sync` 补偿；主动推送（ntfy/FCM）尚未实现。
- 单进程单会话并发：同一会话在流式中再次 `prompt` 会被排队（steer），不做并行。
- 客户端未做图片/附件上行（`prompt.images` 协议已具备，UI 未接）。
- 无 TLS：依赖 Tailscale 隧道；如需公网暴露必须自行加反向代理与 wss。
