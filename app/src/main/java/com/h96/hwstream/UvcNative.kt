package com.h96.hwstream

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Wrapper Kotlin sul bridge JNI libuvc (`libuvccam.so`).
 * Apre la camera dal file descriptor di UsbManager (uvc_wrap), streamma YUYV,
 * e pilota pan/tilt/zoom sullo stesso handle libusb (provato: cattura + PTZ insieme).
 *
 * Prerequisiti dal chiamante: uvcvideo gia' unbindato (UvcControl) e una
 * UsbDeviceConnection aperta sulla 045e:8888 da cui prendere `fileDescriptor`.
 */
object UvcNative {
    private const val TAG = "HwStream"

    @Volatile var loaded = false; private set
    @Volatile var opened = false; private set
    @Volatile var streaming = false; private set

    init {
        loaded = try { System.loadLibrary("uvccam"); true }
        catch (e: Throwable) { Log.e(TAG, "loadLibrary uvccam: ${e.message}"); false }
    }

    private external fun nativeOpen(fd: Int): Int
    private external fun nativeStart(w: Int, h: Int, fps: Int): Int
    private external fun nativeReset(): Int
    private external fun nativeStop()
    private external fun nativeClose()
    private external fun nativeSetPanTilt(pan: Int, tilt: Int): Int
    private external fun nativeSetZoom(zoom: Int): Int
    private external fun nativeGetFrame(out: ByteArray): Int
    private external fun nativeGetFrameNV12(out: ByteArray): Int
    private external fun nativeSetLuma(gain100: Int, gamma100: Int, ae: Boolean): Int
    private external fun nativeRawSet(unit: Int, sel: Int, data: ByteArray): Int
    private external fun nativeRawGet(unit: Int, sel: Int, len: Int): String
    private external fun nativeGetCtrl(id: Int, req: Int): Int
    private external fun nativeSetCtrl(id: Int, value: Int): Int
    private external fun nativeProbe(unit: Int): String
    private external fun nativeSeq(): Long
    private external fun nativeFrames(): Long
    private external fun nativeWidth(): Int
    private external fun nativeHeight(): Int
    private external fun nativeVfdIoctl(nr: Int, value: Int): Int

    /** ioctl raw sul display VFD (/dev/digital_dis). Vedi VfdControl. 0=ok, <0=-errno. */
    fun vfdIoctl(nr: Int, value: Int): Int = if (loaded) nativeVfdIoctl(nr, value) else -9999

    // Owner UNICO del device: libuvc deve avere la USB tutta per se' (control + stream).
    private var conn: UsbDeviceConnection? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var lastW = 1280; @Volatile private var lastH = 720; @Volatile private var lastFps = 30

    /**
     * Apre la camera come unico proprietario: UsbManager.openDevice -> fd -> uvc_wrap.
     * Prerequisito: uvcvideo gia' unbindato (lo fa UvcControl) e permesso USB concesso.
     */
    @Synchronized
    fun openCamera(ctx: Context): Boolean {
        if (!loaded) return false
        if (opened) return true
        appCtx = ctx.applicationContext   // prima del lookup: resetCamera deve poter agire anche se il primo open fallisce
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = UvcControl.findCamera(um) ?: run { Log.w(TAG, "nessuna camera UVC collegata"); return false }
        camDev = dev
        // fd: via UsbManager se c'e' il permesso, altrimenti FALLBACK ROOT sul nodo usbfs
        // (box rootato) -> niente dialog di permesso, ne' al boot ne' a ogni avvio.
        val fd: Int
        if (um.hasPermission(dev)) {
            val c = um.openDevice(dev) ?: run { Log.e(TAG, "openDevice null"); return false }
            conn = c
            fd = c.fileDescriptor
        } else {
            fd = openViaRoot(dev.deviceName)   // dev.deviceName = "/dev/bus/usb/004/002"
            if (fd < 0) {
                // device non rootato: dialog permesso USB standard (il watchdog riprova ogni ~30s)
                val now = System.currentTimeMillis()
                if (now - lastPermAsk > 60_000) { lastPermAsk = now; UvcControl(ctx).requestPermission() }
                Log.w(TAG, "permesso USB mancante (niente root): richiesto all'utente"); return false
            }
            Log.i(TAG, "USB aperto via root-fd (no dialog): ${dev.deviceName}")
        }
        val r = nativeOpen(fd)
        opened = (r == 0)
        if (!opened) { Log.e(TAG, "nativeOpen failed: $r"); conn?.close(); conn = null; closeRootFd() }
        return opened
    }

    @Volatile private var camDev: android.hardware.usb.UsbDevice? = null
    private var lastPermAsk = 0L

