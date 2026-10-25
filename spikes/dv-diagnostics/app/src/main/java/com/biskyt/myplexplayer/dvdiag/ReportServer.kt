package com.biskyt.myplexplayer.dvdiag

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Serves the report as plain text on the home network, so a phone can open it from the QR code and copy it: a TV has
 * no easy way to send a long text anywhere.
 */
class ReportServer(private val report: () -> String) {
    /** Any free port: both builds can serve at once. */
    private val socket = ServerSocket(0)

    /** The address to open, or null when the device has no home-network address. */
    val address: String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.let { "http://${it.hostAddress}:${socket.localPort}/" }

    init {
        thread(isDaemon = true, name = "report-server") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // Each request on its own, so a browser's idle connection can't hold the others up.
                thread(isDaemon = true) { answer(client) }
            }
        }
    }

    fun close() = socket.close()

    private fun answer(client: Socket) = runCatching {
        client.use {
            it.soTimeout = 10_000
            val reader = it.getInputStream().bufferedReader()
            while (!reader.readLine().isNullOrEmpty()) Unit // the request, whatever it is
            val body = report().toByteArray()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            it.getOutputStream().apply {
                write(head.toByteArray())
                write(body)
                flush()
            }
        }
    }
}
