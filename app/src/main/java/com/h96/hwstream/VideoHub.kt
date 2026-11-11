package com.h96.hwstream

import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Hub dello stream video HW. UN SOLO encoder condiviso da tutti i client `/video`
 * (non si puo' transcodificare per-client). Si avvia al primo client, si ferma all'ultimo.
 * Broadcast dei NAL Annex-B a tutti i sink connessi.
 *
 * Codec di default = HEVC (massima compressione). Se il browser non lo decodifica
 * (WebCodecs HEVC assente), si cambia MIME a "video/avc" qui.
 */
object VideoHub {
    const val MIME = "video/hevc"          // <- per passare a H264: "video/avc"
    private const val FPS = 25

    private val clients = CopyOnWriteArraySet<(ByteArray, Boolean) -> Unit>()
    private var enc: VideoEncoder? = null

    // ABR: un client che non regge (coda piena) abbassa il bitrate dell'encoder condiviso;
    // senza congestione per UP_MS risale verso il tetto della risoluzione.
    // ponytail: encoder unico -> il client piu' lento detta la qualita' per tutti.
    private const val FLOOR = 300_000
    private const val UP_MS = 6000L
    @Volatile private var cap = 0
    @Volatile private var cur = 0
    @Volatile private var lastCongest = 0L
    @Volatile private var lastUp = 0L

    fun congested() {
        val now = System.currentTimeMillis()
        if (now - lastCongest < 1000) return   // un passo al secondo al massimo
        lastCongest = now
        val nb = maxOf(FLOOR, (cur * 0.6).toInt())
        if (nb != cur) { cur = nb; enc?.setBitrate(nb); Log.i(tag, "ABR giu': ${nb / 1000}kbps") }
    }

    private fun maybeRaise() {
        val now = System.currentTimeMillis()
        if (cur >= cap || now - lastCongest < UP_MS || now - lastUp < 2500) return
        lastUp = now
        cur = minOf(cap, (cur * 1.35).toInt()); enc?.setBitrate(cur); Log.i(tag, "ABR su: ${cur / 1000}kbps")
    }

    fun bitrateKbps() = cur / 1000
    private val tag = "VideoHub"

    private val kickers = java.util.concurrent.ConcurrentHashMap<(ByteArray, Boolean) -> Unit, () -> Unit>()

    /** Cambio risoluzione: l'encoder e' dimensionato sul vecchio frame -> chiudi i WS video;
     *  il FE si ricollega da solo (~2s) e riparte un encoder alle dimensioni nuove. */
    @Synchronized
    fun kickAll() {
        if (clients.isEmpty()) return
        val ks = kickers.values.toList()
        clients.clear(); kickers.clear(); stopEncoder()   // subito: un client nuovo riparte da un encoder fresco
        Thread { ks.forEach { it() } }.start()
    }

    @Synchronized
    fun add(sink: (ByteArray, Boolean) -> Unit, kick: () -> Unit = {}) {
        kickers[sink] = kick
        clients.add(sink)
        if (enc == null) startEncoder()
        enc?.requestKeyframe()
        Log.i(tag, "client +1 (tot=${clients.size})")
    }

    @Synchronized
    fun remove(sink: (ByteArray, Boolean) -> Unit) {
        clients.remove(sink); kickers.remove(sink)
        Log.i(tag, "client -1 (tot=${clients.size})")
        if (clients.isEmpty()) stopEncoder()
    }

    fun mime(): String = enc?.codecMime ?: MIME
    fun clientCount(): Int = clients.size

    private fun startEncoder() {
        // dopo boot/idle lo stream e' appena ripartito: attendi il primo frame (dimensioni reali)
        var tries = 0
        while ((UvcNative.width() <= 0 || UvcNative.frames() == 0L) && tries++ < 30) Thread.sleep(100)
        val w = UvcNative.width(); val h = UvcNative.height()
        if (w <= 0 || h <= 0) { Log.w(tag, "stream non pronto, encoder non avviato"); return }
        // Bitrate scalato alla risoluzione (HEVC su VPU RK3566): 1080->6M, 720->3M, sotto->1.5M.
        val bitrate = when {
            w * h >= 1920 * 1080 -> 6_000_000
            w * h >= 1280 * 720  -> 3_000_000
            else                 -> 1_500_000
        }
        cap = bitrate; cur = bitrate; lastCongest = 0
        // HEVC se c'e' l'encoder HW (es. VPU Rockchip), altrimenti H264 (HW se disponibile)
        for (m in listOf(MIME, "video/avc")) {
        val e = VideoEncoder(m, w, h, FPS, bitrate) { data, key ->
            if (key) maybeRaise()
            // Viewer HW attivo: tieni vivo lo StreamGate come fa l'MJPEG (WebServer.serveMjpeg).
            // Senza questo, chi guarda via /video (WebCodecs) non tocca mai lo StreamGate:
            // idleMs() supera i 3 min e lo stop idle scatta al primo drop transitorio del WS
            // (Cloudflare chiude i WS ~idle 100s / tab in background) -> video che si ferma.
            StreamGate.touch()
            clients.forEach { sink -> try { sink(data, key) } catch (_: Exception) {} }
        }
        if (e.start()) { enc = e; return }
        }
        Log.e(tag, "encoder start fallito (resta il fallback MJPEG)")
    }

    private fun stopEncoder() {
        enc?.stop(); enc = null
        Log.i(tag, "encoder fermato (nessun client)")
    }
}
