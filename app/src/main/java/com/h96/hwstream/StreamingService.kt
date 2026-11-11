package com.h96.hwstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Servizio foreground sempre attivo: tiene su il WebServer e (in seguito)
 * la pipeline video HW. Riavviato dal sistema se ucciso (START_STICKY).
 */
class StreamingService : Service() {

    companion object {
        const val TAG = "HwStream"
        const val PORT = 8080
        private const val CH_ID = "hwstream"
        private const val NOTIF_ID = 1
    }

    private var server: WebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var uvc: UvcControl? = null

    @Volatile private var watchdogRunning = false
    private var watchdog: Thread? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("avvio..."))
        acquireWakeLock()
        startServer()
        startWatchdog()
    }

    /**
     * Watchdog dello stream + status LED (gira ogni ~3s):
     *  1) se lo stream e' attivo ma `seq` non avanza (link USB caduto, frame strappati),
     *     tenta un restart leggero; se persiste, fa il reset USB completo (resetCamera).
     *  2) tiene il pannello LED SEMPRE spento (inutile) ri-affermando off ad ogni ciclo.
     */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        watchdog = Thread {
            var lastSeq = -1L
            var stall = 0
            var tick = 0
            LedControl.allOff()   // pannello spento all'avvio (risparmio)
            while (watchdogRunning) {
                try {
                    if (UvcNative.streaming) {
                        // IDLE: nessun viewer (video WS / MJPEG / snapshot) da >3min -> stop (risparmio)
                        if (VideoHub.clientCount() == 0 && StreamGate.idleMs() > StreamGate.IDLE_MS) {
                            Log.i(TAG, "idle >3min senza viewer: stop stream (risparmio)")
                            UvcNative.stop(); lastSeq = -1; stall = 0
                        } else {
                            val seq = UvcNative.seq()
                            if (seq == lastSeq) {
                                stall++
                                when (stall) {
                                    3 -> {
                                        Log.w(TAG, "stream stallo: restart leggero")
                                        // restart fallito -> streaming=false e questo ramo non girerebbe piu': reset subito
                                        if (!UvcNative.restart()) { Log.w(TAG, "restart fallito: reset USB"); UvcNative.resetCamera(); stall = 0 }
                                    }
                                    6 -> { Log.w(TAG, "stream stallo persistente: reset USB"); UvcNative.resetCamera(); stall = 0 }
                                }
                            } else { stall = 0; lastSeq = seq }
                        }
                    } else { lastSeq = -1; stall = 0 }
                    // camera assente al boot o persa dopo un reset fallito: riprova ogni ~30s
                    if (!UvcNative.opened && ++tick % 10 == 0) {
                        uvc?.unbindKernelDriver()
                        if (UvcNative.openCamera(applicationContext)) {
                            Log.i(TAG, "camera trovata: libuvc aperto")
                            PtzController.restore()
                        }
                    }
                    LedControl.allOff()   // pannello sempre spento (inutile): ri-afferma off
                } catch (e: Exception) { Log.w(TAG, "watchdog: ${e.message}") }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "stream-watchdog"; isDaemon = true; start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // se per qualche motivo il server e' giu', rialzalo
        if (server == null) startServer()
        return START_STICKY
    }

    private fun startServer() {
        try {
            uvc = UvcControl(applicationContext)
            // Stacca uvcvideo (root, NON serve permesso USB) cosi' i control transfer su ep0
            // passano e libuvc resta unico proprietario del device.
            uvc!!.unbindKernelDriver()
            // libuvc apre la propria connessione. Su box rootato openCamera usa il FALLBACK
            // ROOT-FD se manca il permesso UsbManager -> nessun dialog, ne' al boot ne' dopo.
            PtzController.attach(applicationContext)
            LumaControl.attach(applicationContext)   // luma software persistita (LUT nativa)
            AccessControl.attach(applicationContext)
            if (UvcNative.openCamera(applicationContext)) {
                Log.i(TAG, "libuvc (UvcNative) opened as sole owner")
                PtzController.restore()   // ripristina l'ultima inquadratura (motore senza readback)
            } else {
                Log.w(TAG, "UvcNative.openCamera failed (loaded=${UvcNative.loaded})")
            }

            server = WebServer(applicationContext, PORT, uvc).also {
                it.start(NanoTimeouts.SOCKET_READ_TIMEOUT, false)
            }
            val ip = localIp()
            Log.i(TAG, "WebServer up: http://$ip:$PORT")
            updateNotification("UI: http://$ip:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "startServer failed", e)
            updateNotification("errore: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:wl").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @Suppress("DEPRECATION")
    private fun localIp(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wm.connectionInfo.ipAddress
            if (ipInt != 0) {
                "%d.%d.%d.%d".format(
                    ipInt and 0xff, ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff, ipInt shr 24 and 0xff
                )
            } else ethernetIp() ?: "0.0.0.0"
        } catch (e: Exception) {
            ethernetIp() ?: "0.0.0.0"
        }
    }

    /** Le TV box di solito sono in ethernet: cerca l'IPv4 non-loopback. */
    private fun ethernetIp(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        } catch (e: Exception) { null }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "HW Stream", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH_ID) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("H96 HW Stream")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        watchdogRunning = false
        watchdog?.interrupt()
        LedControl.allOff()
        server?.stop()
        uvc?.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Timeout socket NanoHTTPD (ms). */
object NanoTimeouts {
    const val SOCKET_READ_TIMEOUT = 10_000
}

/**
 * Registro attivita' viewer: ogni client web (MJPEG/video WS/snapshot/start, e UI PTZ) chiama
 * [touch]. Se nessun viewer per > [IDLE_MS] il watchdog ferma lo stream (risparmio); alla
 * riattivazione (nuovo client) lo stream riparte.
 */
object StreamGate {
    const val IDLE_MS = 180_000L   // 3 minuti
    @Volatile private var lastTouch = System.currentTimeMillis()
    fun touch() { lastTouch = System.currentTimeMillis() }
    fun idleMs() = System.currentTimeMillis() - lastTouch
}
