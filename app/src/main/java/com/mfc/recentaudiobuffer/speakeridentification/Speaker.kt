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
