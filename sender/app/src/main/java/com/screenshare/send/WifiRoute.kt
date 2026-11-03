package com.screenshare.send

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Keeps the phone on the car's Wi-Fi and sends all of this app's traffic over it. The car's casting
 * hotspot has no internet, so with `network_avoid_bad_wifi` on, Android uses 4G for internet and
 * may drop a Wi-Fi nobody asks for. Requesting the Wi-Fi keeps it connected, and binding the
 * process stops the app's requests to the car from following the phone onto 4G. The app itself
 * never needs the internet, so binding the whole process is safe.
 */
object WifiRoute {
    @Volatile private var bound: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(context: Context) {
        if (callback != null) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        // Bind now if Wi-Fi is already up, so the first request to the car already uses it.
        try {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }?.let { bind(cm, it) }
        } catch (_: Exception) {}
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = bind(cm, network)
            override fun onLost(network: Network) {
                if (bound == network) {
                    try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
                    bound = null
                }
            }
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

    private fun bind(cm: ConnectivityManager, network: Network) {
        try {
            if (cm.bindProcessToNetwork(network)) bound = network
        } catch (_: Exception) {}
    }
}
