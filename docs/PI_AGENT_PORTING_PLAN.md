# Pi Agent Kotlin 复刻计划

## 1. 目标与参考基线

本计划的目标是用 Kotlin 复刻 Pi 的 Headless Runtime，并将其接入 XueXue。
这里的“复刻”指除明确排除项外，行为、协议、事件顺序、会话语义、资源优先级
和扩展点均与 Pi 0.80.6 对齐。实现不是只提供一个带工具调用的聊天面板，也不是
逐行翻译源码；语言和平台适配不得改变外部可观察行为。

参考实现固定为：

- 本地源码：`参考内容/Pi源码/pi-main`
- 参考包版本：`@earendil-works/pi-ai`、`pi-agent-core`、
  `pi-coding-agent` 0.80.6
- 架构要求：`Agent实现路径建议.md`

### 1.1 唯一排除范围

仅排除以下内容：

- Pi 的 TUI 实现、终端组件、主题、键位和纯终端渲染行为；
- 网络遥测的采集、排队、上传和远端开关；本地诊断、用量与成本统计不属于遥测；
- 依赖 Node 运行时的入口、适配器和进程宿主；相应 Headless 能力如果仍属复刻范围，
  必须用 Kotlin/JVM 平台能力实现；
- TypeScript 扩展加载、执行和兼容层。

排除项不得扩大解释。例如，原实现使用 Node 文件 API 不代表文件工具可以省略，
而是要使用 JVM 文件 API 保持同一行为。除语言差异、平台实现所必需的调整，或在
不改变外部语义前提下有明确证据支持的更好实现外，不新增流程、规则和产品能力。
所有必要差异必须记录在 parity matrix 中并有测试证明边界。

## 2. 当前实现定位

当前代码已经建立三层 Kotlin 模块和可验证的 Headless 核心，但仍处于迁移阶段，
不是最终完整 Runtime。

| 领域 | 当前状态 | 与 Pi 的主要差距 |
| --- | --- | --- |
| 模型调用 | `:pi-ai` 已有消息/内容/模型协议、事件流、取消、估算和基础错误工具 | 缺 Provider 目录、图片生成、OAuth、各 Provider adapter 和完整兼容 facade |
| Agent Loop | `:pi-agent-core` 已复刻双层 Loop、完整事件、并行/顺序工具、两类队列和钩子 | 原 Agent 核心测试已映射；仍依赖上层补齐 retry、compaction 和资源刷新 |
| 工具 | `read/write/edit/bash/grep/find/ls` 可运行 | 参数和输出语义尚未逐项对齐 Pi，缺文件修改队列 |
| 会话 | `:pi-coding-agent` 已有 Pi v3 append-only tree/JSONL、`AgentSession`、retry 与手动/阈值/overflow compaction | 缺 branch summary、Runtime/services、导入导出和原测试逐文件映射 |
| 上下文 | 已有 Pi 系统提示构建器、ResourceLoader、父目录 AGENTS/CLAUDE 继承、SYSTEM/APPEND_SYSTEM、Skills 与模板发现 | 缺完整 YAML/ignore 规则、package/extension metadata、AgentSession prompt 重建和 token 管理 |
| UI | Compose 已订阅新 Agent 状态和事件并调用 prompt/steer/abort | 仍使用旧会话 DTO、临时 Provider/工具桥和消息投影，尚未切换到正式 AgentSession Runtime |
| 设置、扩展与包 | SettingsManager 已有全局/项目深合并、迁移、信任隔离和外部编辑保留；ResourceLoader 已消费受信任设置资源 | 扩展 ABI/runner/loader、包安装、跨进程锁和 package 资源 reload 未实现 |
| SDK/RPC | 未实现 | 未实现 |

当前的 `PiAgentRuntime`、`AgentClient`、`WorkbenchAgentController` 和会话文件
必须提供迁移兼容，但允许在新 Runtime 完成后被替换。现有
`.xuexue/agent-sessions.jsonl` 要提供一次性迁移器，不能直接当作 Pi 会话格式。

## 3. 目标模块边界

按照 Pi 的三个主要包建立对应的 Kotlin Gradle 模块，避免 Agent 继续依附于
`WorkbenchState`：

| Kotlin 模块 | 对应 Pi 包 | 责任 |
| --- | --- | --- |
| `:pi-ai` | `packages/ai` | 模型、内容块、Provider API、流式事件、认证和用量 |
| `:pi-agent-core` | `packages/agent` | Agent 状态、Loop、队列、工具执行、事件和稳定错误类型 |
| `:pi-coding-agent` | `packages/coding-agent/core` | 会话、compaction、资源、工具、扩展、包管理和 SDK |
| `:pi-platform-desktop` | Desktop 平台能力 | HTTP、文件系统、Shell、凭据库、JVM/Process 插件宿主和 RPC 进程 |
| `:app` | Headless Runtime 宿主 | Compose 状态投影、用户交互请求和工作台接线 |

