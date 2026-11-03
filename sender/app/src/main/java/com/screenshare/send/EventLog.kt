package com.screenshare.send

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short record of what happened on a drive: which network carried the internet, joining the
 * car's Wi-Fi, finding the display, casts and reconnects. It is kept in `files/events.log`
 * (read it with `adb shell run-as com.screenshare.send cat files/events.log`) and shown by the
 * app's "Connection log" button. Lines also go to logcat under the tag "ScreenShare".
 */
object EventLog {
    private const val MAX_BYTES = 200_000L
    private const val KEEP_CHARS = 100_000
    @Volatile private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "events.log")
    }

    @Synchronized
    fun add(message: String) {
        android.util.Log.i("ScreenShare", message)
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast(KEEP_CHARS)
                f.writeText(tail.substringAfter('\n'))
            }
            f.appendText("${time.format(Date())}  $message\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(): String = try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
}
