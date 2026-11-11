package com.freebrowser.app

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Socket look-alike whose bytes travel through a WebSocket to the relay
 * (Cloudflare Worker or own VPS) which opens the real connection to the website.
 */
class WsSocket private constructor() : Socket() {
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var closedFlag = false

    private val inStream = object : InputStream() {
        private var cur: ByteArray? = null
        private var pos = 0
        private var eof = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (eof) return -1
            var c = cur
            if (c == null || pos >= c.size) {
                val next = queue.poll(300, TimeUnit.SECONDS)
                if (next == null || next === EOF) {
                    eof = true
                    return -1
                }
                c = next
                cur = next
                pos = 0
            }
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }
    }

    private val outStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closedFlag) throw IOException("closed")
            val w = ws ?: throw IOException("not connected")
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, 16384)
                if (!w.send(b.toByteString(o, n))) throw IOException("send failed")
                o += n
                left -= n
            }
        }
    }

    override fun getInputStream(): InputStream = inStream

    override fun getOutputStream(): OutputStream = outStream

    override fun isClosed(): Boolean = closedFlag

    override fun isConnected(): Boolean = !closedFlag

    override fun close() {
        if (closedFlag) return
        closedFlag = true
        try {
            ws?.close(1000, null)
        } catch (_: Exception) {
        }
        queue.offer(EOF)
        try {
            super.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private val EOF = ByteArray(0)

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        val r = Doh.resolve(hostname)
                        if (r.isEmpty()) throw UnknownHostException(hostname)
                        return r
                    }
                })
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        /** Opens a tunnel to host:port through the relay, or returns null if the relay can't be reached. */
        fun open(host: String, port: Int): WsSocket? {
            val base = Config.relayUrl?.toHttpUrlOrNull() ?: return null
            val url = base.newBuilder()
                .addPathSegment("t")
                .addQueryParameter("h", host)
                .addQueryParameter("p", port.toString())
                .addQueryParameter("k", Config.relayKey)
                .build()
            val s = WsSocket()
            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            s.ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ok.set(true)
                    latch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    s.queue.offer(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    s.queue.offer(text.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    s.queue.offer(EOF)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    s.queue.offer(EOF)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    s.queue.offer(EOF)
                    latch.countDown()
                }
            })
            latch.await(9, TimeUnit.SECONDS)
            if (!ok.get()) {
                s.close()
                return null
            }
            return s
        }
    }
}
