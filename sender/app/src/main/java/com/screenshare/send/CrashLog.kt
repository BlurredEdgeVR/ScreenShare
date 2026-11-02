package com.screenshare.send

import android.content.Context

/** Saves the last crash so the app can show it on the next launch. */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            save(app, e, t.name)
            prev?.uncaughtException(t, e)
        }
    }

    fun save(ctx: Context, e: Throwable, thread: String = Thread.currentThread().name) {
        try {
            val text = "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nThread: $thread\n\n" +
                android.util.Log.getStackTraceString(e)
            ctx.applicationContext.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
        } catch (_: Throwable) {}
    }

    fun take(ctx: Context): String? = try {
        val f = ctx.applicationContext.getFileStreamPath(FILE)
        if (f.exists()) f.readText().also { f.delete() } else null
    } catch (_: Throwable) { null }
}
