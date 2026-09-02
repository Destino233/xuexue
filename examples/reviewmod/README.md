# reviewmod example

This example shows the smallest JVM Mod shape. Generate a plugin project with
the host's `pi-mod-api` dependency, implement `PiMod`, and place the built JAR
under either:

- `<workspace>/.xuexue/pi/extensions/`
- `<agentDirectory>/extensions/`

The manifest is loaded before the entrypoint, so dependency and trust failures
are visible without executing plugin code.

```kotlin
class ReviewMod : PiMod {
    override val id = ModId("com.example.review")
    override val version = ModVersion("0.1.0")

    override suspend fun enable(context: ModContext): ModHandle {
        context.tools.register("com.example.review:review", reviewTool)
        context.ui.registerPanel(
            UiPanelContribution(
                id = "com.example.review:panel",
                slot = UiSlot.RIGHT_DOCK,
                title = "Review",
            ),
        )
        return ModHandle { }
    }
}
```

The complete API is intentionally data-oriented for Process compatibility:
tools and UI contributions are ordinary registrations, while `SessionApi` and
`RuntimeRegistry` are available to advanced mods that need Agent Loop control.
