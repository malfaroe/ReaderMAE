package com.mae.reader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_positions")
data class ReadingPosition(
    @PrimaryKey val bookPath: String,
    val bookTitle: String = "",
    val bookAuthor: String = "",
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val lastOpened: Long = System.currentTimeMillis()
)
