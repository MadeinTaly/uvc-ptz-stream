package com.h96.hwstream

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Server HTTP + WebSocket embedded. Espone la UI web, le API REST e il canale
 * WebSocket `/ws` per il controllo PTZ in tempo reale (joystick + leva zoom).
 * Estende NanoWSD: stessa porta 8080 sia per HTTP (UI/MJPEG) che per il WS.
 */
class WebServer(
    private val ctx: Context,
    port: Int,
    private val uvc: UvcControl? = null
) : NanoWSD(port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket =
        if (handshake.uri == "/video") VideoSocket(handshake) else PtzSocket(handshake)

    // Il read-timeout socket di NanoHTTPD (NanoTimeouts.SOCKET_READ_TIMEOUT=10s) vale anche per
    // i WS: senza traffico IN INGRESSO la read scade e il server chiude. Su /video il browser
    // non manda mai nulla (stream solo in uscita) -> drop ogni 10s esatti; su /ws idem a
    // joystick fermo (il FE pinga solo ogni 30s). Ping server->client ogni 5s: il pong
    // automatico del browser resetta il timeout e il canale resta vivo.
    private fun startKeepalive(ws: WebSocket) = Thread {
        try {
            while (ws.isOpen) { ws.ping(ByteArray(0)); Thread.sleep(5_000) }
        } catch (_: Exception) {}   // socket chiuso: il thread muore da solo
    }.apply { name = "ws-keepalive"; isDaemon = true; start() }

    // WS BINARIO: stream video HW (HEVC/H264) Annex-B. Setup testuale {video,mime} poi NAL binari.
    inner class VideoSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private val ident = AccessControl.identify(handshake.headers, handshake.remoteIpAddress)
        // ogni frame binario = [1 byte flag: 1=keyframe,0=delta] + NAL Annex-B
        // invio su thread dedicato: un client lento non blocca l'encoder ne' gli altri viewer.
        // Coda oltre MAX_Q frame -> scarta fino al prossimo keyframe e segnala congestione (ABR).
        private val tx = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val pending = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var needKey = false
        private val sink: (ByteArray, Boolean) -> Unit = sink@{ data, key ->
            if (pending.get() > 8) { needKey = true; VideoHub.congested(); return@sink }
            if (needKey && !key) return@sink
            needKey = false
            val m = ByteArray(data.size + 1); m[0] = if (key) 1 else 0
            System.arraycopy(data, 0, m, 1, data.size)
            pending.incrementAndGet()
            try { tx.execute { try { send(m) } catch (_: Exception) {} finally { pending.decrementAndGet() } } }
            catch (_: Exception) { pending.decrementAndGet() }
        }
        override fun onOpen() {
            // utente bloccato dall'admin: chiudi subito, niente video
            if (AccessControl.isBlocked(ident)) {
                try { close(WebSocketFrame.CloseCode.PolicyViolation, "accesso bloccato", false) } catch (_: Exception) {}
                return
            }
            AccessControl.wsOpen(ident)
            StreamGate.touch()
            if (UvcNative.opened && !UvcNative.streaming) UvcNative.restart()   // riattivazione da idle
            try { send(JSONObject().apply { put("video", true); put("mime", VideoHub.mime()) }.toString()) } catch (_: IOException) {}
            VideoHub.add(sink) { try { close(WebSocketFrame.CloseCode.GoingAway, "risoluzione cambiata", false) } catch (_: Exception) {} }
            startKeepalive(this)
        }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) { AccessControl.wsClose(ident); VideoHub.remove(sink); tx.shutdownNow() }
        override fun onException(exception: IOException?) { AccessControl.wsClose(ident); VideoHub.remove(sink); tx.shutdownNow() }
        override fun onMessage(message: WebSocketFrame) {}
        override fun onPong(pong: WebSocketFrame?) {}
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val ident = AccessControl.identify(session.headers, session.remoteIpAddress)
        AccessControl.touch(ident)

        // Utente bloccato dall'admin: puo' solo caricare la pagina (che mostra l'avviso) e whoami.
        if (AccessControl.isBlocked(ident)) {
            return when (session.uri) {
                "/", "/index.html" -> serveAsset("index.html", "text/html")
                "/api/access/whoami" -> json(AccessControl.whoamiJson(ident))
                else -> forbidden("accesso bloccato dall'amministratore")
            }
        }
        // Gate per capacita' sugli endpoint sensibili (view/stream restano liberi).
        capFor(session)?.let { cap ->
            if (!AccessControl.can(ident, cap)) return forbidden("permesso '$cap' non concesso")
        }
        // Gestione accessi: solo admin puo' modificare.
        if (session.uri == "/api/access/set" && !AccessControl.isAdmin(ident))
            return forbidden("solo l'amministratore puo' gestire gli accessi")

        return try {
            when {
                session.uri == "/" || session.uri == "/index.html" -> serveAsset("index.html", "text/html")
                session.uri == "/api/access/whoami" -> json(AccessControl.whoamiJson(ident))
                session.uri == "/api/access/list" -> json(AccessControl.listJson(ident))
                session.uri == "/api/access/set" -> handleAccessSet(session)
                session.uri == "/api/status" -> json(status())
                session.uri == "/api/codecs" -> json(HwCodecs.toJson())
                session.uri == "/ptz" -> handlePtz(session)
                session.uri == "/api/ptz/limits" -> json(PtzController.limitsJson())
                session.uri == "/api/rawset" -> handleRawSet(session)
                session.uri == "/api/rawget" -> {
                    @Suppress("DEPRECATION") val u = session.parms["unit"]?.toIntOrNull() ?: 3
                    @Suppress("DEPRECATION") val s = session.parms["sel"]?.toIntOrNull() ?: 1
                    @Suppress("DEPRECATION") val l = session.parms["len"]?.toIntOrNull() ?: 8
                    newFixedLengthResponse(Response.Status.OK, "text/plain", UvcNative.rawGet(u, s, l))
                }
                session.uri == "/api/probe" -> {
                    @Suppress("DEPRECATION") val u = session.parms["unit"]?.toIntOrNull() ?: 1
                    newFixedLengthResponse(Response.Status.OK, "text/plain", UvcNative.probe(u))
                }
                session.uri == "/api/stream/formats" -> json(JSONObject().apply { put("formats", streamFormats()) })
                session.uri == "/api/stream/stats" -> json(streamStats())
                session.uri == "/api/debug" -> json(debugProbe())
                session.uri == "/api/ptz/pos" -> json(JSONObject().apply {
                    put("real", UvcNative.readPtz() ?: JSONObject.NULL); put("app", PtzController.stateJson()) })
                session.uri == "/stream/start" -> handleStreamStart(session)
                session.uri == "/stream/stop" -> { UvcNative.stop(); json(JSONObject().apply { put("stopped", true) }) }
                session.uri == "/stream/snapshot.jpg" -> serveSnapshot()
                session.uri == "/api/snapshot/hires" -> handleSnapshotHires()
                session.uri == "/stream/mjpeg" -> serveMjpeg()
                session.uri == "/api/controls" -> json(JSONObject().apply { put("controls", UvcNative.controlsJson()) })
                session.uri == "/api/control/set" -> handleControlSet(session)
                session.uri == "/api/luma" -> handleLuma(session)
                session.uri == "/api/snapshot/save" -> handleSnapshotSave()
                session.uri == "/api/vfd" -> handleVfd(session)
                session.uri == "/api/lights" -> json(JSONObject().apply { put("lights", HaLights.all()) })
                session.uri == "/api/lights/toggle" -> handleLightsToggle(session)
                session.uri == "/api/app/restart" -> handleAppRestart()
                session.uri == "/api/system/reboot" -> handleSystemReboot()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "ERR: ${e.message}")
        }
    }

    private fun status(): JSONObject = JSONObject().apply {
        put("app", "H96 HW Stream")
        put("version", "0.1.0")
        put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        put("android_sdk", android.os.Build.VERSION.SDK_INT)
        put("board", android.os.Build.BOARD)
        put("hardware", android.os.Build.HARDWARE)
        put("uptime_ms", android.os.SystemClock.elapsedRealtime())
    }

    private fun json(obj: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString(2))

    private fun forbidden(msg: String): Response =
        newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json",
            JSONObject().apply { put("error", msg); put("forbidden", true) }.toString())

    /** Capacita' richiesta per un endpoint HTTP sensibile (null = libero per chi non e' bloccato). */
    private fun capFor(session: IHTTPSession): String? = when (session.uri) {
        "/ptz" -> "ptz"
        "/stream/start", "/stream/stop" -> "stream"
        "/api/control/set" -> "image"
        "/api/snapshot/save", "/api/snapshot/hires" -> "snapshot"
        "/api/lights/toggle" -> "lights"
        "/api/app/restart", "/api/system/reboot" -> "system"
        // control transfer raw / diagnostica USB: potenzialmente distruttivi -> solo "system"
        "/api/rawset", "/api/rawget", "/api/probe", "/api/vfd", "/api/debug" -> "system"
        else -> null
    }

    /** Admin: imposta/azzera la policy di un'email. Parametri: email, blocked, admin, caps(CSV), remove. */
    private fun handleAccessSet(session: IHTTPSession): Response {
        @Suppress("DEPRECATION") val p = session.parms
        val email = p["email"] ?: return json(JSONObject().apply { put("error", "email mancante") })
        val remove = p["remove"] == "1"
        val blocked = p["blocked"]?.let { it == "1" }
        val admin = p["admin"]?.let { it == "1" }
        val caps = p["caps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val ok = AccessControl.setPolicy(email, blocked, admin, caps, remove)
        return json(JSONObject().apply { put("ok", ok) })
    }

    /** GET senza parametri = leggi; con ?gain=&gamma=[&ae=0|1] = imposta e PERSISTE la luma SW.
     *  Con ae=1 il gain e' automatico (AE software) e il parametro gain fa da target. */
    private fun handleLuma(session: IHTTPSession): Response {
        @Suppress("DEPRECATION") val g = session.parms["gain"]?.toIntOrNull()
        @Suppress("DEPRECATION") val gm = session.parms["gamma"]?.toIntOrNull()
        @Suppress("DEPRECATION") val ae = session.parms["ae"]?.let { it == "1" || it == "true" }
        var ok = true
        if (g != null && gm != null) ok = LumaControl.set(g, gm, ae ?: LumaControl.ae)
        return json(JSONObject().apply {
            put("ok", ok)
            put("gain", LumaControl.gain); put("gamma", LumaControl.gamma)
            put("ae", LumaControl.ae)
        })
    }

    /** SET_CUR raw: /api/rawset?unit=3&sel=1&hex=8101043f025fff (esperimenti OSD/VISCA). */
    private fun handleRawSet(session: IHTTPSession): Response {
        @Suppress("DEPRECATION") val u = session.parms["unit"]?.toIntOrNull() ?: 3
        @Suppress("DEPRECATION") val s = session.parms["sel"]?.toIntOrNull() ?: 1
        @Suppress("DEPRECATION") val hex = (session.parms["hex"] ?: "").replace(" ", "")
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.matches(Regex("[0-9a-fA-F]+")))
            return json(JSONObject().apply { put("ok", false); put("err", "hex non valido") })
        // Camera Terminal FOCUS_ABS/REL/AUTO/SIMPLE: un SET disattiva l'autofocus di fabbrica -> vietato
        if (u == 1 && s in setOf(0x06, 0x07, 0x08, 0x13))
            return json(JSONObject().apply { put("ok", false); put("err", "selettore fuoco vietato") })
        val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val rc = UvcNative.rawSet(u, s, data)
        return json(JSONObject().apply { put("ok", rc >= 0); put("rc", rc) })
    }

    private fun serveAsset(path: String, mime: String): Response {
        val ins: InputStream = ctx.assets.open(path)
        val r = newChunkedResponse(Response.Status.OK, mime, ins)
        // niente cache: il browser deve sempre prendere l'HTML/JS aggiornato (es. dopo update APK)
        r.addHeader("Cache-Control", "no-store, must-revalidate")
        return r
    }

    private fun handlePtz(session: IHTTPSession): Response {
        if (!UvcNative.opened) return json(JSONObject().apply { put("error", "libuvc not opened") })
        @Suppress("DEPRECATION")
        val op = session.parms["op"] ?: ""
        val success = PtzController.step(op)
        return json(PtzController.stateJson().apply { put("op", op); put("success", success) })
    }

    // ===== WebSocket controllo PTZ in tempo reale =====
    // Protocollo (JSON per messaggio testuale):
    //  FE->BE  {"t":"abs","pan":..,"tilt":..,"zoom":..}  posizione assoluta dal joystick
    //          {"t":"home"}                              ritorno al centro
    //          {"t":"afReset"}                           recovery camera (re-enum USB)
    // NB: nessun comando di FUOCO — la camera ha l'autofocus di fabbrica e il software
    // non lo tocca mai (un SET UVC sul fuoco lo disattiva).
    //          {"t":"ctrl","id":..,"value":..}           controllo immagine
    //          {"t":"ping"}                              keepalive
    //  BE->FE  {"pan":..,"tilt":..,"zoom":..}            echo stato (ack)
    //          {"limits":{...}}                          inviato all'apertura
    inner class PtzSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private val ident = AccessControl.identify(handshake.headers, handshake.remoteIpAddress)

        override fun onOpen() {
            if (AccessControl.isBlocked(ident)) {
                try { close(WebSocketFrame.CloseCode.PolicyViolation, "accesso bloccato", false) } catch (_: Exception) {}
                return
            }
            AccessControl.wsOpen(ident)
            StreamGate.touch()
            try {
                send(JSONObject().apply { put("limits", PtzController.limitsJson()) }.toString())
                send(PtzController.stateJson().toString())
                send(JSONObject().apply { put("presets", PtzController.presetsJson()) }.toString())
                send(JSONObject().apply { put("perms", AccessControl.whoamiJson(ident)) }.toString())
            } catch (_: IOException) {}
            startKeepalive(this)
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) { AccessControl.wsClose(ident) }
        override fun onException(exception: IOException?) { AccessControl.wsClose(ident); Log.w("HwStream", "ws ex: ${exception?.message}") }
        override fun onPong(pong: WebSocketFrame?) {}

        // Capacita' richiesta per ciascun tipo di comando WS (null = libero, es. ping/presetList).
        private fun capForMsg(t: String): String? = when (t) {
            "abs", "home" -> "ptz"
            "ctrl" -> "image"
            "presetSave", "presetDel", "presetRename", "presetGo" -> "presets"
            "afReset" -> "stream"
            else -> null
        }

        override fun onMessage(message: WebSocketFrame) {
            try {
                val o = JSONObject(message.textPayload)
                val t = o.optString("t")
                // Il ping e' SOLO keepalive del canale (evita la chiusura idle di Cloudflare): NON
                // conta come "viewer attivo", altrimenti un tab aperto in background terrebbe la cam
                // sempre sveglia e il power-save non scatterebbe mai. Tocca solo su azione reale.
                if (t != "ping") { StreamGate.touch(); AccessControl.touch(ident) }
                // gate permessi: comando negato -> avvisa il FE e ignora
                capForMsg(t)?.let { cap ->
                    if (!AccessControl.can(ident, cap)) {
                        try { send(JSONObject().apply { put("denied", cap) }.toString()) } catch (_: IOException) {}
                        return
                    }
                }
                when (t) {
                    // solo target: il SET USB e' coalescato lato PtzController (~12Hz)
                    "abs" -> PtzController.setTarget(o.optInt("pan"), o.optInt("tilt"), o.optInt("zoom"))
                    "home" -> { PtzController.home(); send(PtzController.stateJson().toString()) }
                    // recovery camera: re-enumerazione USB (link piantato / stream morto)
                    "afReset" -> {
                        val ok = UvcNative.resetCamera()
                        send(JSONObject().apply { put("afReset", true); put("ok", ok) }.toString())
                    }
                    "ctrl" -> UvcNative.setControl(o.optInt("id"), o.optInt("value"))
                    // preset di posizione ("multi-home"): salva/elimina/rinomina/vai
                    "presetSave" -> send(JSONObject().apply {
                        put("presets", PtzController.presetAdd(if (o.has("name")) o.optString("name") else null)) }.toString())
                    "presetDel" -> send(JSONObject().apply {
                        put("presets", PtzController.presetDel(o.optInt("id"))) }.toString())
                    "presetRename" -> send(JSONObject().apply {
                        put("presets", PtzController.presetRename(o.optInt("id"), o.optString("name"))) }.toString())
                    "presetGo" -> { PtzController.presetGo(o.optInt("id")); send(PtzController.stateJson().toString()) }
                    "presetList" -> send(JSONObject().apply { put("presets", PtzController.presetsJson()) }.toString())
                    "ping" -> send("{\"pong\":1}")
                }
            } catch (e: Exception) {
                Log.w("HwStream", "ws msg err: ${e.message}")
            }
        }
    }

    // ===== STREAM VIDEO CAMERA UVC (via libuvc / UvcNative) =====
    // Risoluzioni YUYV verificate dai descrittori SuperSpeed della 045e:8888.
    private val resolutions = listOf(
        1920 to 1080, 1280 to 720, 1024 to 768, 800 to 600, 1024 to 576, 960 to 540,
        704 to 576, 640 to 480, 576 to 448, 768 to 448, 640 to 360, 512 to 288, 352 to 288, 176 to 144
    )
    private val defaultW = 1280
    private val defaultH = 720
    private val defaultFps = 30

    private fun streamFormats(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for ((w, h) in resolutions) arr.put(JSONObject().apply { put("w", w); put("h", h) })
        return arr
    }

    private fun streamStats(): JSONObject = JSONObject().apply {
        put("loaded", UvcNative.loaded)
        put("opened", UvcNative.opened)
        put("streaming", UvcNative.streaming)
        put("width", UvcNative.width()); put("height", UvcNative.height())
        put("frames", UvcNative.frames()); put("seq", UvcNative.seq())
    }

    /** Sonda diagnostica SOLO LETTURA: non tocca StreamGate, camera o encoder.
     *  seq/ts permettono di calcolare gli fps reali confrontando due letture. */
    private fun debugProbe(): JSONObject = JSONObject().apply {
        val rt = Runtime.getRuntime()
        put("ts", System.currentTimeMillis())
        put("uptime_ms", android.os.SystemClock.elapsedRealtime())
        put("stream", streamStats())
        put("idle_ms", StreamGate.idleMs())
        put("video_ws_clients", VideoHub.clientCount())
        put("video_kbps", VideoHub.bitrateKbps())
        put("heap_used_mb", (rt.totalMemory() - rt.freeMemory()) / 1048576)
        put("heap_max_mb", rt.maxMemory() / 1048576)
        put("threads", Thread.activeCount())
        if (UvcNative.opened) {   // letture USB GET-only: nessun SET, nessun effetto sulla camera
            put("ptz_real", UvcNative.readPtz() ?: JSONObject.NULL)
            put("controls", UvcNative.controlsJson())
        }
        put("ptz_app", PtzController.stateJson())
        put("log", org.json.JSONArray(recentWarnings()))
    }

    /** Ultimi W/E del solo processo app (logcat del proprio pid: nessun permesso richiesto). */
    private fun recentWarnings(): List<String> = try {
        val pr = ProcessBuilder("logcat", "-d", "-t", "60", "--pid", android.os.Process.myPid().toString(), "*:W")
            .redirectErrorStream(true).start()
        val lines = pr.inputStream.bufferedReader().readLines()
        pr.waitFor(); lines.takeLast(60)
    } catch (e: Exception) { listOf("logcat: ${e.message}") }

    /** Avvia lo stream alla risoluzione richiesta (default se non passata). */
    private fun handleStreamStart(session: IHTTPSession): Response {
        if (!UvcNative.opened) return json(JSONObject().apply { put("error", "libuvc not opened") })
        @Suppress("DEPRECATION")
        val p = session.parms
        val w = p["w"]?.toIntOrNull() ?: defaultW
        val h = p["h"]?.toIntOrNull() ?: defaultH
        val fps = p["fps"]?.toIntOrNull() ?: defaultFps
        val ok = UvcNative.start(w, h, fps)
        StreamGate.touch()
        return json(JSONObject().apply { put("started", ok); put("w", w); put("h", h); put("fps", fps) })
    }

    /** Assicura che lo stream sia attivo (per snapshot/mjpeg on-demand). */
    private fun ensureStreaming() {
        if (UvcNative.opened && !UvcNative.streaming) {
            UvcNative.start(defaultW, defaultH, defaultFps)
            Thread.sleep(500)
        }
    }

    private fun yuyvToJpeg(yuy2: ByteArray, w: Int, h: Int, quality: Int = 80): ByteArray {
        // YuvImage non supporta YUY2 direttamente: converti YUYV -> NV21
        val nv21 = ByteArray(w * h * 3 / 2)
        var yi = 0
        // Y plane
        var p = 0
        for (i in 0 until w * h) { nv21[yi++] = yuy2[p]; p += 2 }
        // VU plane (NV21 = Y + interleaved V,U) sottocampionato 2x2
        var uvi = w * h
        for (row in 0 until h step 2) {
            var base = row * w * 2
            for (col in 0 until w step 2) {
                val u = yuy2[base + 1]
                val v = yuy2[base + 3]
                nv21[uvi++] = v
                nv21[uvi++] = u
                base += 4
            }
        }
        val yi2 = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yi2.compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    private fun serveSnapshot(): Response {
        if (!UvcNative.opened) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "libuvc not opened")
        StreamGate.touch()
        ensureStreaming()
        val f = UvcNative.latestFrame() ?: return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "no frame")
        val w = UvcNative.width()
        val h = UvcNative.height()
        if (f.size != w * h * 2) return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "frame size mismatch")
        val jpg = yuyvToJpeg(f, w, h)
        val r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpg), jpg.size.toLong())
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun serveMjpeg(): Response {
        if (!UvcNative.opened) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "libuvc not opened")
        StreamGate.touch()
        ensureStreaming()
        val boundary = "hwstreamframe"
        val ins = object : InputStream() {
            private var buf: ByteArray = ByteArray(0)
            private var off = 0
            private var lastSeq = -1L
            override fun read(): Int {
                val b = ByteArray(1); val n = read(b, 0, 1); return if (n <= 0) -1 else b[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, o: Int, l: Int): Int {
                while (off >= buf.size) {
                    var seq = UvcNative.seq()
                    var tries = 0
                    while (seq == lastSeq && tries < 200) { Thread.sleep(10); seq = UvcNative.seq(); tries++ }
                    val f = UvcNative.latestFrame() ?: return -1
                    lastSeq = seq
                    StreamGate.touch()   // viewer MJPEG attivo: tieni vivo lo stream
                    val w = UvcNative.width()
                    val h = UvcNative.height()
                    if (f.size != w * h * 2) return -1   // frame size mismatch: scarta
                    val jpg = yuyvToJpeg(f, w, h, 70)
                    val head = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.size}\r\n\r\n"
                    val out = ByteArrayOutputStream()
                    out.write(head.toByteArray()); out.write(jpg); out.write("\r\n".toByteArray())
                    buf = out.toByteArray(); off = 0
                }
                val n = minOf(l, buf.size - off)
                System.arraycopy(buf, off, b, o, n); off += n
                return n
            }
        }
        val r = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$boundary", ins)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    /**
     * Snapshot adattivo a risoluzione massima: prova 1920x1080, fallback a 1280x720 se timeout.
     * Risoluzione corrente -> target hires, aspetta frame valido, converti JPEG, ripristina.
     * Serializzato via lock per evitare race col watchdog e richieste concorrenti.
     */
    private val hiresLock = java.lang.Object()
    private fun handleSnapshotHires(): Response {
        if (!UvcNative.opened) return json(JSONObject().apply { put("error", "libuvc not opened") })
        synchronized(hiresLock) {
            StreamGate.touch()
            // Salva risoluzione corrente
            val prevW = UvcNative.width()
            val prevH = UvcNative.height()
            val prevFps = 25  // FPS per il ripristino
            val responseRef: Array<Response?> = arrayOf(null)

            try {
                // Lista target: 1920x1080 (8s timeout), fallback 1280x720 (5s timeout)
                val targets = listOf(
                    Triple(1920, 1080, 80),  // 80 * 100ms = 8s
                    Triple(1280, 720, 50)   // 50 * 100ms = 5s
                )

                var frameOk = false
                var successW = 0
                var successH = 0
                var frameData: ByteArray? = null

                // Prova ogni risoluzione target fino a che non ottieni un frame
                for ((targetW, targetH, maxAttempts) in targets) {
                    if (frameOk) break

                    // Passa a targetW x targetH
                    UvcNative.start(targetW, targetH, prevFps)

                    // Aspetta un frame valido con il timeout specificato
                    for (attempt in 1..maxAttempts) {
                        val f = UvcNative.latestFrame()
                        if (f != null && f.size == targetW * targetH * 2) {
                            frameOk = true
                            successW = targetW
                            successH = targetH
                            frameData = f
                            break
                        }
                        Thread.sleep(100)
                    }
                }

                // Nessuna risoluzione ha dato frame: errore
                if (!frameOk || frameData == null) {
                    responseRef[0] = json(JSONObject().apply { put("error", "frame timeout") })
                    return responseRef[0]!!
                }

                // Converti frame in JPEG q90 con le dimensioni ottenute
                val jpg = yuyvToJpeg(frameData, successW, successH, 90)

                StreamGate.touch()
                val r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpg), jpg.size.toLong())
                r.addHeader("Cache-Control", "no-store")
                responseRef[0] = r
                return r
            } catch (e: Exception) {
                responseRef[0] = json(JSONObject().apply { put("error", e.message ?: "unknown error") })
                return responseRef[0]!!
            } finally {
                // Ripristino robusto su TUTTI i path di uscita (successo, timeout, eccezione)
                try {
                    if (prevW > 0 && prevH > 0) {
                        // Stream era attivo a prevW x prevH: ripristina quella risoluzione
                        UvcNative.start(prevW, prevH, prevFps)
                    } else {
                        // Stream era in idle (prevW <= 0): torna in idle power-save
                        UvcNative.stop()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** Salva uno snapshot JPEG su storage app (no permessi): getExternalFilesDir/snapshots. */
    private fun handleSnapshotSave(): Response {
        if (!UvcNative.opened) return json(JSONObject().apply { put("error", "libuvc not opened") })
        ensureStreaming()
        val f = UvcNative.latestFrame() ?: return json(JSONObject().apply { put("error", "no frame") })
        val jpg = yuyvToJpeg(f, UvcNative.width(), UvcNative.height(), 90)
        return try {
            val dir = java.io.File(ctx.getExternalFilesDir(null), "snapshots").apply { mkdirs() }
            val out = java.io.File(dir, "snap_${System.currentTimeMillis()}.jpg")
            out.writeBytes(jpg)
            json(JSONObject().apply { put("saved", true); put("path", out.absolutePath); put("bytes", jpg.size) })
        } catch (e: Exception) {
            json(JSONObject().apply { put("error", e.message) })
        }
    }

    /**
     * Riavvia l'app: lancia via root un `am start` ritardato (la nostra forced-stop uccide il
     * processo prima del kill self), poi termina questo processo. Il watchdog/foreground service
     * riparte da capo all'avvio della nuova istanza.
     */
    private fun handleAppRestart(): Response {
        val pkg = ctx.packageName
        Thread {
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            try {
                // root: forza stop + riavvio attivita' (detached, sopravvive al kill self).
                // Aspetta che il processo su sia partito (accede stdin/stdout) prima di killProcess:
                // la sequenza shell "sleep 1; ..." deve avere tempo di entrare in esecuzione root
                // come processo figlio detached prima che questo processo muoia.
                val p = Runtime.getRuntime().exec(arrayOf(
                    "/system/xbin/su", "0", "sh", "-c",
                    "sleep 1; am force-stop $pkg; am start -n $pkg/.MainActivity"
                ))
                // Chiudi stream per sganciarsi: su avrà fatto il fork/exec entro ~100ms
                p.inputStream?.close()
                p.errorStream?.close()
                p.outputStream?.close()
            } catch (e: Exception) {
                Log.w("HwStream", "restart root failed: ${e.message}")
            }
            // Aspetta che il comando su sia ben avviato (1.5s > 100ms start + margine)
            try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            UvcNative.stop()
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
        return json(JSONObject().apply { put("restarting", true) })
    }

    /** Riavvia Android (root). svc power reboot e' il path pulito su questo box userdebug. */
    private fun handleSystemReboot(): Response {
        Thread {
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            try {
                Runtime.getRuntime().exec(arrayOf(
                    "/system/xbin/su", "0", "sh", "-c", "svc power reboot || reboot"
                ))
            } catch (e: Exception) {
                Log.w("HwStream", "reboot root failed: ${e.message}")
            }
        }.start()
        return json(JSONObject().apply { put("rebooting", true) })
    }

    /**
     * Diagnostica VFD: /api/vfd?init=1&open=1&mode=&a=&b=&pwr= — chiama le funzioni vendor con
     * valori arbitrari per scoprire l'encoding del display guardando il box.
     * Es: /api/vfd?init=1&open=1&pwr=1&a=1234&b=0
     */
    private fun handleVfd(session: IHTTPSession): Response {
        @Suppress("DEPRECATION") val p = session.parms
        fun list(k: String) = (p[k] ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }
        val nrs = list("nr"); val vals = list("val")
        if (nrs.isEmpty()) return json(JSONObject().apply { put("error", "nr mancante, es: /api/vfd?nr=7,5,9&val=1,0,1") })
        return json(JSONObject().apply { put("vfd", VfdControl.raw(nrs, vals)) })
    }

    private fun handleControlSet(session: IHTTPSession): Response {
        @Suppress("DEPRECATION")
        val p = session.parms
        val id = p["id"]?.toIntOrNull() ?: return json(JSONObject().apply { put("error", "id mancante") })
        val value = p["value"]?.toIntOrNull() ?: return json(JSONObject().apply { put("error", "value mancante") })
        val ok = UvcNative.setControl(id, value)
        return json(JSONObject().apply { put("id", id); put("value", value); put("success", ok) })
    }

    /**
     * Toggle una luce Home Assistant: /api/lights/toggle?id=<entityId>
     * Esegue la chiamata su thread di lavoro per non bloccare l'HTTP.
     */
    private fun handleLightsToggle(session: IHTTPSession): Response {
        @Suppress("DEPRECATION")
        val entityId = session.parms["id"] ?: return json(JSONObject().apply { put("error", "id mancante") })
        if (!HaLights.isKnown(entityId)) return json(JSONObject().apply { put("error", "luce non configurata") })
        val responseRef: Array<JSONObject?> = arrayOf(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        Thread {
            try {
                val prevState = HaLights.state(entityId)
                val ok = HaLights.toggle(entityId)
                var newState = "unknown"
                if (ok) {
                    var tries = 0
                    while (tries < 3) {
                        Thread.sleep(400)
                        newState = HaLights.state(entityId)
                        if (newState != prevState && newState != "unknown") break
                        tries++
                    }
                }
                responseRef[0] = JSONObject().apply {
                    put("ok", ok)
                    put("state", newState)
                }
            } catch (e: Exception) {
                responseRef[0] = JSONObject().apply {
                    put("error", e.message ?: "unknown error")
                }
            } finally {
                latch.countDown()
            }
        }.start()
        try {
            if (!latch.await(6, java.util.concurrent.TimeUnit.SECONDS)) {
                return json(JSONObject().apply { put("error", "timeout") })
            }
        } catch (e: InterruptedException) {
            return json(JSONObject().apply { put("error", "interrupted") })
        }
        return json(responseRef[0] ?: JSONObject().apply { put("error", "no response") })
    }

}
