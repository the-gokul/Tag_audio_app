package com.nordic.tagmobile.data.cloud.server

import android.content.Context
import android.os.Build
import com.nordic.tagmobile.BuildConfig
import com.nordic.tagmobile.data.cloud.CloudBackend
import com.nordic.tagmobile.data.cloud.NetworkProbe
import com.nordic.tagmobile.data.cloud.SecureDns
import com.nordic.tagmobile.data.cloud.UploadProgress
import com.nordic.tagmobile.domain.model.CloudPetIds
import com.nordic.tagmobile.domain.model.CloudUser
import com.nordic.tagmobile.domain.model.CloudUserIds
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.AppUser
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Own server backend (see /server in this repo).
 *
 * Each install registers once with the shared enrollment key and gets its own token.
 * Session files upload in [CHUNK_BYTES] chunks; the server remembers every byte it has,
 * so a long video that is cut off by Wi‑Fi drops, the 10‑minute background limit or the
 * app being killed continues where it stopped instead of starting over.
 */
class TagServerCloudBackend(
    private val appContext: Context,
    private val baseUrl: String = BuildConfig.TAG_SERVER_URL.trimEnd('/'),
    private val enrollKey: String = BuildConfig.TAG_ENROLL_KEY,
) : CloudBackend {

    private class HttpResult(val code: Int, val body: String) {
        val json: JSONObject by lazy { runCatching { JSONObject(body) }.getOrDefault(JSONObject()) }
        val ok get() = code in 200..299

        fun error(label: String): IOException {
            val msg = json.optString("message").ifBlank { json.optString("error") }.ifBlank { body.take(200) }
            return IOException("$label failed: HTTP $code $msg")
        }
    }

    private val jsonMedia = "application/json".toMediaType()
    private val octetMedia = "application/octet-stream".toMediaType()
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val authLock = Any()

    init {
        require(baseUrl.startsWith("https://")) { "TAG_SERVER_URL must start with https://" }
        require(enrollKey.isNotBlank()) { "TAG_ENROLL_KEY missing" }
    }

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .dns(SecureDns.get())
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            // The last chunk of a file waits while the server checks the SHA-256 of the whole file.
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
        // Same Wi‑Fi binding as the Supabase client (flaky below API 30).
        if (Build.VERSION.SDK_INT >= 30) {
            NetworkProbe.findWifiNetwork(appContext)?.let { wifi ->
                runCatching { builder.socketFactory(wifi.socketFactory) }
            }
        }
        builder.build()
    }

    // ------------------------------------------------------------------ auth

    private fun storedToken(): String? {
        val token = prefs.getString(KEY_TOKEN, null)
        val sameServer = prefs.getString(KEY_SERVER, null) == baseUrl
        val sameUser = prefs.getString(KEY_LOCAL_USER, null) == AppUser.load(appContext).id
        return token?.takeIf { sameServer && sameUser }
    }

    /** Returns a valid token, registering this install if needed. */
    private fun token(forceNew: Boolean = false): String = synchronized(authLock) {
        if (!forceNew) storedToken()?.let { return it }
        val user = AppUser.load(appContext)
        val body = JSONObject()
            .put("local_user_id", user.id)
            .put("name", user.name)
            .put("phone", user.phone)
            .put("device_label", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        val req = Request.Builder()
            .url("$baseUrl/api/v1/auth/register")
            .header("X-Enroll-Key", enrollKey)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val result = execute(req)
        if (!result.ok) throw result.error("Server sign-in")
        val token = result.json.getString("token")
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_CLOUD_USER, result.json.getString("cloud_user_id"))
            .putString(KEY_LOCAL_USER, user.id)
            .putString(KEY_SERVER, baseUrl)
            .apply()
        TagLogger.log(LogCategory.APP, "SERVER_REGISTERED", "cloudUser=${result.json.getString("cloud_user_id")}")
        token
    }

    private fun execute(req: Request): HttpResult =
        client.newCall(req).execute().use { resp -> HttpResult(resp.code, resp.body?.string().orEmpty()) }

    /** Sends with the bearer token; on 401 registers again once and retries. */
    private fun authed(build: (Request.Builder) -> Request.Builder): HttpResult {
        var token = token()
        for (attempt in 0..1) {
            val result = execute(build(Request.Builder()).header("Authorization", "Bearer $token").build())
            if (result.code != 401 || attempt == 1) return result
            TagLogger.log(LogCategory.APP, "SERVER_TOKEN_REJECTED", "re-registering")
            token = token(forceNew = true)
        }
        error("unreachable")
    }

    private fun jsonCall(method: String, path: String, body: JSONObject?): HttpResult = authed {
        // OkHttp throws "method POST must have a request body" on a null body for POST/PUT/PATCH.
        val json = body ?: if (method in setOf("POST", "PUT", "PATCH")) JSONObject() else null
        it.url("$baseUrl$path").method(method, json?.toString()?.toRequestBody(jsonMedia))
    }

    private fun seg(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------- user / pet data

    override suspend fun ensureSignedIn(localUserId: String, name: String, phone: String): CloudUser {
        val ids = upsertUser(localUserId, name, phone)
        return CloudUser(cloudUserId = ids.cloudUserId, localUserId = localUserId, name = name, phone = phone)
    }

    override suspend fun upsertUser(localUserId: String, name: String, phone: String): CloudUserIds =
        withContext(Dispatchers.IO) {
            val result = jsonCall("PUT", "/api/v1/me", JSONObject().put("name", name).put("phone", phone))
            if (!result.ok) throw result.error("User update")
            CloudUserIds(cloudUserId = result.json.getString("cloud_user_id"), localUserId = localUserId)
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
        val body = JSONObject()
            .put("name", name)
            .put("animal_type", animalType)
            .put("breed", breed)
            .put("sex", sex)
            .put("age", age)
            .put("weight_kg", weightKg)
        val result = jsonCall("PUT", "/api/v1/pets/${seg(localPetId)}", body)
        if (!result.ok) throw result.error("Pet update")
        CloudPetIds(cloudPetId = result.json.getString("cloud_pet_id"), localPetId = localPetId)
    }

    // ---------------------------------------------------------------- upload

    override suspend fun uploadSessionPackage(
        sessionId: String,
        folder: File,
        metadata: Map<String, Any?>,
    ): String = withContext(Dispatchers.IO) {
        val files = folder.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in UPLOAD_EXTENSIONS }
            ?.sortedBy { uploadOrder(it.name) }
            .orEmpty()
        if (files.isEmpty()) error("Session folder empty: ${folder.absolutePath}")
        try {
            uploadFiles(sessionId, files, metadata)
        } finally {
            UploadProgress.finished(sessionId)
        }
    }

    private suspend fun uploadFiles(
        sessionId: String,
        files: List<File>,
        metadata: Map<String, Any?>,
    ): String = withContext(Dispatchers.IO) {
        val base = "/api/v1/sessions/${seg(sessionId)}"
        val totalBytes = files.sumOf { it.length() }
        UploadProgress.preparing(sessionId, totalBytes)

        val manifest = files.firstOrNull { it.name.equals("manifest.json", true) }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: JSONObject()
        val declared = JSONArray()
        for (f in files) {
            ensureActive()
            declared.put(
                JSONObject()
                    .put("name", f.name)
                    .put("size", f.length())
                    .put("sha256", FileHashCache.sha256(appContext, f)),
            )
        }
        val meta = JSONObject()
        metadata.forEach { (k, v) -> if (v != null) meta.put(k, v) }
        val initBody = JSONObject()
            .put("local_pet_id", metadata["local_pet_id"]?.toString().orEmpty())
            .put("manifest", manifest)
            .put("meta", meta)
            .put("files", declared)

        var status = retrying("init") { jsonCall("POST", "$base/init", initBody) }
        if (status.json.optString("status") == "COMPLETE") {
            TagLogger.log(LogCategory.APP, "SERVER_ALREADY_COMPLETE", sessionId)
            return@withContext status.json.optString("storage_path", "sessions/$sessionId")
        }

        // Start the bar at what the server already has (a resumed upload is not at 0%).
        val alreadyOnServer = files.sumOf { f ->
            remoteFile(status.json, f.name)?.let { if (it.optBoolean("complete")) f.length() else it.optLong("received_bytes") } ?: 0L
        }
        UploadProgress.report(sessionId, alreadyOnServer, totalBytes)
        var doneBefore = 0L
        TagLogger.log(
            LogCategory.APP,
            "SERVER_UPLOAD_BEGIN",
            "session=$sessionId total=${NetworkProbe.formatBytes(totalBytes)} " +
                files.joinToString { "${it.name}:${NetworkProbe.formatBytes(it.length())}" },
        )
        for (file in files) {
            val remote = remoteFile(status.json, file.name)
            if (remote?.optBoolean("complete") == true) {
                doneBefore += file.length()
                continue
            }
            val start = remote?.optLong("received_bytes", 0L) ?: 0L
            if (start > 0) {
                TagLogger.log(LogCategory.APP, "SERVER_UPLOAD_RESUME", "${file.name} from=${NetworkProbe.formatBytes(start)}")
            }
            val offsetBase = doneBefore
            uploadFile(base, sessionId, file, start) { sent ->
                UploadProgress.report(sessionId, offsetBase + sent, totalBytes)
            }
            doneBefore += file.length()
        }

        status = retrying("complete") { jsonCall("POST", "$base/complete", null) }
        TagLogger.log(LogCategory.APP, "SERVER_UPLOAD_OK", "session=$sessionId total=${NetworkProbe.formatBytes(totalBytes)}")
        status.json.optString("storage_path", "sessions/$sessionId")
    }

    private fun remoteFile(status: JSONObject, name: String): JSONObject? {
        val arr = status.optJSONArray("files") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") == name) return o
        }
        return null
    }

    /** Asks the server how many bytes of [name] it has. */
    private fun receivedOnServer(base: String, name: String): Long? = runCatching {
        val r = jsonCall("GET", base, null)
        if (r.ok) remoteFile(r.json, name)?.optLong("received_bytes") else null
    }.getOrNull()

    private suspend fun uploadFile(
        base: String,
        sessionId: String,
        file: File,
        startOffset: Long,
        onProgress: (Long) -> Unit,
    ) {
        val size = file.length()
        val url = "$baseUrl$base/files/${seg(file.name)}"
        val buffer = ByteArray(CHUNK_BYTES)
        var offset = startOffset
        var failures = 0
        var checksumFailures = 0
        var lastLogPercent = -1
        RandomAccessFile(file, "r").use { raf ->
            while (offset < size) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val len = minOf(CHUNK_BYTES.toLong(), size - offset).toInt()
                raf.seek(offset)
                raf.readFully(buffer, 0, len)
                val chunkOffset = offset
                try {
                    val r = authed {
                        it.url("$url?offset=$chunkOffset").put(buffer.toRequestBody(octetMedia, 0, len))
                    }
                    when {
                        r.ok -> {
                            offset = r.json.getLong("received_bytes")
                            failures = 0
                            if (r.json.optBoolean("complete")) offset = size
                        }
                        r.code == 409 && r.json.has("received_bytes") -> offset = r.json.getLong("received_bytes")
                        r.code == 409 -> { // another request for this file is still finishing
                            Thread.sleep(3_000)
                            offset = receivedOnServer(base, file.name) ?: offset
                        }
                        r.code == 422 -> {
                            checksumFailures++
                            if (checksumFailures >= 2) throw IOException("${file.name}: checksum mismatch twice")
                            TagLogger.log(LogCategory.ERRORS, "SERVER_CHECKSUM_MISMATCH", "${file.name} restarting file")
                            offset = 0
                        }
                        r.code in 500..599 || r.code == 429 -> throw r.error("Upload ${file.name}")
                        else -> throw FatalUploadError(r.error("Upload ${file.name}"))
                    }
                } catch (e: FatalUploadError) {
                    throw e.cause!!
                } catch (e: IOException) {
                    failures++
                    TagLogger.log(
                        LogCategory.APP,
                        "SERVER_CHUNK_RETRY",
                        "${file.name} at=${NetworkProbe.formatBytes(chunkOffset)} attempt=$failures err=${e.message}",
                    )
                    if (failures >= MAX_CHUNK_FAILURES) throw e
                    Thread.sleep(2_000L * failures)
                    offset = receivedOnServer(base, file.name) ?: offset
                }
                onProgress(offset)
                val percent = if (size == 0L) 100 else (offset * 100 / size).toInt()
                if (percent / 10 != lastLogPercent / 10) {
                    lastLogPercent = percent
                    TagLogger.log(LogCategory.APP, "SERVER_UPLOAD_PROGRESS", "$sessionId ${file.name} $percent%")
                }
            }
        }
    }

    /** For JSON calls: retries network errors and 5xx a few times. */
    private fun retrying(label: String, block: () -> HttpResult): HttpResult {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                val r = block()
                if (r.ok) return r
                if (r.code < 500 && r.code != 429) throw FatalUploadError(r.error(label))
                last = r.error(label)
            } catch (e: FatalUploadError) {
                throw e.cause!!
            } catch (e: IOException) {
                last = e
            }
            TagLogger.log(LogCategory.APP, "SERVER_RETRY", "$label attempt=$attempt err=${last?.message}")
            if (attempt < 3) Thread.sleep(2_000L * attempt)
        }
        throw last ?: IOException("$label failed")
    }

    /** A server answer that retrying will not fix (bad request, blocked user, …). */
    private class FatalUploadError(cause: IOException) : Exception(cause)

    private fun uploadOrder(name: String): Int = when {
        name.equals("manifest.json", true) -> 0
        name.endsWith(".log", true) -> 1
        name.endsWith(".xlsx", true) -> 2
        name.endsWith(".mp4", true) -> 3
        else -> 4
    }

    companion object {
        private const val PREFS = "tag_server_auth_v1"
        private const val KEY_TOKEN = "token"
        private const val KEY_CLOUD_USER = "cloud_user_id"
        private const val KEY_LOCAL_USER = "local_user_id"
        private const val KEY_SERVER = "server_url"

        /** 8 MB: small enough to resend quickly after a drop, large enough to keep throughput. */
        private const val CHUNK_BYTES = 8 * 1024 * 1024
        private const val MAX_CHUNK_FAILURES = 6
        private val UPLOAD_EXTENSIONS = setOf("mp4", "xlsx", "log", "json", "csv", "txt")
    }
}
