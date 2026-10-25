package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayAcceptLimitsTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val accepted = CopyOnWriteArrayList<AirPlayTcpAccepted>()
    private val clients = CopyOnWriteArrayList<Socket>()
    private lateinit var service: CarPlayVpnService

    @Before fun attach() {
        service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(loopback, config,
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onTcpAccepted(event: AirPlayTcpAccepted) { accepted.add(event) }
            }, object : AirPlayMediaHandler {}, listenerIdentity = AirPlayListenerIdentity(1)))
    }

    @After fun detach() {
        clients.forEach { runCatching { it.close() } }
        service.detach()
    }

    private fun connect(count: Int) = repeat(count) {
        clients.add(Socket(loopback, service.boundPort()!!))
    }

    private fun waitFor(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
    }

    private fun isClosedByServer(client: Socket): Boolean {
        client.soTimeout = 100
        return try { client.getInputStream().read() == -1 } catch (_: SocketTimeoutException) { false } catch (_: IOException) { true }
    }

    @Test fun idleConnectionsFromOneAddressAreCapped() {
        connect(200)
        var open = clients.size
        waitFor(timeoutMillis = 20_000) {
            open = clients.count { !isClosedByServer(it) }
            open <= 32
        }
        assertTrue("$open of 200 idle connections were still held", open <= 32)
    }

    @Test fun idleConnectionIsClosedBeforePairingCompletes() {
        connect(1)
        val client = clients.single().apply { soTimeout = 13_000 }
        val closed = try {
            client.getInputStream().read() == -1
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: IOException) {
            true
        }
        assertTrue("idle unauthenticated connection was still open after 13 s", closed)
    }

    @Test fun connectionsUnderTheCapAreAcceptedAndStayOpen() {
        connect(3)
        waitFor { accepted.size == 3 }
        assertEquals(3, accepted.size)
        Thread.sleep(500)
        clients.forEach { assertFalse(it.isClosed) }
    }

    @Test fun slotsAreReleasedWhenClientsDisconnect() {
        repeat(5) {
            connect(8)
            waitFor { accepted.size == (it + 1) * 8 }
            clients.forEach { c -> c.close() }
            clients.clear()
            Thread.sleep(600)
        }
        assertEquals(40, accepted.size)
    }

    @Test fun newConnectionFromAFullAddressIsAcceptedAndTheOldestIdleOneIsDropped() {
        connect(8)
        waitFor { accepted.size == 8 }
        Thread.sleep(2_500)
        connect(1)
        waitFor { accepted.size == 9 }
        assertEquals("new connection was refused at the per-address cap", 9, accepted.size)
        val oldest = clients.first().apply { soTimeout = 2_000 }
        val closed = try { oldest.getInputStream().read() == -1 } catch (_: SocketTimeoutException) { false } catch (_: IOException) { true }
        assertTrue("oldest idle connection was not dropped", closed)
    }
}
