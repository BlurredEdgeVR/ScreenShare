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

    private var endpoints: RendererEndpoints? = null
    private lateinit var statusView: TextView
    private lateinit var deviceButton: Button
    private lateinit var pickButton: Button
    private lateinit var controlsRow: LinearLayout
    private lateinit var spinner: ProgressBar
    private lateinit var mirrorButton: Button
    private lateinit var urlButton: Button
    private lateinit var rootLayout: LinearLayout

    @SuppressLint("SetTextI18n")
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
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
        pickButton = Button(this).apply {
            text = "Choose a video to cast"
            visibility = View.GONE
        }
        mirrorButton = Button(this).apply {
            text = "Screen mirror (whole phone screen)"
            visibility = View.GONE
            setOnClickListener { startMirrorFlow() }
        }
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
        root.addView(pickButton)
        root.addView(mirrorButton)
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
        rootLayout = root
        setContentView(root)

        deviceButton.setOnClickListener { showDevicePicker() }
        pickButton.setOnClickListener { pickVideo() }
        val lastIp = getPreferences(MODE_PRIVATE).getString("last_tv_ip", null)
        if (lastIp != null) {
            connect(CastDevice("", "http://$lastIp:49152/description.xml", lastIp))
        } else {
            startSearch()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startSearch() {
        statusView.text = "Searching for displays…"
        spinner.visibility = View.VISIBLE
        deviceButton.visibility = View.GONE
        pickButton.visibility = View.GONE
        controlsRow.visibility = View.GONE
        DlnaDiscoverer.search(4000) { devices ->
            runOnUiThread {
                spinner.visibility = View.GONE
                if (devices.isEmpty()) {
                    // cross-subnet fallback: TCP-probe 49152 on the phone's own /24 (SSDP cannot cross subnets)
                    SubnetScanner.scan { ips ->
                        // verify each candidate really is a cast device before offering it
                        var remaining = ips.size
                        val verified = java.util.Collections.synchronizedList(mutableListOf<CastDevice>())
                        if (ips.isEmpty()) {
                            runOnUiThread {
                                statusView.text = "No display found\n\nPhone and display may be on different networks\nUse the button below to enter the IP manually"
                                addRefreshButton()
                                addManualEntryButton()
                            }
                            return@scan
                        }
                        for (ip in ips) {
                            DlnaController.describe("http://$ip:49152/description.xml") { ep ->
                                if (ep != null) verified.add(CastDevice(ep.friendlyName, "http://$ip:49152/description.xml", ip))
                                remaining--
                                if (remaining == 0) {
                                    runOnUiThread {
                                        if (verified.isNotEmpty()) chooseFrom(verified.toList())
                                        else {
                                            statusView.text = "No display found\n\nUse the button below to enter the IP manually"
                                            addRefreshButton()
                                            addManualEntryButton()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    chooseFrom(devices)
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
                setOnClickListener { removeView(this); removeManual(); startSearch() }
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
        statusView.text = "Connecting: ${device.ip}…"
        DlnaController.describe(device.descriptionUrl) { ep ->
            runOnUiThread {
                if (ep == null) {
                    statusView.text = "Connection failed, tap to search again"
                    addRefreshButton()
                } else {
                    endpoints = ep
                    getPreferences(MODE_PRIVATE).edit().putString("last_tv_ip", device.ip).apply()
                    deviceButton.text = "Connected: ${ep.friendlyName}"
                    deviceButton.visibility = View.VISIBLE
                    statusView.text = "Choose a video on your phone to cast"
                    pickButton.visibility = View.VISIBLE
                    mirrorButton.visibility = View.VISIBLE
                    urlButton.visibility = View.VISIBLE
                    refreshButton?.let { removeView(it) }
                }
            }
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
                        statusView.text = "Mirroring phone screen to display"
                        controlsRow.visibility = View.VISIBLE
                    } else {
                        showReport("Display refused the mirror stream", DlnaController.takeErrorLog().ifBlank { DlnaController.lastError } + "\n\n" + DlnaController.info)
                    }
                }
            }
        }, 1200)
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
