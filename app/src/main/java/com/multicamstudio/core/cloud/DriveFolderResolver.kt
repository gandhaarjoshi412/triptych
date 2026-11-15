package com.multicamstudio.core.cloud

import com.multicamstudio.data.local.AppDatabase
import com.multicamstudio.data.local.DriveFolderEntity
import com.multicamstudio.data.local.SessionEntity
import com.multicamstudio.data.prefs.SettingsRepository
import com.multicamstudio.domain.session.SessionNaming

/**
 * Drive layout: <root>/<yyyy-MM-dd>/<HH-mm-ss[_label]>/{front,rear_main,rear_wide,audio,metadata}
 *
 * Folders are always addressed by their persisted Drive ids, never looked up by name/path. Ids are
 * re-validated once per process; a missing/trashed folder (e.g. a different account) is recreated.
 * With the drive.file scope the app can only see folders it created, so the root folder is app-created;
 * the user can freely rename/move it in Drive afterwards – the id keeps working.
 */
class DriveFolderResolver(
    private val db: AppDatabase,
    private val drive: DriveClient,
    private val settings: SettingsRepository,
) {
    private val validated = mutableSetOf<String>()

    suspend fun rootId(token: String): String =
        ensure(token, "root", settings.current().driveRootFolderName, null)

    suspend fun sessionFolderId(token: String, session: SessionEntity): String {
        val root = rootId(token)
        val date = ensure(token, "date:${session.localDate}", session.localDate, root)
        val id = ensure(token, "session:${session.sessionId}", sessionFolderName(session), date)
        if (session.driveFolderId != id) db.sessions().setDriveFolder(session.sessionId, id)
        return id
    }

    suspend fun subFolderId(token: String, session: SessionEntity, sub: String): String {
        val parent = sessionFolderId(token, session)
        if (sub.isEmpty()) return parent
        return ensure(token, "session:${session.sessionId}/$sub", sub, parent)
    }

    private suspend fun ensure(token: String, key: String, name: String, parentId: String?): String {
        val cached = db.driveFolders().get(key)
        if (cached != null && (cached.parentDriveId == parentId || parentId == null)) {
            if (cached.driveId in validated) return cached.driveId
            val remote = drive.getFile(token, cached.driveId)
            if (remote != null && !remote.trashed) {
                validated += cached.driveId
                return cached.driveId
            }
        }
        val created = drive.createFolder(token, name, parentId)
        db.driveFolders().upsert(DriveFolderEntity(key, created.id, name, parentId, System.currentTimeMillis()))
        validated += created.id
        return created.id
    }

    fun forgetValidation() = validated.clear()

    companion object {
        /**
         * Default names map to the local folder name (e.g. 09-30-25 or 09-30-25_Business_Shoot).
         * A custom display name becomes HH-mm-ss_<sanitized name>.
         */
        fun sessionFolderName(session: SessionEntity, displayName: String = session.displayName): String {
            val localName = session.relativeDir.substringAfterLast('/')
            val isDefault = displayName == session.sessionId || displayName.endsWith(" ${session.sessionId}")
            if (isDefault) return localName
            val time = localName.substringBefore('_')
            val label = SessionNaming.sanitize(displayName)
            return if (label.isEmpty()) time else "${time}_$label"
        }
    }
}
