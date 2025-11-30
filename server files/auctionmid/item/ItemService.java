package com.aurora.auctionmid.item;

import com.aurora.auctionmid.user.UserEntity;
import com.aurora.auctionmid.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final UserRepository userRepository;

    // 🔹 All auction timing logic uses this fixed zone
    private static final ZoneId AUCTION_ZONE = ZoneId.of("America/Toronto");

    /**
     * Ensure the item's status matches its endTime using AUCTION_ZONE.
     * If status is ACTIVE and endTime <= now → flip to ENDED and save.
     */
    private void refreshStatusIfNeeded(ItemEntity item) {
        if (item == null) return;
        if (!"ACTIVE".equalsIgnoreCase(item.getStatus())) {
            return;
        }
        LocalDateTime endTime = item.getEndTime();
        if (endTime == null) {
            return;
        }

        ZonedDateTime end = endTime.atZone(AUCTION_ZONE);
        ZonedDateTime now = ZonedDateTime.now(AUCTION_ZONE);

        if (!end.isAfter(now)) {
            item.setStatus("ENDED");
            itemRepository.save(item);
        }
    }

    public List<ItemResponse> listAllItems() {
        return itemRepository.findAll()
                .stream()
                .peek(this::refreshStatusIfNeeded)
                .map(this::toResponse)
                .toList();
    }

    public List<ItemResponse> listActiveItems() {
        List<ItemEntity> items = itemRepository.findByStatus("ACTIVE");
        items.forEach(this::refreshStatusIfNeeded);

        return items.stream()
                .filter(i -> "ACTIVE".equalsIgnoreCase(i.getStatus()))
                .map(this::toResponse)
                .toList();
    }

    public List<ItemResponse> listEndedItems() {
        List<ItemEntity> all = itemRepository.findAll();
        all.forEach(this::refreshStatusIfNeeded);

        return all.stream()
                .filter(i -> "ENDED".equalsIgnoreCase(i.getStatus()))
                .map(this::toResponse)
                .toList();
    }

    public ItemResponse createItem(ItemRequest request) {
        if (request.sellerId() == null) {
            throw new IllegalArgumentException("sellerId (userId) is required");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (request.startingPrice() == null) {
            throw new IllegalArgumentException("startingPrice is required");
        }

        String auctionType = request.auctionType() != null
                ? request.auctionType().toUpperCase()
                : "FORWARD";

        // ----- Defaults for extra fields -----
        String conditionCode = request.conditionCode() != null
                ? request.conditionCode().toUpperCase()
                : "USED";

        BigDecimal shipCostStd = request.shipCostStd() != null
                ? request.shipCostStd()
                : BigDecimal.ZERO;

        BigDecimal shipCostExp = request.shipCostExp() != null
                ? request.shipCostExp()
                : BigDecimal.ZERO;

        Integer shipDays = request.shipDays() != null
                ? request.shipDays()
                : 5;

        Integer quantity = request.quantity() != null
                ? request.quantity()
                : 1;

        // ----- Build and save entity -----
        ItemEntity entity = ItemEntity.builder()
                .sellerId(request.sellerId())
                .title(request.title())
                .description(request.description())
                .conditionCode(conditionCode)
                .coverImageUrl(request.coverImageUrl())
                .shipCostStd(shipCostStd)
                .shipCostExp(shipCostExp)
                .shipDays(shipDays)
                .startingPrice(request.startingPrice())
                .currentPrice(request.startingPrice())
                .minimumPrice(request.minimumPrice())
                .auctionType(auctionType)
                .status("ACTIVE")
                .endTime(request.endTime())
                .category(request.category())
                // keywords not collected from UI right now – leave null
                .quantity(quantity)
                .paymentStatus("UNPAID")
                .build();

        ItemEntity saved = itemRepository.save(entity);
        return toResponse(saved);
    }

    public List<ItemResponse> searchItems(String query) {
        if (query == null || query.isBlank()) {
            return listAllItems();
        }

        List<ItemEntity> results = itemRepository
                .findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(query, query);

        results.forEach(this::refreshStatusIfNeeded);

        return results.stream()
                .map(this::toResponse)
                .toList();
    }

    public ItemResponse getItem(Long itemId) {
        ItemEntity entity = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        refreshStatusIfNeeded(entity);

        return toResponse(entity);
    }

    public ItemResponse endAuction(Long itemId) {
        ItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        refreshStatusIfNeeded(item);

        if ("ENDED".equalsIgnoreCase(item.getStatus())) {
            return toResponse(item);
        }

        item.setStatus("ENDED");
        ItemEntity saved = itemRepository.save(item);
        return toResponse(saved);
    }

    // ---- Dutch pricing ----

    public BigDecimal calculateCurrentDutchPrice(ItemEntity item, LocalDateTime now) {
        if (!"DUTCH".equalsIgnoreCase(item.getAuctionType())) {
            throw new IllegalArgumentException("Not a Dutch auction");
        }

        if (item.getCreatedAt() == null || item.getEndTime() == null) {
            return item.getCurrentPrice();
        }

        BigDecimal start = item.getStartingPrice();
        BigDecimal min = item.getMinimumPrice() != null ? item.getMinimumPrice() : BigDecimal.ZERO;

        Duration total = Duration.between(item.getCreatedAt(), item.getEndTime());
        Duration elapsed = Duration.between(item.getCreatedAt(), now);

        if (elapsed.isNegative()) {
            return start;
        }
        if (elapsed.compareTo(total) >= 0) {
            return min;
        }

        double fraction = (double) elapsed.toMillis() / (double) total.toMillis();
        BigDecimal drop = start.subtract(min)
                .multiply(BigDecimal.valueOf(fraction));

        BigDecimal current = start.subtract(drop);
        if (current.compareTo(min) < 0) {
            current = min;
        }
        return current;
    }

    public BigDecimal getCurrentDutchPrice(Long itemId) {
        ItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        if (!"DUTCH".equalsIgnoreCase(item.getAuctionType())) {
            throw new IllegalArgumentException("Not a Dutch auction");
        }

        LocalDateTime nowToronto = ZonedDateTime.now(AUCTION_ZONE).toLocalDateTime();
        return calculateCurrentDutchPrice(item, nowToronto);
    }

    public ItemResponse acceptDutch(Long itemId, Long buyerId) {
        ItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        refreshStatusIfNeeded(item);

        if (!"DUTCH".equalsIgnoreCase(item.getAuctionType())) {
            throw new IllegalArgumentException("Not a Dutch auction");
        }
        if (!"ACTIVE".equalsIgnoreCase(item.getStatus())) {
            throw new IllegalArgumentException("Auction is not active");
        }
        if (buyerId == null) {
            throw new IllegalArgumentException("buyerId is required");
        }

        LocalDateTime nowToronto = ZonedDateTime.now(AUCTION_ZONE).toLocalDateTime();
        if (item.getEndTime() != null && nowToronto.isAfter(item.getEndTime())) {
            item.setStatus("ENDED");
            itemRepository.save(item);
            throw new IllegalArgumentException("Auction has ended");
        }

        BigDecimal price = calculateCurrentDutchPrice(item, nowToronto);

        item.setCurrentPrice(price);
        item.setStatus("ENDED");
        item.setCurrentWinnerId(buyerId);

        ItemEntity saved = itemRepository.save(item);
        return toResponse(saved);
    }

    /**
     * Simulate payment for an item:
     * - Auction must be ENDED
     * - There must be a winner
     * - payerId must match currentWinnerId
     * - PaymentStatus must not already be PAID
     */
    public ReceiptResponse payForItem(Long itemId, PaymentRequest request) {
        ItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        refreshStatusIfNeeded(item);

        if (!"ENDED".equalsIgnoreCase(item.getStatus())) {
            throw new IllegalArgumentException("Auction has not ended yet");
        }

        if (item.getCurrentWinnerId() == null) {
            throw new IllegalArgumentException("No winner for this auction");
        }

        if (request == null || request.payerId() == null) {
            throw new IllegalArgumentException("payerId is required");
        }

        if (!item.getCurrentWinnerId().equals(request.payerId())) {
            throw new IllegalArgumentException("Only the winning bidder can pay for this item");
        }

        if ("PAID".equalsIgnoreCase(item.getPaymentStatus())) {
            return getReceipt(itemId);
        }

        item.setPaymentStatus("PAID");
        item.setPaymentTime(ZonedDateTime.now(AUCTION_ZONE).toLocalDateTime());
        itemRepository.save(item);

        return getReceipt(itemId);
    }

    public ReceiptResponse getReceipt(Long itemId) {
        ItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        refreshStatusIfNeeded(item);

        if (!"ENDED".equalsIgnoreCase(item.getStatus())) {
            throw new IllegalArgumentException("Auction has not ended yet");
        }

        UserEntity sellerEntity = userRepository.findById(item.getSellerId())
                .orElse(null);

        ReceiptUserView seller = sellerEntity != null
                ? new ReceiptUserView(
                        sellerEntity.getUserId(),
                        sellerEntity.getUsername(),
                        sellerEntity.getFirstName(),
                        sellerEntity.getLastName(),
                        sellerEntity.getEmail()
        )
                : null;

        ReceiptUserView buyer = null;
        if (item.getCurrentWinnerId() != null) {
            UserEntity buyerEntity = userRepository.findById(item.getCurrentWinnerId())
                    .orElse(null);
            if (buyerEntity != null) {
                buyer = new ReceiptUserView(
                        buyerEntity.getUserId(),
                        buyerEntity.getUsername(),
                        buyerEntity.getFirstName(),
                        buyerEntity.getLastName(),
                        buyerEntity.getEmail()
                );
            }
        }

        BigDecimal finalPrice = item.getCurrentPrice();

        return new ReceiptResponse(
                item.getItemId(),
                item.getTitle(),
                item.getAuctionType(),
                item.getStatus(),
                finalPrice,
                item.getCreatedAt(),
                item.getEndTime(),
                seller,
                buyer,
                item.getPaymentStatus(),
                item.getPaymentTime()
        );
    }

    // ---- DTO mapping ----

    private ItemResponse toResponse(ItemEntity e) {
        return new ItemResponse(
                e.getItemId(),
                e.getSellerId(),
                e.getTitle(),
                e.getDescription(),
                e.getStartingPrice(),
                e.getCurrentPrice(),
                e.getMinimumPrice(),
                e.getAuctionType(),
                e.getStatus(),
                e.getCurrentWinnerId(),
                e.getCreatedAt(),
                e.getEndTime(),
                e.getConditionCode(),
                e.getCoverImageUrl(),
                e.getShipCostStd(),
                e.getShipCostExp(),
                e.getShipDays(),
                e.getCategory(),
                e.getKeywords(),
                e.getQuantity()
        );
    }
}
