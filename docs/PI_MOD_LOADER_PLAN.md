# Pi Mod Loader 升级计划

本文档定义 XueXue Agent 插件系统从当前 `PiExtension` 机制演进为
Minecraft Mod 风格运行时的目标、用户开发方式和实施顺序。设计参考
DeepSeek Harness（以下简称 DSH）的动态插件运行模型，但使用 Kotlin/JVM
和 Compose 的本地能力，不直接复制 TypeScript 或浏览器实现。

## 1. 设计结论

当前 `PiExtensionRunner` 已经提供了加载、事件分发、工具/命令/Provider
注册、错误隔离和卸载等基础设施。它的执行能力并不缺少图灵完备性：受信任的
JVM 插件可以执行任意 Kotlin/JVM 代码，Process 插件也可以运行任意外部程序。

当前的主要限制是宿主能力模型较窄，插件主要只能注册固定类型的能力，不能正式
参与或替换 Agent Runtime。目标不是推倒重写，而是将现有 Runner 逐步升级为
`PiModLoader`，同时保留 `PiExtension` 兼容层。

目标原则：

- 插件包、插件版本和一次激活运行实例彼此分离。
- 每次激活拥有唯一 `ModRunId`，旧实例的异步操作不能影响新实例。
- 所有注册、协程、监听器、资源和后台任务都归属于一个运行实例，并可完整回收。
- 普通插件可以低成本注册工具和 UI；高级插件可以扩展或接管 Agent Loop。
- JVM 和 Process 宿主实现同一套语义 API，只在信任边界和传输方式上不同。
- UI 通过稳定的贡献模型接线，不把宿主内部 Compose 状态直接变成 ABI。

## 2. 面向插件作者的体验

### 2.1 插件类型

插件分为两个层级：

**Feature Mod** 适合大多数插件，提供工具、命令、资源、Provider、事件监听、
配置、存储和面板等能力。它不需要接管 Agent 主循环。

**Runtime Mod** 面向规划器、多 Agent 编排器、安全策略和模型路由等高级场景，
可以注册 Agent Loop 阶段，或在明确权限下替换整个 Loop。Runtime Mod 必须声明
更高的权限和兼容的 API 版本。

### 2.2 Kotlin/JVM 插件工程

推荐使用模板生成一个独立 Gradle 工程，只依赖稳定的 `pi-mod-api`：

```text
reviewmod/
├── build.gradle.kts
├── src/main/kotlin/com/example/review/ReviewMod.kt
├── src/main/resources/META-INF/xuexue-pi-mod.json
└── README.md
```

最小插件形态：

```kotlin
class ReviewMod : PiMod {
    override val id = ModId("com.example.review")

    override suspend fun enable(context: ModContext): ModHandle {
        context.tools.register("com.example.review:review", reviewTool)
        context.commands.register("com.example.review:review", reviewCommand)
        context.events.on<ToolResultEvent> { event ->
            // 处理工具结果
        }
        return ModHandle { /* 可选的显式清理 */ }
    }
}
```

Manifest 使用不可变包版本和明确运行时：

```json
{
  "schemaVersion": 1,
  "id": "com.example.review",
  "version": "0.1.0",
  "apiVersion": "1",
  "runtime": "JVM",
  "entrypoint": "com.example.review.ReviewMod",
  "dependencies": [],
  "permissions": ["workspace.read"],
  "contributes": ["tool", "command", "panel"]
}
```

Process 插件使用同一 manifest 的 `PROCESS` runtime，通过官方 SDK 连接 JSONL
协议。SDK 负责请求/响应 ID、事件订阅、超时、取消和错误格式，作者不需要手写
底层管道协议。

### 2.3 插件安装位置

默认扫描位置如下，扫描顺序和冲突规则必须保持确定：

```text
项目级：<workspace>/.xuexue/pi/extensions/
用户级：<agentDirectory>/extensions/
```

现有设置中的自定义用户路径、项目路径和临时路径继续支持。项目级插件只有在
工作区信任后才加载；JVM 插件必须明确批准，因为它与宿主共享 JVM，不能视为沙箱。

