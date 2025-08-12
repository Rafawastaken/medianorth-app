package com.medianorthapp.network

import android.util.Log
import at.favre.lib.crypto.bcrypt.BCrypt
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.datetime.Clock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

object SupabaseClient {

    // ── Config ─────────────────────────────────────────────────────
    private const val SUPABASE_URL = "https://nwwlfkosnjrpflmtcttg.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im53d2xma29zbmpycGZsbXRjdHRnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTAyNzE2MDcsImV4cCI6MjA2NTg0NzYwN30.GMaR_PAaEkN-WGV_NCt9-gQUKCjkwOF3tPIUTbyP6iI"
    private const val DEVICES_TABLE = "device"

    private val client = OkHttpClient()
    private val gson   = Gson()

    // ── Data-classes ───────────────────────────────────────────────
    data class Device(
        val id: Long,
        val login: String,
        val password_hash: String,
        val name: String,
        val active: String?,
        val site_id: Long
    )
    data class Video(
        val url: String,
        val title: String,
        val customerId: Long,
        val contractEndDate: String,
        val status: String
    )

    // ── LOGIN ──────────────────────────────────────────────────────
    fun loginDevice(login: String, password: String): Device? {
        val url = "$SUPABASE_URL/rest/v1/$DEVICES_TABLE?login=eq.$login&select=*"

        val req = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Accept", "application/json")
            .build()

        return try {
            val res  = client.newCall(req).execute()
            val body = res.body?.string() ?: "[]"

            if (!body.trimStart().startsWith("[")) {            // ← object==erro
                Log.e("SUPA", "Login falhou: $body")
                return null
            }

            val arr = gson.fromJson(body, Array<Device>::class.java)
            val dev = arr.firstOrNull() ?: return null

            if (BCrypt.verifyer().verify(password.toCharArray(), dev.password_hash).verified)
                dev else null
        } catch (e: Exception) {
            Log.e("SUPA", "Excepção no login", e)
            null
        }
    }

    // ── PLAYLIST ───────────────────────────────────────────────────
    fun getValidVideos(deviceId: Long): List<Video> {
        val url = "$SUPABASE_URL/rest/v1/device_video" +
                "?device_id=eq.$deviceId" +
                "&order=play_order.asc" +
                "&select=customer_video(id,video_url,video_title,video_status," +
                "customer(id,contract_end_date))"

        val req = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Accept", "application/json")
            .build()

        return try {
            val res  = client.newCall(req).execute()
            val body = res.body?.string() ?: "[]"

            if (!body.trimStart().startsWith("[")) {
                Log.e("SUPA", "Erro playlist: $body")
                return emptyList()
            }

            val rows: List<Map<String, Any>> = gson.fromJson(
                body, object : TypeToken<List<Map<String, Any>>>() {}.type
            )

            val fmt   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val today = Date()
            val valid = mutableListOf<Video>()

            for (row in rows) {
                val cv   = row["customer_video"] as? Map<*, *> ?: continue
                val cust = cv["customer"]       as? Map<*, *> ?: continue

                if (cv["video_status"]?.toString()?.lowercase() != "active") continue

                val endStr = cust["contract_end_date"]?.toString() ?: continue
                val endDt  = runCatching { fmt.parse(endStr) }.getOrNull() ?: continue
                if (endDt.before(today)) continue

                val urlVid = cv["video_url"]?.toString() ?: continue
                val title  = cv["video_title"]?.toString() ?: "Sem título"
                val custId = (cust["id"] as? Number)?.toLong() ?: continue

                valid.add(Video(urlVid, title, custId, endStr, "active"))
            }
            valid
        } catch (e: Exception) {
            Log.e("SUPA", "Excepção playlist", e)
            emptyList()
        }
    }

    // ── HEART-BEAT ────────────────────────────────────────────────
    fun updateLastSeen(deviceId: Long) {
        val url  = "$SUPABASE_URL/rest/v1/device?id=eq.$deviceId"
        val now  = Clock.System.now().toString()
        val json = """{"last_seen":"$now"}""".toRequestBody("application/json".toMediaType())

        try {
            val req = Request.Builder()
                .url(url)
                .method("PATCH", json)
                .addHeader("apikey", SUPABASE_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                .addHeader("Prefer", "return=minimal")
                .build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.e("SUPA", "Erro last_seen", e)
        }
    }
}
