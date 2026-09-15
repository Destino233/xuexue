import { randomUUID } from "node:crypto";
import { createInterface, type Interface } from "node:readline";
import { stdin, stdout } from "node:process";

export type JsonObject = Record<string, unknown>;

export interface ToolResult {
  content: Array<{ type: "text"; text: string } | { type: "image"; data: string; mimeType: string }>;
  details?: unknown;
  terminate?: boolean;
}

export interface ToolContext {
  requestId: string;
  toolCallId: string;
  signal: AbortSignal;
}

export type ToolHandler = (params: JsonObject, context: ToolContext) => ToolResult | Promise<ToolResult>;
export type CommandHandler = (args: string, context: RequestContext) => void | Promise<void>;
export type EventHandler = (payload: JsonObject, context: RequestContext) => JsonObject | void | Promise<JsonObject | void>;
export type ProviderHandler = (request: JsonObject, context: RequestContext) => AsyncIterable<JsonObject> | Iterable<JsonObject> | Promise<AsyncIterable<JsonObject> | Iterable<JsonObject>>;

export interface RequestContext {
  requestId: string;
  signal: AbortSignal;
}

export interface ProcessModOptions {
  extensionId: string;
  apiVersion?: string;
  input?: NodeJS.ReadableStream;
  output?: NodeJS.WritableStream;
}

export interface ToolRegistration {
  name: string;
  description: string;
  parameters: JsonObject;
  handler: ToolHandler;
  label?: string;
  promptSnippet?: string;
  promptGuidelines?: string[];
}

/** JSONL host SDK. Register capabilities before start(); stdout remains protocol-only. */
export class ProcessMod {
  private readonly extensionId: string;
  private readonly apiVersion: string;
  private readonly input: NodeJS.ReadableStream;
  private readonly output: NodeJS.WritableStream;
  private readonly registrations: JsonObject[] = [];
  private readonly tools = new Map<string, ToolHandler>();
  private readonly commands = new Map<string, CommandHandler>();
  private readonly events = new Map<string, EventHandler>();
  private readonly providers = new Map<string, ProviderHandler>();
  private readonly requests = new Map<string, AbortController>();
  private readonly interactions = new Map<string, (response: JsonObject) => void>();
  private reader?: Interface;
  private initialized = false;
  private closed = false;

  constructor(options: ProcessModOptions) {
    this.extensionId = options.extensionId;
    this.apiVersion = options.apiVersion ?? "1";
    this.input = options.input ?? stdin;
    this.output = options.output ?? stdout;
  }

  registerTool(registration: ToolRegistration): void {
    this.ensureBeforeStart();
    this.tools.set(registration.name, registration.handler);
    this.registrations.push({
      type: "register_tool",
      name: registration.name,
      label: registration.label ?? registration.name,
      description: registration.description,
      parameters: registration.parameters,
      ...(registration.promptSnippet === undefined ? {} : { promptSnippet: registration.promptSnippet }),
      ...(registration.promptGuidelines === undefined ? {} : { promptGuidelines: registration.promptGuidelines }),
    });
  }

  registerCommand(name: string, description: string, handler: CommandHandler): void {
    this.ensureBeforeStart();
    this.commands.set(name, handler);
    this.registrations.push({ type: "register_command", name, description });
  }

  on(event: string, handler: EventHandler): void {
    this.ensureBeforeStart();
    this.events.set(event.toUpperCase(), handler);
    this.registrations.push({ type: "subscribe", event: event.toUpperCase() });
  }

  registerProvider(id: string, name: string, supportedApis: string[], handler: ProviderHandler): void {
    this.ensureBeforeStart();
    this.providers.set(id, handler);
    this.registrations.push({ type: "register_provider", id, name, supportedApis });
  }

  contributeResources(skillPaths: string[] = [], promptPaths: string[] = []): void {
    this.ensureBeforeStart();
    this.registrations.push({ type: "contribute_resources", skillPaths, promptPaths });
  }

  start(): void {
    if (this.reader || this.closed) throw new Error("ProcessMod has already started or closed");
    this.reader = createInterface({ input: this.input });
    this.reader.on("line", (line) => {
      void this.handleLine(line);
    });
    this.reader.on("close", () => {
      this.closed = true;
      for (const controller of this.requests.values()) controller.abort();
      this.requests.clear();
    });
  }

