package com.freebrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bookmarks + history kept in SharedPreferences (small JSON lists). */
class Store(ctx: Context) {
    data class Item(val title: String, val url: String)

    private val sp = ctx.getSharedPreferences("data", Context.MODE_PRIVATE)

    private fun load(key: String): MutableList<Item> {
        val out = ArrayList<Item>()
        try {
            val a = JSONArray(sp.getString(key, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Item(o.optString("t"), o.getString("u")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun save(key: String, list: List<Item>) {
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("t", x.title).put("u", x.url))
        sp.edit().putString(key, a.toString()).apply()
    }

    fun bookmarks(): MutableList<Item> = load("bm")

    fun addBookmark(item: Item): Boolean {
        val l = load("bm")
        if (l.any { it.url == item.url }) return false
        l.add(0, item)
        save("bm", l)
        return true
    }

    fun removeBookmark(url: String) {
        val l = load("bm")
        l.removeAll { it.url == url }
        save("bm", l)
    }

    fun history(): MutableList<Item> = load("hist")

    fun addHistory(item: Item) {
        val l = load("hist")
        l.removeAll { it.url == item.url }
        l.add(0, item)
        while (l.size > 200) l.removeAt(l.size - 1)
        save("hist", l)
    }

    fun removeHistory(url: String) {
        val l = load("hist")
        l.removeAll { it.url == url }
        save("hist", l)
    }

    fun clearHistory() {
        sp.edit().remove("hist").apply()
    }
}

/** Very small domain based ad / popup network blocklist. */
object AdBlock {
    private val domains = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adnxs.com", "exoclick.com", "exosrv.com", "realsrv.com", "magsrv.com", "juicyads.com",
        "trafficjunky.net", "trafficfactory.biz", "popads.net", "popcash.net", "propellerads.com",
        "propellerclick.com", "adsterra.com", "highperformanceformat.com", "effectivegatecpm.com",
        "clickadu.com", "hilltopads.net", "tsyndicate.com", "plugrush.com", "adcash.com",
        "ad-maven.com", "a-ads.com", "mgid.com", "adskeeper.com", "taboola.com", "outbrain.com",
        "onclickads.net", "bidvertiser.com", "ero-advertising.com", "adspyglass.com", "ads.exoclick.com",
        "syndication.realsrv.com", "pubadx.one", "cdn4ads.com", "adtng.com", "adxxx.com"
    )

    fun isAd(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return domains.any { h == it || h.endsWith(".$it") }
    }
}
