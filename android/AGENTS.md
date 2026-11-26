# android/AGENTS.md

Android 客户端：把 Bridge 的事件流渲染成可操作的对话界面。

## 验证命令

必须显式给两个环境变量（`local.properties` 不可依赖）：

```bash
ANDROID_HOME=$HOME/Library/Android/sdk \
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew :app:testDebugUnitTest    # 15 个 reducer 用例
```

装到模拟器：`./gradlew :app:installDebug`。

仓库没有 lint/ktlint，单测是唯一自动化门禁。改动 `TranscriptReducer` 后必须跑。

## 分层

```
MainActivity      两个屏幕的切换，无导航库
  ui/PiScreen      顶部状态 / 会话抽屉 / 对话流 / 发送-停止按钮
  ui/SessionSheet  会话列表 + 从工作区白名单新建
  ui/components/ToolCard  折叠工具卡片
    ui/PiViewModel   意图 → 上行帧；事件 → reduce
      bridge/TranscriptReducer   纯函数，(state, event) → state
        bridge/Wire              JsonElement 便捷访问
      bridge/BridgeSocketClient  OkHttp WS + 指数退避
  data/BridgeSettings  SharedPreferences（地址 + Token）
```

## 硬规则

- **`TranscriptReducer` 不许依赖 Android**，否则无法 JVM 单测。所有平台调用挤到边缘。
- **`seq` 是去重游标**：切会话必须 `resetCursor()`，否则新会话的低 seq 事件会被当重复丢掉。
- **不要用 `agent_end` 判定空闲**，只认 `agent_settled`。
- 文本渲染以 `assistant_message_end` 的全文为准，`message_delta` 只是增量预览（重连后可能有缺口）。
- 底部 sheet 用 `Column + verticalScroll`，不要用 `LazyColumn`：sheet 以无界高度测量，
  LazyColumn 会塌成 0 高（踩过一次）。
- **助手正文必须走 `PiMarkdown`**（`com.mikepenz.markdown.m3.Markdown`），不要再退回纯
  `Text`：pi 的输出带标题/列表/代码块，原样输出在手机上一团糟。
- **输入框右侧只用图标按钮**（`FilledIconButton`）。把 `Icon` 塞进 `Button` 会被默认内边距
  挤成大方块；停止键用 `errorContainer` 容器色，别只把图标染红。

- **扩展对话框必须能回答**：`ui_request` 到达就弹 `ExtensionDialog`，取消也要回
  `cancelled: true`，否则 pi 侧阻塞。`/` 开头时显示 `CommandPalette`（数据来自
  `get_commands`）。

- **Markdown 对齐 pi 的语义**，不是库默认主题：标题与正文同字号、靠颜色区分；
  行内代码用正文字体加 tinted chip；只有代码块用等宽；引用斜体带左rule。
  参照 `pi/dist/modes/interactive/theme/system-theme.js` 与
  `pi/dist/core/export-html/template.css`。

- **`sessions_snapshot` 不得改变当前会话**：`resume_session` 的回复和每次（重）连接推送的
  快照存在竞争，若此处在“当前 id 不在列表里”时回落到 `first()`，用户会被静默切走、
  正文被清空。只允许它在 `activeSessionId == null` 时选定初始会话。
- **拉历史要按事件里带的 id**（`session_created.session.id`），不要靠
  “activeSessionId 变了”推断，同一原因。
- 错误提示用 `ErrorChip`（角落图标 + 自动消失），不要做常驻横幅。

## 与 Bridge 的契约

见仓库根 `README.md` 的事件表。Bridge 侧对应实现是 `bridge/src/normalize.ts`，
两边的单测互为镜像——改一边就要改另一边。