插件管理器应显示来源（项目/用户/临时）、版本、信任状态、依赖、权限、运行状态
和诊断信息，并支持启用、禁用、更新、回滚和重新加载。重新加载不覆盖旧运行实例，
而是先停止旧 `ModRun`，再创建新的 `ModRunId`。

## 3. 目标运行时模型

```text
PiModLoader
├── ModRegistry              发现、安装和版本选择
├── ModLifecycle              enable/disable/update/reload
├── ModRun                    一次激活的身份、作用域和任务
├── EventRegistry
├── ToolRegistry
├── CommandRegistry
├── ProviderRegistry
├── ResourceRegistry
├── SessionApi
├── RuntimeRegistry           Agent Loop 和各阶段扩展
├── UiRegistry
├── StorageApi
├── PermissionBroker
└── DiagnosticsRegistry
```

核心状态：

```text
DEFINED -> STARTING -> RUNNING -> STOPPING -> STOPPED
                         \-> FAILED
```

建议的公共抽象：

```kotlin
@JvmInline
value class ModId(val value: String)

@JvmInline
value class ModRunId(val value: String)

interface PiMod {
    val id: ModId
    val version: String

    suspend fun enable(context: ModContext): ModHandle
}

interface ModContext {
    val mod: ModInfo
    val events: EventRegistry
    val tools: ToolRegistry
    val commands: CommandRegistry
    val providers: ProviderRegistry
    val resources: ResourceRegistry
    val session: SessionApi
    val runtime: RuntimeRegistry
    val ui: UiRegistry
    val storage: StorageApi
    val permissions: PermissionBroker
    val diagnostics: DiagnosticsRegistry
}

interface Registration {
    suspend fun unregister()
}
```

所有注册 API 返回 `Registration`，并自动归属于当前 `ModRun`。停止插件时，
Loader 必须回收注册项、事件订阅、插件作用域中的协程、Process 进程和 ClassLoader
引用。API 调用在 `STOPPING` 或 `STOPPED` 后应失败，而不是悄悄写入全局状态。

## 4. Agent Loop 扩展模型

DSH 的关键启发是：插件不只是给 Agent 注册工具，Agent Loop 本身也应是可组合的
Runtime 能力。当前结构是固定的 `AgentSession` 调用扩展 hooks；目标结构是稳定的
Agent Kernel 加可插拔阶段：

```text
PiKernel
├── AgentLoop
├── ContextPipeline
├── ModelRouter
├── ToolDispatcher
├── SessionStore
└── EventBus
```

第一版优先暴露阶段扩展，避免让任意 Feature Mod 直接破坏主循环：

- 输入预处理
- 上下文构建和追加系统提示
- 模型选择与路由
- 工具调用策略和权限判断
- 工具结果变换
- 是否继续循环的 continuation policy
- 消息持久化

高级 Runtime Mod 在声明权限并通过审批后，才可提供完整 `AgentLoop` 实现：

```kotlin
interface AgentLoop {
    suspend fun run(request: AgentRequest, context: LoopContext): AgentResult
}

interface AgentLoopMiddleware {
    suspend fun intercept(
        request: AgentRequest,
        next: suspend (AgentRequest) -> AgentResult,
    ): AgentResult
}
```

现有 `AgentSession` 先作为默认 Loop 实现，通过适配器接入新接口；现有扩展事件
继续保持顺序和行为兼容。

## 5. UI 接线方案

插件 UI 不应只显示在静态“插件列表”中，而应贡献到 Workbench 的正式槽位。
首批稳定槽位：

- 左/右 Dock 面板
- 底部工具面板
- 工具栏动作
- 命令面板项
- Agent 消息渲染器
- 状态栏贡献
- 用户确认、输入和选择请求

概念 API：

```kotlin
context.ui.panels.register(
    id = "com.example.review:panel",
    slot = UiSlot.RIGHT_DOCK,
    title = "Review",
) {
    // 由宿主 Compose 渲染的面板模型和交互
}

context.ui.actions.register(
    id = "com.example.review:run",
    location = UiSlot.TOOLBAR,
)
```

