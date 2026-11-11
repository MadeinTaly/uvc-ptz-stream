package com.h96.hwstream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Avvia il servizio all'accensione della box. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(StreamingService.TAG, "boot received: ${intent?.action}")
        val svc = Intent(context, StreamingService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
