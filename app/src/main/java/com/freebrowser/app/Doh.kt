package com.freebrowser.app

import org.json.JSONObject
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/** Settings shared between the UI and the proxy. */
object Config {
    /** 0 = only DoH, 1 = TCP split of ClientHello, 2 = TLS record split + TCP split, 3 = auto (tries 1, 2, 0) */
    @Volatile
    var mode = 3

    @Volatile
    var adBlock = true

    /** Optional upstream proxy used only when direct connection fails. type: 0 = HTTP CONNECT, 1 = SOCKS5 */
    @Volatile
    var upstreamHost: String? = null

    @Volatile
    var upstreamPort = 0

    @Volatile
    var upstreamType = 0

    /** Relay (Cloudflare Worker or own VPS) reached over WebSocket. Works for IP-blocked sites too. */
    @Volatile
    var relayUrl: String? = null

    @Volatile
    var relayKey = ""

    /** true = every site goes through the relay, false = only when direct connection fails */
    @Volatile
    var relayAlways = false
}

/** Minimal DNS-over-HTTPS resolver (JSON API). Talks to the DoH servers by IP, so ISP DNS is never used. */
object Doh {
    private class Entry(val addrs: List<InetAddress>, val expires: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val ipv6 = Regex("^[0-9a-fA-F]*:[0-9a-fA-F:.]*$")

    // template, Accept header
    private val servers = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A" to "application/dns-json",
        "https://8.8.8.8/resolve?name=%s&type=A" to "application/json",
        "https://1.0.0.1/dns-query?name=%s&type=A" to "application/dns-json",
        "https://8.8.4.4/resolve?name=%s&type=A" to "application/json"
    )

    fun resolve(host: String): List<InetAddress> {
        if (ipv4.matches(host) || ipv6.matches(host)) {
            return try {
                listOf(InetAddress.getByName(host))
            } catch (e: Exception) {
                emptyList()
            }
        }
        val key = host.lowercase()
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.expires > now) return it.addrs }

        for ((template, accept) in servers) {
            try {
                val e = query(template, accept, host)
                if (e != null) {
                    cache[key] = e
                    return e.addrs
                }
            } catch (_: Exception) {
            }
        }
        // Last resort: system DNS (may be poisoned by ISP, but better than nothing)
        return try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun query(template: String, accept: String, host: String): Entry? {
        val url = URL(String.format(template, URLEncoder.encode(host, "UTF-8")))
        val conn = url.openConnection() as HttpsURLConnection
        try {
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.setRequestProperty("Accept", accept)
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val answers = JSONObject(body).optJSONArray("Answer") ?: return null
            val list = ArrayList<InetAddress>()
            var ttl = 600
            for (i in 0 until answers.length()) {
                val o = answers.getJSONObject(i)
                if (o.optInt("type") == 1) {
                    list.add(InetAddress.getByName(o.getString("data")))
                    ttl = minOf(ttl, o.optInt("TTL", 60))
                }
            }
            if (list.isEmpty()) return null
            val ms = maxOf(30, minOf(ttl, 600)) * 1000L
            return Entry(list, System.currentTimeMillis() + ms)
        } finally {
            conn.disconnect()
        }
    }
}
