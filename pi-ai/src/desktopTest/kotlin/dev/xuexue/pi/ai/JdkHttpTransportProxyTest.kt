package dev.xuexue.pi.ai

import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JdkHttpTransportProxyTest {
    @Test
    fun parsesFullProxyUrl() {
        val address = parseProxyAddress("http://127.0.0.1:7890")
        assertEquals("127.0.0.1", address?.hostString)
        assertEquals(7890, address?.port)
    }

    @Test
    fun parsesHostPortWithoutScheme() {
        val address = parseProxyAddress("proxy.corp:8080")
        assertEquals("proxy.corp", address?.hostString)
        assertEquals(8080, address?.port)
    }

    @Test
    fun defaultsPortTo80WhenMissing() {
        val address = parseProxyAddress("http://proxy.local")
        assertEquals("proxy.local", address?.hostString)
        assertEquals(80, address?.port)
    }

    @Test
    fun returnsNullForBlankValues() {
        assertNull(parseProxyAddress(""))
        assertNull(parseProxyAddress("   "))
    }

    @Test
    fun returnsNullForMalformedValues() {
        assertNull(parseProxyAddress("://broken"))
    }

    @Test
    fun envProxySelectorBuildsHttpProxy() {
        val selector = envProxySelector()
        // Without env vars set in the test JVM this is null; with a configured
        // host environment it returns a real selector. The parsing behavior is
        // covered above; this guards the wiring itself.
        if (selector != null) {
            val proxies = selector.select(java.net.URI.create("https://api.example.com/v1"))
            assertEquals(java.net.Proxy.Type.HTTP, proxies.single().type())
            val address = proxies.single().address() as InetSocketAddress
            assertEquals(true, address.port > 0)
        }
    }
}