# XueXue Mod 插件开发

XueXue 插件是一个带有 `META-INF/xuexue-pi-plugin.json` 清单的 JVM JAR，或一个实现 JSONL Process 协议的可执行目录。宿主会先读取清单，再根据作用域、信任状态、依赖和权限决定是否启动入口。

## 放置位置

- 项目级：`<workspace>/.xuexue/pi/extensions/`
- 用户级：`<user>/.xuexue/pi/extensions/`
- 包管理器安装的插件：由 Pi package manager 管理的扩展目录

项目级插件只在工作区获得信任后启用。用户级插件可以参与信任决策，但不会绕过项目权限。

## JVM JAR

插件实现 `dev.xuexue.pi.coding.extensions.PiMod`，并在清单中声明入口类：

```json
{
  "id": "com.example.review",
  "version": "0.1.0",
  "runtime": "jvm",
  "entrypoint": "com.example.review.ReviewMod",
  "permissions": ["workspace.read", "ui.panel"]
}
```

在 `enable(context)` 中注册工具、命令、事件、Runtime 策略和 UI 面板。返回的 `ModHandle` 会在 disable、reload、rollback 或更新时调用，从而释放协程、监听器和外部进程。

## Process 插件

Process 插件通过标准输入输出传输 JSONL 消息。启动、请求、取消、事件和响应格式见 [PI_MOD_PROCESS_PROTOCOL.md](PI_MOD_PROCESS_PROTOCOL.md)；该协议可由 TypeScript、Python 或其他语言直接实现。完整 JVM 示例见 [../examples/reviewmod/README.md](../examples/reviewmod/README.md)。

## 生命周期和权限

插件只能使用 `ModContext` 暴露的 API。权限请求返回 `GRANTED` 之前，插件应保持操作禁用并返回可读诊断。宿主在 reload 前停止旧实例并撤销其注册；更新包后会重新发现 manifest、重建 Mod 实例并刷新 Agent 工具和资源。

## 兼容旧插件

旧 `PiExtension` 可继续通过兼容适配器加载。迁移时先保留原 manifest 和事件处理，再逐步将全局注册改为 `ModContext` 下的工具、命令、Session 和 UI 注册。
