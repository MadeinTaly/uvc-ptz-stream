package com.h96.hwstream

import android.util.Log

/**
 * Pilotaggio del display VFD frontale via ioctl raw su `/dev/digital_dis` (impl. nativa in
 * uvccam.cpp, vedi [UvcNative.vfdIoctl]). Reverse-eng da `libled-jni.so`:
 * ioctl = `_IOW('T', nr, int)`; led_dev_init usava nr 7/5/9 con valori byte (config).
 *
 * Il device e' root-only e la nostra app gira come uid normale → `chmod 666` via root prima
 * (VfdService e' disabilitato, node libero). Il display e' 4-cifre 7-segmenti: encoding da
 * calibrare LIVE con [raw] guardando il box. [message] mostra un codice per ~6s poi spegne.
 */
object VfdControl {
    private const val TAG = "HwStream"
    private const val NODE = "/dev/digital_dis"
    @Volatile private var chmodDone = false
    private var offTimer: java.util.Timer? = null

    private fun chmod() {
        if (chmodDone) return
        try {
            Runtime.getRuntime().exec(arrayOf("/system/xbin/su", "0", "chmod", "666", NODE)).waitFor()
            chmodDone = true
        } catch (e: Exception) { Log.w(TAG, "chmod $NODE: ${e.message}") }
    }

    /** ioctl singolo: _IOW('T', nr, value). Ritorna 0 ok / <0 errno. */
    @Synchronized
    fun ioctl(nr: Int, value: Int): Int { chmod(); return UvcNative.vfdIoctl(nr, value) }

    /**
     * Diagnostica: spara una sequenza di ioctl (nr/value separati da virgola) per scoprire
     * l'encoding del display. Es: nr="7,5,9" val="1,0,1". Ritorna i codici di ritorno.
     */
    @Synchronized
    fun raw(nrs: List<Int>, vals: List<Int>): String {
        chmod()
        val sb = StringBuilder()
        for (idx in nrs.indices) {
            val nr = nrs[idx]; val v = vals.getOrElse(idx) { 0 }
            sb.append("ioctl(0x${(0x40045400 or (nr and 0xff)).toString(16)},$v)=").append(UvcNative.vfdIoctl(nr, v)).append(' ')
        }
        return sb.toString().trim()
    }

    /** Mostra un valore (ledSet-equivalente, encoding da calibrare) per ms, poi spegne. */
    @Synchronized
    fun message(nr: Int, value: Int, ms: Long = 6000L) {
        chmod()
        UvcNative.vfdIoctl(nr, value)
        offTimer?.cancel()
        offTimer = java.util.Timer().apply {
            schedule(object : java.util.TimerTask() { override fun run() { /* off: da calibrare */ } }, ms)
        }
    }
}
