package com.aurora.auctionmid.bid;

import com.aurora.auctionmid.item.ItemEntity;
import com.aurora.auctionmid.item.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BidService {

    private final BidRepository bidRepository;
    private final ItemRepository itemRepository;

    // Use the same fixed zone as ItemService
    private static final ZoneId AUCTION_ZONE = ZoneId.of("America/Toronto");

    private void refreshStatusIfNeeded(ItemEntity item) {
        if (item == null) return;
        if (!"ACTIVE".equalsIgnoreCase(item.getStatus())) {
            return;
        }
        var endTime = item.getEndTime();
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

    public BidResponse placeBid(Long itemId, BidRequest request) {
        ItemEntity item = itemRepository.findById(itemId)
            .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        refreshStatusIfNeeded(item);

        if (!"FORWARD".equalsIgnoreCase(item.getAuctionType())) {
            throw new IllegalArgumentException("Bidding is only allowed on FORWARD auctions");
        }

        if (!"ACTIVE".equalsIgnoreCase(item.getStatus())) {
            throw new IllegalArgumentException("Auction is not active");
        }

        if (item.getEndTime() != null) {
            LocalDateTime nowToronto = ZonedDateTime.now(AUCTION_ZONE).toLocalDateTime();
            if (nowToronto.isAfter(item.getEndTime())) {
                item.setStatus("ENDED");
                itemRepository.save(item);
                throw new IllegalArgumentException("Auction has ended");
            }
        }

        if (request.bidderId() == null) {
            throw new IllegalArgumentException("bidderId is required");
        }
        if (request.amount() == null) {
            throw new IllegalArgumentException("amount is required");
        }

        BigDecimal amount = request.amount();

        if (amount.compareTo(item.getStartingPrice()) < 0) {
            throw new IllegalArgumentException("Bid must be >= starting price");
        }
        if (amount.compareTo(item.getCurrentPrice()) <= 0) {
            throw new IllegalArgumentException("Bid must be higher than current price");
        }

        BidEntity bid = BidEntity.builder()
                .itemId(itemId)
                .bidderId(request.bidderId())
                .amount(amount)
                .build();

        BidEntity savedBid = bidRepository.save(bid);

        item.setCurrentPrice(amount);
        item.setCurrentWinnerId(request.bidderId());
        itemRepository.save(item);

        return new BidResponse(
                savedBid.getBidId(),
                savedBid.getItemId(),
                savedBid.getBidderId(),
                savedBid.getAmount(),
                savedBid.getBidTime()
        );
    }

    public List<BidResponse> getBidsForItem(Long itemId) {
        return bidRepository.findByItemIdOrderByAmountDesc(itemId)
                .stream()
                .map(b -> new BidResponse(
                        b.getBidId(),
                        b.getItemId(),
                        b.getBidderId(),
                        b.getAmount(),
                        b.getBidTime()
                ))
                .toList();
    }
}
