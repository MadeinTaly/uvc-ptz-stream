package com.h96.hwstream

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val svc = Intent(this, StreamingService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)

        // Device rootati: il service apre la cam via root-fd, nessun dialog.
        // Altrimenti serve CAMERA (Android 9+ la richiede per le UVC); il permesso USB lo chiede il service.
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)

        val tv = TextView(this).apply {
            textSize = 22f
            setPadding(48, 48, 48, 48)
            text = "UVC PTZ Stream attivo.\n\n" +
                    "Apri da un browser in rete:\n" +
                    "http://<IP-dispositivo>:${StreamingService.PORT}\n\n" +
                    "Il servizio riparte da solo a ogni avvio."
        }
        setContentView(tv)
    }
}
