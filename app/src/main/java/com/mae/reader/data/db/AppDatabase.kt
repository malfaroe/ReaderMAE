package com.mae.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mae.reader.data.model.ReadingPosition

@Database(entities = [ReadingPosition::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun positionDao(): PositionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "readermae.db"
                )
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
    }
}
