package com.shieldbrowser.app

import android.content.res.AssetManager

/** Domain blocklist. Matches the host itself and any parent domain (a.b.com -> b.com). */
class TrackerBlocker(assets: AssetManager) {

    private val domains = HashSet<String>()

    init {
        try {
            assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.substringBefore('#').trim().lowercase()
                    if (line.isEmpty()) continue
                    val parts = line.split(Regex("\\s+"))
                    val domain =
                        if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1]
                        else parts[0]
                    if (domain.contains('.')) domains.add(domain)
                }
            }
        } catch (e: Exception) {
            // No list available: blocker simply does nothing.
        }
    }

    fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (domains.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
        }
    }
}