依赖方向必须固定为：

```text
Compose UI -> pi-coding-agent -> pi-agent-core -> pi-ai
                    |
                    +-> platform capability interfaces
                              |
                              +-> Desktop implementations
```

`pi-ai`、`pi-agent-core` 和 `pi-coding-agent` 的公共 API 放在 `commonMain`。
这些模块不得依赖 Compose、JVM 文件 API、`ProcessBuilder` 或工作台状态。
平台实现通过能力接口注入，例如 `FileSystem`、`ExecutionEnvironment`、
`HttpTransport`、`CredentialStore` 和 `Clock`。

### 3.1 明确的宿主差异

以下平台差异必须记录在 parity matrix 中，不能伪装成 Pi 原行为：

- 被排除的终端界面不进入 Runtime；仍被 Headless 扩展使用的用户交互转换为
  与界面无关的 `InteractionRequest`。
- Pi 默认不提供工具权限弹窗；XueXue 架构要求 workspace trust、写入、Shell
  和外部路径策略时，这些限制只能由宿主 policy layer 叠加，并在 parity 测试中
  关闭；Agent Loop 和扩展事件本身仍保持 Pi 语义。
- XueXue 使用自己的应用数据目录和工作区 `.xuexue` 目录，但资源合并、
  启用状态和信任时序要与 Pi 对齐。
- 被排除源码必须在 matrix 中标为 `excluded` 并写明排除依据，不能以此掩盖其
  相邻模块中的非终端、非遥测 Headless 行为。

## 4. 必须先冻结的协议

以下协议是 Provider、会话、扩展、RPC 和 UI 的共同基础，完成前不开发插件加载器。

### 4.1 消息与内容块

按 Pi 建立结构化内容，不再用 `role + String` 表示所有消息：

- `UserMessage`：文本和图片内容块。
- `AssistantMessage`：文本、thinking、tool call、停止原因、错误和用量。
- `ToolResultMessage`：tool call id、工具名、文本/图片结果、details 和错误状态。
- `CustomMessage`：扩展产生的可持久化自定义消息。
- `AgentMessage`：上述消息的封闭联合类型。

Provider 转换只发生在 `pi-ai` 边界。会话、工具和 UI 始终保存完整的
`AgentMessage`，不能把工具参数或 reasoning 丢成展示字符串。

### 4.2 事件顺序

事件名称和顺序以 Pi 为准，至少包括：

```text
agent_start
  turn_start
    message_start -> message_update* -> message_end
    tool_execution_start -> tool_execution_update* -> tool_execution_end
    message_start(tool result) -> message_end(tool result)
  turn_end
agent_end
agent_settled
```

扩展、UI、RPC 和测试订阅同一条事件流。修改型钩子要定义先后顺序、超时、
异常处理和合并规则，不能由 Compose 回调临时决定。

### 4.3 Runtime 状态与交互请求

Runtime 对外暴露 `StateFlow<AgentState>` 和 `Flow<AgentEvent>`，并接受命令：

- prompt、steer、follow-up、abort；
- 设置模型和 thinking level；
- 新建、切换、分叉、克隆和树导航；
- compact、reload resources 和 retry；
- 执行用户 Bash 或调用注册命令。

需要用户参与的动作通过 `InteractionRequest` 暂停并等待结果，例如
confirm、select、input、editor、权限确认和凭据登录。Compose 只负责展示请求并
返回答案，RPC 则把相同请求编码为 JSONL 事件。

## 5. 分阶段实施

### 阶段 0：建立行为矩阵和测试基线

交付物：

- `docs/PI_PARITY_MATRIX.md`，逐项记录 Pi 源文件、Kotlin 对应物、状态和测试。
- 固定 Pi 0.80.6 的消息、事件、会话和 RPC JSON fixture。
- 把 Pi 核心测试按行为分组：AI/Provider、Loop、队列、工具、会话、compaction、
  资源、extensions、packages、SDK 和 RPC。
- 对四类排除内容建立逐文件排除表；其余源文件和测试必须有 Kotlin 对应项或
  明确的迁移任务，不能以模块级描述代替逐项审计。

验收：每一个“已完成”状态都必须指向自动化测试，不能以 UI 可见为依据。

