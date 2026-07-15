# Pi Agent Kotlin 复刻计划

## 1. 目标与参考基线

本计划的目标是用 Kotlin 复刻 Pi 的 Headless Runtime，并将其接入 XueXue。
这里的“复刻”指行为、协议、事件顺序、会话语义和扩展点与 Pi 对齐，
不是只提供一个带工具调用的聊天面板，也不是逐行翻译 TypeScript。

参考实现固定为：

- 本地源码：`参考内容/Pi源码/pi-main`
- 参考包版本：`@earendil-works/pi-ai`、`pi-agent-core`、
  `pi-coding-agent` 0.80.6
- 架构要求：`Agent实现路径建议.md`

TUI 不在复刻范围内。Pi 的 TUI 交互请求要转换为与界面无关的协议，
由 Compose、RPC 或测试宿主分别处理。除这个平台适配外，不主动重新设计
Pi 的运行规则、消息角色、事件名称、资源优先级或会话格式。

## 2. 当前实现定位

当前代码已经验证了最小闭环，但它是后续复刻的迁移起点，不是最终架构。

| 领域 | 当前状态 | 与 Pi 的主要差距 |
| --- | --- | --- |
| 模型调用 | OpenAI 兼容 SSE、文本、推理、工具调用 | 缺统一内容块、Provider 目录、图片、OAuth、重试和缓存用量 |
| Agent Loop | 顺序工具循环、取消、简单 steering | 缺完整事件序列、并行工具、队列模式、钩子和错误分类 |
| 工具 | `read/write/edit/bash/grep/find/ls` 可运行 | 参数和输出语义尚未逐项对齐 Pi，缺文件修改队列 |
| 会话 | 多会话和 JSONL 快照 | 不是 Pi 的 append-only entry tree，分叉、树导航和 compaction 不完整 |
| 上下文 | 当前文档、附件、片段、根目录指令文件 | 缺父目录扫描、系统提示组合、资源来源信息和 token 管理 |
| UI | 能显示消息、工具、差异、审批和用量 | UI 仍直接依赖 Desktop/Compose 状态，不是 Headless Runtime 消费者 |
| 扩展与包 | 只有设置页占位 | 未实现 |
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
| `:pi-platform-desktop` | Node/Desktop 能力 | HTTP、文件系统、Shell、凭据库、插件宿主和 RPC 进程 |
| `:app` | Pi interactive mode 的替代者 | Compose 状态投影、用户交互请求和工作台接线 |

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

以下差异必须记录在 parity matrix 中，不能伪装成 Pi 原行为：

- 删除 TUI，并把可表达的交互转换为 Headless `InteractionRequest`。
- Pi 默认不提供工具权限弹窗；XueXue 架构要求 workspace trust、写入、Shell
  和外部路径策略。这些限制由宿主 policy layer 叠加，Agent Loop 本身仍保持
  Pi 语义。
- XueXue 使用自己的应用数据目录和工作区 `.xuexue` 目录，但资源合并、
  启用状态和信任时序要与 Pi 对齐。
- 原版 TypeScript 扩展需要可选 Node compatibility host；纯 Kotlin Runtime
  在没有 Node 时仍完整工作，但不能声称支持这些扩展。

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
- 把 Pi 核心测试按行为分组：Loop、队列、工具、会话、compaction、资源、
  extensions、Provider 和 RPC。

验收：每一个“已完成”状态都必须指向自动化测试，不能以 UI 可见为依据。

### 阶段 1：拆出 `pi-ai` 和公共消息协议

工作项：

- 实现完整内容块、模型、用量、停止原因和稳定错误类型。
- 定义流式 `AssistantMessageEvent`，支持增量文本、thinking 和 tool call 参数。
- 将当前 OpenAI Chat Completions 实现迁入 Provider 接口。
- 添加 OpenAI Responses、Anthropic Messages 和 Google Generative AI；其他
  Provider 在相同接口上逐个补齐。
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
- 实现 reload，并保证失败资源不会破坏已经可用的 Runtime。
- 项目资源仅在信任后加载；非交互模式遵守默认信任策略。

验收：全局、父目录、项目、package 和显式路径的合并结果与 Pi fixture 一致。

### 阶段 6：插件 ABI 和事件运行器

插件系统必须建立在阶段 1-5 的稳定协议上，分为统一 API 与多个宿主。
详细设计见第 6 节。

验收：工具、命令、Provider、资源路径和关键生命周期钩子可以由插件注册；
插件异常被隔离并生成诊断；reload 后旧监听器和资源全部释放。

### 阶段 7：Pi Packages 和原版扩展兼容

工作项：

- 实现 path、npm 和 git source 的解析、安装、删除、更新、启停和固定版本。
- 支持 package manifest 的 extensions/skills/prompts/themes 贡献及传统目录发现。
- 全局缓存放在应用数据目录，项目包放在 `.xuexue/pi/`；安装采用 staging、
  校验、原子替换和失败回滚。
- 引入可选 Node 扩展宿主，运行原版 Pi TypeScript/npm extensions。
- 包脚本默认禁用；安装和运行前显示来源、版本、权限及信任状态。

验收：至少选取 Pi 官方示例中的工具、命令、权限门、动态资源、自定义
Provider 和 compaction 扩展作为兼容测试集。

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
- Provider 请求诊断、缓存统计、成本、日志、更新检查和可选遥测。
- 插件 API 兼容测试套件和示例项目。
- Desktop 打包包含所需宿主；没有 Node 时清楚标记 TS 插件不可用，
  Kotlin Runtime 和 JVM 插件仍可工作。

