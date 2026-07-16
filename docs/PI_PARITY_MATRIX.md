# Pi 0.80.6 Kotlin Parity Matrix

## 1. 基线与判定规则

- 参考根目录：`参考内容/Pi源码/pi-main`
- 参考包：`packages/ai`、`packages/agent`、`packages/coding-agent`
- 固定版本：`0.80.6`
- 开发指导：`docs/PI_AGENT_PORTING_PLAN.md`
- 唯一排除：TUI、网络遥测、Node 运行时实现、TypeScript 扩展兼容

状态只使用以下值：

| 状态 | 含义 |
| --- | --- |
| `pending` | 尚未开始，或只有旧实现而未按 Pi 语义审计 |
| `in-progress` | 已有 Kotlin 对应实现，但行为或测试尚未齐全 |
| `verified` | Kotlin 实现和移植测试均证明与 Pi 对应语义一致 |
| `excluded` | 属于唯一排除范围，并有明确源码依据 |

`verified` 必须同时指向 Kotlin 实现和自动化测试。仅能编译、UI 可见或接口名称
相同均不能判为完成。原文件依赖 Node API 时，只排除该平台实现；文件表达的
非排除 Headless 语义仍须使用 JVM 能力复刻。

当前源码清点：

| 包 | 源文件数 | 当前状态 |
| --- | ---: | --- |
| `packages/ai/src` | 149 | `pending` |
| `packages/agent/src` | 25 | `pending` |
| `packages/coding-agent/src` | 172 | `pending` |
| 合计 | 346 | `pending` |

## 2. 明确排除项

| Pi 源码 | 排除内容 | 保留边界 | 状态 |
| --- | --- | --- | --- |
| `packages/coding-agent/src/modes/interactive/**` | 终端 UI、组件、主题和键位呈现 | Agent Session、交互语义和状态仍由 Compose/RPC 承载 | `excluded` |
| `packages/coding-agent/src/cli/startup-ui.ts` | 终端启动界面 | 非 UI 启动参数和 Runtime 创建语义仍需审计 | `excluded` |
| `packages/coding-agent/src/core/keybindings.ts` | 终端键位系统 | Headless 命令不排除 | `excluded` |
| `packages/coding-agent/src/core/footer-data-provider.ts` | TUI footer 数据投影 | 用量、模型和会话数据本身不排除 | `excluded` |
| `packages/coding-agent/src/core/telemetry.ts` | 网络遥测 | 本地诊断、日志、timings、用量和成本不排除 | `excluded` |
| `packages/agent/src/node.ts` | Node 入口与导出 | `agent.ts`、`agent-loop.ts` 和公共协议不排除 | `excluded` |
| `packages/agent/src/harness/env/nodejs.ts` | Node 环境实现 | 等价环境能力使用 Kotlin/JVM 实现 | `excluded` |
| `packages/ai/src/utils/node-http-proxy.ts` | Node HTTP 代理安装实现 | Provider 代理行为由 JVM HTTP transport 承载 | `excluded` |
| `packages/coding-agent/src/bun/**` | Bun/Node 启动适配 | Bedrock 注册和环境恢复的非平台语义另行审计 | `excluded` |
| TypeScript extension factory/npm execution paths | TS 加载和直接兼容 | 扩展 API、事件和资源语义移植到 Kotlin/Process 插件 | `excluded` |

### 2.1 已记录的宿主路径差异

| Pi 行为 | XueXue 对应 | 保持不变的语义 | 状态 |
| --- | --- | --- | --- |
| 项目设置位于 `{cwd}/.pi/settings.json` | 位于 `{cwd}/.xuexue/pi/settings.json` | 全局/项目优先级、未受信任项目隔离、按字段保存和首次写入才创建目录 | `in-progress` |

## 3. `packages/ai` 对照

