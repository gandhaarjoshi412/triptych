package com.multicamstudio.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.multicamstudio.domain.model.AssetType
import com.multicamstudio.domain.model.BackupState
import com.multicamstudio.domain.model.CameraRole
import com.multicamstudio.domain.model.FinalizationState
import com.multicamstudio.domain.model.LocalDeletionState
import com.multicamstudio.domain.model.RecordingState
import com.multicamstudio.domain.model.StreamStatus
import com.multicamstudio.domain.model.UploadState

class Converters {
    @TypeConverter fun roleToString(v: CameraRole): String = v.name
    @TypeConverter fun stringToRole(v: String): CameraRole = CameraRole.valueOf(v)
    @TypeConverter fun recToString(v: RecordingState): String = v.name
    @TypeConverter fun stringToRec(v: String): RecordingState = RecordingState.valueOf(v)
    @TypeConverter fun backupToString(v: BackupState): String = v.name
    @TypeConverter fun stringToBackup(v: String): BackupState = BackupState.valueOf(v)
    @TypeConverter fun uploadToString(v: UploadState): String = v.name
    @TypeConverter fun stringToUpload(v: String): UploadState = UploadState.valueOf(v)
    @TypeConverter fun finToString(v: FinalizationState): String = v.name
    @TypeConverter fun stringToFin(v: String): FinalizationState = FinalizationState.valueOf(v)
    @TypeConverter fun delToString(v: LocalDeletionState): String = v.name
    @TypeConverter fun stringToDel(v: String): LocalDeletionState = LocalDeletionState.valueOf(v)
    @TypeConverter fun assetToString(v: AssetType): String = v.name
    @TypeConverter fun stringToAsset(v: String): AssetType = AssetType.valueOf(v)
    @TypeConverter fun streamToString(v: StreamStatus): String = v.name
    @TypeConverter fun stringToStream(v: String): StreamStatus = StreamStatus.valueOf(v)
}

/**
 * Version history:
 *  1 – initial schema.
 * Add explicit [androidx.room.migration.Migration]s for every future version; destructive migration is
 * never enabled because the database tracks which originals are safely backed up.
 */
@Database(
    entities = [
        SessionEntity::class,
        CameraRecordingEntity::class,
        MediaSegmentEntity::class,
        AudioRecordingEntity::class,
        UploadTaskEntity::class,
        DriveFolderEntity::class,
        SessionEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun media(): MediaDao
    abstract fun uploads(): UploadDao
    abstract fun driveFolders(): DriveFolderDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "multicam.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
