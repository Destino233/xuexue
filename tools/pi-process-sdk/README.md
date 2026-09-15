# `@xuexue/pi-process-sdk`

The SDK wraps the XueXue Process Mod JSONL protocol. Register capabilities before
calling `start()`; the SDK keeps stdout protocol-only and handles request IDs,
abort signals, interaction responses, and terminal frames.

```ts
import { ProcessMod } from "@xuexue/pi-process-sdk";

const mod = new ProcessMod({ extensionId: "com.example.review" });
mod.registerTool({
  name: "review",
  description: "Review a document",
  parameters: { type: "object" },
  handler: async () => ({ content: [{ type: "text", text: "No issues" }] }),
});
mod.on("TOOL_RESULT", async (payload) => ({
  result: "tool_result",
  details: { source: "reviewmod", payload },
}));
mod.start();
```

Build with `npm run build`. The generated package has no runtime dependency;
Node's standard `readline`, `crypto`, and stream APIs are used directly.
