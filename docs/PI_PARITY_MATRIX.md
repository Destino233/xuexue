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
| `src/models.ts`、`models.generated.ts` | `pi-ai/ModelRegistry.kt`、`defaultModelCatalog()` | `ModelRegistryTest` 覆盖 custom `models.json`、provider/model override、URL/comment 解析、exact/canonical/ambiguous/glob/thinking suffix；脚本从 Pi 0.80.6 导入 1057 个文本模型 | `in-progress` |
| `src/image-models*.ts`、`images-models.ts` | `ImagesModel`、`ImagesContext`、`AssistantImages` 和图像 registry | `ProviderTest.openRouterImageProviderDecodesImageAndUsage`、`ProviderErrorTest.openRouterImageErrorEventRetainsGatewayBody` 覆盖输入、data URL、usage、状态码和网关错误体；脚本导入 35 个图像模型 | `in-progress` |
| `src/providers/*.models.ts` | `PiGeneratedModelCatalog.kt`、`PiGeneratedImageModelCatalog.kt` | 35 个 provider catalog 和图像目录的确定性生成脚本；完整元数据语义仍需逐项快照校验 | `in-progress` |
| `src/providers/*.ts` | Provider 注册定义 | API、base URL、headers、OAuth 和模型绑定 | `pending` |
| `src/api/lazy.ts`、`*.lazy.ts` | `pi-ai/LazyProvider.kt`、`LazyPiImageProvider` | `LazyProviderTest` 覆盖首次调用加载、成功缓存、加载错误转终止事件和错误缓存；Provider 元数据/API 不匹配会在加载边界失败 | `verified` |
| `src/api/openai-completions.ts` | `OpenAiCompletionsProvider` | SSE 文本/tool/usage/error fixture；Prompt Cache key Unicode 64 码点截断、short/long/none、代理兼容开关、session-affinity 和 Anthropic cache marker 已覆盖；同时承载 Mistral-compatible endpoint | `in-progress` |
| `src/api/openai-responses*.ts` | `OpenAiResponsesProvider` | payload、reasoning、独立 function-call item、参数 delta/done、usage/终止 fixture；provider-neutral cache key、`store=false`、24h retention、none、session headers 和 `supportsToolSearch` 的 immediate/tool-search call/output/defer_loading 已覆盖 | `in-progress` |
| `src/api/openai-codex-responses.ts` | `OpenAiCodexResponsesProvider` | `OpenAiCodexResponsesProviderTest` 覆盖 JWT `chatgpt-account-id`、Codex `/codex/responses` payload、低 verbosity、加密 reasoning include、tool call/usage SSE、session headers 和 429 `Retry-After` 重试；JVM 公共 transport 当前统一走 SSE，WebSocket 连接复用仍待补 | `in-progress` |
| `src/api/azure-openai-responses.ts` | `OpenAiResponsesProvider` 的 Azure 分支 | `ProviderTest.azureResponsesUsesDeploymentBaseUrlCacheAndReasoningOptions`、`azureResponsesResolvesResourceAndDeploymentEnvironment`、`azureResponsesReportsInvalidBaseUrlAsProviderError` 覆盖 URL 规范化、deployment/version、认证、缓存键、`store=false`、reasoning 和错误 | `in-progress` |
| `src/api/anthropic-messages.ts` | `AnthropicMessagesProvider` | thinking、tool input 分片、空初始 input、usage、SSE fixture；system/最后 user block/tool 的 cache marker、long TTL 兼容、none、deferred `defer_loading`/`tool_reference`、全 deferred immediate 回退和 OAuth 工具名标准化已覆盖 | `in-progress` |
| `src/api/google-*.ts` | `GoogleGenerativeAiProvider` | message/tool 转换、thinking、重复 function-call 分片合并、usage fixture；Vertex API-key/project/location path 和显式 access-token env 已测试，ADC credential file/refresh 仍待补 | `in-progress` |
| `src/api/bedrock-converse-stream.ts` | `BedrockConverseProvider`、`AwsSigV4` | `ProviderTest.bedrockConverseBuildsPayloadUsesBearerAuthAndDecodesToolStream`、`bedrockConverseSignsSigV4RequestWithScopedEnvironment`、`bedrockConverseDecodesAwsEventStreamFrames` 覆盖 Converse payload/tool、Bearer、SigV4、AWS Event Stream、usage/stop；profile 文件/完整 AWS credential chain、proxy 和 cache/deferred 细节仍待补 | `in-progress` |
| `src/api/mistral-conversations.ts` | `OpenAiCompletionsProvider` 的 Mistral-compatible 分支 | `/v1/chat/completions` 路径、stream payload 和 SSE fixture | `in-progress` |
| `src/api/transform-messages.ts` | `pi-ai/MessageTransform.kt` | `MessageTransformTest` 覆盖无视觉模型图片占位合并、跨模型 thinking/signature 处理、错误 assistant 清理和孤立 tool call 的 synthetic error result；五个内置 Provider 在请求边界统一调用，原始 session history 不变 | `in-progress` |
| `src/api/*cache*.ts` | `pi-ai/PromptCache.kt` 与三类 Provider | `ProviderTest` 覆盖 `PI_CACHE_RETENTION` 默认值、none/short/long、OpenAI key Unicode 截断、兼容代理和 Anthropic marker；Bedrock cache point、跨 Provider cache usage 与完整 deferred-tool cache 语义仍待补 | `in-progress` |
| `src/auth/**`、`src/oauth.ts` | `pi-ai/AuthStore.kt` | `AuthStoreTest` 覆盖 runtime/stored/environment precedence、credential-scoped env、OAuth expiry view；文件锁持久化、刷新/login/cancel OAuth 仍待补 | `in-progress` |
| `src/env-api-keys.ts` | `findEnvironmentApiKeys()`、`InMemoryAuthStore` | `AuthStoreTest` 覆盖主要 provider aliases 和 environment source status；完整 ambient AWS/Vertex credential discovery待补 | `in-progress` |
| `src/images.ts`、`images-api-registry.ts` | `ImagesApiRegistry.kt`、`ProviderRegistry` 图像分发 | `ImagesApiRegistryTest` 覆盖按 API 注册/替换、sourceId、未知 API、provider 能力不匹配和空 API 拒绝；生成结果仍需补齐 abort/error/usage 端到端 fixture | `in-progress` |
| `src/session-resources.ts` | `pi-ai/SessionResources.kt`、`AgentSession.shutdown/dispose` | `SessionResourcesTest` 覆盖注册、幂等注销、sessionId 传递、聚合错误和继续清理；AgentSession 最终释放路径调用全局资源清理 | `verified` |
| `src/compat.ts`、`legacy-api-aliases.ts` | 公共兼容 facade | stream/complete/model aliases | `pending` |
| `src/utils/abort-signals.ts` | 取消信号组合 | 原因、竞态和资源清理 | `pending` |
| `src/utils/deferred-tools.ts` | `DeferredTools.kt`、Anthropic/OpenAI Responses/Codex payload 分支 | `DeferredToolsTest`、`ProviderTest.openAiResponsesUsesNativeDeferredToolSearch`、`openAiResponsesDoesNotSearchWhenCapabilityIsDisabled`、`anthropicMessagesUsesDeferredReferencesAndMarksLateTools`、`anthropicMessagesKeepsOneImmediateToolWhenAllAreDeferred`、`anthropicOAuthNormalizesDeferredToolNames`、`OpenAiCodexResponsesProviderTest`；共享拆分、去重、已用工具保持 immediate、原生加载点和 duplicate load guard 已覆盖；deferred cache 细节与完整 Pi harness 对照仍待补 | `in-progress` |
| `src/utils/estimate.ts`、`overflow.ts` | token 估算与溢出识别 | Pi context tests | `pending` |
| `src/utils/retry.ts`、`error-body.ts` | 重试与错误体 | `ProviderSupport.kt` 统一保留非 2xx status/body、空 JSON 对象处理和 4000 字符截断；`ProviderErrorTest` 覆盖 403/429、空体、超长体、OpenAI Completions 和 OpenRouter Images 网关原因；Codex adapter 已覆盖状态码、数字/毫秒/HTTP-date `Retry-After`、429 delay cap 和网络重试 | `in-progress` |
| `src/utils/json-parse.ts`、`validation.ts` | `JsonRepair.kt`、`PartialJsonParser.kt`、`StreamingJson.kt`、`ToolArgumentValidation.kt` | `JsonParsingTest` 覆盖修复/部分 JSON；`ToolArgumentValidationTest` 覆盖 Pi primitive coercion、嵌套 properties、required、additionalProperties、allOf/anyOf/oneOf 和错误格式；复杂 TypeBox-only 关键字仍待补 | `in-progress` |
| `src/utils/hash.ts`、`headers.ts`、`provider-env.ts` | 通用 Provider 辅助逻辑 | 对应单元测试 | `pending` |
| `src/utils/sanitize-unicode.ts` | Unicode 清理 | surrogate fixture | `pending` |

