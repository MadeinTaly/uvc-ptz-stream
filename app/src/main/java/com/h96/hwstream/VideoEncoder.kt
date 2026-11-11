package com.h96.hwstream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Encoder video HARDWARE (VPU RK3566). Prende i frame YUYV della camera, li converte in NV12
 * (lato nativo) e li comprime con l'encoder HW migliore disponibile (HEVC `c2.rk.hevc.encoder`,
 * fallback H264). Emette NAL Annex-B: ogni keyframe e' reso self-contained (VPS/SPS/PPS prepesi)
 * cosi' un client che si attacca a meta' stream puo' decodificare dal primo keyframe.
 *
 * Trasporto: i NAL vanno a un sink (WebSocket binario), il browser li decodifica con WebCodecs.
 * Un solo encoder per tutti i client (stream condiviso): non si puo' transcodificare per-client.
 */
class VideoEncoder(
    private val mime: String,          // "video/hevc" o "video/avc"
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onUnit: (ByteArray, Boolean) -> Unit   // (annexB, keyframe)
) {
    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var config: ByteArray? = null     // VPS/SPS/PPS Annex-B
    private val tag = "HwEnc"

    /** Nome del codec HW scelto, per diagnostica (null se sw/none). */
    var codecName: String? = null; private set
    val codecMime get() = mime

    fun start(): Boolean {
        try {
            val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // keyframe ogni 1s
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            val name = pickHwEncoder(mime)
            // HEVC software e' troppo lento per il live: senza encoder HW si ripiega su H264
            if (name == null && mime == "video/hevc") { Log.i(tag, "nessun encoder HEVC HW"); return false }
            codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(mime)
            codecName = codec?.name
            codec!!.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec!!.start()
            running = true
            thread = Thread({ loop() }, "hw-enc").also { it.start() }
            Log.i(tag, "encoder avviato: $codecName mime=$mime ${width}x$height@$fps ${bitrate/1000}kbps")
            return true
        } catch (e: Exception) {
            Log.e(tag, "start fallito: ${e.message}", e)
            stop(); return false
        }
    }

    private fun pickHwEncoder(mime: String): String? {
        return try {
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } &&
                        HwCodecs.isHardware(it) }
                ?.name
        } catch (e: Exception) { null }
    }

    private fun loop() {
        val c = codec ?: return
        val nv12 = ByteArray(width * height * 3 / 2)
        val info = MediaCodec.BufferInfo()
        var lastSeq = -1L
        val frameIntervalUs = 1_000_000L / fps
        var ptsUs = 0L
        while (running) {
            // INPUT: prendi un frame NUOVO (poll su seq) e mandalo all'encoder
            val seq = UvcNative.seq()
            if (seq != lastSeq) {
                val n = UvcNative.frameNV12(nv12)
                if (n > 0) {
                    val ii = c.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        ib.clear(); ib.put(nv12, 0, n)
                        c.queueInputBuffer(ii, 0, n, ptsUs, 0)
                        ptsUs += frameIntervalUs
                        lastSeq = seq
                    }
                }
            }
            // OUTPUT: drena i NAL pronti
            var oi = c.dequeueOutputBuffer(info, 5000)
            while (oi >= 0) {
                val ob = c.getOutputBuffer(oi)!!
                ob.position(info.offset); ob.limit(info.offset + info.size)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                val bytes = ByteArray(info.size); ob.get(bytes)
                if (isConfig) {
                    config = bytes                       // VPS/SPS/PPS: tienili da parte
                } else {
                    if (isKey && config != null) {       // keyframe self-contained
                        val cfg = config!!
                        val merged = ByteArray(cfg.size + bytes.size)
                        System.arraycopy(cfg, 0, merged, 0, cfg.size)
                        System.arraycopy(bytes, 0, merged, cfg.size, bytes.size)
                        onUnit(merged, true)
                    } else onUnit(bytes, isKey)
                }
                c.releaseOutputBuffer(oi, false)
                oi = c.dequeueOutputBuffer(info, 0)
            }
            if (seq == lastSeq && oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                try { Thread.sleep(5) } catch (_: InterruptedException) {}
            }
        }
    }

    /** Config NAL (VPS/SPS/PPS) Annex-B, per inviarlo a un client appena connesso. */
    fun configBytes(): ByteArray? = config

    /** Bitrate al volo (ABR): nessun riavvio dell'encoder. */
    fun setBitrate(bps: Int) {
        try { codec?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) } catch (_: Exception) {}
    }

    /** Forza un keyframe immediato (client appena connesso non aspetta fino a 1s). */
    fun requestKeyframe() {
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }
}