### 阶段 1：拆出 `pi-ai` 和公共消息协议

工作项：

- 实现完整内容块、模型、用量、停止原因和稳定错误类型。
- 定义流式 `AssistantMessageEvent`，支持增量文本、thinking 和 tool call 参数。
- 将当前 OpenAI Chat Completions 实现迁入 Provider 接口。
- 添加 OpenAI Responses、Anthropic Messages 和 Google Generative AI；其他
  Provider 在相同接口上逐个补齐。
- 复刻图片生成协议、模型目录、Provider 转换、认证与凭据解析等非排除行为。
- 实现请求超时、取消、重试、Retry-After、上下文溢出识别和 Unicode 清理。

验收：同一段标准消息可无损经过 Provider 转换；取消和错误只通过协议返回，
不会破坏会话历史。

### 阶段 2：复刻 `pi-agent-core`

工作项：

- 将当前简单循环替换成 Pi 的 Agent/Agent Loop 双层结构。
- 支持 sequential/parallel 工具执行和每个工具的模式覆盖。
- 实现参数 schema 校验、before/after tool call、部分进度和错误工具结果。
- 实现 steering/follow-up 的 `all` 与 `one-at-a-time` 队列模式。
- 实现 retry、prepare-next-turn、should-stop 和 agent settled 语义。
- Agent Core 只依赖能力接口，完全移除 Compose snapshot state。

验收：移植的 Pi Agent Loop 测试通过，事件顺序与 fixture 完全一致。

### 阶段 3：会话仓库、树和上下文治理

工作项：

- 使用 append-only JSONL entry：header、message、model change、thinking change、
  compaction、branch summary、custom entry。
- 每个 entry 使用稳定 id 和 parent id，恢复活动 leaf 并构建消息树。
- 实现 name、switch、fork、clone、tree navigation、import/export 和统计。
- 实现主动阈值 compaction、上下文溢出恢复、重试和分支摘要。
- 完整历史永远保留，compaction 只改变发送给模型的活动上下文。
- 迁移现有 XueXue 会话快照；迁移失败时保留原文件并报告诊断。

验收：任意进程退出点后均可恢复；fork/clone/tree 操作不修改源分支；
compaction 后可以继续对话并回看原始历史。

### 阶段 4：Coding Agent 工具与安全策略

工作项：

- 逐项复刻 Pi 的 `read/write/edit/bash/grep/find/ls` 参数、截断和 details。
- 实现 shell output accumulator、二进制/图片读取、文件修改串行队列和 edit diff。
- 文件系统禁止绕过 canonical workspace 边界和 symlink 规则。
- 工具 allowlist/exclude list、read-only、offline 和 project trust 使用统一策略。
- 将审批实现成扩展钩子/交互请求，不写死在工具内部。

验收：对每个工具运行 Pi 对应 fixture；并发写入不会相互覆盖；取消 Bash 后
进程及其子进程被回收。

### 阶段 5：资源、设置和系统提示

工作项：

- 复刻全局与项目 settings 的深度合并、迁移、原子写入和 trust 限制。
- 按 Pi 路径加载 `AGENTS.md`/`CLAUDE.md`、`SYSTEM.md`、
  `APPEND_SYSTEM.md`、Skills 和 Prompt Templates。
- 保留每项资源的 source info、diagnostic、启用状态和冲突信息。
- 工具定义必须贡献 Pi 对应的 `promptSnippet` 和 `promptGuidelines`；Runtime 根据
  活跃工具、Skills、项目上下文和 system/append system 资源统一重建提示词。
- 复刻 `before_agent_start` 的消息注入和单轮 system prompt 替换，以及 `context`
  事件在每次模型调用前按扩展顺序改写消息的行为。
- 实现 reload，并保证失败资源不会破坏已经可用的 Runtime。
- 项目资源仅在信任后加载；非交互模式遵守默认信任策略。

验收：全局、父目录、项目、package 和显式路径的合并结果与 Pi fixture 一致。

### 阶段 6：插件 ABI 和事件运行器

插件系统必须建立在阶段 1-5 的稳定协议上，分为统一 API 与多个宿主。
详细设计见第 6 节。

验收：工具、命令、Provider、资源路径和关键生命周期钩子可以由插件注册；
插件异常被隔离并生成诊断；reload 后旧监听器和资源全部释放。

### 阶段 7：能力包与安装管理

工作项：

- 实现本地 path、git、JVM JAR/Maven 和 Process 包 source 的解析、安装、删除、
  更新、启停和固定版本。
