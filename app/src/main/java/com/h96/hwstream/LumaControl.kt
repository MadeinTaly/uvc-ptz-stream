package com.h96.hwstream

import android.content.Context

/**
 * Luminosita'/gamma SOFTWARE sul canale Y della pipeline YUYV (frame_cb nativo),
 * prima di JPEG/encoder: agisce su MJPEG, snapshot, WS video e REC.
 * E' l'unico controllo esposizione possibile: il firmware della cam ignora tutti
 * i SET esposizione via USB (verificato su 045e:8888). 100/100 = neutro (pass-through).
 * Persistito in SharedPreferences "img", ri-applicato all'avvio.
 */
object LumaControl {
    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile var gain = 100;  private set   // percento, 10..300 (con AE = target luma)
    @Volatile var gamma = 100; private set   // x100, 30..300
    @Volatile var ae = false;  private set   // AE software: gain automatico in frame_cb

    /** Collega il context, carica i valori salvati e li applica alla LUT nativa. */
    fun attach(ctx: Context) {
        val p = ctx.getSharedPreferences("img", Context.MODE_PRIVATE)
        prefs = p
        gain = p.getInt("lumaGain", 100)
        gamma = p.getInt("lumaGamma", 100)
        ae = p.getBoolean("lumaAe", false)
        apply()
    }

    /** Imposta, PERSISTE e applica subito. Ritorna false su valori fuori range. */
    @Synchronized
    fun set(gain100: Int, gamma100: Int, aeOn: Boolean): Boolean {
        if (gain100 !in 10..300 || gamma100 !in 30..300) return false
        gain = gain100; gamma = gamma100; ae = aeOn
        prefs?.edit()?.putInt("lumaGain", gain)?.putInt("lumaGamma", gamma)
            ?.putBoolean("lumaAe", ae)?.apply()
        return apply()
    }

    fun apply(): Boolean = UvcNative.setLuma(gain, gamma, ae)
}