| Pi 源码 | Kotlin 目标 | 主要验证 | 状态 |
| --- | --- | --- | --- |
| `src/types.ts` | `:pi-ai` 公共消息、内容块、模型、选项和事件协议 | JSON round-trip、类型不变量、事件终止 | `in-progress` |
| `src/utils/event-stream.ts` | `AssistantMessageEventStream` | 队列、等待者、完成结果、结束语义 | `in-progress` |
| `src/utils/diagnostics.ts` | Assistant diagnostic DTO 与辅助函数 | thrown value、error/code/details 保留 | `in-progress` |
| `src/models.ts`、`models.generated.ts` | 模型查询和内置模型目录 | provider/model 查找、覆盖、排序 | `pending` |
| `src/image-models*.ts`、`images-models.ts` | 图片模型目录和协议 | 模型查找、输入输出能力 | `pending` |
| `src/providers/*.models.ts` | 生成的 Provider 模型元数据 | 与参考快照逐项一致 | `pending` |
| `src/providers/*.ts` | Provider 注册定义 | API、base URL、headers、OAuth 和模型绑定 | `pending` |
| `src/api/lazy.ts`、`*.lazy.ts` | 延迟 Provider 装载 | 首次调用、缓存和错误传播 | `pending` |
| `src/api/openai-completions.ts` | OpenAI Completions adapter | 对应 Pi provider tests 和 SSE fixture | `pending` |
| `src/api/openai-responses*.ts` | OpenAI Responses adapter | payload、reasoning、tool result、终止事件 | `pending` |
| `src/api/openai-codex-responses.ts` | Codex Responses adapter | OAuth headers、缓存 affinity、stream | `pending` |
| `src/api/azure-openai-responses.ts` | Azure Responses adapter | URL/deployment/version/header 解析 | `pending` |
| `src/api/anthropic-messages.ts` | Anthropic Messages adapter | thinking、cache、tool reference、SSE | `pending` |
| `src/api/google-*.ts` | Google AI 与 Vertex adapters | message/tool 转换、签名、认证 | `pending` |
| `src/api/bedrock-converse-stream.ts` | Bedrock adapter | endpoint、signing、thinking、stream | `pending` |
| `src/api/mistral-conversations.ts` | Mistral adapter | schema、reasoning 和 streaming | `pending` |
| `src/api/transform-messages.ts` | 跨 Provider 消息转换 | Pi cross-provider handoff tests | `pending` |
| `src/api/*cache*.ts` | Prompt cache 规则 | retention、cache marker 和 usage | `pending` |
| `src/auth/**`、`src/oauth.ts` | 凭据、OAuth 和解析上下文 | 登录、刷新、取消和持久化 fixture | `pending` |
| `src/env-api-keys.ts` | 环境 API key 解析 | provider aliases 和 precedence | `pending` |
| `src/images.ts`、`images-api-registry.ts` | 图片生成调度 | abort、error、结果和用量 | `pending` |
| `src/session-resources.ts` | 会话资源生命周期 | create/dispose 和异常清理 | `pending` |
| `src/compat.ts`、`legacy-api-aliases.ts` | 公共兼容 facade | stream/complete/model aliases | `pending` |
| `src/utils/abort-signals.ts` | 取消信号组合 | 原因、竞态和资源清理 | `pending` |
| `src/utils/deferred-tools.ts` | 延迟工具规则 | tool reference load points | `pending` |
| `src/utils/estimate.ts`、`overflow.ts` | token 估算与溢出识别 | Pi context tests | `pending` |
| `src/utils/retry.ts`、`error-body.ts` | 重试与错误体 | Retry-After、delay cap、body passthrough | `pending` |
| `src/utils/json-parse.ts`、`validation.ts` | JSON/schema 解析与校验 | partial JSON、lax content、invalid schema | `pending` |
| `src/utils/hash.ts`、`headers.ts`、`provider-env.ts` | 通用 Provider 辅助逻辑 | 对应单元测试 | `pending` |
| `src/utils/sanitize-unicode.ts` | Unicode 清理 | surrogate fixture | `pending` |

## 4. `packages/agent` 对照

