# reviewmod example

This example is a complete Feature Mod. Generate a plugin project with
the host's `pi-mod-api` dependency, implement `PiMod`, and place the built JAR
under either:

- `<workspace>/.xuexue/pi/extensions/`
- `<agentDirectory>/extensions/`

The manifest is loaded before the entrypoint, so dependency and trust failures
are visible without executing plugin code.

`src/main/kotlin/com/example/review/ReviewMod.kt` demonstrates two tools, a
command, a `TOOL_RESULT` event hook, a Session API custom message, a permission
request, a Runtime context hook, and a right-dock panel.

The complete API is intentionally data-oriented for Process compatibility:
tools and UI contributions are ordinary registrations, while `SessionApi` and
`RuntimeRegistry` are available to advanced mods that need Agent Loop control.

For a dependency-free TypeScript Process client, see `process-sdk/index.ts`.
It uses newline-delimited JSON and can be adapted to Node, Bun, or Deno stream
interfaces without a framework dependency.
