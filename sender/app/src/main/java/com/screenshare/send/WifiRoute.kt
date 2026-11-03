package com.screenshare.send

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection

/**
 * Keeps the phone on the car's Wi-Fi and sends the app's requests to the car over it.
 *
 * The car's casting hotspot has no internet, so with `network_avoid_bad_wifi` on, Android uses
 * 4G for internet and may drop a Wi-Fi nobody asks for. Requesting the Wi-Fi keeps it connected.
 * Outgoing sockets are pinned to it with [open] and [bind], so requests to the car don't follow
 * the phone onto 4G.
 *
 * Only outgoing sockets are pinned, not the whole process. A listening socket created while
 * pinned keeps the old network's mark, so after a Wi-Fi drop and rejoin (a new Network) the car
 * could no longer reach the stream. Unpinned listeners answer on whichever network a connection
 * arrives on.
 */
object WifiRoute {
    @Volatile private var current: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(context: Context) {
        if (callback != null) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        // Pick up Wi-Fi now if it's already up, so the first request to the car already uses it.
        try {
            @Suppress("DEPRECATION")
            current = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) {}
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { current = network }
            override fun onLost(network: Network) { if (current == network) current = null }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            // A request, not just a listener: it tells Android this app needs the Wi-Fi, so the
            // phone stays connected to the car's hotspot even while 4G carries the internet.
            cm.requestNetwork(request, cb)
            callback = cb
        } catch (_: Exception) {
            try { cm.registerNetworkCallback(request, cb); callback = cb } catch (_: Exception) {}
        }
    }

    /** Opens [url] over Wi-Fi when it's up, otherwise the normal way. */
    fun open(url: URL): URLConnection {
        val n = current ?: return url.openConnection()
        return try { n.openConnection(url) } catch (_: Exception) { url.openConnection() }
    }

    /** Pins an unconnected socket to Wi-Fi when it's up. */
    fun bind(socket: Socket) {
        val n = current ?: return
        try { n.bindSocket(socket) } catch (_: Exception) {}
    }

    /** Pins an unconnected datagram socket to Wi-Fi when it's up. */
    fun bind(socket: DatagramSocket) {
        val n = current ?: return
        try { n.bindSocket(socket) } catch (_: Exception) {}
    }
}