  async requestInteraction(request: JsonObject): Promise<JsonObject> {
    const requestId = String(request.id ?? randomUUID());
    const frame = { type: "interaction_request", requestId, request: { ...request, id: requestId } };
    this.send(frame);
    return new Promise<JsonObject>((resolve) => {
      this.interactions.set(requestId, resolve);
    });
  }

  notify(request: JsonObject): void {
    this.send({ type: "notify", request });
  }

  close(): void {
    this.closed = true;
    this.reader?.close();
    for (const controller of this.requests.values()) controller.abort();
    this.requests.clear();
  }

  private async handleLine(line: string): Promise<void> {
    if (!line.trim()) return;
    let message: JsonObject;
    try {
      message = JSON.parse(line) as JsonObject;
    } catch {
      this.send({ type: "error", message: "Invalid JSONL frame" });
      return;
    }
    const type = String(message.type ?? "");
    if (type === "initialize") {
      if (message.extensionId !== this.extensionId || message.apiVersion !== this.apiVersion) {
        this.send({ type: "error", message: "Process mod handshake mismatch" });
        return;
      }
      for (const registration of this.registrations) this.send(registration);
      this.initialized = true;
      this.send({ type: "ready" });
      return;
    }
    if (!this.initialized) return;
    if (type === "cancel") {
      this.requests.get(String(message.requestId))?.abort();
      return;
    }
    if (type === "interaction_response") {
      const resolve = this.interactions.get(String(message.requestId));
      if (resolve) {
        this.interactions.delete(String(message.requestId));
        resolve((message.response as JsonObject | undefined) ?? message);
      }
      return;
    }
    if (type === "shutdown") {
      this.close();
      return;
    }
    const requestId = String(message.requestId ?? randomUUID());
    const controller = new AbortController();
    this.requests.set(requestId, controller);
    try {
      const context = { requestId, signal: controller.signal };
      if (type === "tool_call") await this.handleToolCall(message, context);
      else if (type === "command") await this.handleCommand(message, context);
      else if (type === "event") await this.handleEvent(message, context);
      else if (type === "provider_stream") await this.handleProvider(message, context);
    } finally {
      this.requests.delete(requestId);
    }
  }

  private async handleToolCall(message: JsonObject, context: RequestContext): Promise<void> {
    const name = String(message.name ?? "");
    const handler = this.tools.get(name);
    if (!handler) {
      this.send({ type: "tool_result", requestId: context.requestId, content: [{ type: "text", text: `Unknown tool: ${name}` }], isError: true });
      return;
    }
    try {
      const result = await handler((message.params as JsonObject | undefined) ?? {}, {
        ...context,
        toolCallId: String(message.toolCallId ?? ""),
      });
      this.send({ type: "tool_result", requestId: context.requestId, ...result });
    } catch (error) {
      this.send({ type: "tool_result", requestId: context.requestId, content: [{ type: "text", text: errorMessage(error) }], isError: true });
    }
  }

  private async handleCommand(message: JsonObject, context: RequestContext): Promise<void> {
    const handler = this.commands.get(String(message.name ?? ""));
    if (handler) await handler(String(message.args ?? ""), context);
    this.send({ type: "command_result", requestId: context.requestId });
  }

  private async handleEvent(message: JsonObject, context: RequestContext): Promise<void> {
    const handler = this.events.get(String(message.event ?? "").toUpperCase());
    const result = handler ? await handler((message.payload as JsonObject | undefined) ?? {}, context) : undefined;
    this.send({ type: "event_result", requestId: context.requestId, ...(result ?? {}) });
  }

  private async handleProvider(message: JsonObject, context: RequestContext): Promise<void> {
    const handler = this.providers.get(String(message.providerId ?? ""));
    if (!handler) {
      this.send({ type: "provider_error", requestId: context.requestId, message: "Unknown provider" });
      return;
    }
    try {
      const output = await handler(message, context);
      for await (const event of output) this.send({ type: "provider_event", requestId: context.requestId, ...event });
      this.send({ type: "provider_done", requestId: context.requestId });
    } catch (error) {
      this.send({ type: "provider_error", requestId: context.requestId, message: errorMessage(error) });
    }
  }

  private send(frame: JsonObject): void {
    if (this.closed) return;
    this.output.write(`${JSON.stringify(frame)}\n`);
  }

  private ensureBeforeStart(): void {
    if (this.reader || this.closed || this.initialized) throw new Error("Register capabilities before ProcessMod.start()");
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
