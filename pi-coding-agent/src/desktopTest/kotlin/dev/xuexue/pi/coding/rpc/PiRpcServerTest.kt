package dev.xuexue.pi.coding.rpc

import dev.xuexue.pi.coding.AgentSessionHarness
import dev.xuexue.pi.coding.AgentSessionRuntime
import dev.xuexue.pi.coding.AgentSessionRuntimeFactory
import dev.xuexue.pi.coding.AgentSessionRuntimeResult
import dev.xuexue.pi.coding.AgentSessionRuntimeServices
import dev.xuexue.pi.coding.extensions.SessionStartReason
import dev.xuexue.pi.coding.session.SessionManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class PiRpcServerTest {
    @Test
    fun serverReadsCommandsAndWritesOneJsonResponsePerLine() =
        runTest {
            val harness = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(harness.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)
            val handler = AgentSessionRpcHandler(runtime)
            val output = StringWriter()
            PiRpcServer(
                handler = handler,
                input = StringReader("""{"type":"get_state","id":"state-1"}""" + "\n"),
                output = output,
                scope = this,
            ).serve()

            val response = PiRpcCodec.json.parseToJsonElement(output.toString().trim()).jsonObject
            assertEquals("response", response["type"]?.toString()?.trim('"'))
            assertEquals("state-1", response["id"]?.toString()?.trim('"'))
            runtime.dispose()
        }
}
