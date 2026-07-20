package dev.xuexue.platform

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

object PaddleOcrTestSidecarMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4)
        val port = args[0].toInt()
        val version = args[1]
        val started = Path.of(args[2])
        val stopped = Path.of(args[3])
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/health") { exchange ->
            val body = "{\"version\":\"$version\",\"ready\":true}".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { output -> output.write(body) }
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { Files.writeString(stopped, "stopped") }
                server.stop(0)
            },
        )
        server.start()
        Files.writeString(started, "started")
        try {
            while (true) Thread.sleep(1_000)
        } finally {
            server.stop(0)
        }
    }
}
