package com.mfc.recentaudiobuffer.speakeridentification

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

// --- DAO (Data Access Object) ---
@Dao
interface SpeakerDao {
    @Query("SELECT * FROM speakers ORDER BY name ASC")
    fun getAllSpeakers(): Flow<List<Speaker>>

    @Query("SELECT * FROM speakers WHERE id IN (:ids)")
    suspend fun getSpeakersByIds(ids: List<String>): List<Speaker>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(speakers: List<Speaker>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpeaker(speaker: Speaker)

    @Update
    suspend fun updateSpeaker(speaker: Speaker)

    @Delete
    suspend fun deleteSpeaker(speaker: Speaker)

    @Query("DELETE FROM speakers")
    suspend fun clearAll()
}

// --- Type Converters for Room ---
class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): String? {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toFloatArray(value: String?): FloatArray? {
        if (value == null) return null
        val type = object : TypeToken<FloatArray>() {}.type
        return gson.fromJson(value, type)
    }
}

// --- Room Database Definition ---
@Database(entities = [Speaker::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun speakerDao(): SpeakerDao
}

// --- Hilt Module to Provide the Database ---
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "speaker_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideSpeakerDao(db: AppDatabase): SpeakerDao {
        return db.speakerDao()
    }
}
