package com.h96.hwstream

import android.content.Context
import org.json.JSONObject

/**
 * Stato PTZ assoluto condiviso tra HTTP (/ptz) e WebSocket (/ws).
 * Unita': pan/tilt in arcsec (gradi*3600), zoom in unita' camera 0..16384.
 *
 * La camera 045e:8888 e' PTZ MOTORIZZATA (motore fisico pan/tilt + zoom):
 * setPanTilt/setZoom via PANTILT_ABS (0x0D) / ZOOM_ABS (0x0B) muovono il motore.
 * Verificato live: sweep assoluto 0->+170deg fa girare fisicamente la testa.
 * Gli aggiornamenti assoluti continui dal joystick reggono senza busy-abort.
 * NB: il firmware NON implementa GET_CUR per pan/tilt/zoom (GET_LEN=0, cur=n/a):
 * il SET viene accettato ma la posizione non e' rileggibile -> stato tenuto qui.
 *
 * Limiti dal firmware (vedi D:\Dev\ptz README): pan +-170 deg, tilt -30..90 deg,
 * zoom 0..16384.
 */
object PtzController {

    // Limiti assoluti (arcsec / unita' zoom). Esposti per far frazionare il movimento al FE.
    const val PAN_MIN = -612000;  const val PAN_MAX = 612000      // +-170 deg
    const val TILT_MIN = -108000; const val TILT_MAX = 324000     // -30..90 deg
    const val ZOOM_MIN = 0;       const val ZOOM_MAX = 16384

    private const val PT_STEP = 5 * 3600   // 5 gradi, per i pulsanti legacy
    private const val Z_STEP = 1024

    @Volatile var pan = 0;  private set
    @Volatile var tilt = 0; private set
    @Volatile var zoom = 0; private set

    // --- Persistenza (il motore non ha readback: salviamo lo stato logico e lo ri-applichiamo
    //     all'avvio cosi' la testa torna all'ultima inquadratura) ---------------------------
    @Volatile private var prefs: android.content.SharedPreferences? = null

    // --- Fuoco: NON gestito dal software. La camera ha un autofocus di fabbrica completo;
    //     qualunque comando UVC sul fuoco lo disattiva e lo rompe. Nessuna API, nessuno stato.

    /** Collega il context e carica l'ultima posizione salvata nello stato logico. */
    fun attach(ctx: Context) {
        val p = ctx.getSharedPreferences("ptz", Context.MODE_PRIVATE)
        prefs = p
        pan = p.getInt("pan", 0); tilt = p.getInt("tilt", 0); zoom = p.getInt("zoom", 0)
        tPan = pan; tTilt = tilt; tZoom = zoom
        loadPresets()
    }

    /** Ri-applica al motore la posizione salvata (chiamare dopo openCamera). */
    @Synchronized
    fun restore(): Boolean {
        if (!UvcNative.opened) return false
        return UvcNative.setPanTilt(pan, tilt) and UvcNative.setZoom(zoom)
    }

    private fun save() {
        prefs?.edit()?.putInt("pan", pan)?.putInt("tilt", tilt)?.putInt("zoom", zoom)?.apply()
    }

    // --- Coalescenza WS -> USB ---------------------------------------------
    // Il joystick WS arriva a decine di Hz; ogni SET_CUR su iface0 contende col
    // bulk video (a 1080 -> frame strappati, e troppi control transfer stressano
    // il link SS/wifi). Quindi il WS aggiorna SOLO un target; un thread invia al
    // massimo un SET USB ogni SEND_MS con l'ultimo stato (i burst collassano).
    // primo SET immediato, poi al massimo uno ogni SEND_MS (~10 Hz, coalescato)
    private const val SEND_MS = 100L
    private val wake = Object()
    @Volatile private var tPan = 0
    @Volatile private var tTilt = 0
    @Volatile private var tZoom = 0
    @Volatile private var dirty = false
    @Volatile private var sender: Thread? = null

    private fun clamp(v: Int, lo: Int, hi: Int) = maxOf(lo, minOf(hi, v))

    /** Aggiorna il target assoluto dal joystick (NON tocca l'USB qui: coalescato). */
    @Synchronized
    fun setTarget(p: Int, t: Int, z: Int) {
        if (!UvcNative.opened) return
        tPan = clamp(p, PAN_MIN, PAN_MAX)
        tTilt = clamp(t, TILT_MIN, TILT_MAX)
        tZoom = clamp(z, ZOOM_MIN, ZOOM_MAX)
        dirty = true
        ensureSender()
        synchronized(wake) { wake.notify() }
    }

    private fun ensureSender() {
        if (sender?.isAlive == true) return
        sender = Thread {
            while (UvcNative.opened) {
                if (dirty) {
                    val p = tPan; val t = tTilt; val z = tZoom; dirty = false
                    var changed = false
                    if (p != pan || t != tilt) { if (UvcNative.setPanTilt(p, t)) { pan = p; tilt = t; changed = true } }
                    if (z != zoom) { if (UvcNative.setZoom(z)) { zoom = z; changed = true } }
                    if (changed) save()
                    try { Thread.sleep(SEND_MS) } catch (_: InterruptedException) { break }
                } else try { synchronized(wake) { if (!dirty) wake.wait(1000) } } catch (_: InterruptedException) { break }
            }
            sender = null
        }.apply { name = "ptz-sender"; isDaemon = true; start() }
    }

