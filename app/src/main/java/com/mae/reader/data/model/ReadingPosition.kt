package com.mae.reader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_positions")
data class ReadingPosition(
    @PrimaryKey
    val bookPath: String,
    val chapterIndex: Int,
    val scrollY: Int,
    val lastOpened: Long = System.currentTimeMillis()
)
