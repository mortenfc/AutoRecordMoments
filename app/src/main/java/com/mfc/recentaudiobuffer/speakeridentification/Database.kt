/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer.speakeridentification

import android.content.Context
import android.net.Uri
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    @TypeConverter
    fun fromUri(uri: Uri?): String? {
        return uri?.toString()
    }

    @TypeConverter
    fun toUri(uriString: String?): Uri? {
        return uriString?.let { Uri.parse(it) }
    }
}

// --- Room Database Definition ---
@Database(entities = [Speaker::class], version = 2, exportSchema = false)  // Increment version to 2
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun speakerDao(): SpeakerDao
}

// --- Migration from version 1 to 2 ---
object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add the new columns with default values
            db.execSQL("ALTER TABLE speakers ADD COLUMN sampleUri TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE speakers ADD COLUMN createdAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
            db.execSQL("ALTER TABLE speakers ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
            db.execSQL("UPDATE speakers SET createdAt = ${System.currentTimeMillis()} WHERE createdAt IS NULL")
            db.execSQL("UPDATE speakers SET lastUsedAt = ${System.currentTimeMillis()} WHERE lastUsedAt IS NULL")
        }
    }
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
        )
        .addMigrations(DatabaseMigrations.MIGRATION_1_2)  // Add migration
        .fallbackToDestructiveMigration()  // Optional: destroy and recreate if migration fails (for development)
        .build()
    }

    @Provides
    @Singleton
    fun provideSpeakerDao(db: AppDatabase): SpeakerDao {
        return db.speakerDao()
    }
}