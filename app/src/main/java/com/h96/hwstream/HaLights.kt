package com.h96.hwstream

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Integrazione Home Assistant (opzionale): controllo luci switch.* via REST API.
 * Token long-lived fornito da HA, timeout 5s per ogni request.
 */
object HaLights {
    // tutto da local.properties, mai nel repo: ha.url, ha.token (long-lived), ha.lights
    private val BASE_URL = BuildConfig.HA_URL.trimEnd('/')
    private val TOKEN = BuildConfig.HA_TOKEN
    private const val TIMEOUT_MS = 5000

    data class Light(val label: String, val entityId: String)

    // ha.lights = "Etichetta=switch.entity;Altra=switch.altra" (vuoto o ha.url mancante = nessuna luce)
    private val lights = if (BASE_URL.isEmpty()) emptyList() else BuildConfig.HA_LIGHTS.split(';')
        .mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { Light(it[0].trim(), it[1].trim()) } }

    /** Solo le luci configurate sono comandabili (niente entity_id arbitrari dal client). */
    fun isKnown(entityId: String) = lights.any { it.entityId == entityId }

    /**
     * Alterna lo stato di una luce: on -> off, off -> on.
     * Ritorna true se la richiesta HTTP è andata a buon fine (200/201).
     */
    fun toggle(entityId: String): Boolean = try {
        val url = URL("$BASE_URL/api/services/switch/toggle")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $TOKEN")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        val body = JSONObject().apply { put("entity_id", entityId) }.toString()
        conn.outputStream.write(body.toByteArray())
        conn.outputStream.flush()
        conn.outputStream.close()
        val code = conn.responseCode
        conn.disconnect()
        code == 200 || code == 201
    } catch (e: Exception) {
        Log.w("HaLights", "toggle($entityId) failed: ${e.message}")
        false
    }

    /**
     * Imposta lo stato di una luce: true = turn_on, false = turn_off.
     * Ritorna true se la richiesta HTTP è andata a buon fine (200/201).
     */
    fun setState(entityId: String, on: Boolean): Boolean = try {
        val service = if (on) "turn_on" else "turn_off"
        val url = URL("$BASE_URL/api/services/switch/$service")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $TOKEN")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        val body = JSONObject().apply { put("entity_id", entityId) }.toString()
        conn.outputStream.write(body.toByteArray())
        conn.outputStream.flush()
        conn.outputStream.close()
        val code = conn.responseCode
        conn.disconnect()
        code == 200 || code == 201
    } catch (e: Exception) {
        Log.w("HaLights", "setState($entityId, $on) failed: ${e.message}")
        false
    }

    /**
     * Legge lo stato di una luce. Ritorna "on", "off", "unavailable", o "unknown" se errore.
     */
    fun state(entityId: String): String {
        return try {
            val url = URL("$BASE_URL/api/states/$entityId")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $TOKEN")
            }
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return "unknown"
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(response)
            json.optString("state", "unknown")
        } catch (e: Exception) {
            Log.w("HaLights", "state($entityId) failed: ${e.message}")
            "unknown"
        }
    }

    /**
     * Ritorna un JSONArray con oggetti {label, entityId, state} per popolare la UI.
     */
    fun all() = JSONArray().also { arr ->
        for (light in lights) {
            arr.put(JSONObject().apply {
                put("label", light.label)
                put("entityId", light.entityId)
                put("state", state(light.entityId))
            })
        }
    }
}
