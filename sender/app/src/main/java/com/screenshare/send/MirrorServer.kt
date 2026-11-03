package com.screenshare.send

import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves the live MPEG-TS mirror stream at http://<phone>:8080/live.ts,
 * same shape as the vendor sender app so any DLNA renderer can play it.
 * Multiple clients allowed; each gets the stream from the next keyframe.
 */
object MirrorServer {
    /**
     * One connected display. A [queued] sink is fed by its own writer thread, so a slow
     * display only backs up its own queue. Otherwise [broadcast] writes to it directly.
     */
    private class Sink(val sock: Socket, val out: OutputStream, val queued: Boolean) {
        @Volatile var primed = false
        /** Last time a write to this display went through; a stuck display stops updating it. */
        @Volatile var lastWriteMs = System.currentTimeMillis()
        val queue = LinkedBlockingQueue<ByteArray>()
        val queuedBytes = AtomicLong()

        fun enqueue(chunk: ByteArray) {
            queuedBytes.addAndGet(chunk.size.toLong())
            queue.offer(chunk)
        }

        /** Drops the backlog. Chunks are whole TS packets, so the stream stays aligned. */
        fun clear() {
            val dropped = ArrayList<ByteArray>()
            queue.drainTo(dropped)
            queuedBytes.addAndGet(-dropped.sumOf { it.size.toLong() })
        }
    }

    /**
     * Set before [start]. When true, a display that falls more than [maxQueuedBytes] behind
     * has its backlog dropped and restarts at the next keyframe, instead of stalling the encoder.
     */
    @Volatile var skipWhenBehind = false
    @Volatile var maxQueuedBytes = 512 * 1024

    private val executor = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Sink>()
    private val writeLock = Any()

    /**
     * Builds a new PAT+PMT on each call so the continuity counters advance.
     * Re-sending one cached packet repeats the same counter forever.
     */
    @Volatile var psiSource: (() -> ByteArray)? = null

    /** Fired after a client is admitted, so the encoder can emit an IDR immediately. */
    @Volatile var onClientConnected: (() -> Unit)? = null

    @Volatile var running = false

    /** Shared secret for this mirror session. The cast URL must carry `k=<token>`. */
    @Volatile var streamToken: String = ""
        private set

    fun start() {
        if (running) return
        if (streamToken.isEmpty()) {
            val raw = ByteArray(16)
            SecureRandom().nextBytes(raw)
            streamToken = raw.joinToString("") { "%02x".format(it) }
        }
        executor.execute {
            try {
                val ss = ServerSocket(8080, 16, java.net.InetAddress.getByName("0.0.0.0"))
                server = ss
                running = true
                while (running && !ss.isClosed) {
                    val s = ss.accept()
                    executor.execute { handle(s) }
                }
            } catch (_: Exception) { running = false }
        }
    }

    fun stop() {
        running = false
        streamToken = ""
        try { server?.close() } catch (_: Exception) {}
        synchronized(writeLock) {
            for (c in clients) { try { c.out.close() } catch (_: Exception) {} }
            clients.clear()
        }
        server = null
        psiSource = null
        onClientConnected = null
    }