    /** Apre il nodo usbfs come root (chmod 666 + ParcelFileDescriptor). -1 se fallisce. */
    private var rootPfd: ParcelFileDescriptor? = null
    private fun openViaRoot(devNode: String): Int {
        return try {
            Runtime.getRuntime().exec(arrayOf("/system/xbin/su", "0", "chmod", "666", devNode)).waitFor()
            val pfd = ParcelFileDescriptor.open(File(devNode), ParcelFileDescriptor.MODE_READ_WRITE)
            rootPfd = pfd
            pfd.fd
        } catch (e: Exception) { Log.e(TAG, "openViaRoot: ${e.message}"); -1 }
    }
    private fun closeRootFd() { try { rootPfd?.close() } catch (_: Throwable) {}; rootPfd = null }

    @Synchronized
    fun start(w: Int, h: Int, fps: Int): Boolean {
        if (!opened) return false
        if (streaming) nativeStop()
        val r = nativeStart(w, h, fps)
        streaming = (r == 0)
        if (streaming) {
            if (w != lastW || h != lastH) VideoHub.kickAll()
            lastW = w; lastH = h; lastFps = fps
        }
        return streaming
    }

    /**
     * Recovery della camera via re-enumerazione USB VERA (toggle di .../authorized via root):
     * rimette in piedi un link USB piantato e fa ripartire il firmware da zero (autofocus
     * di fabbrica incluso). Sequenza: chiudi handle -> authorized 0/1 sul device 045e:8888
     * -> uvcvideo si ri-binda, ri-staccalo -> reopen + restart stream.
     */
    @Synchronized
    fun resetCamera(): Boolean {
        val ctx = appCtx ?: return false
        val wasW = if (lastW > 0) lastW else 1280
        val wasH = if (lastH > 0) lastH else 720
        val wasFps = if (lastFps > 0) lastFps else 30
        try { nativeClose() } catch (_: Throwable) {}
        opened = false; streaming = false
        conn?.close(); conn = null
        closeRootFd()
        reauthorizeUsb()                 // re-enumerazione vera -> firmware riparte in AF
        Thread.sleep(3000)               // attesa re-enumerazione + ricomparsa device
        // Dopo la re-enumerazione uvcvideo si ri-binda e il nodo usbfs cambia: ritenta
        // unbind + open finche' il device non e' pronto (USB fragile su questo box).
        var ok = false
        for (attempt in 1..4) {
            try { UvcControl(ctx).unbindKernelDriver() } catch (e: Exception) { Log.w(TAG, "re-unbind#$attempt: ${e.message}") }
            Thread.sleep(500)
            if (openCamera(ctx)) { ok = true; break }
            Log.w(TAG, "reopen tentativo $attempt fallito"); Thread.sleep(900)
        }
        if (!ok) { Log.e(TAG, "reopen dopo re-enum fallito"); return false }
        return start(wasW, wasH, wasFps)
    }

    /** Toggle di /sys/.../authorized sul device 045e:8888 (root): re-enumerazione fisica. */
    private fun reauthorizeUsb() {
        try {
            val cam = camDev ?: return
            val cmd = "for d in /sys/bus/usb/devices/*; do " +
                "if ${UvcControl.sysfsMatch(cam)}; then " +
                "echo 0 > \"\$d/authorized\"; sleep 2; echo 1 > \"\$d/authorized\"; fi; done"
            Runtime.getRuntime().exec(arrayOf("/system/xbin/su", "0", "sh", "-c", cmd)).waitFor()
            Log.i(TAG, "USB re-authorized (firmware camera ripartito)")
        } catch (e: Exception) { Log.e(TAG, "reauthorizeUsb: ${e.message}") }
    }

    /** Riavvia lo stream con l'ultima risoluzione usata (recovery leggero del watchdog). */
    @Synchronized
    fun restart(): Boolean { if (!opened) return false; return start(lastW, lastH, lastFps) }

    @Synchronized
    fun stop() { if (streaming) { nativeStop(); streaming = false } }

    @Synchronized
    fun close() {
        stop()
        if (opened) { nativeClose(); opened = false }
        conn?.close(); conn = null
        closeRootFd()
    }

    @Synchronized  // stesso lock di reset/close: niente uso di g_devh durante uvc_close
    fun setPanTilt(pan: Int, tilt: Int): Boolean = opened && nativeSetPanTilt(pan, tilt) == 0
    @Synchronized  // stesso lock di reset/close: niente uso di g_devh durante uvc_close
    fun setZoom(zoom: Int): Boolean = opened && nativeSetZoom(zoom) == 0

    /**
     * Copia l'ultimo frame YUYV (alloca un buffer adatto). null se non pronto.
     * Guard su `opened` (non `streaming`): durante un restart leggero del watchdog `streaming`
     * e' false per pochi ms ma il buffer nativo regge l'ultimo frame valido — cosi' il loop MJPEG
     * continua a servirlo invece di chiudere la connessione HTTP (= frame nero/freeze lato browser).
     */
    fun latestFrame(): ByteArray? {
        if (!opened) return null
        val w = nativeWidth(); val h = nativeHeight()
        if (w <= 0 || h <= 0) return null
        val buf = ByteArray(w * h * 2)
        val n = nativeGetFrame(buf)
        return if (n > 0) buf else null
    }

