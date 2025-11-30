package com.aurora.auctionmid.item;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for exposing item data to the front-end.
 */
public record ItemResponse(
        Long itemId,
        Long sellerId,
        String title,
        String description,
        BigDecimal startingPrice,
        BigDecimal currentPrice,
        BigDecimal minimumPrice,
        String auctionType,
        String status,
        Long currentWinnerId,
        LocalDateTime createdAt,
        LocalDateTime endTime,

        // Extra descriptive / shipping fields
        String conditionCode,
        String coverImageUrl,
        BigDecimal shipCostStd,
        BigDecimal shipCostExp,
        Integer shipDays,
        String category,
        String keywords,
        Integer quantity
) {}
