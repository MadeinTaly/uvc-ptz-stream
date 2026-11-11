package com.h96.hwstream

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Interroga il sistema per i codec VIDEO hardware disponibili.
 * Sui box RK3566 i codec HW si chiamano c2.rk.* (Codec2) o OMX.rk.* (legacy).
 * Serve a verificare che stiamo effettivamente parlando col VPU, non con sw.
 */
object HwCodecs {

    data class Codec(
        val name: String,
        val encoder: Boolean,
        val hardware: Boolean,
        val mimes: List<String>
    )

    fun list(): List<Codec> {
        val out = ArrayList<Codec>()
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            val mimes = info.supportedTypes.filter { it.startsWith("video/") }
            if (mimes.isEmpty()) continue
            out.add(
                Codec(
                    name = info.name,
                    encoder = info.isEncoder,
                    hardware = isHardware(info),
                    mimes = mimes.toList()
                )
            )
        }
        return out
    }

    /** Su API 29+ c'e' isHardwareAccelerated(); sotto, euristica sul nome. */
    fun isHardware(info: MediaCodecInfo): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            try { info.isHardwareAccelerated } catch (e: Throwable) { nameLooksHw(info.name) }
        } else {
            nameLooksHw(info.name)
        }
    }

    private fun nameLooksHw(name: String): Boolean {
        val n = name.lowercase()
        // sw noti: google/c2.android.* ; hw rockchip: c2.rk.* / omx.rk.*
        if (n.contains("c2.android") || n.contains("omx.google") || n.contains(".sw.")) return false
        return n.contains("rk") || n.contains("rockchip") || n.contains("hardware") ||
                (!n.contains("android") && !n.contains("google"))
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        var hwDec = 0
        var hwEnc = 0
        for (c in list()) {
            if (c.hardware && c.encoder) hwEnc++
            if (c.hardware && !c.encoder) hwDec++
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("type", if (c.encoder) "encoder" else "decoder")
                put("hardware", c.hardware)
                put("mimes", JSONArray(c.mimes))
            })
        }
        return JSONObject().apply {
            put("hw_decoders", hwDec)
            put("hw_encoders", hwEnc)
            put("codecs", arr)
        }
    }
}