- 支持 capability manifest 的 extensions、skills、prompts 和 resources 贡献。
- 全局缓存放在应用数据目录，项目包放在 `.xuexue/pi/`；安装采用 staging、
  校验、原子替换和失败回滚。
- 安装和运行前显示来源、解析版本、内容 hash、权限及信任状态；安装过程不得执行
  任意包脚本。

验收：将 Pi 官方示例中的工具、命令、权限门、动态资源、自定义 Provider 和
compaction 扩展等非排除行为移植为 Kotlin/Process fixture，并通过相同语义测试。

### 阶段 8：SDK、JSONL RPC 和 Compose 接线

工作项：

- 提供直接创建 Runtime/Session 的 Kotlin SDK。
- 按 Pi 的 command/response/event 类型实现 stdin/stdout JSONL RPC。
- 支持 prompt、steer、follow-up、abort、模型、队列、compaction、会话树、
  Bash、命令查询和 extension UI request。
- `WorkbenchAgentController` 降为 Runtime 命令适配器。
- Compose UI 收集 StateFlow/AgentEvent，并处理 InteractionRequest；UI 不再
  直接修改会话消息或执行工具。

验收：同一套集成测试可以分别驱动 SDK、RPC 和 Compose adapter，得到相同
Runtime 状态与会话文件。

### 阶段 9：诊断、兼容性和发布

工作项：

- 会话/设置/插件 API schema version 与迁移策略。
- Provider 请求诊断、缓存统计、成本、日志和更新检查；不得包含遥测上传路径。
- 插件 API 兼容测试套件和示例项目。
- Desktop 打包包含 Kotlin Runtime、JVM 插件宿主、Process 插件宿主和 RPC 所需
  平台实现。

验收：干净安装、旧数据升级、离线启动、插件故障和异常退出恢复全部通过。

## 6. 插件系统设计

### 6.1 统一 Headless API

公共模块定义稳定的 `PiExtensionApi`，至少支持：

- `on(event, handler)`：生命周期、session、context、provider、agent、turn、
  message、tool、model、input 和 user bash 事件；
- `registerTool`：schema、执行模式、进度、details、`promptSnippet`、
  `promptGuidelines` 和与宿主无关的展示元数据；
- `registerCommand`：名称、描述、参数补全和 suspend handler；
- `registerProvider`/`unregisterProvider`；
- `sendMessage`、`sendUserMessage`、`setActiveTools` 和 Pi 对应的会话操作；
- `resources_discover`：动态添加 extension、skill、prompt 和资源路径；
- `before_agent_start`：注入自定义消息并可按 Pi 合并规则替换单轮 system prompt；
- `context`：在每次 Provider 调用前按加载顺序替换模型上下文消息；
- `InteractionPort`：select、confirm、input、editor 和 notify。

注册方法的返回类型、事件 handler 顺序、修改/阻止流程时的合并方式和异常传播
均按 Pi 对应 API 复刻。宿主在 reload/unload 时按扩展归属清理监听器、工具、
Provider 和资源，防止重复注册及 classloader 泄漏；不得为方便实现而改变事件语义。

纯终端能力不进入 Headless ABI。仍属于 Headless 流程的选择、确认、输入、编辑和
通知请求映射为通用 `InteractionRequest`，由 Compose、RPC 或测试宿主处理。

### 6.2 插件宿主

| 宿主 | 格式 | 用途 | 隔离方式 |
| --- | --- | --- | --- |
| Built-in | Kotlin object | 内置工具和官方能力 | 与 Runtime 同进程 |
| JVM | JAR + manifest | Kotlin/Java 原生插件 | 独立 classloader、权限门、可卸载 |
| Process | 可执行文件 + JSONL | 不可信或跨语言插件 | 独立进程、最小环境、超时和终止 |

JVM 和 Process 宿主只承载插件代码。Agent 状态机、会话、资源优先级、事件合并、
权限判定和工具调度始终由 Kotlin Runtime 掌控，两个宿主必须通过同一套扩展
conformance 测试。

### 6.3 JVM 插件清单

JVM JAR 在 `META-INF/xuexue-pi-plugin.json` 中提供独立清单，例如：

```json
{
  "schemaVersion": 1,
  "id": "dev.example.review-tools",
  "version": "1.0.0",
  "apiVersion": "1",
  "runtime": "jvm",
  "entrypoint": "dev.example.ReviewToolsPlugin",
  "permissions": ["workspace.read", "workspace.write", "process.exec"],
  "contributes": ["tools", "commands"]
}
```

插件只编译依赖一个小型 `pi-extension-api` 模块。API 使用稳定 DTO、sealed event
和 suspend 接口；不暴露 WorkbenchState、Compose 类型或平台实现类。

