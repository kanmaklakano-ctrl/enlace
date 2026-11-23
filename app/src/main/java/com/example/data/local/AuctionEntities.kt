package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.FavoriteLotItem
import com.example.model.SavedSearch

@Entity(tableName = "cached_auctions")
data class CachedAuctionEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val title: String,
    val description: String,
    val currentBid: Double?,
    val currency: String,
    val bidCount: Int,
    val imageUrl: String?,
    val auctionUrl: String,
    val provinceCode: String,
    val city: String,
    val auctionHouse: String,
    val categoryName: String,
    val closingTimeMillis: Long?,
    val closingLabel: String,
    val lotNumber: String,
    val buyersPremiumPercent: Double,
    val fetchedAtMillis: Long
) {
    fun toDomain(): AuctionLot = AuctionLot(
        id = id,
        sourceId = AuctionSourceId.fromNameOrDefault(sourceId),
        title = title,
        description = description,
        currentBid = currentBid,
        currency = currency,
        bidCount = bidCount,
        imageUrl = imageUrl,
        auctionUrl = auctionUrl,
        province = CanadianProvince.fromCodeOrText(provinceCode),
        city = city,
        auctionHouse = auctionHouse,
        category = runCatching { AuctionCategory.valueOf(categoryName) }.getOrDefault(AuctionCategory.ALL),
        closingTimeMillis = closingTimeMillis,
        closingLabel = closingLabel,
        lotNumber = lotNumber,
        buyersPremiumPercent = buyersPremiumPercent,
        fetchedAtMillis = fetchedAtMillis
    )

    companion object {
        fun fromDomain(lot: AuctionLot): CachedAuctionEntity = CachedAuctionEntity(
            id = lot.id,
            sourceId = lot.sourceId.name,
            title = lot.title,
            description = lot.description,
            currentBid = lot.currentBid,
            currency = lot.currency,
            bidCount = lot.bidCount,
            imageUrl = lot.imageUrl,
            auctionUrl = lot.auctionUrl,
            provinceCode = lot.province.code,
            city = lot.city,
            auctionHouse = lot.auctionHouse,
            categoryName = lot.category.name,
            closingTimeMillis = lot.closingTimeMillis,
            closingLabel = lot.closingLabel,
            lotNumber = lot.lotNumber,
            buyersPremiumPercent = lot.buyersPremiumPercent,
            fetchedAtMillis = lot.fetchedAtMillis
        )
    }
}

@Entity(tableName = "favorite_auctions")
data class FavoriteAuctionEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val title: String,
    val description: String,
    val currentBid: Double?,
    val currency: String,
    val bidCount: Int,
    val imageUrl: String?,
    val auctionUrl: String,
    val provinceCode: String,
    val city: String,
    val auctionHouse: String,
    val categoryName: String,
    val closingTimeMillis: Long?,
    val closingLabel: String,
    val lotNumber: String,
    val buyersPremiumPercent: Double,
    val fetchedAtMillis: Long,
    val maxBidNote: Double?,
    val personalNote: String,
    val savedAtMillis: Long
) {
    fun toDomain(): FavoriteLotItem = FavoriteLotItem(
        lot = AuctionLot(
            id = id,
            sourceId = AuctionSourceId.fromNameOrDefault(sourceId),
            title = title,
            description = description,
            currentBid = currentBid,
            currency = currency,
            bidCount = bidCount,
            imageUrl = imageUrl,
            auctionUrl = auctionUrl,
            province = CanadianProvince.fromCodeOrText(provinceCode),
            city = city,
            auctionHouse = auctionHouse,
            category = runCatching { AuctionCategory.valueOf(categoryName) }.getOrDefault(AuctionCategory.ALL),
            closingTimeMillis = closingTimeMillis,
            closingLabel = closingLabel,
            lotNumber = lotNumber,
            buyersPremiumPercent = buyersPremiumPercent,
            fetchedAtMillis = fetchedAtMillis
        ),
        maxBidNote = maxBidNote,
        personalNote = personalNote,
        savedAtMillis = savedAtMillis
    )

    companion object {
        fun fromDomain(
            lot: AuctionLot,
            maxBidNote: Double? = null,
            personalNote: String = ""
        ): FavoriteAuctionEntity = FavoriteAuctionEntity(
            id = lot.id,
            sourceId = lot.sourceId.name,
            title = lot.title,
            description = lot.description,
            currentBid = lot.currentBid,
            currency = lot.currency,
            bidCount = lot.bidCount,
            imageUrl = lot.imageUrl,
            auctionUrl = lot.auctionUrl,
            provinceCode = lot.province.code,
            city = lot.city,
            auctionHouse = lot.auctionHouse,
            categoryName = lot.category.name,
            closingTimeMillis = lot.closingTimeMillis,
            closingLabel = lot.closingLabel,
            lotNumber = lot.lotNumber,
            buyersPremiumPercent = lot.buyersPremiumPercent,
            fetchedAtMillis = lot.fetchedAtMillis,
            maxBidNote = maxBidNote,
            personalNote = personalNote,
            savedAtMillis = System.currentTimeMillis()
        )
    }
}

@Entity(tableName = "saved_searches")
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val label: String,
    val query: String,
    val provinceCode: String,
    val categoryName: String,
    val createdAtMillis: Long
) {
    fun toDomain(): SavedSearch = SavedSearch(
        id = id,
        label = label,
        query = query,
        province = CanadianProvince.fromCodeOrText(provinceCode),
        category = runCatching { AuctionCategory.valueOf(categoryName) }.getOrDefault(AuctionCategory.ALL),
        createdAtMillis = createdAtMillis
    )
}
