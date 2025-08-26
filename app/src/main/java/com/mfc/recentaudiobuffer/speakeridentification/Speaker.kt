package com.mfc.recentaudiobuffer.speakeridentification

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.util.UUID

@Entity(tableName = "speakers")
@TypeConverters(Converters::class)
data class Speaker(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val embedding: FloatArray,
    val sampleUri: Uri? = null,  // Add sample URI for playback
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Speaker

        if (id != other.id) return false
        if (name != other.name) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (sampleUri != other.sampleUri) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (sampleUri?.hashCode() ?: 0)
        return result
    }
}
