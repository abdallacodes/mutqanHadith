package com.mutqan.hadith.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mutqan.hadith.data.entity.NarratorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NarratorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(narrator: NarratorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(narrators: List<NarratorEntity>)

    @Update
    suspend fun update(narrator: NarratorEntity)

    @Delete
    suspend fun delete(narrator: NarratorEntity)

    @Query("SELECT * FROM narrator WHERE id = :id")
    suspend fun getById(id: Long): NarratorEntity?

    @Query("SELECT * FROM narrator ORDER BY id")
    fun observeAll(): Flow<List<NarratorEntity>>

    @Query("SELECT * FROM narrator WHERE isPivot = 1 ORDER BY id")
    suspend fun getPivots(): List<NarratorEntity>
}