### 6.4 信任与权限

加载顺序遵循 Pi 的安全边界：先加载内置和全局插件以参与 `project_trust`，
得到信任结果后才读取项目设置、项目插件和项目 packages。

插件权限最少包括：

- workspace read/write；
- workspace 外路径访问；
- process execution；
- network；
- credential/provider access；
- UI interaction；
- session mutation。

JVM classloader 不是安全沙箱，获得同进程执行权的插件理论上可以绕过权限。
因此第三方插件默认走 Process host；只有内置或用户明确标记可信的 JVM 插件才
允许进程内加载。UI 必须明确展示这个边界，不能宣称 JVM 插件已被沙箱化。

### 6.5 包管理

包管理器保存 source、resolved version/ref、内容 hash、安装时间、scope、启用资源
和权限批准记录。支持：

- `git:url[@ref]`；
- 本地 path；
- `maven:group:artifact:version` 或本地 JVM JAR；
- 带 manifest 的 Process 插件目录或归档。

固定版本和 git ref 默认不自动升级。所有下载先进入临时目录，校验 manifest 和
hash 后原子替换；更新失败保留旧版本。项目包需要 workspace trust，offline 模式
禁止解析或更新远程 source。

## 7. Compose 宿主接线

Compose UI 必须完整承载 Headless 行为：

UI 接线不是复刻完成后的附加层，而是 Runtime 协议的验收宿主。阶段 2 起就必须
让 Agent Core 的事件可被 StateFlow/Flow 订阅；阶段 3-6 的会话、资源、工具和扩展
状态必须通过同一 Runtime 投影；阶段 8 只允许把现有 `WorkbenchAgentController`
降为命令/状态适配器，不得保留第二套 Agent Loop 或直接修改会话。

- 会话树、分叉、克隆、命名、compaction 和恢复；
- 模型/Provider/thinking level、上下文用量和成本；
- structured message、thinking、工具参数、进度、结果和错误；
- steering/follow-up 队列及当前运行阶段；
- extension interaction request 和通知；
- resources/packages/extensions 的来源、冲突、信任、启停、更新和诊断；
- RPC/SDK 状态与 Compose 状态一致。

UI 只发送命令和呈现状态。任何会话写入、工具执行、Provider 切换或扩展注册都
必须由 Runtime 完成。

## 8. 验证策略

每个阶段都执行仓库质量链：

```bash
./gradlew ktlintFormat
./gradlew ktlintCheck detektMainDesktop detektTestDesktop desktopTest koverXmlReport
```

此外建立以下专项测试：

- Pi 行为 fixture：事件顺序、消息转换、队列、错误和 RPC JSON。
- Provider mock server：SSE 分片、tool arguments、重试、取消和错误体。
- 会话故障注入：截断 JSONL、重复 entry、崩溃恢复和迁移回滚。
- 工具边界：symlink、路径穿越、超大输出、并发写和子进程取消。
- 插件 conformance：注册、事件、超时、reload、崩溃、UI request 和权限。
- package fixture：path/git/Maven/JAR/Process、固定版本、更新失败、offline 和 trust。
- SDK/RPC/Compose adapter 三宿主一致性测试。
- 排除项测试：构建产物不包含遥测上传、终端实现或被排除的运行时兼容宿主；
  排除审计不能替代其相邻 Headless 行为的 parity 测试。

## 9. 实施顺序与完成定义

严格按阶段 0 到 9 推进。插件 ABI 依赖消息、事件、会话、Provider、资源和信任
协议，不能提前用 WorkbenchState 回调拼装。实现顺序可以为解除依赖而细分，但
不得以较小的兼容层替代目标 Runtime。

整个 Pi 复刻只有在以下条件同时成立时才算完成：

1. `PI_PARITY_MATRIX.md` 覆盖 Pi 0.80.6 的相关源文件和测试；所有非排除行为均为
   完成，排除项均有与第 1.1 节完全一致的逐项依据。
2. SDK、RPC 和 Compose 使用同一个 Runtime，不存在三套行为实现。
3. 原版 Pi 核心行为 fixture、会话迁移、插件 conformance 和项目质量链全部通过。
4. 设置页不再展示未接线的占位功能；所有可见状态来自真实 Runtime。
5. 完成审计能逐项证明消息、Provider、Agent Loop、工具、会话、compaction、资源、
   系统提示、扩展、能力包、SDK 和 RPC 均达到 Pi 对应语义，且构建中不存在被排除
   内容的实现路径。
