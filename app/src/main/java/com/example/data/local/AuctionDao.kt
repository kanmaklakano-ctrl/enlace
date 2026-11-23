package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuctionDao {
    @Query("SELECT * FROM cached_auctions ORDER BY fetchedAtMillis DESC")
    fun observeCachedAuctions(): Flow<List<CachedAuctionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedAuctions(items: List<CachedAuctionEntity>)

    @Query("DELETE FROM cached_auctions WHERE fetchedAtMillis < :cutoffMillis")
    suspend fun purgeOldCache(cutoffMillis: Long)

    @Query("SELECT * FROM favorite_auctions ORDER BY savedAtMillis DESC")
    fun observeFavorites(): Flow<List<FavoriteAuctionEntity>>

    @Query("SELECT id FROM favorite_auctions")
    fun observeFavoriteIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(item: FavoriteAuctionEntity)

    @Query("UPDATE favorite_auctions SET maxBidNote = :maxBid, personalNote = :note WHERE id = :id")
    suspend fun updateFavoriteNotes(id: String, maxBid: Double?, note: String)

    @Query("DELETE FROM favorite_auctions WHERE id = :id")
    suspend fun deleteFavorite(id: String)

    @Query("SELECT * FROM saved_searches ORDER BY createdAtMillis DESC")
    fun observeSavedSearches(): Flow<List<SavedSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedSearch(search: SavedSearchEntity)

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun deleteSavedSearch(id: Long)
}
