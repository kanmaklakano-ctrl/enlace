package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState

data class ConnectorResult(
    val sourceId: AuctionSourceId,
    val state: ConnectorState,
    val lots: List<AuctionLot>,
    val latencyMs: Long,
    val statusDetail: String,
    val directSearchUrl: String
)

interface AuctionConnector {
    val sourceId: AuctionSourceId

    suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult

    fun buildDirectSearchUrl(
        query: String,
        province: CanadianProvince
    ): String
}
