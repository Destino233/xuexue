// The desktop source suffix and default host dispatcher are intentional for this platform adapter.
@file:Suppress("InjectDispatcher", "MatchingDeclarationName")

package dev.xuexue.pi.coding.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Reader
import java.io.Writer

/** Desktop stdin/stdout host for the Pi-compatible JSONL handler. */
class PiRpcServer(
    private val handler: AgentSessionRpcHandler,
    private val input: Reader = System.`in`.reader(Charsets.UTF_8),
    private val output: Writer = System.out.writer(Charsets.UTF_8),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    suspend fun serve() {
        val reader = input.buffered()
        val writer = output.buffered()
        val eventJob =
            scope.launch {
                handler.events.collect { event ->
                    writeLine(writer, PiRpcCodec.encodeEvent(event))
                }
            }
        try {
            withContext(Dispatchers.IO) {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        val response = handler.handleLineForServer(line)
                        if (response != null) writeLine(writer, response)
                        if (handler.isShutdownRequested()) return@withContext
                    }
                }
            }
        } finally {
            eventJob.cancelAndJoin()
            handler.close()
            reader.close()
            writer.flush()
        }
    }

    private fun writeLine(
        writer: BufferedWriter,
        line: String,
    ) {
        synchronized(writer) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }
}
