package com.freebrowser.app

/**
 * Fill URL before building if you want the APK to ship already connected to your relay
 * (e.g. "https://myrelay.myname.workers.dev"). The key matches relay/worker.js and relay/vps-server.js.
 */
object RelayDefaults {
    const val URL = ""
    const val KEY = "23228a446f85b394e78f2ed9f8bf0864"
}
