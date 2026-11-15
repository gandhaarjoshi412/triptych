package com.multicamstudio.core.cloud

import com.multicamstudio.domain.upload.ResumableProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

data class DriveFile(
    val id: String,
    val name: String,
    val size: Long?,
    val md5Checksum: String?,
    val mimeType: String?,
    val parents: List<String>,
    val trashed: Boolean,
)

class DriveHttpException(val code: Int, val body: String) : IOException("Drive HTTP $code: ${body.take(300)}") {
    val isAuth get() = code == 401
    val isTransient get() = ResumableProtocol.isTransientHttp(code) || (code == 403 && (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded")))
    val isQuota get() = code == 403 && body.contains("storageQuotaExceeded")
}

sealed interface ResumableStatus {
    data class Complete(val file: DriveFile) : ResumableStatus
    data class Incomplete(val nextOffset: Long) : ResumableStatus
    /** Session expired or unknown (404/410): start a new resumable session. */
    data object Expired : ResumableStatus
}

/**
 * Minimal Google Drive v3 REST client (OkHttp). Only what the app needs: folders, metadata, rename and
 * resumable uploads. It never calls a delete endpoint – deleting local copies must never touch Drive.
 */
class DriveClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    suspend fun aboutEmail(token: String): String? = io {
        val req = Request.Builder().url("$API/about?fields=user(emailAddress)").auth(token).get().build()
        http.newCall(req).execute().use { r ->
            val body = r.check()
            json.parseToJsonElement(body).jsonObject["user"]?.jsonObject?.get("emailAddress")?.jsonPrimitive?.contentOrNull
        }
    }

    suspend fun createFolder(token: String, name: String, parentId: String?): DriveFile = io {
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            if (parentId != null) putJsonArray("parents") { add(parentId) }
        }
        val req = Request.Builder().url("$API/files?fields=$FIELDS").auth(token)
            .post(meta.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { r -> parseFile(r.check()) }
    }

    /** Metadata or null if the file does not exist / is not visible to this app. */
    suspend fun getFile(token: String, id: String): DriveFile? = io {
        val req = Request.Builder().url("$API/files/$id?fields=$FIELDS").auth(token).get().build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) null else parseFile(r.check())
        }
    }

    suspend fun rename(token: String, id: String, newName: String): DriveFile = io {
        val meta = buildJsonObject { put("name", newName) }
        val req = Request.Builder().url("$API/files/$id?fields=$FIELDS").auth(token)
            .patch(meta.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { r -> parseFile(r.check()) }
    }

    /**
     * Starts a resumable session. With [existingFileId] the content of that file is replaced (used for the
     * manifest after a rename); otherwise a new file is created in [parentId]. Returns the session URI.
     */
    suspend fun startResumable(token: String, name: String, parentId: String, mimeType: String, size: Long, existingFileId: String?): String = io {
        val meta = buildJsonObject {
            put("name", name)
            if (existingFileId == null) putJsonArray("parents") { add(parentId) }
        }
        val url = if (existingFileId == null) "$UPLOAD/files?uploadType=resumable&fields=$FIELDS"
        else "$UPLOAD/files/$existingFileId?uploadType=resumable&fields=$FIELDS"
        val body = meta.toString().toRequestBody(jsonType)
        val req = Request.Builder().url(url).auth(token)
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", size.toString())
            .method(if (existingFileId == null) "POST" else "PATCH", body)
            .build()
        http.newCall(req).execute().use { r ->
            r.check()
            r.header("Location") ?: throw IOException("Resumable session URI missing")
        }
    }

    /** Asks the server how much of the upload it has (PUT with Content-Range: bytes * /total). */
    suspend fun queryResumable(sessionUri: String, total: Long): ResumableStatus = io {
        val req = Request.Builder().url(sessionUri.toHttpUrl())
            .header("Content-Range", ResumableProtocol.statusQueryContentRange(total))
            .put(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r -> interpret(r) }
    }

    suspend fun uploadChunk(sessionUri: String, data: ByteArray, length: Int, start: Long, total: Long, mimeType: String): ResumableStatus = io {
        val req = Request.Builder().url(sessionUri.toHttpUrl())
            .header("Content-Range", ResumableProtocol.contentRange(start, length.toLong(), total))
            .put(data.toRequestBody(mimeType.toMediaType(), 0, length))
            .build()
        http.newCall(req).execute().use { r -> interpret(r) }
    }

    private fun interpret(r: Response): ResumableStatus = when (r.code) {
        200, 201 -> ResumableStatus.Complete(parseFile(r.body?.string().orEmpty()))
        308 -> ResumableStatus.Incomplete(ResumableProtocol.nextOffsetFromRange(r.header("Range")))
        404, 410 -> ResumableStatus.Expired
        else -> throw DriveHttpException(r.code, r.body?.string().orEmpty())
    }

    private fun parseFile(body: String): DriveFile {
        val o: JsonObject = json.parseToJsonElement(body).jsonObject
        return DriveFile(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Drive response without id"),
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            md5Checksum = o["md5Checksum"]?.jsonPrimitive?.contentOrNull,
            mimeType = o["mimeType"]?.jsonPrimitive?.contentOrNull,
            parents = (o["parents"] as? JsonArray)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            trashed = o["trashed"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private fun Response.check(): String {
        val text = body?.string().orEmpty()
        if (!isSuccessful) throw DriveHttpException(code, text)
        return text
    }

    private fun Request.Builder.auth(token: String) = header("Authorization", "Bearer $token")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FIELDS = "id,name,size,md5Checksum,mimeType,parents,trashed"
        fun folderUrl(id: String) = "https://drive.google.com/drive/folders/$id"
    }
}