## 4. `packages/agent` 对照

| Pi 源码 | Kotlin 目标 | 主要验证 | 状态 |
| --- | --- | --- | --- |
| `src/types.ts` | `:pi-agent-core` Agent 状态、工具和事件协议 | `AgentLoopTest*`、`AgentStateTest` | `verified` |
| `src/agent-loop.ts` | Agent Loop、`ToolExecution.kt` | 原 20 项行为映射到 `AgentLoopTest*`，另含并发预检回归；工具执行按 `prepare -> validateToolArguments -> before hook -> execute`，schema/coercion 由 `ToolArgumentValidationTest` 覆盖 | `in-progress` |
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
| `src/core/agent-session*.ts` | `AgentSession`、runtime 和 services | 当前 `:pi-coding-agent:desktopTest` 共 163 项；覆盖 prompt/custom、两类队列、ordered persistence、settled/abort、Bash 延迟落盘、统计、指数 retry、手动/阈值/overflow compaction 与取消，以及资源提示词重建、`/skill:` 和模板命令展开；`AgentSessionRuntime` 已覆盖 startup/new/resume/fork/shutdown 生命周期，Compose 控制器已开始接入，旧 UI 投影待移除 | `in-progress` |
| `src/core/session-manager.ts`、`session-cwd.ts` | `:pi-coding-agent` append-only 会话树、JSONL 存储和 cwd 校验 | 50 项 session/context/codec/messages/persistence/cwd 测试；待完成原测试逐文件映射 | `in-progress` |
| `src/core/compaction/**` | `Compaction.kt`、`CompactionGeneration.kt`、`CompactionUtils.kt` | `CompactionTest`、`CompactionGenerationTest`、`CompactionUtilsTest`、`AgentSessionCompactionTest`、`AgentSessionAutoCompactionTest` 覆盖 token 估算、cut point、file operations、summary/reasoning、手动/阈值/overflow 与取消；extension compaction hook 待移植 | `in-progress` |
| `src/core/messages.ts` | `:pi-coding-agent` bash/custom/branch/compaction messages | `SessionMessagesTest`、`SessionContextTest`、`AgentSessionBashPersistenceTest` | `in-progress` |
| `src/core/system-prompt.ts` | `resources/SystemPrompt.kt` | `SystemPromptTest` 与 `AgentSessionResourcesTest` 覆盖自定义/追加提示、上下文、Skills、工具可见性、guideline 去重、日期和 cwd 顺序，以及初始化/reload 重建；自定义 `SYSTEM.md` 不注入工具段，默认提示词注入工具元数据 | `in-progress` |
| `src/core/resource-loader.ts` | `resources/ResourceLoader.kt`、`ResourceFileSystem.kt` | `ResourceLoaderTest` 与 `AgentSessionResourcesTest` 覆盖 AGENTS/CLAUDE 根至 cwd 顺序、SYSTEM/APPEND_SYSTEM、项目资源覆盖、collision 诊断、trust 边界、`.gitignore`/`.ignore`/`.fdignore` 和 `extendResources` metadata；`PiPackageManager` 已能在 AgentSession startup/reload 时注入 package skills/prompts，异步 reload 失败隔离和完整 package precedence 待补 | `in-progress` |
| `src/core/skills.ts`、`prompt-templates.ts` | `SkillLoader.kt`、`PromptTemplateLoader.kt`、`PromptTemplates.kt` | `PromptTemplatesTest`、`FrontmatterTest`、`ResourceLoaderTest`、`AgentSessionResourcesTest` 覆盖 quoted arguments、`$1`/`$@`/默认值/slice、non-recursive replacement、frontmatter scalar/block、技能递归发现、first-win、XML escaping 以及会话层 `/skill:`/模板展开；`PiPackageManifestCodec` 已覆盖 package metadata/filter，完整 YAML 和 package pattern 语义待补 | `in-progress` |
| `src/core/settings-manager.ts` | `settings/SettingsManager.kt`、`SettingsStorage.kt` | 8 项 SettingsManager/desktop storage 测试覆盖深合并、旧字段迁移、外部编辑保留、信任、reload 错误、AgentSession retry/compaction 映射和 `.xuexue/pi` 延迟创建；完整设置表面与跨进程文件锁待补 | `in-progress` |
| `src/core/model-registry.ts`、`model-resolver.ts` | `pi-ai/ModelRegistry.kt`、`AgentSession` scoped model cycle | dynamic provider、custom models、scoped exact/glob/thinking suffix、request auth、scope 顺序/认证过滤/顺逆时针/当前模型缺失和 thinking 继承已测试；完整 scoped model persistence 与 provider adapters 待补 | `in-progress` |
| `src/core/auth-*.ts`、`resolve-config-value.ts` | `pi-ai/AuthStore.kt`、`resolveConfigValue()` | credential precedence、env template 和 OAuth expiry view 已测试；migration、command resolver、文件锁和 refresh/login OAuth 待补 | `in-progress` |
| `src/core/extensions/types.ts` | `extensions/PiExtensionApi.kt` | `PiExtensionRunnerTest` 覆盖 Headless event/registration DTO、资源贡献、工具与命令元数据；共享 `events` 总线按扩展生命周期隔离；TUI-only 类型按排除边界省略 | `in-progress` |
| `src/core/extensions/runner.ts` | `extensions/PiExtensionRunner.kt` | `PiExtensionRunnerTest`、`AgentSessionExtensionTest` 覆盖顺序、链式 context/before-agent-start、工具冲突、错误隔离、stale dispose 和 AgentSession hooks | `in-progress` |
| `src/core/extensions/loader.ts` | `JvmPiPluginLoader`、`ProcessPiExtension`、`PiPluginDiscovery` | `ProcessPiExtensionTest` 覆盖 JSONL handshake、工具/文本和图像 Provider 注册、调用、AssistantMessageEvent/AssistantImages 结果和 shutdown；JAR manifest/entrypoint/trust 门、用户/项目/临时 discovery、大小写归一化和清单版本校验已测试；`PiPackageResolution.extensionPaths` 已提供给 Desktop discovery，完整 package 自动加载/reload 仍待补 | `in-progress` |
| `src/core/extensions/wrapper.ts` | `AgentSession` extension hook bridge | `AgentSessionExtensionTest` 覆盖 tool call/result、context 和 before-agent-start 接线；Kotlin extension 可注册 Provider/ImageProvider 并由 Desktop registry 合并，Process 文本和图像 Provider 已分别通过 `register_provider`/`provider_stream`、`register_image_provider`/`image_generate` 接入 | `in-progress` |
| `src/core/event-bus.ts` | `extensions/PiEventBus.kt`、`PiEventBusController` | `PiEventBusTest` 覆盖 registration order、unsubscribe、handler error isolation、clear；`PiExtensionRunnerTest.extensionEventSubscriptionsAreRemovedWhenRunnerDisposes` 覆盖扩展 reload/dispose 自动移除监听器 | `verified` |
| `src/core/package-manager.ts` | `packages/PiPackageModels.kt`、`DefaultPiPackageManager.desktop.kt` | `PiPackageModelsTest`、`DefaultPiPackageManagerTest` 覆盖 path source、git/Maven source 解析、package.json/自有 manifest、资源路径、settings 持久化、内容 hash、project trust 和 `PI_OFFLINE` 缺包诊断；git staging 原子替换/回滚代码已落地，远程 Maven 解析、完整 update 语义和 package pattern 仍待补 | `in-progress` |
| `src/core/project-trust.ts`、`trust-manager.ts` | `trust/ProjectTrustStore.kt`、`ProjectTrustStorage`、`resolveProjectTrusted()` | `ProjectTrustStoreTest` 覆盖 trust.json 严格 JSON、父目录最近决策、清除子目录覆盖、项目资源检测、override/saved/default 时序；JVM 文件锁、原子写入和 Compose/RPC 选择入口已实现，Runtime 启动前的全局插件预加载接线仍待补 | `in-progress` |
| `src/core/tools/read.ts` | Desktop `ReadTool`、`ToolOutput.kt`、`PiImageProcessResult.kt` | `DesktopPiCodingToolsTest.readHonorsOffsetLimitAndMacScreenshotVariant`、`readReturnsImageContentForSupportedImageMagic`、`readConvertsBmpToPngAndReportsConversionHint`、`readLongFirstLineUsesTheOriginalPathInFallback`；head truncation、UTF-8 byte/line limits、offset/limit、图片尺寸/Base64 限制和 BMP 转 PNG 已覆盖；JVM `ImageIO` 不承诺 WebP | `in-progress` |
| `src/core/tools/write.ts`、`edit.ts`、`edit-diff.ts` | Desktop `WriteTool`/`EditTool`、`FileMutationQueue.kt`、`DesktopPiEditSupport.kt` | `DesktopPiCodingToolsTest.writeCreatesParentDirectories`、`editAppliesMultipleOriginalRangesAndReturnsPatchDetails`、`editPreservesBomAndCrLfWhileMatchingNormalizedText`、`editUsesPiFuzzyMatchingForSmartQuotesAndTrailingWhitespace`；exact replacement、写入前后 `fileDiff` details、原始区间多编辑、BOM/CRLF、Pi fuzzy matching、LCS unified diff、同路径串行队列和异常释放已覆盖；完整 Pi diff/render details 与 legacy args 待补 | `in-progress` |
| `src/core/tools/bash.ts`、`bash-executor.ts` | `AgentSessionBashExecutor`、Desktop `LocalBashExecutor`、Desktop `BashTool` | `PiRpcProtocolTest.handlerExecutesAndPersistsBashResults`、`LocalBashExecutorTest` 和 `DesktopPiCodingToolsTest.bashEmitsInitialAndCoalescedStreamingUpdates`/`bashPersistsFullOutputForLineTruncation`/`bashTimeoutIncludesOutputCollectedBeforeCancellation` 覆盖 cwd/合并输出/退出码、共享 UTF-8 accumulator、50KB/2000 行 tail、full-output 文件、初始/合并增量更新和 timeout partial output；取消销毁及 descendants 回收已实现，结果经 `AgentSession.recordBashResult` 统一落盘 | `in-progress` |
| `src/core/tools/grep.ts`、`find.ts`、`ls.ts` | Desktop `GrepTool`/`FindTool`/`LsTool` | `DesktopPiCodingToolsTest.grepSupportsLiteralContextAndSkipsBinaryFiles`、`findUsesGlobAndGitignoreRules`、`findScopesNestedGitignoreRulesLikePiRegression3303`、`findHonorsNegationAndNestedGitRepositoryBoundaries`、`lsIncludesDotfilesAndReportsEntryLimit`；读取、排序、limit、head truncation、literal/context、二进制跳过、glob、层级 `.gitignore`、nested Git 边界、ignore negation/安全剪枝、dotfile 和 entry limit 已接入；完整 Pi 输出格式待补 | `in-progress` |
| `src/core/tools/file-mutation-queue.ts` | `FileMutationQueue.kt` | 同路径串行和异常释放 | `in-progress` |
| `src/core/tools/output-accumulator.ts`、`truncate.ts` | `PiBashOutputAccumulator`、`ToolOutput.kt` | `PiBashOutputAccumulatorTest.decodesUtf8CharactersSplitAcrossChunks`、`countsTrailingNewlineWithoutAddingAnExtraLine`、`truncatesOnlyTheTailOfAnOversizedLastLine` 覆盖跨 chunk UTF-8 解码、trailing newline 行数、50KB/2000 行 tail、超长 partial 行、full output persistence 和 `truncatedBy`/line-byte metadata；Process group/detached child 和完整 Pi details 仍待补 | `in-progress` |
| `src/core/tools/path-utils.ts` | 工具路径语义 | cwd、absolute、symlink 和 display path | `pending` |
| `src/core/tools/tool-definition-wrapper.ts` | ToolDefinition 转换 | schema、prompt metadata、progress | `pending` |
| `src/core/cache-stats.ts`、`timings.ts` | 本地统计 | token/cost/cache/timing 计算 | `pending` |
| `src/core/diagnostics.ts`、`output-guard.ts` | 本地诊断与输出保护 | error capture、stdout cleanliness | `pending` |
| `src/core/http-dispatcher.ts` | HTTP dispatch 平台接口 | proxy、headers、lifecycle | `pending` |
| `src/core/exec.ts` | `exec/PiExec.kt`、Desktop `LocalPiCommandExecutor` | `LocalPiCommandExecutorTest` 覆盖非 shell argv、分离 stdout/stderr 和退出码；Kotlin/JVM extension API 已注入 `exec`，超时/AbortSignal/Process JSONL 请求仍待补 | `in-progress` |
| `src/core/sdk.ts` | `AgentSessionRuntimeFactory` / Kotlin SDK | Runtime 创建、资源、模型、stream options；当前已提供 Runtime 生命周期工厂与 Compose 接入骨架，完整 provider/model resolver 仍待补 | `in-progress` |
| `src/modes/rpc/**`、`src/rpc-entry.ts` | `rpc/PiRpcProtocol.kt`、`AgentSessionRpcHandler.kt`、`PiRpcServer.desktop.kt` | `PiRpcProtocolTest` 覆盖 canonical/flat request、业务 `bash.command` 保留、response/state/message-update event 编码、Bash 持久化、命令 `sourceInfo`、`export_html`、异步 prompt response event、extension UI response 和 `cycle_model data:null`；handler/server 已覆盖 prompt/steer/follow-up/abort、model、queue、compaction、session tree/fork/clone、Bash、commands、interaction 和 JSONL stdin/stdout；三宿主一致性仍待补 | `in-progress` |
| `src/modes/print-mode.ts` | 非交互输出模式 | stdout、错误和退出语义 | `pending` |
| `src/core/export-html/**` | `SessionHtmlExport.kt`、Desktop `LocalSessionHtmlExporter` | `PiRpcProtocolTest.htmlRendererEscapesMarkupAndKeepsWhitespace`、`LocalSessionHtmlExporterTest.writesEscapedTranscriptToRequestedPath`、`LocalSessionHtmlExporterTest.rendersStructuredMessagesSkillBlocksImagesBranchesAndSafeMarkdown` 覆盖 HTML 转义、换行、落盘、thinking/tool call/result、skill block Markdown、图片 data URL、session tree、属性转义和 Markdown URL scheme allow-list；静态导出已接入 RPC/Compose 宿主，完整 Pi Markdown/highlight/theme fidelity 仍待补 | `in-progress` |
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
| `src/desktopMain/kotlin/dev/xuexue/WorkbenchAgentController.kt` | 已通过 `AgentSessionRuntime` 调用 `prompt/steer/abort`，并把 Runtime StateFlow/事件重新绑定给 `AgentRuntimeState`；保留旧会话 DTO 作为迁移投影 | 随 structured-message Runtime 投影完成降为 command/state adapter |
| `src/desktopMain/kotlin/dev/xuexue/platform/DesktopPiAgentAdapter.kt` | 旧 `AgentClient`/工具到 `:pi-agent-core` 的临时平台桥 | 由正式 Provider 和 platform capability 实现替换 |
| `src/desktopMain/kotlin/dev/xuexue/WorkbenchAgentProjection.kt` | 将 `AgentEvent` 临时投影到旧 Compose 消息列表，保留 assistant thinking/tool call/tool result/diff | `AgentSession` 成为唯一会话状态后删除 |
| `pi-coding-agent/src/commonMain/kotlin/dev/xuexue/pi/coding/AgentSession*.kt` | 已建立正式会话 facade、状态和事件协议；已覆盖消息落盘、队列、settled、Bash 延迟、统计、retry、compaction、资源提示词/命令展开和插件 hooks | 继续移植 extension loader/runtime，再替换 Compose 旧会话状态 |
| `pi-coding-agent/src/**/packages/**` | 能力包 source、manifest、缓存和资源贡献 | path/JAR/Process 目录、git staging、Maven 本地/远程下载、文件锁、glob 资源选择、hash、settings、trust/offline 测试；AgentSession startup/reload 已消费 skills/prompts，Desktop Runtime 已接入 package extension paths | 安装 UI、完整 git 更新/回滚、package filter/pattern 全语义和完整 reload/回滚 fixture |
| `pi-coding-agent/src/**/session/**` | 已建立 Pi v3 session entry/tree/context 与 Desktop JSONL 存储 | 继续补齐原 session tests，然后接入正式 `AgentSession` |
| `.xuexue/agent-sessions.jsonl` | 非 Pi append-only entry tree | 提供一次性迁移器，原文件失败时保留 |

