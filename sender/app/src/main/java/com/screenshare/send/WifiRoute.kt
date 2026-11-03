package com.screenshare.send

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Socket
import java.net.URL
import java.net.URLConnection

/**
 * Keeps the phone on the car's Wi-Fi and sends the app's requests to the car over it.
 *
 * It works in one of two ways:
 * - **The phone joins the car's Wi-Fi** (default). The user connects in Wi-Fi settings, and the
 *   app requests that Wi-Fi so Android keeps it connected. Whether other apps then get internet
 *   over 4G is up to Android (and Samsung's "Switch to mobile data").
 * - **The app joins the car's Wi-Fi** ([setCarWifi], Android 10+). The app asks Android for that
 *   one network with a [WifiNetworkSpecifier]. Android makes it a link for this app only, without
 *   internet, so it can never become the phone's internet connection and 4G stays in use. The
 *   first time, Android asks the user to allow it. The car's network should be forgotten in Wi-Fi
 *   settings, so the phone doesn't also join it the normal way.
 *
 * Outgoing sockets are pinned to the Wi-Fi with [open] and [bind]. Only outgoing sockets are
 * pinned, not the whole process: a listening socket created while pinned keeps the old network's
 * mark, so after a Wi-Fi drop and rejoin (a new Network) the car could no longer reach the
 * stream. Unpinned listeners answer on whichever network a connection arrives on.
 */
object WifiRoute {
    private const val PREFS = "car_wifi"

    @Volatile private var current: Network? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null
    @Volatile private var carSsid: String? = null
    private var carPass = ""
    private var lastRequestMs = 0L
    /** Ask again on our own only after the car's Wi-Fi was joined and then lost, not after a refusal. */
    private var autoRetry = false
    @Volatile private var lastDefault = ""

    /** True when the app joins the car's Wi-Fi itself, rather than the phone. */
    val joinsCarWifi: Boolean get() = carSsid != null

    /** The car Wi-Fi name the app joins, if it does. */
    val carWifiName: String? get() = carSsid

    val carWifiPassword: String get() = carPass

    /** True while the app is asking for the car's Wi-Fi and hasn't joined it yet. */
    val joiningCarWifi: Boolean get() = carSsid != null && callback != null && current == null

    @Synchronized
    fun start(context: Context) {
        if (cm == null) {
            val app = context.applicationContext
            val c = app.getSystemService(ConnectivityManager::class.java) ?: return
            cm = c
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 29) carSsid = p.getString("ssid", null)?.takeIf { it.isNotBlank() }
            carPass = p.getString("pass", "") ?: ""
            watchInternet(c)
        }
        ensure(userAction = true)
    }

    /**
     * Makes sure the Wi-Fi is being asked for. In app-joins mode, Android may show a prompt, so
     * without a [userAction] it only asks again after the car's Wi-Fi was joined and then lost,
     * at most every 15 s. A cancelled prompt isn't repeated until the user acts.
     */
    @Synchronized
    fun ensure(userAction: Boolean) {
        val c = cm ?: return
        if (callback != null) return
        val ssid = carSsid
        if (ssid == null) { requestAnyWifi(c); return }
        val now = System.currentTimeMillis()
        if (!userAction && (!autoRetry || now - lastRequestMs < 15_000)) return
        lastRequestMs = now
        requestCarWifi(c, ssid)
    }

    /** Stops asking for the car's Wi-Fi if it hasn't joined, so no prompt is left waiting. */
    @Synchronized
    fun cancelPending() {
        if (!joiningCarWifi) return
        callback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        autoRetry = false
    }

    /** Switches between the two modes. [ssid] null means the phone joins the car's Wi-Fi itself. */
    @Synchronized
    fun setCarWifi(context: Context, ssid: String?, pass: String) {
        if (ssid != null && Build.VERSION.SDK_INT < 29) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (ssid == null) clear() else putString("ssid", ssid).putString("pass", pass)
        }.apply()
        callback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        current = null
        carSsid = ssid
        carPass = pass
        EventLog.add(if (ssid == null) "Car Wi-Fi: the phone joins it" else "Car Wi-Fi: the app joins \"$ssid\"")
        ensure(userAction = true)
    }

    private fun requestAnyWifi(c: ConnectivityManager) {
        // Pick up Wi-Fi now if it's already up, so the first request to the car already uses it.
        try {
            @Suppress("DEPRECATION")
            current = c.allNetworks.firstOrNull {
                c.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) {}
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { current = network; EventLog.add("Wi-Fi joined") }
            override fun onLost(network: Network) { if (current == network) current = null; EventLog.add("Wi-Fi lost") }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            // A request, not just a listener: it tells Android this app needs the Wi-Fi, so the
            // phone stays connected to the car's hotspot even while 4G carries the internet.
            c.requestNetwork(request, cb)
            callback = cb
        } catch (_: Exception) {
            try { c.registerNetworkCallback(request, cb); callback = cb } catch (_: Exception) {}
        }
    }

    @TargetApi(29)
    private fun requestCarWifi(c: ConnectivityManager, ssid: String) {
        current = null
        val spec = try {
            WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
                if (carPass.isNotEmpty()) setWpa2Passphrase(carPass)
            }.build()
        } catch (e: Exception) {
            EventLog.add("Car Wi-Fi settings not accepted: ${e.message}")
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current = network
                synchronized(this@WifiRoute) { autoRetry = true }
                EventLog.add("Joined the car's Wi-Fi \"$ssid\" (phone ${ipOf(network) ?: "has no address yet"})")
            }
            override fun onLost(network: Network) {
                if (current == network) current = null
                EventLog.add("Lost the car's Wi-Fi")
                drop(this)
            }
            override fun onUnavailable() {
                EventLog.add("Didn't join the car's Wi-Fi (not found, or not allowed)")
                synchronized(this@WifiRoute) { autoRetry = false }
                drop(this)
            }
        }
        try {
            c.requestNetwork(request, cb)
            callback = cb
            EventLog.add("Asking Android to join the car's Wi-Fi \"$ssid\"")
        } catch (e: Exception) {
            EventLog.add("Couldn't ask for the car's Wi-Fi: ${e.message}")
        }
    }

    @Synchronized
    private fun drop(cb: ConnectivityManager.NetworkCallback) {
        if (callback !== cb) return
        try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        callback = null
    }

    /** Logs which network carries the phone's internet, so a drive's log shows whether 4G was used. */
    private fun watchInternet(c: ConnectivityManager) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "a VPN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    else -> "another network"
                }
                val works = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val text = "Internet goes over $kind (${if (works) "working" else "no internet"})"
                if (text != lastDefault) { lastDefault = text; EventLog.add(text) }
            }
            override fun onLost(network: Network) { lastDefault = ""; EventLog.add("No internet connection") }
        }
        try { c.registerDefaultNetworkCallback(cb); defaultCallback = cb } catch (_: Exception) {}
    }

    /** The phone's address on the Wi-Fi in use, or null if it isn't up. */
    fun localIp(): String? = current?.let { ipOf(it) }

    private fun ipOf(n: Network): String? = try {
        cm?.getLinkProperties(n)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
    } catch (_: Exception) { null }

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