第一阶段 UI 贡献使用稳定的数据模型（标题、图标、状态、动作、列表、表单和消息
块），由宿主 Compose 负责渲染。受信任 JVM Mod 后续可获得可选的 `@Composable`
扩展，但不得让内部 `MutableState`、导航栈或 Workbench 状态对象成为公共 ABI。
Process 插件使用 JSON UI 描述和交互事件。

## 6. 宿主、隔离和加载时间

JAR 加载本身通常只涉及 manifest、ClassLoader、入口类和轻量注册，预期为毫秒级到
几十毫秒级。启动延迟主要来自插件自己的网络、索引、数据库或模型初始化，不应在
`enable()` 中阻塞加载线程。

Loader 应采用：

- 发现阶段只读取 manifest；
- `enable()` 只做必要注册；
- 重型服务按需启动；
- 独立插件在满足依赖后并行启用；
- 启动耗时写入诊断信息。

宿主取舍：

| 宿主 | 优点 | 限制 |
| --- | --- | --- |
| Trusted JVM | Kotlin 类型安全、Compose 原生 UI、Minecraft 级自由度 | 几乎没有安全隔离，ClassLoader/线程/协程需要严格回收 |
| Process/JSONL | 进程隔离、跨语言、可限制权限 | 协议带宽和能力边界更窄，UI 需要声明式模型 |

两种宿主必须共享同一套 Mod 语义；不能因为 Process 协议较窄，就让 JVM API
退化成一组孤立的 `registerXxx()` 函数。

## 7. 分阶段实施

### 阶段 0：冻结兼容契约

- 固定 `PiExtensionApi`、`PiExtensionRunner`、manifest 和现有事件顺序。
- 为现有 JVM/Process 插件补齐行为测试和迁移边界。

### 阶段 1：运行实例和注册所有权

- 引入 `ModId`、`ModRunId`、`ModRunState` 和 `ModPackage`。
- 将 `LoadedExtension` 演进为 `ModRun`。
- 引入带所有权的 Registry 和 `Registration`。
- 保留 `PiExtension` 适配器。

验收：停止或 reload 后所有注册和后台任务都被回收；旧实例不能响应新事件。

### 阶段 2：版本、依赖和生命周期

- 支持不可变包版本、依赖约束、拓扑排序和失败诊断。
- 增加 define/run/update/stop/rollback/undefine 状态转换。
- 增加精确请求 ID 和过期响应拒绝。

### 阶段 3：Session 和 Agent Runtime API

- 抽出默认 `AgentLoop`。
- 增加 `SessionApi`：消息、steering/follow-up、上下文、模型/工具选择、会话树、
  持久化自定义条目和 abort/reload。
- 增加 Runtime 阶段 Registry；高权限插件再开放完整 Loop 替换。

### 阶段 4：Compose UI Registry

- 实现 Dock、工具栏、命令面板、消息渲染器和交互请求。
- 将插件状态、面板和诊断接入 Workbench StateFlow。
- 提供一个完整 `reviewmod` 示例验证工具、Session 和 UI 的联动。

### 阶段 5：权限、Process SDK 和管理界面

- 实现 workspace、文件、命令、网络、模型和 UI 权限审批。
- 提供 Kotlin/TypeScript Process SDK。
- 完成插件库存、启用状态、审批、更新、回滚和诊断界面。

### 阶段 6：迁移和发布

- 提供插件模板、开发文档、manifest schema 和迁移指南。
- 以旧 `PiExtension` 适配器保证已有插件可继续运行。
- 增加 JVM/Process 一致性、生命周期、依赖、UI 和 Agent Loop 集成测试。

## 8. 第一版完成标准

一个 `reviewmod` 应能够：

1. 从项目级或用户级目录被发现并显示 manifest 状态。
2. 注册两个工具、一个命令、一个事件监听器和一个右侧面板。
3. 通过 `SessionApi` 发送自定义消息并请求用户确认。
4. 在工具结果阶段修改消息详情，而不破坏核心会话格式。
5. 通过 Runtime 阶段 API 增加一次上下文处理策略。
6. reload 后旧实例的协程、监听器、面板和进程全部停止。
7. 在未授权项目或缺少权限时保持禁用，并给出可检查的诊断信息。

达到这些标准后，系统才算从“扩展点集合”升级为可供用户开发的 Mod Runtime。
