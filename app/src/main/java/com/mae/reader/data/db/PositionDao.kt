package com.mae.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mae.reader.data.model.ReadingPosition

@Dao
interface PositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(position: ReadingPosition)

    @Query("SELECT * FROM reading_positions WHERE bookPath = :path LIMIT 1")
    suspend fun get(path: String): ReadingPosition?

    @Query("SELECT * FROM reading_positions ORDER BY lastOpened DESC")
    suspend fun getAll(): List<ReadingPosition>
}