    /** Applica una posizione assoluta IMMEDIATA (clampata). Usato dai pulsanti legacy/home. */
    @Synchronized
    fun setAbsolute(p: Int, t: Int, z: Int): Boolean {
        if (!UvcNative.opened) return false
        val np = clamp(p, PAN_MIN, PAN_MAX)
        val nt = clamp(t, TILT_MIN, TILT_MAX)
        val nz = clamp(z, ZOOM_MIN, ZOOM_MAX)
        // allinea anche il target cosi' il sender non rimanda una posizione vecchia
        tPan = np; tTilt = nt; tZoom = nz
        var ok = true
        if (np != pan || nt != tilt) { ok = UvcNative.setPanTilt(np, nt); pan = np; tilt = nt }
        if (nz != zoom) { ok = UvcNative.setZoom(nz) && ok; zoom = nz }
        save()
        return ok
    }

    @Synchronized
    fun home(): Boolean {
        if (!UvcNative.opened) return false
        pan = 0; tilt = 0; zoom = ZOOM_MIN
        tPan = 0; tTilt = 0; tZoom = ZOOM_MIN
        save()
        return UvcNative.setPanTilt(0, 0) and UvcNative.setZoom(ZOOM_MIN)
    }

    /** Movimento a passo fisso per i pulsanti legacy (HTTP /ptz?op=). */
    @Synchronized
    fun step(op: String): Boolean {
        if (!UvcNative.opened) return false
        return when (op) {
            "pan_left"  -> setAbs(pan - PT_STEP, tilt, zoom)
            "pan_right" -> setAbs(pan + PT_STEP, tilt, zoom)
            "tilt_up"   -> setAbs(pan, tilt + PT_STEP, zoom)
            "tilt_down" -> setAbs(pan, tilt - PT_STEP, zoom)
            "zoom_in"   -> setAbs(pan, tilt, zoom + Z_STEP)
            "zoom_out"  -> setAbs(pan, tilt, zoom - Z_STEP)
            "home"      -> home()
            "af_reset"  -> UvcNative.resetCamera()
            "stop"      -> true
            else        -> false
        }
    }

    private fun setAbs(p: Int, t: Int, z: Int) = setAbsolute(p, t, z)

    /** Snapshot stato corrente (per echo verso il client). */
    fun stateJson(): JSONObject = JSONObject().apply {
        put("pan", pan); put("tilt", tilt); put("zoom", zoom)
    }

    /** Limiti + step, inviati al FE una volta per configurare joystick e leva zoom. */
    fun limitsJson(): JSONObject = JSONObject().apply {
        put("panMin", PAN_MIN);   put("panMax", PAN_MAX)
        put("tiltMin", TILT_MIN); put("tiltMax", TILT_MAX)
        put("zoomMin", ZOOM_MIN); put("zoomMax", ZOOM_MAX)
    }

    // ===== Preset di posizione ("multi-home") =====================================
    // Lista persistente di inquadrature {id,name,pan,tilt,zoom}. Salvate in SharedPreferences
    // (chiave "presets" del file ptz). Il motore non ha readback -> uso lo stato logico corrente.
    private val presets = mutableListOf<JSONObject>()
    private var presetSeq = 1

    private fun loadPresets() {
        presets.clear()
        val s = prefs?.getString("presets", null) ?: return
        try {
            val a = org.json.JSONArray(s)
            for (i in 0 until a.length()) presets.add(a.getJSONObject(i))
            presetSeq = (presets.maxOfOrNull { it.optInt("id") } ?: 0) + 1
        } catch (_: Exception) {}
    }
    private fun savePresets() {
        val a = org.json.JSONArray(); presets.forEach { a.put(it) }
        prefs?.edit()?.putString("presets", a.toString())?.apply()
    }
    fun presetsJson(): org.json.JSONArray {
        val a = org.json.JSONArray(); presets.forEach { a.put(it) }; return a
    }

    /** Salva l'inquadratura corrente come nuovo preset. Ritorna la lista aggiornata. */
    @Synchronized
    fun presetAdd(name: String?): org.json.JSONArray {
        val id = presetSeq++
        val nm = if (name.isNullOrBlank()) "P$id" else name.trim()
        presets.add(JSONObject().apply {
            put("id", id); put("name", nm)
            put("pan", pan); put("tilt", tilt); put("zoom", zoom)
        })
        savePresets(); return presetsJson()
    }
    @Synchronized
    fun presetDel(id: Int): org.json.JSONArray {
        presets.removeAll { it.optInt("id") == id }; savePresets(); return presetsJson()
    }
    /** Rinomina un preset esistente. */
    @Synchronized
    fun presetRename(id: Int, name: String?): org.json.JSONArray {
        presets.firstOrNull { it.optInt("id") == id }?.let {
            if (!name.isNullOrBlank()) it.put("name", name.trim()); savePresets()
        }
        return presetsJson()
    }
    /** Muove la camera all'inquadratura del preset id. */
    @Synchronized
    fun presetGo(id: Int): Boolean {
        val p = presets.firstOrNull { it.optInt("id") == id } ?: return false
        return setAbsolute(p.optInt("pan"), p.optInt("tilt"), p.optInt("zoom"))
    }
}
