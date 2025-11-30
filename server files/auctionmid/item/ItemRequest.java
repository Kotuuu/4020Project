package com.aurora.auctionmid.item;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Request body for creating an item/auction.
 * Most fields are optional except sellerId, title, and startingPrice.
 */
public record ItemRequest(
        Long sellerId,
        String title,
        String description,

        // Pricing / auction core
        BigDecimal startingPrice,
        BigDecimal minimumPrice,       // for DUTCH floor price (optional)
        String auctionType,            // "FORWARD" or "DUTCH"
        LocalDateTime endTime,         // optional end date/time

        // Extra details (all optional)
        String category,
        String conditionCode,
        BigDecimal shipCostStd,
        BigDecimal shipCostExp,
        Integer shipDays,
        Integer quantity,
        String coverImageUrl           // URL from Sell page
) {}