| Pi 源码 | Kotlin 目标 | 主要验证 | 状态 |
| --- | --- | --- | --- |
| `src/types.ts` | `:pi-agent-core` Agent 状态、工具和事件协议 | `AgentLoopTest*`、`AgentStateTest` | `verified` |
| `src/agent-loop.ts` | Agent Loop | 原 20 项行为映射到 `AgentLoopTest*`，另含并发预检回归 | `verified` |
| `src/agent.ts` | Stateful Agent facade | 原 19 项行为映射到 `Agent*Test`，另含 StateFlow/Flow 一致性 | `verified` |
| `src/proxy.ts` | Agent proxy 语义 | 调用转发和错误传播 | `pending` |
| `src/harness/types.ts` | Harness 公共协议 | 与核心类型边界一致 | `pending` |
| `src/harness/agent-harness.ts` | Harness orchestration | stream、session、compaction | `pending` |
| `src/harness/messages.ts` | 自定义消息转换 | Agent message 转 model message | `pending` |
| `src/harness/system-prompt.ts` | Skill system prompt 片段 | escaping、visibility 和顺序 | `pending` |
| `src/harness/skills.ts`、`prompt-templates.ts` | Skills 与模板发现 | frontmatter、路径、覆盖和诊断 | `pending` |
| `src/harness/session/**` | Session repo/storage/tree | JSONL、memory、UUID、恢复和遍历 | `pending` |
| `src/harness/compaction/**` | compaction 与分支摘要 | 阈值、summary、恢复和继续运行 | `pending` |
| `src/harness/utils/**` | truncate 与 shell output | 字节/行截断和尾部输出 | `pending` |

## 5. `packages/coding-agent` 对照

