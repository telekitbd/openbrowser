package com.freebrowser.app

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tiny HTTP/CONNECT proxy on 127.0.0.1 used by the WebView.
 *  - hostnames are resolved with DoH (see [Doh])
 *  - the first TLS packet (ClientHello) is split so simple ISP DPI can't read the SNI
 *  - in auto mode several split styles are tried per site and the working one is remembered
 *  - if everything direct fails and an upstream proxy is configured, it is used as a fallback
 */
class LocalProxy {
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true }
    }

    /** host -> working mode (0,1,2) or -1 when only the upstream proxy worked */
    private val hostMode = ConcurrentHashMap<String, Int>()

    @Volatile
    private var server: ServerSocket? = null

    fun start(onReady: (Int) -> Unit) {
        Thread {
            try {
                val s = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
                server = s
                onReady(s.localPort)
                while (!s.isClosed) {
                    val c = s.accept()
                    pool.execute { handle(c) }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        closeQuiet(server)
        server = null
    }

    // ---------------------------------------------------------------- connection handling

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 300_000
            val cin = BufferedInputStream(client.getInputStream(), 16384)
            val cout = client.getOutputStream()

            val head = readHead(cin) ?: return
            val text = String(head, Charsets.ISO_8859_1)
            val lines = text.split("\r\n")
            val first = lines[0].split(" ")
            if (first.size < 3) return
            val method = first[0]
            val target = first[1]

            if (method.equals("CONNECT", ignoreCase = true)) {
                val (host, port) = splitHostPort(target, 443)
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                cout.flush()

                val hdr = readFully(cin, 5) ?: return
                val r: Socket
                if (hdr[0].toInt() != 0x16) {
                    // not TLS: plain tunnel
                    r = openAny(host, port) ?: return
                    remote = r
                    r.getOutputStream().write(hdr)
                    r.getOutputStream().flush()
                } else {
                    val payload = readFully(cin, u16(hdr, 3)) ?: return
                    r = connectTls(host, port, hdr, payload, cout) ?: return
                    remote = r
                }
                pipe(client, cin, r)
            } else {
                // plain HTTP via proxy: "GET http://host[:port]/path HTTP/1.1"
                if (!target.startsWith("http://", ignoreCase = true)) {
                    cout.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    cout.flush()
                    return
                }
                val rest = target.substring(7)
                val idx = rest.indexOfFirst { it == '/' || it == '?' }
                val hostPort = if (idx < 0) rest else rest.substring(0, idx)
                val path = when {
                    idx < 0 -> "/"
                    rest[idx] == '?' -> "/" + rest.substring(idx)
                    else -> rest.substring(idx)
                }
                val (host, port) = splitHostPort(hostPort, 80)
                val r = openAny(host, port)
                if (r == null) {
                    cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    cout.flush()
                    return
                }
                remote = r

                val sb = StringBuilder()
                sb.append(method).append(' ').append(path).append(' ').append(first[2]).append("\r\n")
                for (i in 1 until lines.size) {
                    val l = lines[i]
                    if (l.isEmpty()) continue
                    val name = l.substringBefore(':').trim().lowercase()
                    if (name == "proxy-connection" || name == "connection" ||
                        name == "proxy-authorization" || name == "keep-alive"
                    ) continue
                    sb.append(l).append("\r\n")
                }
                sb.append("Connection: close\r\n\r\n")
                val req = sb.toString()
                val bytes = req.toByteArray(Charsets.ISO_8859_1)
                val rout = r.getOutputStream()
                var cut = -1
                if (Config.mode != 0) {
                    val hi = req.indexOf("\r\nhost:", ignoreCase = true)
                    if (hi > 0) {
                        var vs = hi + 7
                        while (vs < req.length && req[vs] == ' ') vs++
                        val ve = req.indexOf("\r\n", vs).let { if (it < 0) req.length else it }
                        cut = vs + maxOf(1, (ve - vs) / 2)
                    }
                }
                if (cut in 1 until bytes.size) {
                    rout.write(bytes, 0, cut)
                    rout.flush()
                    Thread.sleep(2)
                    rout.write(bytes, cut, bytes.size - cut)
                } else {
                    rout.write(bytes)
                }
                rout.flush()
                pipe(client, cin, r)
            }
        } catch (_: Exception) {
        } finally {
            closeQuiet(client)
            closeQuiet(remote)
        }
    }

    // ---------------------------------------------------------------- outgoing connections

    /** Direct connection (DoH-resolved). */
    private fun open(host: String, port: Int): Socket? {
        for (addr in Doh.resolve(host)) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(addr, port), if (hasFallback()) 4000 else 8000)
                s.soTimeout = 300_000
                return s
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun hasFallback(): Boolean = Config.relayUrl != null || Config.upstreamHost != null

    private fun relayOpen(host: String, port: Int): Socket? =
        if (Config.relayUrl != null) WsSocket.open(host, port) else null

    /** Relay first, then the user's upstream proxy. */
    private fun fallbackOpen(host: String, port: Int): Socket? =
        relayOpen(host, port) ?: openUpstream(host, port)

    private fun openAny(host: String, port: Int): Socket? {
        if (Config.relayAlways && Config.relayUrl != null) {
            val r = relayOpen(host, port)
            if (r != null) return r
        }
        return open(host, port) ?: fallbackOpen(host, port)
    }

    /** "m.example.com" -> "example.com" (good enough to remember which sites need the relay). */
    private fun baseKey(host: String): String {
        val h = host.lowercase()
        val p = h.split('.')
        return if (p.size <= 2) h else p.takeLast(2).joinToString(".")
    }

    private fun sendRaw(s: Socket, hdr: ByteArray, payload: ByteArray): Boolean {
        return try {
            val out = s.getOutputStream()
            out.write(hdr)
            out.write(payload)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * TLS connection. Order:
     *  1. relay-always mode -> relay
     *  2. site known to need the relay -> relay / upstream
     *  3. direct with ClientHello splitting (auto mode tries several styles, remembers the one that works)
     *  4. direct failed -> relay / upstream (and remember that)
     */
    private fun connectTls(host: String, port: Int, hdr: ByteArray, payload: ByteArray, cout: OutputStream): Socket? {
        val key = baseKey(host)
        val remembered = hostMode[key]
        val fallback = hasFallback()

        fun viaFallback(): Socket? {
            val u = fallbackOpen(host, port) ?: return null
            if (!sendRaw(u, hdr, payload)) {
                closeQuiet(u)
                return null
            }
            hostMode[key] = -1
            return u
        }

        if (Config.relayAlways && Config.relayUrl != null) {
            val r = relayOpen(host, port)
            if (r != null) {
                if (sendRaw(r, hdr, payload)) return r
                closeQuiet(r)
            }
        }
        if (fallback && remembered == -1) {
            val u = viaFallback()
            if (u != null) return u
        }

        val waitReply = Config.mode == 3 || fallback
        var modes: List<Int> = when {
            Config.mode == 0 -> listOf(0)
            Config.mode == 1 -> listOf(1)
            Config.mode == 2 -> listOf(2)
            remembered != null && remembered >= 0 -> listOf(remembered) + listOf(1, 2, 0).filter { it != remembered }
            else -> listOf(1, 2, 0)
        }
        if (fallback && Config.mode == 3) modes = modes.take(1) // the relay is the safety net, don't waste time

        for (m in modes) {
            val s = open(host, port) ?: break // no route at all (DNS/connect failed) - splitting can't help
            try {
                val out = s.getOutputStream()
                if (m == 0) {
                    out.write(hdr)
                    out.write(payload)
                    out.flush()
                } else {
                    sendFragmented(out, hdr, payload, m)
                }
                if (!waitReply) return s

                s.soTimeout = if (fallback) 4000 else 6000
                val buf = ByteArray(16384)
                val n = s.getInputStream().read(buf)
                if (n > 0 && (buf[0].toInt() == 0x16 || buf[0].toInt() == 0x15)) {
                    cout.write(buf, 0, n)
                    cout.flush()
                    s.soTimeout = 300_000
                    hostMode[key] = m
                    return s
                }
            } catch (_: Exception) {
            }
            closeQuiet(s)
        }

        return if (fallback) viaFallback() else null
    }

    /** Connects through the user's upstream proxy (HTTP CONNECT or SOCKS5, no auth). */
    private fun openUpstream(host: String, port: Int): Socket? {
        val uh = Config.upstreamHost ?: return null
        val up = Config.upstreamPort
        val type = Config.upstreamType
        val s = Socket()
        try {
            val addr = Doh.resolve(uh).firstOrNull() ?: return null
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(addr, up), 8000)
            s.soTimeout = 15000
            val ins = s.getInputStream()
            val outs = s.getOutputStream()
            if (type == 1) {
                outs.write(byteArrayOf(5, 1, 0))
                outs.flush()
                val r = readFully(ins, 2) ?: throw IOException("socks")
                if (r[1].toInt() != 0) throw IOException("socks auth")
                val hb = host.toByteArray()
                val req = ByteArrayOutputStream()
                req.write(byteArrayOf(5, 1, 0, 3))
                req.write(hb.size)
                req.write(hb)
                req.write(port shr 8)
                req.write(port and 0xff)
                outs.write(req.toByteArray())
                outs.flush()
                val h = readFully(ins, 4) ?: throw IOException("socks")
                if (h[1].toInt() != 0) throw IOException("socks connect")
                when (h[3].toInt()) {
                    1 -> readFully(ins, 6)
                    3 -> {
                        val l = ins.read()
                        if (l < 0) throw IOException("socks")
                        readFully(ins, l + 2)
                    }
                    4 -> readFully(ins, 18)
                    else -> throw IOException("socks addr")
                } ?: throw IOException("socks")
            } else {
                outs.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray())
                outs.flush()
                val resp = readHead(ins) ?: throw IOException("http")
                val line = String(resp, Charsets.ISO_8859_1)
                if (!line.startsWith("HTTP/1.1 200") && !line.startsWith("HTTP/1.0 200")) throw IOException("http " + line.take(20))
            }
            s.soTimeout = 300_000
            return s
        } catch (_: Exception) {
            closeQuiet(s)
            return null
        }
    }

    private fun pipe(a: Socket, ain: InputStream, b: Socket) {
        pool.execute {
            try {
                copy(b.getInputStream(), a.getOutputStream())
            } catch (_: Exception) {
            } finally {
                closeQuiet(a)
                closeQuiet(b)
            }
        }
        try {
            copy(ain, b.getOutputStream())
        } catch (_: Exception) {
        }
        closeQuiet(a)
        closeQuiet(b)
    }

    private fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(16384)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            out.flush()
        }
    }

    // ---------------------------------------------------------------- TLS ClientHello splitting

    private fun sendFragmented(out: OutputStream, hdr: ByteArray, payload: ByteArray, mode: Int) {
        if (payload.size < 2) {
            out.write(hdr)
            out.write(payload)
            out.flush()
            return
        }
        val sni = findSni(payload)
        var sp = if (sni != null) sni[0] + maxOf(1, sni[1] / 2) else minOf(10, payload.size / 2)
        if (sp < 1) sp = 1
        if (sp >= payload.size) sp = payload.size - 1

        if (mode == 2) {
            // two TLS records, each sent in its own TCP segment
            out.write(record(hdr, payload, 0, sp))
            out.flush()
            Thread.sleep(2)
            out.write(record(hdr, payload, sp, payload.size - sp))
            out.flush()
        } else {
            // one TLS record, cut into two TCP segments in the middle of the SNI
            val all = ByteArray(5 + payload.size)
            System.arraycopy(hdr, 0, all, 0, 5)
            System.arraycopy(payload, 0, all, 5, payload.size)
            val cut = 5 + sp
            out.write(all, 0, cut)
            out.flush()
            Thread.sleep(2)
            out.write(all, cut, all.size - cut)
            out.flush()
        }
    }

    private fun record(hdr: ByteArray, p: ByteArray, off: Int, len: Int): ByteArray {
        val r = ByteArray(5 + len)
        r[0] = hdr[0]
        r[1] = hdr[1]
        r[2] = hdr[2]
        r[3] = (len shr 8).toByte()
        r[4] = len.toByte()
        System.arraycopy(p, off, r, 5, len)
        return r
    }

    /** Returns [offsetOfHostName, hostNameLength] inside the handshake payload, or null. */
    private fun findSni(p: ByteArray): IntArray? {
        try {
            if (p.size < 42 || p[0].toInt() != 1) return null
            var i = 38 // type(1) + len(3) + version(2) + random(32)
            i += 1 + (p[i].toInt() and 0xff) // session id
            i += 2 + u16(p, i) // cipher suites
            i += 1 + (p[i].toInt() and 0xff) // compression
            val extTotal = u16(p, i)
            i += 2
            val end = minOf(p.size, i + extTotal)
            while (i + 4 <= end) {
                val type = u16(p, i)
                val len = u16(p, i + 2)
                i += 4
                if (type == 0) {
                    val nameLen = u16(p, i + 3)
                    return intArrayOf(i + 5, nameLen)
                }
                i += len
            }
        } catch (_: Exception) {
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun readFully(ins: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(b, off, n - off)
            if (r < 0) return null
            off += r
        }
        return b
    }

    /** Reads up to and including the blank line that ends the HTTP header. */
    private fun readHead(ins: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var state = 0 // 0 none, 1 \r, 2 \r\n, 3 \r\n\r, 4 done
        while (buf.size() < 32768) {
            val b = ins.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                b == 13 -> if (state == 2) 3 else 1
                b == 10 -> when (state) {
                    1 -> 2
                    3 -> 4
                    else -> 0
                }
                else -> 0
            }
            if (state == 4) return buf.toByteArray()
        }
        return null
    }

    private fun splitHostPort(s: String, defPort: Int): Pair<String, Int> {
        if (s.startsWith("[")) { // [ipv6]:port
            val end = s.indexOf(']')
            val host = s.substring(1, end)
            val port = if (s.getOrNull(end + 1) == ':') s.substring(end + 2).toIntOrNull() ?: defPort else defPort
            return host to port
        }
        val c = s.lastIndexOf(':')
        return if (c > 0) s.substring(0, c) to (s.substring(c + 1).toIntOrNull() ?: defPort) else s to defPort
    }

    private fun closeQuiet(c: Closeable?) {
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }
}