    fun localIp(): String? {
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (!name.startsWith("wlan")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun handle(sock: Socket) {
        var sink: Sink? = null
        try {
            sock.tcpNoDelay = true
            // Android lets a socket hold ~4 MB unsent (over 10 s of video), which hides a slow
            // display from the skip-ahead queue. Keep it small so the queue sees the backlog.
            if (skipWhenBehind) sock.sendBufferSize = 64 * 1024
            val input = sock.getInputStream()
            val out = sock.getOutputStream()
            // read request head
            val buf = ByteArray(4096)
            var read = 0
            var head = 0
            while (head < buf.size) {
                val n = input.read(buf, head, buf.size - head)
                if (n < 0) return
                head += n
                val s = String(buf, 0, head, Charsets.ISO_8859_1)
                if (s.contains("\r\n\r\n")) break
                read = head
            }
            val req = String(buf, 0, head, Charsets.ISO_8859_1)
            val target = req.lineSequence().firstOrNull()
                ?.substringAfter(' ')?.substringBefore(' ').orEmpty()
            val path = target.substringBefore('?')
            if (path != "/live.ts") {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); sock.close(); return
            }
            if (!tokenMatches(target, streamToken)) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); sock.close(); return
            }
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "transferMode.dlna.org: Streaming\r\n" +
                    "contentFeatures.dlna.org: DLNA.ORG_PN=MPEG_TS_HD_NA_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                    "Accept-Ranges: none\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            if (req.startsWith("HEAD ")) { sock.close(); return }
            val added = Sink(sock, out, skipWhenBehind)
            sink = added
            synchronized(writeLock) { clients.add(added) }
            try { onClientConnected?.invoke() } catch (_: Exception) {}
            if (added.queued) {
                // This thread is the display's writer. A failed write means it went away.
                while (running) {
                    val chunk = added.queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    added.queuedBytes.addAndGet(-chunk.size.toLong())
                    out.write(chunk)
                    out.flush()
                    added.lastWriteMs = System.currentTimeMillis()
                }
            } else {
                // block until client goes away
                try { input.read(ByteArray(1)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            sink?.let { s -> synchronized(writeLock) { clients.remove(s) } }
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /**
     * Write one TS burst. Video and audio call this from different threads;
     * the lock keeps 188-byte packets intact. A client stays silent until the
     * next keyframe, which is prefixed with a freshly built PAT/PMT.
     */
    fun broadcast(ts: ByteArray, keyframe: Boolean = false) {
        val psi = if (keyframe) freshPsi() else null
        synchronized(writeLock) {
            val dead = ArrayList<Sink>()
            for (c in clients) {
                if (c.queued) {
                    if (keyframe) {
                        // Too far behind: drop the backlog and jump straight to this keyframe.
                        if (c.queuedBytes.get() > maxQueuedBytes) c.clear()
                        psi?.let { c.enqueue(it) }
                        c.primed = true
                        c.enqueue(ts)
                    } else if (c.primed) {
                        if (c.queuedBytes.get() > maxQueuedBytes) {
                            // Stop feeding it until the next keyframe (at most 1 s away).
                            c.clear()
                            c.primed = false
                        } else {
                            c.enqueue(ts)
                        }
                    }
                    continue
                }
                try {
                    if (keyframe) {
                        psi?.let { c.out.write(it) }
                        c.primed = true
                    }
                    if (!c.primed) continue
                    c.out.write(ts)
                    c.out.flush()
                    c.lastWriteMs = System.currentTimeMillis()
                } catch (_: Exception) {
                    dead.add(c)
                }
            }
            if (dead.isNotEmpty()) clients.removeAll(dead)
        }
    }

    /** PAT/PMT for clients already in the stream. Does not admit a waiting client. */
    fun broadcastPsi(packets: ByteArray) {
        if (packets.isEmpty()) return
        synchronized(writeLock) {
            val dead = ArrayList<Sink>()
            for (c in clients) {
                if (!c.primed) continue
                if (c.queued) { c.enqueue(packets); continue }
                try {
                    c.out.write(packets)
                    c.out.flush()
                } catch (_: Exception) {
                    dead.add(c)
                }
            }
            if (dead.isNotEmpty()) clients.removeAll(dead)
        }
    }

    /** Displays currently connected to the stream. */
    fun clientCount(): Int = clients.size

    /**
     * Closes connections whose writes have been stuck for [maxAgeMs]. A display that vanished
     * (Wi-Fi dropped) can leave a write blocked for minutes; closing the socket unblocks it, and
     * the normal error path then removes the display. Doesn't take [writeLock], which a stuck
     * write may be holding.
     */
    fun dropStalled(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        for (c in clients) {
            if (now - c.lastWriteMs > maxAgeMs) {
                try { c.sock.close() } catch (_: Exception) {}
                clients.remove(c)
            }
        }
    }

    private fun freshPsi(): ByteArray? = try {
        psiSource?.invoke()
    } catch (_: Exception) {
        null
    }

    private fun tokenMatches(target: String, token: String): Boolean {
        if (token.isEmpty()) return false
        val presented = target.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("k=") }
            ?.substring(2)
            ?: return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )
    }
}