| Pi 源码 | Kotlin 目标 | 主要验证 | 状态 |
| --- | --- | --- | --- |
| `src/core/agent-session*.ts` | `AgentSession`、runtime 和 services | 当前 `:pi-coding-agent:desktopTest` 共 112 项；覆盖 prompt/custom、两类队列、ordered persistence、settled/abort、Bash 延迟落盘、统计、指数 retry、手动/阈值/overflow compaction 与取消。`AgentSessionRuntime`、services、资源/扩展回调和原测试逐文件映射待补 | `in-progress` |
| `src/core/session-manager.ts`、`session-cwd.ts` | `:pi-coding-agent` append-only 会话树、JSONL 存储和 cwd 校验 | 50 项 session/context/codec/messages/persistence/cwd 测试；待完成原测试逐文件映射 | `in-progress` |
| `src/core/compaction/**` | `Compaction.kt`、`CompactionGeneration.kt`、`CompactionUtils.kt` | `CompactionTest`、`CompactionGenerationTest`、`CompactionUtilsTest`、`AgentSessionCompactionTest`、`AgentSessionAutoCompactionTest` 覆盖 token 估算、cut point、file operations、summary/reasoning、手动/阈值/overflow 与取消；extension compaction hook 待移植 | `in-progress` |
| `src/core/messages.ts` | `:pi-coding-agent` bash/custom/branch/compaction messages | `SessionMessagesTest`、`SessionContextTest`、`AgentSessionBashPersistenceTest` | `in-progress` |
| `src/core/system-prompt.ts` | `resources/SystemPrompt.kt` | `SystemPromptTest` 覆盖自定义/追加提示、上下文、Skills、工具可见性、guideline 去重、日期和 cwd 顺序；运行时日期注入及 AgentSession 重建仍待接线 | `in-progress` |
| `src/core/resource-loader.ts` | `resources/ResourceLoader.kt`、`ResourceFileSystem.kt` | `ResourceLoaderTest` 覆盖 AGENTS/CLAUDE 根至 cwd 顺序、SYSTEM/APPEND_SYSTEM、项目资源覆盖、collision 诊断和 trust 边界；extensions/package 资源、metadata origin 与异步 reload lifecycle 待补 | `in-progress` |
| `src/core/skills.ts`、`prompt-templates.ts` | `SkillLoader.kt`、`PromptTemplateLoader.kt`、`PromptTemplates.kt` | `PromptTemplatesTest`、`FrontmatterTest`、`ResourceLoaderTest` 覆盖 quoted arguments、`$1`/`$@`/默认值/slice、non-recursive replacement、frontmatter scalar/block、技能递归发现、first-win 与 XML escaping；完整 YAML、ignore 文件和 package metadata 待补 | `in-progress` |
| `src/core/settings-manager.ts` | `settings/SettingsManager.kt`、`SettingsStorage.kt` | 8 项 SettingsManager/desktop storage 测试覆盖深合并、旧字段迁移、外部编辑保留、信任、reload 错误、AgentSession retry/compaction 映射和 `.xuexue/pi` 延迟创建；完整设置表面、跨进程文件锁与资源 reload 接线待补 | `in-progress` |
| `src/core/model-registry.ts`、`model-resolver.ts` | 模型注册和解析 | dynamic provider、scoped model 和 auth | `pending` |
| `src/core/auth-*.ts`、`resolve-config-value.ts` | 凭据存储和配置解析 | migration、env、command 和 OAuth | `pending` |
| `src/core/extensions/types.ts` | Kotlin Extension API/DTO | API surface snapshot | `pending` |
| `src/core/extensions/runner.ts` | 扩展事件运行器 | 顺序、链式修改、错误和 lifecycle | `pending` |
| `src/core/extensions/loader.ts` | Kotlin/Process 插件发现加载 | reload、source、trust 和故障隔离 | `pending` |
| `src/core/extensions/wrapper.ts` | 工具与 handler 包装 | context、tool events 和 errors | `pending` |
| `src/core/event-bus.ts` | 扩展共享事件总线 | emit/on/dispose 和扩展隔离 | `pending` |
| `src/core/package-manager.ts` | 能力包管理器 | path/git/Maven/Process、rollback、offline | `pending` |
| `src/core/project-trust.ts`、`trust-manager.ts` | 项目信任时序 | global/project load boundary | `pending` |
| `src/core/tools/read.ts` | read 工具 | 文本、图片、offset/limit、truncate | `pending` |
| `src/core/tools/write.ts`、`edit.ts`、`edit-diff.ts` | write/edit 工具 | exact replacement、diff、legacy args | `pending` |
| `src/core/tools/bash.ts`、`bash-executor.ts` | bash 工具 | streaming、cancel、process tree、late output | `pending` |
| `src/core/tools/grep.ts`、`find.ts`、`ls.ts` | 搜索与目录工具 | glob、ignore、limit 和输出格式 | `pending` |
| `src/core/tools/file-mutation-queue.ts` | 文件修改队列 | 同路径串行、异常释放 | `pending` |
| `src/core/tools/output-accumulator.ts`、`truncate.ts` | 输出累计与截断 | byte/line limits 和 metadata | `pending` |
| `src/core/tools/path-utils.ts` | 工具路径语义 | cwd、absolute、symlink 和 display path | `pending` |
| `src/core/tools/tool-definition-wrapper.ts` | ToolDefinition 转换 | schema、prompt metadata、progress | `pending` |
| `src/core/cache-stats.ts`、`timings.ts` | 本地统计 | token/cost/cache/timing 计算 | `pending` |
| `src/core/diagnostics.ts`、`output-guard.ts` | 本地诊断与输出保护 | error capture、stdout cleanliness | `pending` |
| `src/core/http-dispatcher.ts` | HTTP dispatch 平台接口 | proxy、headers、lifecycle | `pending` |
| `src/core/exec.ts` | 进程执行协议 | cwd/env/stdout/stderr/exit/cancel | `pending` |
| `src/core/sdk.ts` | Kotlin SDK | Runtime 创建、资源、模型、stream options | `pending` |
| `src/modes/rpc/**`、`src/rpc-entry.ts` | JSONL RPC | command/response/event 与错误语义 | `pending` |
| `src/modes/print-mode.ts` | 非交互输出模式 | stdout、错误和退出语义 | `pending` |
| `src/core/export-html/**` | 会话 HTML 导出 | escaping、whitespace、skill block 和 XSS | `pending` |
| `src/utils/frontmatter.ts`、`json.ts`、`mime.ts` | 资源解析工具 | Pi 单元测试 | `pending` |
| `src/utils/image-*.ts`、`exif-orientation.ts` | 图片处理 | resize、format、orientation 和 caller tests | `pending` |
| `src/utils/git.ts`、`fs-watch.ts`、`paths.ts` | Desktop 资源平台能力 | git refs、watch failure、目录规则 | `pending` |
| `src/utils/shell.ts`、`child-process.ts` | Desktop 进程平台能力 | shell selection、signals、cleanup | `pending` |
| `src/cli/*.ts` 中非 UI 行为 | Compose/RPC 启动适配 | args、initial message、files、model list、trust | `pending` |
| `src/config.ts`、`migrations.ts` | 应用配置和迁移 | path、version 和 migration fixtures | `pending` |

