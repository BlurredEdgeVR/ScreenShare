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
    private var endpoints: RendererEndpoints? = null
    private lateinit var statusTitle: TextView
    private lateinit var statusView: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var changeButton: Button
    private lateinit var searchActions: LinearLayout
    private lateinit var carButton: Button
    private lateinit var mirrorButton: Button
    private lateinit var wazeButton: Button
    private lateinit var stopMirrorButton: Button
    private lateinit var controlsRow: LinearLayout
    private lateinit var qualityRow: Row
    private lateinit var soundRow: Row
    private lateinit var carWifiRow: Row
    private lateinit var castFileRow: Row
    private lateinit var castLinkRow: Row
    /** No display is connected and the search is over: offer Search again and Enter IP. */
    private var notFound = false
    /** The device picker is open. */
    private var awaitingPick = false
    /** A video file or link is playing on the display (not mirroring). */
    private var castingVideo = false
    private var castStatus = ""

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
        buildScreen()
        carMode = intent?.action == ACTION_CAR_MODE
        EventLog.add(if (carMode) "Car mode started" else "App opened")
        if (carMode) carDeadline = System.currentTimeMillis() + CAR_WAIT_MS
        if (carMode && MirrorService.isRunning) openWaze()
        if (savedDevice() != null) connectSaved() else startSearch()
    }

    /**
     * One column: the display status, the main actions for the current state, then Settings and
     * More as grouped lists. [refreshUi] decides what shows.
     */
    private fun buildScreen() {
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val medium = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val column = Column(this, dp(560)).apply { setPadding(dp(16), dp(16), dp(16), dp(32)) }

        column.addView(LinearLayout(this).apply {
            setPadding(dp(4), dp(8), 0, dp(16))
            addView(TextView(this@MainActivity).apply { text = "Screen"; textSize = 26f; setTextColor(Palette.TEXT) })
            addView(TextView(this@MainActivity).apply { text = "Share"; textSize = 26f; setTextColor(Palette.ACCENT) })
        })

        statusTitle = TextView(this).apply { textSize = 18f; typeface = medium; setTextColor(Palette.TEXT) }
        spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.MUTED)
            setPadding(0, dp(4), dp(8), dp(8))
        }
        changeButton = actionButton("Change display", ButtonStyle.TEXT) { chooseDisplay() }
        searchActions = LinearLayout(this).apply {
            addView(actionButton("Search again", ButtonStyle.TEXT) { searchAgain() })
            addView(actionButton("Enter IP", ButtonStyle.TEXT) { enterIpManually() })
        }
        column.addView(panel().apply {
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(statusTitle, LinearLayout.LayoutParams(0, wrap, 1f))
                addView(spinner, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            })
            addView(statusView)
            // Text buttons carry their own padding; pull them left so their labels line up.
            addView(changeButton, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = -dp(12) })
            addView(searchActions, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = -dp(12) })
        }, LinearLayout.LayoutParams(match, wrap))

        fun gap(top: Int) = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(top) }
        carButton = actionButton("Mirror and open Waze", ButtonStyle.PRIMARY) {
            carMode = true; carModeStarted = true; startMirrorFlow()
        }
        mirrorButton = actionButton("Mirror only", ButtonStyle.SECONDARY) { startMirrorFlow() }
        wazeButton = actionButton("Open Waze", ButtonStyle.PRIMARY) { openWaze() }
        stopMirrorButton = actionButton("Stop mirroring", ButtonStyle.SECONDARY) { stopMirroring() }
        column.addView(carButton, gap(16))
        column.addView(mirrorButton, gap(8))
        column.addView(wazeButton, gap(16))
        column.addView(stopMirrorButton, gap(8))

        controlsRow = LinearLayout(this).apply {
            val controls = listOf<Pair<String, () -> Unit>>(
                "Pause" to { endpoints?.let { e -> DlnaController.pause(e) { ok -> toast(if (ok) "Paused" else "Pause failed") } } },
                "−30 s" to { seekBy(-30) },
                "+30 s" to { seekBy(30) },
                "Stop" to { stopVideo() },
            )
            controls.forEachIndexed { i, (label, action) ->
                addView(actionButton(label, ButtonStyle.SECONDARY, action),
                    LinearLayout.LayoutParams(0, wrap, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
        }
        column.addView(controlsRow, gap(16))

        column.addView(sectionLabel("Settings"))
        qualityRow = listRow("Mirror quality") { chooseQuality() }
        soundRow = listRow("Phone sound to car", withSwitch = true) { toggleSound() }
        carWifiRow = listRow("Keep 4G internet in the car") { chooseCarWifi() }
        column.addView(listGroup(qualityRow.view, soundRow.view, carWifiRow.view))

        column.addView(sectionLabel("More"))
        castFileRow = listRow("Cast a video from the phone") { pickVideo() }
        castLinkRow = listRow("Cast a video link") { castLink() }
        column.addView(listGroup(castFileRow.view, castLinkRow.view,
            listRow("Add Car mode to home screen") { pinCarModeShortcut() }.view,
            listRow("Connection log") { showLog() }.view))

        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.BG)
            addView(android.widget.FrameLayout(this@MainActivity).apply {
                addView(column, android.widget.FrameLayout.LayoutParams(match, wrap, Gravity.CENTER_HORIZONTAL))
            })
        })
        refreshUi()
    }

    /** Shows what fits the current state: searching, connected, mirroring or playing a video. */
    private fun refreshUi() {
        val e = endpoints
        val mirroring = MirrorService.isRunning
        statusTitle.text = e?.friendlyName?.ifBlank { "Display" } ?: "Not connected"
        if (e != null) statusView.text = when {
            mirroring -> "Mirroring"
            castingVideo -> castStatus
            else -> "Connected"
        }
        spinner.shown = e == null && !notFound && !awaitingPick
        searchActions.shown = e == null && notFound
        changeButton.shown = e != null && !mirroring
        carButton.shown = e != null && !mirroring
        mirrorButton.shown = e != null && !mirroring
        wazeButton.shown = mirroring
        stopMirrorButton.shown = mirroring
        controlsRow.shown = e != null && castingVideo && !mirroring
        castFileRow.view.shown = e != null
        castLinkRow.view.shown = e != null
        qualityRow.value.text = mirrorQuality().title
        soundRow.switch?.isChecked = soundOn()
        carWifiRow.value.text = if (WifiRoute.joinsCarWifi) "On" else "Off"
    }

    private fun stopMirroring() {
        endpoints?.let { e -> DlnaController.stop(e) { } }
        startService(Intent(this, MirrorService::class.java).apply { action = MirrorService.ACTION_STOP })
        handler.postDelayed({ if (alive()) refreshUi() }, 500)
    }

    private fun stopVideo() {
        endpoints?.let { e -> DlnaController.stop(e) { ok -> toast(if (ok) "Stopped" else "Stop failed") } }
        castingVideo = false
        refreshUi()
    }

    private fun seekBy(seconds: Int) {
        val e = endpoints ?: return
        DlnaController.getPosition(e) { pos ->
            val now = pos?.first ?: 0
            DlnaController.seekTo(e, (now + seconds).coerceAtLeast(0)) { ok ->
                toast(if (!ok) "Seek failed" else if (seconds < 0) "Skipped back" else "Skipped forward")
            }
        }
    }

    private fun searchAgain() {
        notFound = false
        WifiRoute.ensure(userAction = true)
        startSearch()
    }

    /** Searches afresh and lets the user pick, even if only one display answers. */
    @SuppressLint("SetTextI18n")
    private fun chooseDisplay() {
        statusView.text = "Searching for displays…"
        spinner.shown = true
        findDisplays { devices ->
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                refreshUi()
                if (devices.isEmpty()) toast("No display found") else showDevicePicker(devices)
            }
        }
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
        statusView.text = "Connecting to ${saved.friendlyName.ifBlank { saved.ip }}…"
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
        notFound = false
        refreshUi()
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

    /**
     * The saved display among [found]: by name; with no name saved, by its address ignoring the
     * port (the car's displays get a new port each time it starts), or as the only one found.
     */
    private fun pickSaved(found: List<CastDevice>, saved: CastDevice): CastDevice? {
        if (saved.friendlyName.isNotBlank()) return found.firstOrNull { it.friendlyName == saved.friendlyName }
        fun place(url: String) = try { java.net.URL(url).let { it.host + it.path } } catch (_: Exception) { url }
        return found.firstOrNull { place(it.descriptionUrl) == place(saved.descriptionUrl) } ?: found.singleOrNull()
    }

    @SuppressLint("SetTextI18n")
    private fun noDisplayFound(message: String = "No display found. Check the phone is on the car's Wi-Fi.") {
        notFound = true
        awaitingPick = false
        statusView.text = message
        refreshUi()
    }

    @SuppressLint("SetTextI18n")
    private fun startSearch() {
        if (!alive()) return
        notFound = false
        if (waitForCarWifi { startSearch() }) return
        statusView.text = "Searching for displays…"
        refreshUi()
        findDisplays { devices ->
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                val match = savedDevice()?.let { pickSaved(devices, it) }
                when {
                    match != null -> connect(match)
                    devices.isEmpty() -> noDisplayFound()
                    else -> chooseFrom(devices)
                }
            }
        }
    }

    private fun enterIpManually() {
        val input = android.widget.EditText(this).apply {
            hint = "Display IP, for example 192.168.0.109"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Enter the display's IP")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) connect(CastDevice("", "http://$ip:49152/description.xml", ip))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseFrom(devices: List<CastDevice>) {
        if (devices.size == 1) connect(devices[0]) else showDevicePicker(devices)
    }

    @SuppressLint("SetTextI18n")
    private fun showDevicePicker(devices: List<CastDevice>) {
        val names = devices.map { it.friendlyName.ifBlank { it.ip } }
        awaitingPick = true
        if (endpoints == null) statusView.text = "Pick a display to connect to."
        refreshUi()
        android.app.AlertDialog.Builder(this)
            .setTitle("Choose display")
            .setItems(names.toTypedArray()) { _, which -> awaitingPick = false; connect(devices[which]) }
            .setNeutralButton("Enter IP") { _, _ -> awaitingPick = false; enterIpManually() }
            .setOnCancelListener {
                awaitingPick = false
                if (endpoints == null) noDisplayFound("Pick a display to connect to.") else refreshUi()
            }
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun connect(device: CastDevice) {
        if (endpoints == null) statusView.text = "Connecting to ${device.friendlyName.ifBlank { device.ip }}…"
        notFound = false
        refreshUi()
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
                val name = device.friendlyName.ifBlank { device.ip }
                if (endpoints == null) noDisplayFound("Couldn't connect to $name.") else toast("Couldn't connect to $name")
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun connected(device: CastDevice, ep: RendererEndpoints) {
        EventLog.add("Connected to ${ep.friendlyName} at ${device.descriptionUrl}")
        if (endpoints?.avTransportControlUrl != ep.avTransportControlUrl) castingVideo = false
        endpoints = ep
        notFound = false
        awaitingPick = false
        getPreferences(MODE_PRIVATE).edit()
            .putString("last_tv_ip", device.ip)
            .putString("last_tv_desc", device.descriptionUrl)
            .putString("last_tv_name", ep.friendlyName)
            .apply()
        refreshUi()
        if (!MirrorService.isRunning && carMode && !carModeStarted) {
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
            statusView.text = "Starting the mirror…"
            DlnaController.takeErrorLog()
            DlnaController.playLiveStream(e, "http://$ip:8080/live.ts?k=$token", "Screen mirror") { ok ->
                runOnUiThread {
                    if (ok) {
                        EventLog.add("Mirroring to ${e.friendlyName} (${mirrorQuality().title}, stream at $ip)")
                        castingVideo = false
                        refreshUi()
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

    private fun chooseQuality() {
        val all = MirrorQuality.entries
        val items = all.map { "${it.title}\n${it.detail}" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Mirror quality")
            .setSingleChoiceItems(items, all.indexOf(mirrorQuality())) { dialog, which ->
                getPreferences(MODE_PRIVATE).edit().putString("mirror_quality", all[which].name).apply()
                refreshUi()
                dialog.dismiss()
                if (MirrorService.isRunning) toast("Applies next time you start mirroring")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun soundOn(): Boolean = getPreferences(MODE_PRIVATE).getBoolean("send_sound", false)

    private fun setSound(on: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("send_sound", on).apply()
        refreshUi()
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
                        castingVideo = true
                        castStatus = "Playing $name"
                        refreshUi()
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
                notFound = false
                WifiRoute.ensure(userAction = true)
                connectSaved()
            }
        }
    }

    private fun castLink() {
        val input = android.widget.EditText(this).apply {
            hint = "A direct video link (http…mp4)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cast a video link")
            .setView(input)
            .setPositiveButton("Cast") { _, _ ->
                val link = input.text.toString().trim()
                val e = endpoints ?: return@setPositiveButton
                if (!link.startsWith("http")) { toast("Enter a link starting with http"); return@setPositiveButton }
                statusView.text = "Casting…"
                DlnaController.takeErrorLog()
                DlnaController.setUriAndPlay(e, link, "Online video") { ok ->
                    runOnUiThread {
                        if (ok) { castingVideo = true; castStatus = "Playing the link"; refreshUi() }
                        else showReport("Cast failed: link not playable or refused",
                            DlnaController.takeErrorLog().ifBlank { DlnaController.lastError } + "\n\n" + DlnaController.info)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            text = "The app connects to the car's Wi-Fi itself, so Android keeps using mobile data " +
                "for internet.\n\nAfterwards, forget the car's Wi-Fi in Wi-Fi settings. The first " +
                "time in the car, Android asks to allow the connection: pick the car's Wi-Fi and tap Connect."
            setPadding(0, 0, 0, pad / 2)
        }
        val ssidInput = android.widget.EditText(this).apply {
            hint = "Car Wi-Fi name"
            setSingleLine(true)
            setText(WifiRoute.savedCarWifiName ?: "Zeekr-2882_Casting")
        }
        val passInput = android.widget.EditText(this).apply {
            hint = "Car Wi-Fi password"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(WifiRoute.savedCarWifiPassword)
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
                        refreshUi()
                        if (!on) offerWifiSettings(ssid)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
        if (on) dialog.setNeutralButton("Turn off") { _, _ ->
            WifiRoute.stopJoiningCarWifi(this)
            refreshUi()
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
        // Mirroring may have been stopped from the notification while the app was away.
        if (::statusTitle.isInitialized) refreshUi()
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
