package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedAuctionEntity::class,
        FavoriteAuctionEntity::class,
        SavedSearchEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class EncanDatabase : RoomDatabase() {
    abstract fun auctionDao(): AuctionDao

    companion object {
        @Volatile
        private var instance: EncanDatabase? = null

        fun getInstance(context: Context): EncanDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EncanDatabase::class.java,
                    "encanlive_cache.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