## 6. 当前 XueXue 实现判定

| 当前文件 | 判定 | 后续处理 |
| --- | --- | --- |
| `src/commonMain/kotlin/dev/xuexue/core/PiAgentRuntime.kt` | 最小顺序工具循环，不满足 Pi Agent 协议 | 由 `:pi-agent-core` 替换，保留迁移适配期 |
| `src/desktopMain/kotlin/dev/xuexue/platform/AgentClient.kt` | 单一 OpenAI-compatible adapter | 迁入 `:pi-ai` Provider 边界 |
| `src/desktopMain/kotlin/dev/xuexue/platform/DesktopAgentTools.kt` | 工具原型 | 按 Pi 工具 fixture 重写到 coding/platform 模块 |
| `src/desktopMain/kotlin/dev/xuexue/WorkbenchAgentController.kt` | 已调用新 Agent 的 `prompt/steer/abort`，仍承担旧会话与配置投影 | 随 `AgentSession` 落地降为 Runtime command/state adapter |
| `src/desktopMain/kotlin/dev/xuexue/platform/DesktopPiAgentAdapter.kt` | 旧 `AgentClient`/工具到 `:pi-agent-core` 的临时平台桥 | 由正式 Provider 和 platform capability 实现替换 |
| `src/desktopMain/kotlin/dev/xuexue/WorkbenchAgentProjection.kt` | 将 `AgentEvent` 临时投影到旧 Compose 消息列表 | `AgentSession` 成为唯一会话状态后删除 |
| `pi-coding-agent/src/commonMain/kotlin/dev/xuexue/pi/coding/AgentSession*.kt` | 已建立正式会话 facade、状态和事件协议；已覆盖消息落盘、队列、settled、Bash 延迟、统计、retry 与 compaction | 继续移植 resources/extensions/runtime，再替换 Compose 旧会话状态 |
| `pi-coding-agent/src/**/session/**` | 已建立 Pi v3 session entry/tree/context 与 Desktop JSONL 存储 | 继续补齐原 session tests，然后接入正式 `AgentSession` |
| `.xuexue/agent-sessions.jsonl` | 非 Pi append-only entry tree | 提供一次性迁移器，原文件失败时保留 |

## 6.1 Compose 接线不变量

| 链路 | 必须满足的行为 | 状态 |
| --- | --- | --- |
| `AgentSession/Runtime -> StateFlow<AgentState>` | AgentSession 已输出包含 Agent、队列和会话元数据的 StateFlow；`AgentViews` 目前仍收集底层 AgentState，须替换 Compose 旧消息状态 | `in-progress` |
| `AgentSession/Runtime -> Flow<AgentEvent>` | AgentSession 已输出同源会话事件和有序订阅；Compose 仍订阅底层 AgentEvent，须删除旧消息投影并接入 RPC adapter | `in-progress` |
| `WorkbenchAgentController -> Runtime commands` | `prompt/steer/abort` 已接线；仍须迁移 follow-up、session 和 interaction response | `in-progress` |
| `AgentViews -> InteractionRequest` | confirm/select/input/editor/notify 通过协议往返，不能在 UI 内执行工具或写会话 | `pending` |
| Compose、SDK、RPC 三宿主 | 相同输入产生相同 Runtime 状态、事件和 session entry | `pending` |

## 7. 阶段门

1. 每次把条目标为 `verified` 时，同一提交必须增加或指向对应测试。
2. 每个 Pi test 文件必须最终映射为 Kotlin test、排除项或由更底层 fixture 覆盖，
   并记录具体测试名称。
3. Provider 网络测试可以用确定性 mock server 替代真实凭据，但 payload、stream、
   error、retry 和 usage 语义不能缩减。
4. 完成审计前重新枚举三个参考 `src` 和 `test` 目录；新增、遗漏或未分类文件均使
   总体目标保持未完成。
