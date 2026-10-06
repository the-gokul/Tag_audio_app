package com.nordic.tagmobile.data.cloud.supabase

import com.nordic.tagmobile.BuildConfig
import com.nordic.tagmobile.data.cloud.CloudBackend
import com.nordic.tagmobile.data.cloud.NetworkProbe
import com.nordic.tagmobile.data.cloud.SecureDns
import com.nordic.tagmobile.domain.model.CloudPetIds
import com.nordic.tagmobile.domain.model.CloudUser
import com.nordic.tagmobile.domain.model.CloudUserIds
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full Supabase backend (anon key + PostgREST + Storage).
 * Uses DNS-over-HTTPS so office/ISP Wi‑Fi that hijacks *.supabase.co still works.
 * On API 30+ optionally binds sockets to Wi‑Fi (dual-network).
 */
class SupabaseCloudBackend(
    private val appContext: Context,
    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/'),
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
) : CloudBackend {

    private val jsonMedia = "application/json".toMediaType()

    private val sharedClient: OkHttpClient by lazy { buildHttpClient() }

    init {
        require(baseUrl.isNotBlank() && anonKey.isNotBlank()) {
            "Supabase URL/anon key missing. Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties"
        }
        val host = try {
            java.net.URI(baseUrl).host ?: baseUrl
        } catch (_: Exception) {
            baseUrl
        }
        TagLogger.log(
            LogCategory.APP,
            "SUPABASE_DNS_COMPARE",
            "host=$host system=${SecureDns.systemLookupLogged(host)}",
        )
        runCatching { SecureDns.lookupLogged(host) }
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(SecureDns.get())
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .eventListenerFactory {
                object : okhttp3.EventListener() {
                    override fun dnsStart(call: okhttp3.Call, domainName: String) {
                        TagLogger.log(LogCategory.APP, "HTTP_DNS_START", domainName)
                    }

                    override fun dnsEnd(
                        call: okhttp3.Call,
                        domainName: String,
                        inetAddressList: List<java.net.InetAddress>,
                    ) {
                        TagLogger.log(
                            LogCategory.APP,
                            "HTTP_DNS_END",
                            "$domainName -> ${inetAddressList.joinToString { it.hostAddress ?: "?" }}",
                        )
                    }

                    override fun connectStart(
                        call: okhttp3.Call,
                        inetSocketAddress: java.net.InetSocketAddress,
                        proxy: java.net.Proxy,
                    ) {
                        TagLogger.log(
                            LogCategory.APP,
                            "HTTP_CONNECT_START",
                            "${inetSocketAddress.address?.hostAddress}:${inetSocketAddress.port}",
                        )
                    }

                    override fun connectFailed(
                        call: okhttp3.Call,
                        inetSocketAddress: java.net.InetSocketAddress,
                        proxy: java.net.Proxy,
                        protocol: okhttp3.Protocol?,
                        ioe: java.io.IOException,
                    ) {
                        TagLogger.log(
                            LogCategory.ERRORS,
                            "HTTP_CONNECT_FAIL",
                            "${inetSocketAddress.address?.hostAddress} err=${ioe.message}",
                        )
                    }

                    override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) {
                        TagLogger.log(
                            LogCategory.APP,
                            "HTTP_TLS_OK",
                            "tls=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}",
                        )
                    }
                }
            }
        // Network.socketFactory binding is flaky on Android 9/10; skip below API 30.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val wifi = NetworkProbe.findWifiNetwork(appContext)
            if (wifi != null) {
                try {
                    builder.socketFactory(wifi.socketFactory)
                    TagLogger.log(LogCategory.APP, "SUPABASE_HTTP_BIND", "wifi_network=$wifi")
                } catch (e: Exception) {
                    TagLogger.log(LogCategory.APP, "SUPABASE_HTTP_BIND_SKIP", e.message ?: "error")
                }
            }
        } else {
            TagLogger.log(
                LogCategory.APP,
                "SUPABASE_HTTP_DEFAULT",
                "api=${android.os.Build.VERSION.SDK_INT} no_socket_bind dns=DoH http=1.1",
            )
        }
        return builder.build()
    }

    private fun httpClient(): OkHttpClient = sharedClient

    override suspend fun ensureSignedIn(localUserId: String, name: String, phone: String): CloudUser {
        val cloudId = "user-$localUserId"
        upsertUser(localUserId, name, phone)
        return CloudUser(cloudUserId = cloudId, localUserId = localUserId, name = name, phone = phone)
    }

    override suspend fun upsertUser(localUserId: String, name: String, phone: String): CloudUserIds =
        withContext(Dispatchers.IO) {
            val cloudId = "user-$localUserId"
            val body = JSONObject()
                .put("cloud_user_id", cloudId)
                .put("local_user_id", localUserId)
                .put("name", name)
                .put("phone", phone)
                .put("updated_at_ms", System.currentTimeMillis())
            upsertRow("users", body, "cloud_user_id")
            CloudUserIds(cloudUserId = cloudId, localUserId = localUserId)
        }

    override suspend fun upsertPet(
        localPetId: String,
        localUserId: String,
        cloudUserId: String,
        name: String,
        animalType: String,
        breed: String,
        sex: String,
        age: String,
        weightKg: String,
    ): CloudPetIds = withContext(Dispatchers.IO) {
        val cloudPetId = localPetId
        val body = JSONObject()
            .put("cloud_pet_id", cloudPetId)
            .put("local_pet_id", localPetId)
            .put("cloud_user_id", cloudUserId)
            .put("local_user_id", localUserId)
            .put("name", name)
            .put("animal_type", animalType)
            .put("breed", breed)
            .put("sex", sex)
            .put("age", age)
            .put("weight_kg", weightKg)
            .put("updated_at_ms", System.currentTimeMillis())
        upsertRow("pets", body, "cloud_pet_id")
        CloudPetIds(cloudPetId = cloudPetId, localPetId = localPetId)
    }

    override suspend fun uploadSessionPackage(
        sessionId: String,
        folder: File,
        metadata: Map<String, Any?>,
    ): String = withContext(Dispatchers.IO) {
        val files = folder.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) error("Session folder empty: ${folder.absolutePath}")

        val storagePrefix = sessionId // path inside bucket "sessions"
        // Small files first so a flaky Wi‑Fi drop is less likely mid-video after metadata-ish files.
        val ordered = files.sortedBy { file ->
            when {
                file.name.equals("manifest.json", true) -> 0
                file.name.endsWith(".log", true) -> 1
                file.name.endsWith(".xlsx", true) -> 2
                file.name.endsWith(".mp4", true) -> 3
                else -> 4
            }
        }
        val uploaded = JSONArray()
        val packageStart = System.currentTimeMillis()
        TagLogger.log(
            LogCategory.APP,
            "SUPABASE_UPLOAD_BEGIN",
            "session=$sessionId files=${ordered.size} " +
                ordered.joinToString { "${it.name}:${NetworkProbe.formatBytes(it.length())}" },
        )
        for (file in ordered) {
            val objectPath = "$storagePrefix/${file.name}"
            val t0 = System.currentTimeMillis()
            TagLogger.log(
                LogCategory.APP,
                "SUPABASE_UPLOAD_FILE_START",
                "$objectPath size=${NetworkProbe.formatBytes(file.length())}",
            )
            uploadFileWithRetry(objectPath, file)
            val ms = System.currentTimeMillis() - t0
            uploaded.put(
                JSONObject()
                    .put("name", file.name)
                    .put("size", file.length())
                    .put("storage_path", "sessions/$objectPath"),
            )
            TagLogger.log(
                LogCategory.APP,
                "SUPABASE_UPLOAD_FILE_OK",
                "$objectPath size=${NetworkProbe.formatBytes(file.length())} ms=$ms",
            )
        }

        val row = JSONObject()
            .put("session_id", sessionId)
            .put("cloud_user_id", metadata["cloud_user_id"]?.toString() ?: "")
            .put("cloud_pet_id", metadata["cloud_pet_id"]?.toString() ?: JSONObject.NULL)
            .put("local_user_id", metadata["local_user_id"]?.toString() ?: "")
            .put("local_pet_id", metadata["local_pet_id"]?.toString() ?: "")
            .put("device_id", metadata["device_id"]?.toString() ?: "")
            .put("device_address", metadata["device_address"]?.toString() ?: "")
            .put("quality", metadata["quality"]?.toString() ?: "")
            .put("started_at_ms", (metadata["started_at_ms"] as? Number)?.toLong() ?: 0L)
            .put("ended_at_ms", (metadata["ended_at_ms"] as? Number)?.toLong() ?: 0L)
            .put("storage_path", "sessions/$storagePrefix")
            .put("has_video", files.any { it.name.endsWith(".mp4", true) })
            .put("has_xlsx", files.any { it.name.endsWith(".xlsx", true) })
            .put("has_log", files.any { it.name.endsWith(".log", true) })
            .put("has_manifest", files.any { it.name.equals("manifest.json", true) })
            .put("uploaded_at_ms", System.currentTimeMillis())
            .put("files", uploaded)
        upsertRow("sessions", row, "session_id")
        val totalMs = System.currentTimeMillis() - packageStart
        TagLogger.log(
            LogCategory.APP,
            "SUPABASE_UPLOAD_OK",
            "session=$sessionId files=${files.size} ms=$totalMs",
        )
        "sessions/$storagePrefix"
    }

    private fun upsertRow(table: String, body: JSONObject, onConflict: String) {
        withRetry("PostgREST $table") {
            val req = Request.Builder()
                .url("$baseUrl/rest/v1/$table?on_conflict=$onConflict")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            httpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("PostgREST $table failed: HTTP ${resp.code} ${resp.body?.string()}")
                }
            }
        }
    }

    private fun uploadFileWithRetry(pathInBucket: String, file: File) {
        withRetry("Storage ${file.name}") {
            uploadFileOnce(pathInBucket, file)
        }
    }

    private fun uploadFileOnce(pathInBucket: String, file: File) {
        val media = when {
            file.name.endsWith(".mp4", true) -> "video/mp4"
            file.name.endsWith(".xlsx", true) ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            file.name.endsWith(".json", true) -> "application/json"
            file.name.endsWith(".log", true) -> "text/plain"
            else -> "application/octet-stream"
        }.toMediaType()

        val encoded = pathInBucket.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val req = Request.Builder()
            .url("$baseUrl/storage/v1/object/sessions/$encoded")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("x-upsert", "true")
            .post(file.asRequestBody(media))
            .build()
        httpClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Storage upload failed: HTTP ${resp.code} ${resp.body?.string()}")
            }
        }
    }

    /** Retries transient Wi‑Fi drops like "connection closed". */
    private fun withRetry(label: String, times: Int = 3, block: () -> Unit) {
        var last: Exception? = null
        repeat(times) { attempt ->
            try {
                block()
                return
            } catch (e: Exception) {
                last = e
                val retryable = e is IOException ||
                    (e.message?.contains("connection", ignoreCase = true) == true)
                TagLogger.log(
                    LogCategory.APP,
                    "SUPABASE_RETRY",
                    "$label attempt=${attempt + 1}/$times err=${e.message}",
                )
                if (!retryable || attempt == times - 1) throw e
                try {
                    Thread.sleep(1_500L * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw last ?: IOException("$label failed")
    }
}