    /** Riempie 'nv12' (>= w*h*3/2) con l'ultimo frame convertito in NV12. Byte scritti o <=0. */
    fun frameNV12(nv12: ByteArray): Int = if (opened) nativeGetFrameNV12(nv12) else 0

    private const val UNSUPPORTED = -0x80000000 // 0x80000000 = INT_MIN dal JNI

    // id -> (nome, isToggle). Ordine = visualizzazione UI.
    // NB: nessun controllo di fuoco — la camera ha l'autofocus di fabbrica e il software
    // non lo tocca mai (un SET UVC sul fuoco lo butta in manuale e lo rompe).
    private val controlDefs = listOf(
        Triple(1, "brightness", false), Triple(2, "contrast", false),
        Triple(4, "saturation", false), Triple(3, "hue", false),
        Triple(5, "sharpness", false), Triple(6, "gamma", false),
        Triple(7, "white_balance", false), Triple(8, "white_balance_auto", true),
        Triple(11, "gain", false), Triple(12, "backlight", false),
        Triple(13, "power_line_freq", false), Triple(14, "zoom", false)
    )

    /** Sonda i controlli supportati dalla cam e ritorna min/max/cur/def per ognuno. */
    @Synchronized  // stesso lock di reset/close: niente uso di g_devh durante uvc_close
    fun controlsJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        if (!opened) return arr
        for ((id, name, toggle) in controlDefs) {
            val cur = nativeGetCtrl(id, 0)
            if (cur == UNSUPPORTED) continue // non supportato dalla cam
            val o = org.json.JSONObject()
            o.put("id", id); o.put("name", name); o.put("toggle", toggle); o.put("cur", cur)
            if (toggle) { o.put("min", 0); o.put("max", 1) }
            else {
                val mn = nativeGetCtrl(id, 1); val mx = nativeGetCtrl(id, 2); val df = nativeGetCtrl(id, 3)
                o.put("min", if (mn == UNSUPPORTED) 0 else mn)
                o.put("max", if (mx == UNSUPPORTED) 255 else mx)
                if (df != UNSUPPORTED) o.put("def", df)
            }
            arr.put(o)
        }
        return arr
    }

    @Synchronized  // stesso lock di reset/close: niente uso di g_devh durante uvc_close
    fun setControl(id: Int, value: Int): Boolean = opened && nativeSetCtrl(id, value) == 0

    /** Luminosita'/gamma SOFTWARE sul canale Y in pipeline (100/100 = neutro).
     *  Con ae=true il gain e' automatico (AE software) e gain100 fa da target. */
    fun setLuma(gain100: Int, gamma100: Int, ae: Boolean): Boolean =
        loaded && nativeSetLuma(gain100, gamma100, ae) == 0

    /** SET_CUR raw (esperimenti OSD/VISCA su Extension Unit). Ritorna rc libusb. */
    @Synchronized  // stesso lock di reset/close: niente uso di g_devh durante uvc_close
    fun rawSet(unit: Int, sel: Int, data: ByteArray): Int =
        if (opened) nativeRawSet(unit, sel, data) else -1000

    /** GET_CUR raw: "rc:HEX". */
    @Synchronized  // stesso lock di reset/close: niente uso di g_devh durante uvc_close
    fun rawGet(unit: Int, sel: Int, len: Int): String =
        if (opened) nativeRawGet(unit, sel, len) else "-1000:"
    /** Posizione REALE dal firmware (GET_CUR PANTILT_ABS 0x0D + ZOOM_ABS 0x0B), null se non leggibile. */
    fun readPtz(): org.json.JSONObject? {
        val pt = rawGet(1, 0x0D, 8); val z = rawGet(1, 0x0B, 2)
        fun le(hex: String, off: Int, n: Int): Long {
            var v = 0L; for (k in n - 1 downTo 0) v = (v shl 8) or hex.substring((off + k) * 2, (off + k) * 2 + 2).toLong(16); return v
        }
        if (!pt.startsWith("8:") || !z.startsWith("2:")) return null
        val h = pt.substring(2); val hz = z.substring(2)
        return org.json.JSONObject().apply {
            put("pan", le(h, 0, 4).toInt()); put("tilt", le(h, 4, 4).toInt()); put("zoom", le(hz, 0, 2).toInt())
        }
    }

    @Synchronized  // stesso lock di reset/close: niente uso di g_devh durante uvc_close
    fun probe(unit: Int): String = if (opened) nativeProbe(unit) else "not opened"

    fun seq(): Long = if (opened) nativeSeq() else 0
    fun frames(): Long = if (opened) nativeFrames() else 0
    fun width(): Int = nativeWidth()
    fun height(): Int = nativeHeight()
}
