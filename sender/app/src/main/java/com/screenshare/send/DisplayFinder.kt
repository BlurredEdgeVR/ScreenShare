package com.screenshare.send

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds displays on the Wi-Fi. Shared by the app screen and [MirrorService]'s reconnect, because
 * the car's displays listen on a new port each time the car starts, so a saved address goes
 * stale and the display has to be found again by name.
 */
object DisplayFinder {
    /** Finds displays with SSDP, or by probing port 49152 on the Wi-Fi's /24 if nothing answers, and reads each one's name. */
    fun findAll(cb: (List<CastDevice>) -> Unit) {
        DlnaDiscoverer.search(3000) { ssdp ->
            if (ssdp.isNotEmpty()) describeAll(ssdp, cb)
            // SSDP can't cross subnets or may be filtered: probe the DLNA port instead.
            else SubnetScanner.scan { ips -> describeAll(ips.map { CastDevice("", "http://$it:49152/description.xml", it) }, cb) }
        }
    }

    /** Finds the display called [name]. Calls back with null if it isn't there. */
    fun findByName(name: String, cb: (CastDevice?, RendererEndpoints?) -> Unit) {
        findAll { found ->
            val d = found.firstOrNull { it.friendlyName == name }
            if (d == null) cb(null, null) else DlnaController.describe(d.descriptionUrl) { ep -> cb(d, ep) }
        }
    }

    /** Keeps only the candidates that are renderers, with their names filled in. */
    private fun describeAll(candidates: List<CastDevice>, cb: (List<CastDevice>) -> Unit) {
        if (candidates.isEmpty()) { EventLog.add("Search found no displays"); cb(emptyList()); return }
        val found = Collections.synchronizedList(mutableListOf<CastDevice>())
        val left = AtomicInteger(candidates.size)
        for (d in candidates) {
            DlnaController.describe(d.descriptionUrl) { ep ->
                if (ep != null) found.add(d.copy(friendlyName = ep.friendlyName))
                if (left.decrementAndGet() == 0) {
                    val list = found.sortedBy { it.friendlyName }
                    EventLog.add("Search found ${list.size} display(s)" +
                        list.joinToString("") { "\n    ${it.friendlyName} at ${it.descriptionUrl}" })
                    cb(list)
                }
            }
        }
    }
}
