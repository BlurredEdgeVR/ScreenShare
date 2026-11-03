package com.screenshare.send

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        /** Home-screen shortcut: connect to the saved car screen, start mirroring, open Waze. */
        const val ACTION_CAR_MODE = "com.screenshare.send.CAR_MODE"
        /** How long car mode keeps looking for the car's display. */
        private const val CAR_WAIT_MS = 3 * 60_000L
        /** How long to wait for the app to join the car's Wi-Fi outside car mode. */
        private const val WIFI_WAIT_MS = 40_000L
    }

    private var carMode = false
    private var carModeStarted = false
    private var carDeadline = 0L
    private var wifiWaitUntil = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var carButton: Button
    private lateinit var carWifiButton: Button

    private var endpoints: RendererEndpoints? = null
    private lateinit var statusView: TextView
    private lateinit var deviceButton: Button
    private lateinit var pickButton: Button
    private lateinit var controlsRow: LinearLayout
    private lateinit var spinner: ProgressBar
    private lateinit var mirrorButton: Button
    private lateinit var qualityButton: Button
    private lateinit var soundButton: Button
    private lateinit var urlButton: Button
    private lateinit var rootLayout: LinearLayout

    @SuppressLint("SetTextI18n")
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        EventLog.init(this)
        WifiRoute.start(this)
        // crash surface: any uncaught exception is shown on screen so field crashes are diagnosable
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runOnUiThread {
                val stack = android.util.Log.getStackTraceString(e)
                val tv = android.widget.TextView(this).apply {
                    text = "CRASH in ${t.name}\n\n$stack"
                    textSize = 11f
                    setTextColor(0xFFFF6B6B.toInt())
                    setPadding(32, 64, 32, 32)
                    movementMethod = android.text.method.ScrollingMovementMethod()
                }
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("crash", "$e\n$stack"))
                setContentView(android.widget.ScrollView(this).apply { addView(tv) })
            }
            try { Thread.sleep(3000) } catch (_: Exception) {}
            prevHandler?.uncaughtException(t, e)
        }
        try {
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            multicastLock = wm.createMulticastLock("ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { }
        val pad = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundResource(0)  // replaced below with gradient drawable via background layer
            setBackgroundColor(0xFF241432.toInt())
        }

        val title = TextView(this).apply {
            text = "Screen	Share"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        }
        // two-tone title: Screen white + Share amber
        title.text = ""
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        titleRow.addView(TextView(this).apply { text = "Screen"; textSize = 30f; setTextColor(0xFFFFFFFF.toInt()) })
        titleRow.addView(TextView(this).apply { text = "Share"; textSize = 30f; setTextColor(0xFFFFC53D.toInt()) })

        val deviceSection = TextView(this).apply {
            text = "── Display ──"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9E7FA8.toInt())
            setPadding(0, (pad*1.2f).toInt(), 0, (pad*0.4f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        statusView = TextView(this).apply {
            text = "Searching for displays…"
            textSize = 16f
            setTextColor(0xFFFFC9A8.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        spinner = ProgressBar(this).apply {
            visibility = View.VISIBLE
        }

        deviceButton = Button(this).apply {
            text = "Choose display"
            visibility = View.GONE
        }
        carButton = Button(this).apply {
            text = "Car mode: mirror and open Waze"
            visibility = View.GONE
            setOnClickListener { carMode = true; carModeStarted = true; startMirrorFlow() }
        }
        pickButton = Button(this).apply {
            text = "Choose a video to cast"
            visibility = View.GONE
        }
        mirrorButton = Button(this).apply {
            text = "Screen mirror (whole phone screen)"
            visibility = View.GONE
            setOnClickListener { startMirrorFlow() }
        }
        qualityButton = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { chooseQuality() }
        }
        updateQualityButton()
        soundButton = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { toggleSound() }
        }
        updateSoundButton()
        urlButton = Button(this).apply {
            text = "Cast an online video link"
            visibility = View.GONE
            setOnClickListener {
                val input = android.widget.EditText(this@MainActivity).apply {
                    hint = "Paste a direct video link (http…mp4 etc)"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Cast online video")
                    .setView(input)
                    .setPositiveButton("Cast") { _, _ ->
                        val u = input.text.toString().trim()
                        if (u.startsWith("http")) {
                            val e = endpoints ?: return@setPositiveButton
                            statusView.text = "Casting…"
                            DlnaController.takeErrorLog()
                            DlnaController.setUriAndPlay(e, u, "Online video") { ok ->
                                runOnUiThread {
                                    if (ok) { statusView.text = "Playing link on display"; controlsRow.visibility = View.VISIBLE }
                                    else showReport("Cast failed: link not playable or refused", DlnaController.takeErrorLog().ifBlank { DlnaController.lastError } + "\n\n" + DlnaController.info)
                                }
                            }
                        } else toast("Enter a link starting with http")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            val b1 = Button(this@MainActivity).apply { text = "Pause" }
            val b2 = Button(this@MainActivity).apply { text = "Stop" }
            val b3 = Button(this@MainActivity).apply { text = "-30s" }
            val b4 = Button(this@MainActivity).apply { text = "+30s" }
            b3.setOnClickListener {
                endpoints?.let { e -> DlnaController.getPosition(e) { pos ->
                    val now = pos?.first ?: 0
                    DlnaController.seekTo(e, (now - 30).coerceAtLeast(0)) { ok -> toast(if (ok) "Skipped back" else "Seek failed") }
                } }
            }
            b4.setOnClickListener {
                endpoints?.let { e -> DlnaController.getPosition(e) { pos ->
                    val now = pos?.first ?: 0
                    DlnaController.seekTo(e, now + 30) { ok -> toast(if (ok) "Skipped forward" else "Seek failed") }
                } }
            }
            b1.setOnClickListener { endpoints?.let { e -> DlnaController.pause(e) { ok -> toast(if (ok) "Paused" else "Pause failed") } } }
            b2.setOnClickListener {
                endpoints?.let { e -> DlnaController.stop(e) { ok -> toast(if (ok) "Stopped" else "Stop failed") } }
                if (MirrorService.isRunning) {
                    startService(Intent(this@MainActivity, MirrorService::class.java).apply { action = MirrorService.ACTION_STOP })
                    statusView.text = "Mirroring stopped\nChoose what to cast"
                }
            }
            addView(b1); addView(b2); addView(b3); addView(b4)
        }

        root.addView(titleRow)
        val slogan = TextView(this).apply {
            text = "share your screens"
            textSize = 13f
            setTextColor(0xB3FFE9D6.toInt())   // warm white 70%
            setPadding(0, (pad*0.2f).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }
        root.addView(slogan)
        root.addView(spinner, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(deviceSection)
        root.addView(statusView)
        root.addView(deviceButton)
        val contentSection = TextView(this).apply {
            text = "── What to cast ──"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9E7FA8.toInt())
            setPadding(0, (pad*1.2f).toInt(), 0, (pad*0.4f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(contentSection)
        root.addView(carButton)
        root.addView(pickButton)
        root.addView(mirrorButton)
        root.addView(qualityButton)
        root.addView(soundButton)
        root.addView(urlButton)
        val ctlSection = TextView(this).apply {
            text = "── Controls ──"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9E7FA8.toInt())
            setPadding(0, (pad*1.2f).toInt(), 0, (pad*0.4f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(ctlSection)
        root.addView(controlsRow)
        root.addView(Button(this).apply {
            text = "Add Car mode to home screen"
            setOnClickListener { pinCarModeShortcut() }
        })
        carWifiButton = Button(this).apply { setOnClickListener { chooseCarWifi() } }
        updateCarWifiButton()
        root.addView(carWifiButton)
        root.addView(Button(this).apply {
            text = "Connection log"
            setOnClickListener { showLog() }
        })
        rootLayout = root
        // Scrolls, so the controls stay reachable when the phone is sideways in a car mount.
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFF241432.toInt())
            addView(root)
        })

        deviceButton.setOnClickListener { showDevicePicker() }
        pickButton.setOnClickListener { pickVideo() }
        carMode = intent?.action == ACTION_CAR_MODE
        EventLog.add(if (carMode) "Car mode started" else "App opened")
        if (carMode) carDeadline = System.currentTimeMillis() + CAR_WAIT_MS
        if (carMode && MirrorService.isRunning) openWaze()
        if (savedDevice() != null) connectSaved() else startSearch()
    }

    private fun alive() = !isFinishing && !isDestroyed

    /** The display used last time, if any. */
    private fun savedDevice(): CastDevice? {
        val prefs = getPreferences(MODE_PRIVATE)
        val ip = prefs.getString("last_tv_ip", null) ?: return null
        // The car shows two displays (centre and passenger) on one IP, so reuse the exact
        // description URL that was picked, as long as it still belongs to the saved IP.
        val desc = prefs.getString("last_tv_desc", null)?.takeIf {
            try { java.net.URL(it).host == ip } catch (_: Exception) { false }
        }
        return CastDevice(prefs.getString("last_tv_name", null) ?: "", desc ?: "http://$ip:49152/description.xml", ip)
    }

    /**
     * Connects to the display used last time. If it doesn't answer at its saved address, or a
     * different display answers there, it searches for it by name, because the car's displays
     * may come back on another address. Car mode keeps trying for [CAR_WAIT_MS], since the phone
     * can take a while to join the car's Wi-Fi and the car's displays a while to start.
     */
    @SuppressLint("SetTextI18n")
    private fun connectSaved() {
        if (!alive()) return
        val saved = savedDevice() ?: return startSearch()
        if (carMode) WifiRoute.ensure(userAction = false)
        if (waitForCarWifi { connectSaved() }) return
        statusView.text = "Connecting: ${saved.friendlyName.ifBlank { saved.ip }}…"
        DlnaController.describe(saved.descriptionUrl) { ep ->
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                if (ep != null && (saved.friendlyName.isBlank() || ep.friendlyName == saved.friendlyName)) {
                    connected(saved, ep)
                    return@runOnUiThread
                }
                EventLog.add(if (ep == null) "Saved display didn't answer at ${saved.descriptionUrl}"
                    else "Another display (${ep.friendlyName}) answered at ${saved.descriptionUrl}")
                if (!carMode) { startSearch(); return@runOnUiThread }
                statusView.text = "Car mode: looking for the car's display…"
                findDisplays { found ->
                    runOnUiThread {
                        if (!alive()) return@runOnUiThread
                        val match = pickSaved(found, saved)
                        when {
                            match != null -> connect(match)
                            // Nothing saved to tell the displays apart, so ask.
                            found.isNotEmpty() && saved.friendlyName.isBlank() -> chooseFrom(found)
                            System.currentTimeMillis() < carDeadline -> {
                                statusView.text = "Car mode: waiting for the car's display…"
                                handler.postDelayed({ connectSaved() }, 3000)
                            }
                            found.isNotEmpty() -> chooseFrom(found)
                            else -> noDisplayFound()
                        }
                    }
                }
            }
        }
    }

    /**
     * When the app joins the car's Wi-Fi itself, waits until it has, then runs [then]. Returns
     * false if there's nothing to wait for.
     */
    @SuppressLint("SetTextI18n")
    private fun waitForCarWifi(then: () -> Unit): Boolean {
        if (!WifiRoute.joiningCarWifi) { wifiWaitUntil = 0; return false }
        val now = System.currentTimeMillis()
        if (wifiWaitUntil == 0L) wifiWaitUntil = now + WIFI_WAIT_MS
        if (now > maxOf(wifiWaitUntil, carDeadline)) { wifiWaitUntil = 0; return false }
        statusView.text = "Joining the car's Wi-Fi (${WifiRoute.carWifiName})…"
        spinner.visibility = View.VISIBLE
        handler.postDelayed({ if (alive()) then() }, 1000)
        return true
    }

    /** Finds displays with SSDP, or by probing the Wi-Fi's /24 if nothing answers, and reads each one's name. */
    private fun findDisplays(cb: (List<CastDevice>) -> Unit) {
        DlnaDiscoverer.search(3000) { ssdp ->
            if (ssdp.isNotEmpty()) describeAll(ssdp, cb)
            // SSDP can't cross subnets or may be filtered: probe the DLNA port instead.
            else SubnetScanner.scan { ips -> describeAll(ips.map { CastDevice("", "http://$it:49152/description.xml", it) }, cb) }
        }
    }

    /** Keeps only the candidates that are renderers, with their names filled in. */
    private fun describeAll(candidates: List<CastDevice>, cb: (List<CastDevice>) -> Unit) {
        if (candidates.isEmpty()) { EventLog.add("Search found no displays"); cb(emptyList()); return }
        val found = java.util.Collections.synchronizedList(mutableListOf<CastDevice>())
        val left = java.util.concurrent.atomic.AtomicInteger(candidates.size)
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

    /** The saved display among [found]: by name, or, with no name saved, by address or as the only one. */
    private fun pickSaved(found: List<CastDevice>, saved: CastDevice): CastDevice? =
        if (saved.friendlyName.isNotBlank()) found.firstOrNull { it.friendlyName == saved.friendlyName }
        else found.firstOrNull { it.descriptionUrl == saved.descriptionUrl } ?: found.singleOrNull()

    @SuppressLint("SetTextI18n")
    private fun noDisplayFound() {
        spinner.visibility = View.GONE
        statusView.text = "No display found\n\nCheck the phone is on the car's Wi-Fi and search again, or enter the display's IP"
        addRefreshButton()
        addManualEntryButton()
    }

    @SuppressLint("SetTextI18n")
    private fun startSearch() {
        if (!alive()) return
        if (waitForCarWifi { startSearch() }) return
        statusView.text = "Searching for displays…"
        spinner.visibility = View.VISIBLE
        deviceButton.visibility = View.GONE
        pickButton.visibility = View.GONE
        controlsRow.visibility = View.GONE
        findDisplays { devices ->
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                spinner.visibility = View.GONE
                val match = savedDevice()?.let { pickSaved(devices, it) }
                when {
                    match != null -> connect(match)
                    devices.isEmpty() -> noDisplayFound()
                    else -> chooseFrom(devices)
                }
            }
        }
    }

    private var refreshButton: Button? = null
    private var manualButton: Button? = null

    private fun addRefreshButton() {
        if (refreshButton == null) {
            refreshButton = Button(this).apply {
                text = "Search again"
                setOnClickListener {
                    removeView(this); removeManual()
                    WifiRoute.ensure(userAction = true)
                    startSearch()
                }
            }
            rootLayout.addView(refreshButton)
        }
    }

    private fun addManualEntryButton() {
        if (manualButton == null) {
            manualButton = Button(this).apply {
                text = "Enter display IP manually"
                setOnClickListener {
                    val input = android.widget.EditText(this@MainActivity).apply {
                        hint = "Display IP, e.g. 192.168.0.109"
                        inputType = android.text.InputType.TYPE_CLASS_PHONE
                    }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Manual connect")
                        .setView(input)
                        .setPositiveButton("Connect") { _, _ ->
                            val ip = input.text.toString().trim()
                            if (ip.isNotEmpty()) {
                                connect(CastDevice("", "http://$ip:49152/description.xml", ip))
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            rootLayout.addView(manualButton)
        }
    }

    private fun removeManual() {
        manualButton?.let { rootLayout.removeView(it) }
        manualButton = null
    }

    private fun removeView(v: View) {
        rootLayout.removeView(v)
        refreshButton = null
    }

    private var pendingDevices: List<CastDevice> = emptyList()

    private fun chooseFrom(devices: List<CastDevice>) {
        pendingDevices = devices
        if (devices.size == 1) {
            connect(devices[0])
        } else {
            statusView.text = "Found ${devices.size} device(s), tap to choose"
            deviceButton.visibility = View.VISIBLE
            deviceButton.text = "Choose display (${devices.size})"
        }
    }

    private fun showDevicePicker() {
        val names = pendingDevices.map { it.ip + (if (it.friendlyName.isNotBlank()) " · ${it.friendlyName}" else "") }
        android.app.AlertDialog.Builder(this)
            .setTitle("Choose display")
            .setItems(names.toTypedArray()) { _, which -> connect(pendingDevices[which]) }
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun connect(device: CastDevice) {
        statusView.text = "Connecting: ${device.friendlyName.ifBlank { device.ip }}…"
        DlnaController.describe(device.descriptionUrl) { ep ->
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                if (ep != null) { connected(device, ep); return@runOnUiThread }
                EventLog.add("Couldn't connect to ${device.descriptionUrl}")
                if (carMode && System.currentTimeMillis() < carDeadline) {
                    statusView.text = "Car mode: waiting for the car's display…"
                    handler.postDelayed({ connectSaved() }, 3000)
                    return@runOnUiThread
                }
                statusView.text = "Connection failed, tap to search again"
                addRefreshButton()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun connected(device: CastDevice, ep: RendererEndpoints) {
        EventLog.add("Connected to ${ep.friendlyName} at ${device.descriptionUrl}")
        endpoints = ep
        spinner.visibility = View.GONE
        getPreferences(MODE_PRIVATE).edit()
            .putString("last_tv_ip", device.ip)
            .putString("last_tv_desc", device.descriptionUrl)
            .putString("last_tv_name", ep.friendlyName)
            .apply()
        deviceButton.text = "Connected: ${ep.friendlyName}"
        deviceButton.visibility = View.VISIBLE
        statusView.text = "Choose a video on your phone to cast"
        pickButton.visibility = View.VISIBLE
        mirrorButton.visibility = View.VISIBLE
        qualityButton.visibility = View.VISIBLE
        soundButton.visibility = View.VISIBLE
        urlButton.visibility = View.VISIBLE
        carButton.visibility = View.VISIBLE
        refreshButton?.let { removeView(it) }
        removeManual()
        if (MirrorService.isRunning) {
            statusView.text = "Mirroring to ${ep.friendlyName}"
            controlsRow.visibility = View.VISIBLE
        } else if (carMode && !carModeStarted) {
            carModeStarted = true
            statusView.text = "Car mode: starting…"
            startMirrorFlow()
        }
    }

    private val projectionLauncher = 4242
    private var pendingMirror = false

    private fun startMirrorFlow() {
        val e = endpoints ?: return
        val pmgr = getSystemService(MediaProjectionManager::class.java)
        pendingMirror = true
        @Suppress("DEPRECATION")
        startActivityForResult(pmgr.createScreenCaptureIntent(), projectionLauncher)
    }

    private fun launchMirror(resultCode: Int, data: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // notification permission is optional for FGS but Samsung-family builds log violations;
            // request quietly, continue regardless of the answer
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 78)
        }
        val e = endpoints ?: return
        // 1) start mirror pipeline (local HTTP-TS)
        val svc = Intent(this, MirrorService::class.java).apply {
            putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorService.EXTRA_DATA, data)
            putExtra(MirrorService.EXTRA_QUALITY, mirrorQuality().name)
            putExtra(MirrorService.EXTRA_SOUND, soundOn())
            putExtra(MirrorService.EXTRA_CONTROL_URL, e.avTransportControlUrl)
            putExtra(MirrorService.EXTRA_RENDERER_NAME, e.friendlyName)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } catch (t: Throwable) {
            CrashLog.save(this, t)
            showCrashIfAny()
            return
        }
        // 2) wait a moment for server, then push URL to TV
        android.os.Handler(mainLooper).postDelayed({
            val ip = MirrorServer.localIp() ?: run { toast("Could not get phone IP"); return@postDelayed }
            val token = MirrorServer.streamToken
            if (token.isEmpty()) { toast("Mirror service not ready"); return@postDelayed }
            statusView.text = "Mirroring: $ip:8080/live.ts"
            DlnaController.takeErrorLog()
            DlnaController.playLiveStream(e, "http://$ip:8080/live.ts?k=$token", "Screen mirror") { ok ->
                runOnUiThread {
                    if (ok) {
                        EventLog.add("Mirroring to ${e.friendlyName} (${mirrorQuality().title}, stream at $ip)")
                        statusView.text = "Mirroring phone screen to display"
                        controlsRow.visibility = View.VISIBLE
                        if (carMode) openWaze()
                    } else {
                        val why = DlnaController.takeErrorLog().ifBlank { DlnaController.lastError }
                        EventLog.add("Display refused the mirror stream: ${why.take(300)}")
                        showReport("Display refused the mirror stream", why + "\n\n" + DlnaController.info)
                    }
                }
            }
        }, 1200)
    }

    private fun mirrorQuality(): MirrorQuality =
        MirrorQuality.fromName(getPreferences(MODE_PRIVATE).getString("mirror_quality", null))

    private fun updateQualityButton() {
        qualityButton.text = "Mirror quality: ${mirrorQuality().title}"
    }

    private fun chooseQuality() {
        val all = MirrorQuality.entries
        val items = all.map { "${it.title}\n${it.detail}" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Mirror quality")
            .setSingleChoiceItems(items, all.indexOf(mirrorQuality())) { dialog, which ->
                getPreferences(MODE_PRIVATE).edit().putString("mirror_quality", all[which].name).apply()
                updateQualityButton()
                dialog.dismiss()
                if (MirrorService.isRunning) toast("Applies next time you start mirroring")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun soundOn(): Boolean = getPreferences(MODE_PRIVATE).getBoolean("send_sound", false)

    private fun updateSoundButton() {
        soundButton.text = "Phone sound to car: ${if (soundOn()) "On" else "Off"}"
    }

    private fun setSound(on: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("send_sound", on).apply()
        updateSoundButton()
        if (on) toast("Music and video sound will play through the car with the picture. Waze's voice still uses Bluetooth.")
        if (MirrorService.isRunning) toast("Applies next time you start mirroring")
    }

    /** Sound capture needs Android 10+ and the audio permission (it captures playback, not the mic). */
    private fun toggleSound() {
        if (soundOn()) { setSound(false); return }
        if (android.os.Build.VERSION.SDK_INT < 29) { toast("Needs Android 10 or later"); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            setSound(true)
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 79)
        }
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*", "audio/*"))
        }
        startActivityForResult(intent, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            castUri(uri)
        }
        if (requestCode == projectionLauncher && resultCode == RESULT_OK) {
            data?.let { try { launchMirror(resultCode, it) } catch (t: Throwable) { CrashLog.save(this, t); showCrashIfAny() } }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 79) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) setSound(true)
            else toast("Sound to car needs the audio permission")
            return
        }
        if (requestCode == 77 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMirrorFlow()
        } else if (requestCode == 77) {
            toast("No microphone permission, mirroring without sound")
            val pmgr = getSystemService(MediaProjectionManager::class.java)
            pendingMirror = true
            @Suppress("DEPRECATION")
            startActivityForResult(pmgr.createScreenCaptureIntent(), projectionLauncher)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun castUri(uri: Uri) {
        val e = endpoints ?: return
        statusView.text = "Casting…"
        // Serve the content through a local relay so the TV can read the SAF stream.
        LocalRelay.start(this, uri) { localUrl ->
            val url = if (LocalRelay.contentTypeFor(uri) != "video/mp4") "$localUrl?ct=${LocalRelay.contentTypeFor(uri)}" else localUrl
            val name = queryName(uri) ?: "media"
            DlnaController.takeErrorLog()
            DlnaController.setUriAndPlay(e, url, name) { ok ->
                runOnUiThread {
                    if (ok) {
                        statusView.text = "Playing on display: $name"
                        controlsRow.visibility = View.VISIBLE
                    } else {
                        statusView.text = "Cast failed: display refused the request"
                        showReport("Cast failed: display refused the request", DlnaController.takeErrorLog().ifBlank { DlnaController.lastError } + "\n\n" + DlnaController.info)
                    }
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) { null }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action != ACTION_CAR_MODE) return
        EventLog.add("Car mode started (app was open)")
        val wasCarMode = carMode
        carMode = true
        carDeadline = System.currentTimeMillis() + CAR_WAIT_MS
        when {
            MirrorService.isRunning -> openWaze()
            endpoints != null && !carModeStarted -> { carModeStarted = true; startMirrorFlow() }
            // Not connected and not already retrying in car mode: start looking.
            endpoints == null && !wasCarMode -> {
                refreshButton?.let { removeView(it) }
                removeManual()
                WifiRoute.ensure(userAction = true)
                connectSaved()
            }
        }
    }

    private fun updateCarWifiButton() {
        carWifiButton.text = "Keep 4G internet in the car: ${if (WifiRoute.joinsCarWifi) "On" else "Off"}"
    }

    /**
     * Lets the app join the car's Wi-Fi itself (see [WifiRoute]), so Android never uses it for
     * internet and 4G stays on for Waze and Tidal.
     */
    @SuppressLint("SetTextI18n")
    private fun chooseCarWifi() {
        if (android.os.Build.VERSION.SDK_INT < 29) { toast("Needs Android 10 or later"); return }
        if (MirrorService.isRunning) { toast("Stop mirroring first"); return }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val on = WifiRoute.joinsCarWifi
        val info = TextView(this).apply {
            text = "The app joins the car's Wi-Fi itself, as a link to the car only. Android then " +
                "doesn't use that Wi-Fi for internet, so Waze and Tidal keep using mobile data.\n\n" +
                "After turning this on, forget the car's Wi-Fi in the phone's Wi-Fi settings, so the " +
                "phone doesn't also join it the usual way. The first time, Android asks you to allow " +
                "the connection: pick the car's Wi-Fi and tap Connect."
            setPadding(0, 0, 0, pad / 2)
        }
        val ssidInput = android.widget.EditText(this).apply {
            hint = "Car Wi-Fi name"
            setSingleLine(true)
            setText(WifiRoute.carWifiName ?: "Zeekr-2882_Casting")
        }
        val passInput = android.widget.EditText(this).apply {
            hint = "Car Wi-Fi password"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(WifiRoute.carWifiPassword)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(info); addView(ssidInput); addView(passInput)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Keep 4G internet in the car")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (on) "Save" else "Turn on") { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                val pass = passInput.text.toString()
                when {
                    ssid.isEmpty() -> toast("Enter the car's Wi-Fi name")
                    pass.isNotEmpty() && pass.length !in 8..63 -> toast("Wi-Fi passwords are 8 to 63 characters")
                    else -> {
                        WifiRoute.setCarWifi(this, ssid, pass)
                        updateCarWifiButton()
                        if (!on) offerWifiSettings(ssid)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
        if (on) dialog.setNeutralButton("Turn off") { _, _ ->
            WifiRoute.setCarWifi(this, null, "")
            updateCarWifiButton()
            toast("Join the car's Wi-Fi in the phone's Wi-Fi settings again")
        }
        dialog.show()
    }

    private fun offerWifiSettings(ssid: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Now forget the car's Wi-Fi")
            .setMessage("In Wi-Fi settings, tap \"$ssid\" (or its gear icon) and choose Forget. " +
                "The app joins it from now on, whenever it's open.")
            .setPositiveButton("Open Wi-Fi settings") { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) {}
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showLog() {
        val all = EventLog.read()
        android.app.AlertDialog.Builder(this)
            .setTitle("Connection log")
            .setMessage(all.takeLast(6000).ifBlank { "Nothing logged yet." })
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ScreenShare log", all))
                toast("Log copied")
            }
            .show()
    }

    private fun openWaze() {
        val i = packageManager.getLaunchIntentForPackage("com.waze") ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) {}
    }

    private fun pinCarModeShortcut() {
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            toast("Long-press the ScreenShare icon and drag Car mode to your home screen")
            return
        }
        val info = android.content.pm.ShortcutInfo.Builder(this, "car_mode_home")
            .setShortLabel("Car mode")
            .setLongLabel("ScreenShare car mode")
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(ACTION_CAR_MODE).setClass(this, MainActivity::class.java))
            .build()
        sm.requestPinShortcut(info, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { multicastLock?.release() } catch (_: Exception) {}
    }

    /** Seek/pause callbacks arrive on DlnaController's worker thread. Toast from there crashes. */
    private fun toast(s: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showCrashIfAny()
    }

    private fun showCrashIfAny() {
        val log = CrashLog.take(this) ?: return
        showReport("ScreenShare error report", log)
    }

    private fun showReport(title: String, body: String) {
        val log = "$title\nAndroid ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}\n\n$body"
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(log.take(3000))
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ScreenShare error", log))
                toast("Error report copied")
            }
            .show()
    }
}