## 6.1 Compose 接线不变量

| 链路 | 必须满足的行为 | 状态 |
| --- | --- | --- |
| `AgentSession/Runtime -> StateFlow<AgentState>` | AgentSession 已输出包含 Agent、队列和会话元数据的 StateFlow；`AgentRuntimeState` 会在 Runtime 替换时重新绑定正式 StateFlow，Agent 面板运行态已优先读取；旧投影已覆盖 thinking/tool call/tool result，完整 structured messages 仍待迁移 | `in-progress` |
| `AgentSession/Runtime -> Flow<AgentEvent>` | AgentSession 已输出同源会话事件和有序订阅；`AgentRuntimeState` 暴露正式事件流，RPC handler 和 Compose 仍在兼容期使用旧消息投影 | `in-progress` |
| `WorkbenchAgentController -> Runtime commands` | `prompt/steer/abort`、Runtime lifecycle、follow-up 和 interaction broker 已接线；完整 new/resume/fork UI 命令和状态投影仍待迁移 | `in-progress` |
| `AgentViews -> InteractionRequest` | `AgentRuntimeState` 暴露 broker StateFlow；Compose 已渲染 confirm/select/input/editor 并回传类型化 response，notify 走通知 Flow | `in-progress` |
| Compose、SDK、RPC 三宿主 | 相同输入产生相同 Runtime 状态、事件和 session entry | `pending` |

## 7. 阶段门

1. 每次把条目标为 `verified` 时，同一提交必须增加或指向对应测试。
2. 每个 Pi test 文件必须最终映射为 Kotlin test、排除项或由更底层 fixture 覆盖，
   并记录具体测试名称。
3. Provider 网络测试可以用确定性 mock server 替代真实凭据，但 payload、stream、
   error、retry 和 usage 语义不能缩减。
4. 完成审计前重新枚举三个参考 `src` 和 `test` 目录；新增、遗漏或未分类文件均使
   总体目标保持未完成。
