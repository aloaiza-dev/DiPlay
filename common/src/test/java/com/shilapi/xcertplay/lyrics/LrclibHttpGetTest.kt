package com.shilapi.xcertplay.lyrics

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

class LrclibHttpGetTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @After fun stop() = server.stop(0)

    private fun serve(status: Int, body: String = "") {
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    private fun get() = lrclibHttpGet("http://127.0.0.1:${server.address.port}/api/get")

    @Test fun aBodyIsReturned() {
        serve(200, "{}")
        assertEquals("{}", get())
    }

    @Test fun notFoundMeansNoLyrics() {
        serve(404)
        assertNull(get())
    }

    // A lookup LRCLIB cannot match may come back as 400: it must reach the search fallback, not become a failure.
    @Test fun badRequestMeansNoLyricsSoTheSearchFallbackRuns() {
        serve(400)
        assertNull(get())
    }

    @Test(expected = IOException::class) fun aServerErrorIsAFailure() {
        serve(500)
        get()
    }

    @Test(expected = IOException::class) fun tooManyRequestsIsAFailure() {
        serve(429)
        get()
    }
}
