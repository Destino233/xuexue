# XueXue 插件开发者指南

本文面向希望为 XueXue 增加工具、命令、Agent 行为或界面的开发者。插件在宿主中称为 Mod。一个 Mod 有唯一 ID 和不可变版本，可以作为 JVM JAR 加载，也可以作为独立 Process 运行。

建议先阅读完整示例：[examples/reviewmod](../examples/reviewmod)。它展示了两个工具、一个命令、事件监听、Session 消息、权限请求、Runtime 上下文处理和右侧面板。

## 1. 选择运行方式

JVM JAR 适合 Kotlin/Java 开发，API 最完整，调用开销最低，但插件代码和宿主运行在同一个 JVM 中。Process 插件适合 TypeScript、Python、Rust 等语言，插件崩溃或超时不会直接破坏宿主，但能力通过 JSONL 协议传递，UI 采用声明式贡献模型。

两种方式使用相同的 manifest、作用域、权限和生命周期语义。

## 2. 插件目录

- 项目级：`<workspace>/.xuexue/pi/extensions/`
- 用户级：`<user>/.xuexue/pi/extensions/`
- 包管理器安装的插件：由 Pi package manager 管理的扩展目录

也可以通过 Pi package manager 安装 npm、Git 或本地包。宿主启动时会扫描目录并先读取 manifest，再进行版本选择、依赖解析、信任判断和权限检查。项目级插件只有在工作区获得信任后才会启用。

## 3. Manifest

JVM JAR 必须包含 `META-INF/xuexue-pi-plugin.json`。最小示例：

```json
{
  "id": "com.example.review",
  "version": "0.1.0",
  "apiVersion": "1",
  "runtime": "JVM",
  "entrypoint": "com.example.review.ReviewMod",
  "permissions": ["workspace.read"],
  "contributes": ["tool", "panel"]
}
```

`id` 必须稳定且全局唯一，推荐使用反向域名；`version` 使用语义化版本；`apiVersion` 表示插件依赖的宿主 API 版本；`runtime` 填 `JVM` 或 `PROCESS`；`entrypoint` 是 JVM 入口类或 Process 启动入口；`permissions` 和 `contributes` 用于在执行代码前显示能力和审批信息。

manifest 无法解析、版本不兼容、依赖缺失或插件不受信任时，宿主会把插件放入诊断库存，不执行入口代码。

## 4. JVM 插件

实现 `PiMod`，并在 `enable` 中完成注册：

```kotlin
class ReviewMod : PiMod {
    override val id = ModId("com.example.review")
    override val version = ModVersion("0.1.0")

    override suspend fun enable(context: ModContext): ModHandle? {
        context.tools.register("review", reviewTool(context))
        context.commands.register("review", reviewCommand)
        context.events.on(PiExtensionEventType.TOOL_RESULT, toolResultHandler)
        return ModHandle { /* 释放自建资源 */ }
    }
}
```

实际签名和完整工具参数定义请以 [reviewmod 示例](../examples/reviewmod/src/main/kotlin/com/example/review/ReviewMod.kt) 为准。工具执行函数应返回 `AgentToolResult`；不要在插件中修改宿主的全局 Registry。

## 5. ModContext 与权限

`ModContext` 是插件的能力边界。常用入口包括 `tools`、`commands`、`events`、`session`、`runtime`、`ui` 和 `permissions`。权限请求返回 `GRANTED` 之前，插件必须保持操作禁用，并返回可检查的错误详情。不要把用户确认结果缓存成永久授权；授权由宿主按项目和会话策略保存。

## 6. UI 接线

插件 UI 不是直接操作 Compose 树，而是注册数据模型：面板 ID、标题、槽位、状态 JSON 和动作 ID。宿主将其投影到 Compose，并把用户点击通过 `invokeModUiAction` 送回插件。面板应保持轻量，把业务状态放进可序列化 JSON；动作必须有稳定 ID，并在插件 disable 时失效。

## 7. Process 插件

Process 插件通过 stdin/stdout 交换 UTF-8 JSONL，每行一个 JSON 对象；stdout 只能输出协议帧，日志写 stderr。宿主先发送：

```json
{"type":"initialize","extensionId":"com.example.review","apiVersion":"1"}
```

插件注册能力后发送 `ready`：

```json
{"type":"register_tool","name":"review","description":"Review text","parameters":{"type":"object"}}
{"type":"register_command","name":"review","description":"Review the active document"}
{"type":"subscribe","event":"TOOL_RESULT"}
{"type":"ready"}
```

工具调用必须回传同一个 `requestId`。取消使用 `cancel` 帧；超时后宿主会终止当前 Process。完整帧定义见 [PI_MOD_PROCESS_PROTOCOL.md](PI_MOD_PROCESS_PROTOCOL.md)，可使用 [TypeScript SDK 示例](../examples/reviewmod/process-sdk/index.ts)。

## 8. 生命周期

插件实例按以下顺序管理：发现 manifest → 解析依赖 → 检查信任和权限 → `enable` → 运行 → disable/reload/update/rollback。`ModHandle` 是最后的清理点，应释放协程、文件监听器、事件订阅、线程、临时文件和子进程。

reload 会先停止旧实例并撤销所有注册，再创建新的 `ModRun`。更新 package 后宿主会重新解析包、重新发现 manifest、重建插件实例并刷新 Agent 工具、资源和 UI。旧实例不得继续响应事件。

## 9. 调试和发布检查

开发时可以直接把 JAR 放入项目扩展目录，然后在插件管理界面查看 manifest、运行状态、来源、信任状态和诊断。常见问题包括入口类名称错误、manifest 的 runtime 大小写错误、权限未声明、项目未信任和依赖版本不满足。

发布前确认：

1. 在全新目录中能被发现，manifest 状态正确。
2. 工具、命令和 UI 面板在启用后可见。
3. 缺少权限时插件保持禁用并给出诊断。
4. reload 后旧监听器、协程和 Process 已停止。
5. 更新和 rollback 后版本、工具和面板与当前实例一致。

## 10. 旧 PiExtension 迁移

现有 `PiExtension` 可以通过兼容适配器继续运行。迁移时先保留原 manifest 和事件行为，再把全局注册移动到 `ModContext`，把清理逻辑集中到 `ModHandle`，最后补充权限声明和 UI contribution。