验收：干净安装、旧数据升级、离线启动、插件故障和异常退出恢复全部通过。

## 6. 插件系统设计

### 6.1 统一 Headless API

公共模块定义稳定的 `PiExtensionApi`，至少支持：

- `on(event, handler)`：生命周期、session、context、provider、agent、turn、
  message、tool、model、input 和 user bash 事件；
- `registerTool`：schema、执行模式、进度、details 和可选 UI 展示元数据；
- `registerCommand`：名称、描述、参数补全和 suspend handler；
- `registerProvider`/`unregisterProvider`；
- `sendMessage`、`sendUserMessage`、`setActiveTools` 和 system prompt 操作；
- `resources_discover`：动态添加 skill/prompt/theme 路径；
- `InteractionPort`：select、confirm、input、editor、notify、status 和 widget。

所有注册返回 `Disposable`。插件 reload/unload 时按逆序 dispose，防止重复事件、
残留工具和 classloader 泄漏。事件 handler 默认按加载顺序执行；能够修改或阻止
流程的事件采用 Pi 的合并规则，并设置单 handler 超时。

TUI 专属能力不进入 Headless ABI，例如 raw terminal input、自定义终端 editor、
ANSI theme component 和 overlay component。可以表达为通用 InteractionRequest 的
能力映射到 Compose；不能映射的原版扩展在加载诊断中标为 `tui-only`。

### 6.2 插件宿主

| 宿主 | 格式 | 用途 | 隔离方式 |
| --- | --- | --- | --- |
| Built-in | Kotlin object | 内置工具和官方能力 | 与 Runtime 同进程 |
| JVM | JAR + manifest | Kotlin/Java 原生插件 | 独立 classloader、权限门、可卸载 |
| Process | 可执行文件 + JSONL | 不可信或跨语言插件 | 独立进程、最小环境、超时和终止 |
| Pi TS compatibility | npm/git + Node host | 运行原版 Pi TypeScript 扩展 | 专用 Node 进程，通过 JSONL bridge |

不使用 GraalJS 直接加载 Pi npm 包。Pi 扩展依赖 Node 模块、动态 import、npm
依赖和进程 API，GraalJS 很难获得可靠兼容。Node 兼容宿主只承担扩展执行，
Agent 状态机、会话、权限判定和工具调度仍由 Kotlin Runtime 掌控。

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

### 6.4 TypeScript 兼容桥

Node host 加载 Pi extension factory，并在进程内提供与 Pi 同名的 API facade。
注册信息和事件通过 JSONL bridge 往返：

```text
Kotlin Runtime                    Node extension host
register request <------------- extension factory
event envelope  ----------------> handler
hook result     <---------------- handler result
tool execute    ----------------> extension tool
tool progress   <---------------- partial result
UI request      <---------------- ctx.ui.*
UI response     ----------------> extension promise
```

桥协议必须带 request id、session id、extension id、deadline 和 schema version。
Node host 崩溃时，相关调用返回结构化错误，其他插件和 Agent 会话继续运行。

### 6.5 信任与权限

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
因此第三方插件默认走 Process/Node host；只有内置或用户明确标记可信的 JVM
插件才允许进程内加载。UI 必须明确展示这个边界，不能宣称 JVM 插件已被沙箱化。

### 6.6 包管理

包管理器保存 source、resolved version/ref、内容 hash、安装时间、scope、启用资源
和权限批准记录。支持：

- `npm:name[@version]`；
- `git:url[@ref]`；
- 本地 path；
- JVM JAR/Maven 坐标作为 Kotlin 扩展来源。

固定版本和 git ref 默认不自动升级。所有下载先进入临时目录，校验 manifest 和
hash 后原子替换；更新失败保留旧版本。项目包需要 workspace trust，offline 模式
禁止解析或更新远程 source。

## 7. UI 工作

Compose UI 不复刻 Pi TUI 的外观，但必须完整承载 Headless 行为：

- 会话树、分叉、克隆、命名、compaction 和恢复；
- 模型/Provider/thinking level、上下文用量和成本；
- structured message、thinking、工具参数、进度、结果和错误；
- steering/follow-up 队列及当前运行阶段；
- extension UI request、widget、status 和通知；
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
- package fixture：path/npm/git、固定版本、更新失败、offline 和 trust。
- SDK/RPC/Compose adapter 三宿主一致性测试。

## 9. 实施顺序与完成定义

严格按阶段 0 到 9 推进。插件 ABI 依赖消息、事件、会话、Provider、资源和信任
协议，不能提前用 WorkbenchState 回调拼装；原版 TypeScript 兼容宿主则在 JVM
插件 ABI 稳定后实现。

整个 Pi 复刻只有在以下条件同时成立时才算完成：

1. `PI_PARITY_MATRIX.md` 中 Headless 项目全部为完成或有明确、经确认的
   TUI-only 排除理由。
2. SDK、RPC 和 Compose 使用同一个 Runtime，不存在三套行为实现。
3. 原版 Pi 核心行为 fixture、会话迁移、插件 conformance 和项目质量链全部通过。
4. 设置页不再展示未接线的占位功能；所有可见状态来自真实 Runtime。
5. 文档准确列出不能直接兼容的 Pi TUI 扩展和缺少 Node 时的限制。
