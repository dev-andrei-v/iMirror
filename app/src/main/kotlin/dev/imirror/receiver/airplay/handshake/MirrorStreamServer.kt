package dev.imirror.receiver.airplay.handshake

import android.view.Surface
import dev.imirror.receiver.airplay.StreamStats
import dev.imirror.receiver.airplay.VideoDecoder
import dev.imirror.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → (re)configure the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, convert to Annex-B, decode
 *
 * Architecture: a network thread reads + decrypts packets (keeping the AES-CTR keystream strictly
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. This way
 * the socket is always drained fast — if the decoder can't keep up (this SoC is modest, and the
 * audio codec competes for resources), frames are dropped from the queue instead of stalling the
 * socket, which previously caused macOS to drop the whole session.
 *
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private sealed class Item
    /** Parameter sets. [vps] is present for H.265 (iOS 27 mirrors in HEVC) and null for H.264. */
    private class Config(val sps: ByteArray, val pps: ByteArray, val vps: ByteArray? = null) : Item()
    private class Frame(val annexB: ByteArray) : Item()

    /** The `hvcC` box tag inside an H.265 config payload. */
    private val HVCC_FOURCC = byteArrayOf('h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), 'C'.code.toByte())

    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket = ServerSocket(0)            // OS-assigned free port
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    private var lastVps: ByteArray? = null                // non-null while the stream is H.265
    // The Surface the current decoder was built against. The SurfaceView destroys its Surface when
    // the app backgrounds and creates a NEW one on return, so we watch for the identity changing
    // and rebuild the decoder — otherwise video stays black after foregrounding.
    @Volatile private var configuredSurface: Surface? = null
    private var framePtsUs = 0L
    private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                if (payloadSize == 0) {
                    // iOS 27 sends a header-only (empty) packet, usually type 1, early in the
                    // session. Treating it as an error ended the session after a few seconds.
                    // There is nothing to decrypt or decode, so skip it and keep reading.
                    Logger.w("Mirror: empty payload type=$payloadType — skipping")
                    continue
                }
                if (payloadSize < 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                when (payloadType) {
                    0 -> {
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
                        if (annexB.isNotEmpty()) enqueue(Frame(annexB))
                    }
                    1 -> parseConfig(payload)?.let { enqueue(it) }
                    else -> Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror reader error", e)
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /** Bounded enqueue — if the decoder is behind, drop the oldest item to keep latency bounded. */
    private fun enqueue(item: Item) {
        framesIn++
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            framesDropped++
            awaitingKeyframe = true        // a frame was lost — resync the decoder at the next IDR
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY ${StreamStats.videoFps}fps")
        }
    }

    private fun parseConfig(payload: ByteArray): Config? = try {
        val hvcc = indexOfFourcc(payload, HVCC_FOURCC)
        if (hvcc >= 0) {
            parseHevcConfig(payload, hvcc + 4)
        } else {
            val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
            val sps = payload.copyOfRange(8, 8 + spsSize)
            val ppsLenOffset = 8 + spsSize + 1                   // skip the 1-byte PPS count
            val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
                (payload[ppsLenOffset + 1].toInt() and 0xFF)
            Config(sps, payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize))
        }
    } catch (e: Exception) {
        Logger.e("Mirror: failed to parse SPS/PPS", e); null
    }

    /**
     * H.265 config (iOS 27): the payload is an ISO `hvc1` sample entry that holds an `hvcC` box.
     * The record has a 22-byte header, then `numOfArrays`, then one array per NAL type:
     * 1 byte type (low 6 bits), 2-byte count, and per NAL a 2-byte length and the bytes.
     * VPS = 32, SPS = 33, PPS = 34.
     */
    private fun parseHevcConfig(payload: ByteArray, recordStart: Int): Config? {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var p = recordStart + 22
        val arrays = payload[p].toInt() and 0xFF
        p += 1
        repeat(arrays) {
            val type = payload[p].toInt() and 0x3F
            val count = beShort(payload, p + 1)
            p += 3
            repeat(count) {
                val len = beShort(payload, p)
                val nal = payload.copyOfRange(p + 2, p + 2 + len)
                p += 2 + len
                when (type) {
                    32 -> vps = vps ?: nal
                    33 -> sps = sps ?: nal
                    34 -> pps = pps ?: nal
                }
            }
        }
        val v = vps; val s = sps; val q = pps
        if (v == null || s == null || q == null) {
            Logger.w("Mirror: hvcC without VPS/SPS/PPS (arrays=$arrays)")
            return null
        }
        Logger.i("Mirror: H.265 config (vps=${v.size}B sps=${s.size}B pps=${q.size}B)")
        return Config(s, q, v)
    }

    private fun indexOfFourcc(data: ByteArray, fourcc: ByteArray): Int {
        var i = 0
        while (i + fourcc.size <= data.size) {
            var match = true
            for (k in fourcc.indices) if (data[i + k] != fourcc[k]) { match = false; break }
            if (match) return i
            i++
        }
        return -1
    }

    private fun beShort(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Config -> configureDecoder(item)
                    is Frame -> decodeFrame(item.annexB)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    private fun configureDecoder(config: Config) {
        // New parameter sets (or first config) — cache them and (re)build against the current surface.
        val d = decoder
        val surface = awaitSurface()
        val sameVps = (config.vps == null && lastVps == null) ||
            (config.vps != null && config.vps.contentEquals(lastVps))
        if (d != null && d.isHealthy && config.sps.contentEquals(lastSps) && config.pps.contentEquals(lastPps) &&
            sameVps && surface === configuredSurface) return
        lastSps = config.sps
        lastPps = config.pps
        lastVps = config.vps
        rebuildDecoder(surface)
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs at the next keyframe.
     * If the surface or config isn't available yet, leaves the decoder null and retries on a later
     * frame (frames are dropped until then).
     */
    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null) return                            // backgrounded — wait for the surface to return
        val sc = MirrorCrypto.START_CODE
        val vps = lastVps
        decoder = VideoDecoder(surface).also {
            it.initialize(sc + sps, sc + pps, width, height, vpsBytes = vps?.let { v -> sc + v })
        }
        awaitingKeyframe = true                                // a fresh decoder must start at an IDR
        StreamStats.videoRes = "${width}x${height}"
        Logger.i("Mirror decoder (re)built for surface (${if (vps != null) "H.265" else "H.264"} sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(annexB: ByteArray) {
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            rebuildDecoder(liveSurface)
        }
        val d = decoder ?: return                              // need surface + SPS/PPS first
        if (!d.isHealthy) {                                    // error state — drop, await next config
            Logger.w("Mirror: decoder unhealthy — dropping, awaiting new SPS/PPS")
            d.release(); decoder = null; configuredSurface = null; lastSps = null; lastPps = null; lastVps = null
            return
        }
        if (awaitingKeyframe) {
            // After a dropped frame the stream is reference-broken; skip until the next IDR so we
            // don't feed the decoder predicted frames with missing references (which smear/blocky).
            if (!isKeyframe(annexB)) return
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        d.decodeNalUnit(annexB, framePtsUs)
        framePtsUs += FRAME_INTERVAL_US
    }

    /**
     * True if the Annex-B frame holds a decodable resync point: an H.264 IDR slice (type 5), or an
     * H.265 IDR/CRA picture (types 19, 20, 21; the H.265 type sits in bits 1..6 of the first byte).
     */
    private fun isKeyframe(annexB: ByteArray): Boolean {
        val hevc = lastVps != null
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                val first = annexB[i + 3].toInt() and 0xFF
                if (hevc) {
                    if (((first shr 1) and 0x3F) in 19..21) return true // IDR_W_RADL, IDR_N_LP, CRA
                } else if ((first and 0x1F) == 5) {
                    return true                                          // IDR slice
                }
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame
        private const val FRAME_INTERVAL_US = 1_000_000L / 60  // monotonic PTS hint (~60fps)
        private const val QUEUE_CAPACITY = 90                  // ~1.5s @60fps before dropping
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
    }
}
