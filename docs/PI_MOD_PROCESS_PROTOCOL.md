# Process Mod JSONL Protocol

Process mods use UTF-8, one JSON object per line on stdin/stdout. Stdout is
reserved for protocol frames; diagnostics should go to stderr.

## Handshake

The host sends:

```json
{"type":"initialize","extensionId":"com.example.review","apiVersion":"1"}
```

During initialization the process may register capabilities, then terminates
the handshake with `{"type":"ready"}`:

```json
{"type":"register_tool","name":"review","description":"Review text","parameters":{"type":"object"}}
{"type":"register_command","name":"review","description":"Review the active document"}
{"type":"subscribe","event":"TOOL_RESULT"}
{"type":"contribute_resources","skillPaths":["skills"]}
{"type":"ready"}
```

Supported registration frames are `register_tool`, `register_command`,
`register_provider`, `register_image_provider`, `subscribe`, and
`contribute_resources`.

## Requests and responses

Every request carries a unique `requestId`. The host ignores responses for an
older or unknown ID, so a plugin can safely finish work after cancellation.

Tool invocation:

```json
{"type":"tool_call","requestId":"...","toolCallId":"call-1","name":"review","params":{}}
```

The process replies with `tool_result` and may include text or image content:

```json
{"type":"tool_result","requestId":"...","content":[{"type":"text","text":"No issues"}],"isError":false}
```

Provider requests use `provider_stream` and return stream events followed by a
terminal `provider_done` or `provider_error`. Event callbacks use `event` and
return `event_result`; the result shape depends on the event type.

Cancellation is sent as `cancel` with the original `requestId`. The host also
closes the process when it exceeds the configured request timeout.

## Shutdown and errors

The host sends `{"type":"shutdown"}` before terminating the process. A fatal
startup error is reported as `{"type":"error","message":"..."}`. Unknown
frames are protocol errors and cause the current run to fail without affecting
other mods.

The Kotlin `ProcessPiExtension` bridge implements this protocol and serializes
all requests through a single reader, preserving response ordering while still
allowing independent request IDs.
