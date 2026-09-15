/** Minimal dependency-free Process Mod SDK for Node/Bun/Deno-style hosts. */
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export type ModRequest = {
  type: "request";
  requestId: string;
  method: string;
  params?: Json;
};

export type ModEvent = {
  type: "event";
  event: string;
  data?: Json;
};

export interface ReadableJsonStream {
  on(event: "data", listener: (chunk: string | Uint8Array) => void): void;
}

export interface WritableJsonStream {
  write(chunk: string): void;
}

export class ProcessModClient {
  private sequence = 0;
  private readonly pending = new Map<string, (value: Json) => void>();

  constructor(
    private readonly input: ReadableJsonStream,
    private readonly output: WritableJsonStream,
  ) {
    let buffer = "";
    input.on("data", (chunk: Buffer | string) => {
      buffer += chunk.toString();
      let newline = buffer.indexOf("\n");
      while (newline >= 0) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        if (line) this.accept(JSON.parse(line));
        newline = buffer.indexOf("\n");
      }
    });
  }

  request<T extends Json = Json>(method: string, params?: Json): Promise<T> {
    const requestId = `mod-${++this.sequence}`;
    const request: ModRequest = { type: "request", requestId, method, params };
    this.output.write(`${JSON.stringify(request)}\n`);
    return new Promise<T>((resolve) => this.pending.set(requestId, resolve as (value: Json) => void));
  }

  notify(event: string, data?: Json): void {
    const message: ModEvent = { type: "event", event, data };
    this.output.write(`${JSON.stringify(message)}\n`);
  }

  private accept(message: { type?: string; requestId?: string; result?: Json }): void {
    if (message.type !== "response" || !message.requestId) return;
    const resolve = this.pending.get(message.requestId);
    if (!resolve) return;
    this.pending.delete(message.requestId);
    resolve(message.result ?? null);
  }
}
