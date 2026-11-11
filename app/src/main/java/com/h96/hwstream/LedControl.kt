package com.h96.hwstream

import android.util.Log

/**
 * LED del pannello frontale: vanno tenuti **sempre SPENTI** (inutili, scelta utente).
 * Su questo box c'e' una sola luce visibile (blu) su `/sys/class/leds/power-green` con
 * **polarita' INVERTITA** (verificato live: brightness 255 = SPENTO, 0 = acceso); `power-red`
 * esiste (active-high) ma non pilota luce visibile. Scrittura solo root (`su 0`).
 *
 * ChangeLed (l'app vendor che li accendeva) e' PERSISTENT → e' stato disinstallato per-utente.
 * Qui ci limitiamo a ri-affermare lo SPEGNIMENTO periodicamente (watchdog), col valore corretto
 * per polarita'. Nessun pilotaggio: non si accendono mai dall'app.
 */
object LedControl {
    private const val TAG = "HwStream"
    private const val BASE = "/sys/class/leds"
    private val LEDS = listOf("power-green", "power-red")
    // Valore di brightness che SPEGNE ciascun LED (power-green invertito → 255 = off).
    private val OFF = mapOf("power-green" to 255, "power-red" to 0)

    private fun write(name: String, value: Int) {
        if (!java.io.File("$BASE/$name").exists()) return   // device senza questi LED (non H96)
        try {
            Runtime.getRuntime()
                .exec(arrayOf("/system/xbin/su", "0", "sh", "-c", "echo $value > $BASE/$name/brightness"))
                .waitFor()
        } catch (e: Exception) { Log.w(TAG, "led $name=$value: ${e.message}") }
    }

    /** Spegne tutti i LED (polarita' corretta). Idempotente, chiamabile spesso. */
    fun allOff() { LEDS.forEach { write(it, OFF[it] ?: 0) } }
}
