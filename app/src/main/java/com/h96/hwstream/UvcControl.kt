package com.h96.hwstream

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import org.json.JSONObject

class UvcControl(private val ctx: Context) {

    companion object {
        const val TAG = "HwStream"
        const val ACTION_USB_PERMISSION = "com.h96.hwstream.USB_PERMISSION"
        // Qualsiasi camera UVC (classe video 0x0E); la prima trovata. Testata su 045e:8888.
        fun findCamera(um: UsbManager): UsbDevice? = um.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 0x0E }
        }
        /** Script sh che itera sui device sysfs della camera (vid/pid in hex 4 cifre). */
        fun sysfsMatch(d: UsbDevice) = "[ \"\$(cat \$d/idVendor 2>/dev/null)\" = \"%04x\" ] && [ \"\$(cat \$d/idProduct 2>/dev/null)\" = \"%04x\" ]"
            .format(d.vendorId, d.productId)

        private const val CC_VIDEO = 0x0E
        private const val SC_VIDEOCONTROL = 0x01
        private const val CS_INTERFACE = 0x24
        private const val VC_INPUT_TERMINAL = 0x02
        private const val VC_PROCESSING_UNIT = 0x05
        private const val ITT_CAMERA = 0x0201

        private const val CT_PANTILT_RELATIVE_CONTROL = 0x0E
        private const val CT_ZOOM_RELATIVE_CONTROL = 0x0C
        private const val CT_ZOOM_ABSOLUTE_CONTROL = 0x0B
        private const val CT_PANTILT_ABSOLUTE_CONTROL = 0x0D

        // false = non staccare uvcvideo (test ep0 senza fight col cameraserver)
        private const val CLAIM_INTERFACES = false
    }

    private var usbManager: UsbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var vcInterface = -1

    /** Espone la connessione USB gia' aperta (uvcvideo unbindato) per lo streaming video. */
    fun sharedConnection(): UsbDeviceConnection? = connection
    fun sharedDevice(): UsbDevice? = device
    fun isOpen(): Boolean = connection != null
    fun rawConfigDescriptor(): ByteArray? = rawConfig
    private var rawConfig: ByteArray? = null
    /** File descriptor della connessione USB aperta, per libuvc (uvc_wrap). -1 se chiusa. */
    fun connectionFd(): Int = connection?.fileDescriptor ?: -1
    private var ctId = -1
    private var puId = -1
    private var ctBmControls = 0L
    private var puBmControls = 0L

    // Stato posizione ASSOLUTA (schema provato funzionante su ptz.py).
    // Pan/Tilt in arcosecondi UVC (gradi*3600). Zoom raw.
    private var curPan = 0
    private var curTilt = 0
    private var curZoom = 0
    private var panMin = -612000;  private var panMax = 612000    // +-170 gradi
    private var tiltMin = -108000; private var tiltMax = 324000   // -30..90 gradi
    private var zoomMin = 0;       private var zoomMax = 16384
    // step per click: 5 gradi pan/tilt, 1024 zoom
    private val panStep = 5 * 3600
    private val tiltStep = 5 * 3600
    private val zoomStep = 1024

    private data class ControlRange(
        val min: Int,
        val max: Int,
        val res: Int,
        val def: Int,
        val cur: Int
    )

    private val controlRanges = mutableMapOf<String, ControlRange>()

    fun hasPermission(): Boolean {
        val dev = findDevice() ?: return false
        return usbManager.hasPermission(dev)
    }

    fun requestPermission() {
        val dev = findDevice() ?: run {
            Log.w(TAG, "USB device not found for permission request")
            return
        }
        // Android 12+: serve FLAG_MUTABLE (il sistema inietta l'esito) + Intent esplicito
        val flags = if (android.os.Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val intent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            flags
        )
        usbManager.requestPermission(dev, intent)
        Log.i(TAG, "USB permission requested for UVC camera")
    }

    fun open(): Boolean {
        val dev = findDevice() ?: run {
            Log.w(TAG, "USB device UVC not found")
            return false
        }
        device = dev

        if (!usbManager.hasPermission(dev)) {
            Log.w(TAG, "No USB permission for UVC camera")
            return false
        }

        // ROOT: stacca uvcvideo dalla camera così il control transfer su ep0 (interfaccia
        // VideoControl) non viene piu' bloccato dal kernel/cameraserver. Senza questo i
        // SET_CUR UVC tornano -1. Box userdebug: `su 0` concede root anche all'app.
        unbindKernelDriver()

        connection = usbManager.openDevice(dev) ?: run {
            Log.e(TAG, "Failed to open USB device")
            return false
        }

        if (!parseDescriptors()) {
            Log.e(TAG, "Failed to parse UVC descriptors")
            close()
            return false
        }

        // TEST no-root: NON claimare (niente detach di uvcvideo, niente fight col cameraserver).
        // I control transfer su endpoint 0 non richiedono claim.
        if (CLAIM_INTERFACES) {
            for (i in 0 until dev.interfaceCount) {
                val intf = dev.getInterface(i)
                val claimed = connection!!.claimInterface(intf, true)
                Log.i(TAG, "claimInterface(id=${intf.id} class=${intf.interfaceClass}) force=true -> $claimed")
            }
        } else {
            Log.i(TAG, "skip claimInterface (test ep0 senza detach)")
        }

        Log.i(TAG, "UVC device opened: ctId=$ctId, puId=$puId, vcInterface=$vcInterface")
        Log.i(TAG, "ctBmControls=0x${ctBmControls.toString(16).padStart(16, '0')}, puBmControls=0x${puBmControls.toString(16).padStart(16, '0')}")
        initPtzState()
        return true
    }

    fun close() {
        connection?.close()
        connection = null
        device = null
    }

    /**
     * Stacca il driver kernel uvcvideo dalle interfacce della camera 045e:8888 via root (`su 0`).
     * Necessario sul box: con uvcvideo (e il cameraserver) che possiede la VideoControl,
     * i controlTransfer SET_CUR su ep0 tornano -1. Idempotente: se gia' staccato, no-op.
     */
    fun unbindKernelDriver() {
        val cam = findDevice() ?: return
        val script =
            "for d in /sys/bus/usb/devices/*; do " +
            "${sysfsMatch(cam)} || continue; " +
            "for i in \$d/*:*.*; do " +
            "drv=\$(basename \$(readlink \$i/driver 2>/dev/null) 2>/dev/null); " +
            "if [ \"\$drv\" = \"uvcvideo\" ]; then " +
            "echo -n \$(basename \$i) > /sys/bus/usb/drivers/uvcvideo/unbind 2>/dev/null; " +
            "echo unbound \$(basename \$i); fi; " +
            "done; done; echo UNBIND_DONE"
        try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/xbin/su", "0", "sh", "-c", script))
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            p.waitFor()
            Log.i(TAG, "unbindKernelDriver: out=[$out] err=[$err]")
            // breve attesa: il kernel deve completare il detach prima di riaprire ep0
            Thread.sleep(300)
        } catch (e: Exception) {
            Log.w(TAG, "unbindKernelDriver failed (no root?): ${e.message}")
        }
    }

    private fun findDevice(): UsbDevice? = findCamera(usbManager)

    private fun parseDescriptors(): Boolean {
        val conn = connection ?: return false

        val buffer = ByteArray(1024)
        val len = conn.controlTransfer(
            0x80,
            0x06,
            (0x02 shl 8) or 0,
            0,
            buffer,
            buffer.size,
            500
        )

        if (len < 0) {
            Log.e(TAG, "Failed to read configuration descriptor")
            return false
        }
        rawConfig = buffer.copyOf(len)

        // DEBUG: dump raw config descriptor in chunk hex per analisi offline
        val sb = StringBuilder()
        for (i in 0 until len) {
            sb.append(String.format("%02x", buffer[i].toInt() and 0xFF))
            if ((i + 1) % 32 == 0) { Log.i(TAG, "RAWDESC ${sb}"); sb.clear() }
        }
        if (sb.isNotEmpty()) Log.i(TAG, "RAWDESC $sb")
        Log.i(TAG, "RAWDESC total len=$len")

        var pos = 0
        var foundVideoControl = false

        while (pos < len) {
            if (pos + 2 > len) break
            val bLength = buffer[pos].toInt() and 0xFF
            val bDescriptorType = buffer[pos + 1].toInt() and 0xFF

            if (bLength < 2 || pos + bLength > len) break

            if (bDescriptorType == 0x04) {
                if (pos + 7 <= len) {
                    val bInterfaceClass = buffer[pos + 5].toInt() and 0xFF
                    val bInterfaceSubClass = buffer[pos + 6].toInt() and 0xFF
                    val bInterfaceNumber = buffer[pos + 2].toInt() and 0xFF

                    // attivo solo dentro la VideoControl; si spegne entrando in altre interfacce (es. VideoStreaming)
                    foundVideoControl = (bInterfaceClass == CC_VIDEO && bInterfaceSubClass == SC_VIDEOCONTROL)
                    if (foundVideoControl) {
                        vcInterface = bInterfaceNumber
                        Log.i(TAG, "Found VideoControl interface: $vcInterface")
                    }
                }
            }

            if (foundVideoControl && bDescriptorType == CS_INTERFACE) {
                if (pos + 2 <= len) {
                    val bDescriptorSubtype = buffer[pos + 2].toInt() and 0xFF

                    when (bDescriptorSubtype) {
                        VC_INPUT_TERMINAL -> {
                            // layout: [3]bTerminalID [4-5]wTerminalType [6]bAssocTerminal [7]iTerminal
                            //         [8-9]ObjFocalMin [10-11]ObjFocalMax [12-13]OcularFocal [14]bControlSize [15..]bmControls
                            if (pos + 6 <= len) {
                                val bTerminalID = buffer[pos + 3].toInt() and 0xFF
                                val wTerminalType = ((buffer[pos + 4].toInt() and 0xFF) or
                                        ((buffer[pos + 5].toInt() and 0xFF) shl 8))

                                if (wTerminalType == ITT_CAMERA) {
                                    ctId = bTerminalID
                                    if (pos + 15 <= len) {
                                        val bControlSize = buffer[pos + 14].toInt() and 0xFF
                                        if (bControlSize > 0 && pos + 15 + bControlSize <= len) {
                                            ctBmControls = 0L
                                            for (i in 0 until minOf(bControlSize, 8)) {
                                                ctBmControls =
                                                    ctBmControls or ((buffer[pos + 15 + i].toLong() and 0xFF) shl (i * 8))
                                            }
                                        }
                                    }
                                    Log.i(TAG, "Camera Terminal: ID=$ctId, bmControls=0x${ctBmControls.toString(16).padStart(16, '0')}")
                                }
                            }
                        }

                        VC_PROCESSING_UNIT -> {
                            // layout: [3]bUnitID [4]bSourceID [5-6]wMaxMultiplier [7]bControlSize [8..]bmControls
                            if (pos + 8 <= len) {
                                val bUnitID = buffer[pos + 3].toInt() and 0xFF
                                puId = bUnitID
                                val bControlSize = buffer[pos + 7].toInt() and 0xFF
                                if (bControlSize > 0 && pos + 8 + bControlSize <= len) {
                                    puBmControls = 0L
                                    for (i in 0 until minOf(bControlSize, 8)) {
                                        puBmControls =
                                            puBmControls or ((buffer[pos + 8 + i].toLong() and 0xFF) shl (i * 8))
                                    }
                                }
                                Log.i(TAG, "Processing Unit: ID=$bUnitID, bmControls=0x${puBmControls.toString(16).padStart(16, '0')}")
                            }
                        }
                    }
                }
            }

            pos += bLength
        }

        return vcInterface >= 0 && ctId >= 0
    }

    fun panTiltRelative(panDir: Int, panSpeed: Int, tiltDir: Int, tiltSpeed: Int): Boolean {
        if (!supports(ctBmControls, 12)) return false
        val data = byteArrayOf(
            panDir.toByte(),
            panSpeed.toByte(),
            tiltDir.toByte(),
            tiltSpeed.toByte()
        )
        return setCur(ctId, CT_PANTILT_RELATIVE_CONTROL, data)
    }

    fun zoomRelative(dir: Int, speed: Int): Boolean {
        if (!supports(ctBmControls, 10)) return false
        val data = byteArrayOf(
            dir.toByte(),
            0,
            speed.toByte()
        )
        return setCur(ctId, CT_ZOOM_RELATIVE_CONTROL, data)
    }

    fun zoomAbsolute(value: Int): Boolean {
        if (!supports(ctBmControls, 9)) return false
        val data = byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8).toByte()
        )
        return setCur(ctId, CT_ZOOM_ABSOLUTE_CONTROL, data)
    }

    fun panTiltAbsolute(pan: Int, tilt: Int): Boolean {
        if (!supports(ctBmControls, 11)) return false
        val data = ByteArray(8)
        data[0] = (pan and 0xFF).toByte()
        data[1] = (pan shr 8).toByte()
        data[2] = (pan shr 16).toByte()
        data[3] = (pan shr 24).toByte()
        data[4] = (tilt and 0xFF).toByte()
        data[5] = (tilt shr 8).toByte()
        data[6] = (tilt shr 16).toByte()
        data[7] = (tilt shr 24).toByte()
        return setCur(ctId, CT_PANTILT_ABSOLUTE_CONTROL, data)
    }

    /** Legge range e posizione corrente dal device (chiamare dopo open/parse). */
    fun initPtzState() {
        val conn = connection ?: return
        // PanTilt: GET_MIN/MAX/CUR su 8 byte (pan int32, tilt int32)
        readSigned32x2(conn, CT_PANTILT_ABSOLUTE_CONTROL, 0x82)?.let { panMin = it.first; tiltMin = it.second }
        readSigned32x2(conn, CT_PANTILT_ABSOLUTE_CONTROL, 0x83)?.let { panMax = it.first; tiltMax = it.second }
        readSigned32x2(conn, CT_PANTILT_ABSOLUTE_CONTROL, 0x81)?.let { curPan = it.first; curTilt = it.second }
        // Zoom: GET_MIN/MAX/CUR su 2 byte
        readU16(conn, CT_ZOOM_ABSOLUTE_CONTROL, 0x82)?.let { zoomMin = it }
        readU16(conn, CT_ZOOM_ABSOLUTE_CONTROL, 0x83)?.let { zoomMax = it }
        readU16(conn, CT_ZOOM_ABSOLUTE_CONTROL, 0x81)?.let { curZoom = it }
        Log.i(TAG, "PTZ state: pan=$curPan[$panMin..$panMax] tilt=$curTilt[$tiltMin..$tiltMax] zoom=$curZoom[$zoomMin..$zoomMax]")
    }

    private fun readSigned32x2(conn: UsbDeviceConnection, cs: Int, req: Int): Pair<Int, Int>? {
        val b = ByteArray(8)
        val n = conn.controlTransfer(0xA1, req, cs shl 8, (ctId shl 8) or vcInterface, b, 8, 500)
        if (n < 8) return null
        val a = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or (b[3].toInt() shl 24)
        val c = (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8) or ((b[6].toInt() and 0xFF) shl 16) or (b[7].toInt() shl 24)
        return Pair(a, c)
    }

    private fun readU16(conn: UsbDeviceConnection, cs: Int, req: Int): Int? {
        val b = ByteArray(2)
        val n = conn.controlTransfer(0xA1, req, cs shl 8, (ctId shl 8) or vcInterface, b, 2, 500)
        if (n < 2) return null
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun clamp(v: Int, lo: Int, hi: Int) = maxOf(lo, minOf(hi, v))

    /** dir: -1/+1. Aggiorna stato e invia posizione assoluta. */
    fun stepPan(dir: Int): Boolean {
        curPan = clamp(curPan + dir * panStep, panMin, panMax)
        return panTiltAbsolute(curPan, curTilt)
    }
    fun stepTilt(dir: Int): Boolean {
        curTilt = clamp(curTilt + dir * tiltStep, tiltMin, tiltMax)
        return panTiltAbsolute(curPan, curTilt)
    }
    fun stepZoom(dir: Int): Boolean {
        curZoom = clamp(curZoom + dir * zoomStep, zoomMin, zoomMax)
        return zoomAbsolute(curZoom)
    }
    fun home(): Boolean {
        curPan = 0; curTilt = 0; curZoom = zoomMin
        val a = panTiltAbsolute(0, 0)
        val z = zoomAbsolute(zoomMin)
        return a || z
    }

    // ===== BRUTE-FORCE MOVIMENTO: ogni tentativo prova un approccio diverso =====
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun ptAbs(pan: Int, tilt: Int) = le32(pan) + le32(tilt)
    private fun ptRel(p: Int, ps: Int, t: Int, ts: Int) =
        byteArrayOf(p.toByte(), ps.toByte(), t.toByte(), ts.toByte())

    private fun rawSet(cs: Int, data: ByteArray, idx: Int): Int {
        val c = connection ?: return -999
        return c.controlTransfer(0x21, 0x01, cs shl 8, idx, data, data.size, 1000)
    }
    private fun idxDefault() = (ctId shl 8) or vcInterface
    private fun relMove(data: ByteArray, ms: Long, idx: Int): String {
        val a = rawSet(0x0E, data, idx); Thread.sleep(ms)
        val b = rawSet(0x0E, ptRel(0, 0, 0, 0), idx)
        return "start=$a stop=$b"
    }

    // CS: PANTILT_ABS=0x0D, PANTILT_REL=0x0E
    private val attempts: List<Pair<String, () -> String>> by lazy {
        val I = idxDefault()
        listOf(
            "panAbs +5deg(18000)"        to { "ret=" + rawSet(0x0D, ptAbs(18000, 0), I) },
            "panAbs -5deg(-18000)"       to { "ret=" + rawSet(0x0D, ptAbs(-18000, 0), I) },
            "panAbs +25deg(90000)"       to { "ret=" + rawSet(0x0D, ptAbs(90000, 0), I) },
            "panAbs +100deg(360000)"     to { "ret=" + rawSet(0x0D, ptAbs(360000, 0), I) },
            "panAbs +170deg(612000)max"  to { "ret=" + rawSet(0x0D, ptAbs(612000, 0), I) },
            "panAbs -170deg(-612000)"    to { "ret=" + rawSet(0x0D, ptAbs(-612000, 0), I) },
            "panAbs raw +10"             to { "ret=" + rawSet(0x0D, ptAbs(10, 0), I) },
            "panAbs raw +90"             to { "ret=" + rawSet(0x0D, ptAbs(90, 0), I) },
            "panAbs raw +1000"           to { "ret=" + rawSet(0x0D, ptAbs(1000, 0), I) },
            "tiltAbs +10deg(36000)"      to { "ret=" + rawSet(0x0D, ptAbs(0, 36000), I) },
            "tiltAbs -10deg(-36000)"     to { "ret=" + rawSet(0x0D, ptAbs(0, -36000), I) },
            "panRel +1 spd1 (0.8s)"      to { relMove(ptRel(1, 1, 0, 0), 800, I) },
            "panRel +1 spd16 (0.8s)"     to { relMove(ptRel(1, 16, 0, 0), 800, I) },
            "panRel +1 spd64 (0.8s)"     to { relMove(ptRel(1, 64, 0, 0), 800, I) },
            "panRel +1 spd255 (0.8s)"    to { relMove(ptRel(1, 255, 0, 0), 800, I) },
            "panRel -1(0xFF) spd64"      to { relMove(ptRel(-1, 64, 0, 0), 800, I) },
            "panRel +1 spd64 LONG(2s)"   to { relMove(ptRel(1, 64, 0, 0), 2000, I) },
            "tiltRel +1 spd64 (0.8s)"    to { relMove(ptRel(0, 0, 1, 64), 800, I) },
            "tiltRel -1 spd64 (0.8s)"    to { relMove(ptRel(0, 0, -1, 64), 800, I) },
            "panAbs +25deg idxSWAP"      to { "ret=" + rawSet(0x0D, ptAbs(90000, 0), (vcInterface shl 8) or ctId) },
            "panAbs +25deg idx=ctOnly"   to { "ret=" + rawSet(0x0D, ptAbs(90000, 0), ctId) },
            "panRel +1 spd64 idxSWAP"    to { relMove(ptRel(1, 64, 0, 0), 800, (vcInterface shl 8) or ctId) },
            "panAbs +25deg entity2"      to { "ret=" + rawSet(0x0D, ptAbs(90000, 0), (2 shl 8) or vcInterface) },
            "panRel +1 spd64 entity2"    to { relMove(ptRel(1, 64, 0, 0), 800, (2 shl 8) or vcInterface) },
            "panAbs +25deg entity4"      to { "ret=" + rawSet(0x0D, ptAbs(90000, 0), (4 shl 8) or vcInterface) },
            "panRel +1 spd64 entity4"    to { relMove(ptRel(1, 64, 0, 0), 800, (4 shl 8) or vcInterface) },
            "panRel +1 spd64 NO-STOP"    to { "ret=" + rawSet(0x0E, ptRel(1, 64, 0, 0), I) + " (usa STOP=btn28)" },
            "STOP (panRel 0,0,0,0)"      to { "ret=" + rawSet(0x0E, ptRel(0, 0, 0, 0), I) },
            "panAbs 4byte-only +25deg"   to { "ret=" + rawSet(0x0D, le32(90000), I) }
        )
    }

    fun diagList(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        attempts.forEachIndexed { i, a -> arr.put(JSONObject().apply { put("id", i + 1); put("desc", a.first) }) }
        return arr
    }
    fun diagTry(id: Int): JSONObject {
        val a = attempts.getOrNull(id - 1) ?: return JSONObject().apply { put("error", "id out of range") }
        val res = try { a.second() } catch (e: Exception) { "EXC: ${e.message}" }
        Log.i(TAG, "DIAG #$id [${a.first}] -> $res")
        return JSONObject().apply { put("id", id); put("desc", a.first); put("result", res) }
    }

    fun setProcessing(cs: Int, value: Int): Boolean {
        if (puId < 0) return false
        if (!supportsProcessing(cs)) return false
        val data = byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8).toByte()
        )
        return setCur(puId, cs, data)
    }

    fun getProcessingRange(cs: Int): JSONObject? {
        if (puId < 0) return null
        if (!supportsProcessing(cs)) return null

        val key = "pu_$cs"
        if (controlRanges.containsKey(key)) {
            val r = controlRanges[key]!!
            return JSONObject().apply {
                put("min", r.min)
                put("max", r.max)
                put("res", r.res)
                put("def", r.def)
                put("cur", r.cur)
            }
        }

        val minVal = getCurUnsigned(puId, cs, 2) { buffer, len ->
            if (len >= 2) ((buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)) else 0
        } ?: return null

        val maxVal = getMax(puId, cs, 2) { buffer, len ->
            if (len >= 2) ((buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)) else 0
        } ?: return null

        val resVal = getRes(puId, cs, 2) { buffer, len ->
            if (len >= 2) ((buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)) else 0
        } ?: return null

        val defVal = getDef(puId, cs, 2) { buffer, len ->
            if (len >= 2) ((buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)) else 0
        } ?: return null

        val curVal = getCurUnsigned(puId, cs, 2) { buffer, len ->
            if (len >= 2) ((buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)) else 0
        } ?: return null

        val range = ControlRange(minVal, maxVal, resVal, defVal, curVal)
        controlRanges[key] = range

        return JSONObject().apply {
            put("min", minVal)
            put("max", maxVal)
            put("res", resVal)
            put("def", defVal)
            put("cur", curVal)
        }
    }

    fun infoJson(): JSONObject {
        return JSONObject().apply {
            put("device_found", device != null)
            put("permission", hasPermission())
            put("ct_id", ctId)
            put("pu_id", puId)
            put("vc_interface", vcInterface)

            val ctControls = JSONObject()
            if (supports(ctBmControls, 5)) ctControls.put("focus_absolute", true)
            if (supports(ctBmControls, 9)) ctControls.put("zoom_absolute", true)
            if (supports(ctBmControls, 10)) ctControls.put("zoom_relative", true)
            if (supports(ctBmControls, 11)) ctControls.put("pan_tilt_absolute", true)
            if (supports(ctBmControls, 12)) ctControls.put("pan_tilt_relative", true)
            put("camera_terminal_controls", ctControls)

            put("ptz_state", JSONObject().apply {
                put("pan", curPan); put("pan_min", panMin); put("pan_max", panMax)
                put("tilt", curTilt); put("tilt_min", tiltMin); put("tilt_max", tiltMax)
                put("zoom", curZoom); put("zoom_min", zoomMin); put("zoom_max", zoomMax)
            })

            val puControls = JSONObject()
            if (supportsProcessing(0x02)) puControls.put("brightness", getProcessingRange(0x02))
            if (supportsProcessing(0x03)) puControls.put("contrast", getProcessingRange(0x03))
            if (supportsProcessing(0x06)) puControls.put("hue", getProcessingRange(0x06))
            if (supportsProcessing(0x07)) puControls.put("saturation", getProcessingRange(0x07))
            if (supportsProcessing(0x08)) puControls.put("sharpness", getProcessingRange(0x08))
            if (supportsProcessing(0x09)) puControls.put("gamma", getProcessingRange(0x09))
            if (supportsProcessing(0x0A)) puControls.put("white_balance_temperature", getProcessingRange(0x0A))
            put("processing_unit_controls", puControls)
        }
    }

    private fun supports(bmControls: Long, bit: Int): Boolean {
        return (bmControls and (1L shl bit)) != 0L
    }

    private fun supportsProcessing(cs: Int): Boolean {
        val bitMap = mapOf(
            0x02 to 0, 0x03 to 1, 0x06 to 2, 0x07 to 3, 0x08 to 4, 0x09 to 5, 0x0A to 6
        )
        val bit = bitMap[cs] ?: return false
        return supports(puBmControls, bit)
    }

    private fun setCur(entityId: Int, cs: Int, data: ByteArray): Boolean {
        val conn = connection ?: return false
        var retries = 3
        while (retries > 0) {
            val result = conn.controlTransfer(
                0x21,
                0x01,
                cs shl 8,
                (entityId shl 8) or vcInterface,
                data,
                data.size,
                500
            )
            if (result >= 0) {
                Log.d(TAG, "setCur OK: entityId=$entityId, cs=$cs")
                return true
            }
            retries--
            if (retries > 0) {
                Thread.sleep(200)
            }
        }
        Log.w(TAG, "setCur FAILED: entityId=$entityId, cs=$cs after 3 retries")
        return false
    }

    private fun getCurUnsigned(
        entityId: Int,
        cs: Int,
        len: Int,
        parse: (ByteArray, Int) -> Int
    ): Int? {
        return uvcGet(entityId, cs, len, 0x81, parse)
    }

    private fun getMin(entityId: Int, cs: Int, len: Int, parse: (ByteArray, Int) -> Int): Int? {
        return uvcGet(entityId, cs, len, 0x82, parse)
    }

    private fun getMax(entityId: Int, cs: Int, len: Int, parse: (ByteArray, Int) -> Int): Int? {
        return uvcGet(entityId, cs, len, 0x83, parse)
    }

    private fun getRes(entityId: Int, cs: Int, len: Int, parse: (ByteArray, Int) -> Int): Int? {
        return uvcGet(entityId, cs, len, 0x84, parse)
    }

    private fun getDef(entityId: Int, cs: Int, len: Int, parse: (ByteArray, Int) -> Int): Int? {
        return uvcGet(entityId, cs, len, 0x87, parse)
    }

    private fun uvcGet(
        entityId: Int,
        cs: Int,
        len: Int,
        request: Int,
        parse: (ByteArray, Int) -> Int
    ): Int? {
        val conn = connection ?: return null
        val buffer = ByteArray(len)
        val result = conn.controlTransfer(
            0xA1,
            request,
            cs shl 8,
            (entityId shl 8) or vcInterface,
            buffer,
            len,
            500
        )
        if (result < 0) {
            Log.w(TAG, "uvcGet FAILED: entityId=$entityId, cs=$cs, request=$request")
            return null
        }
        return parse(buffer, result)
    }
}
