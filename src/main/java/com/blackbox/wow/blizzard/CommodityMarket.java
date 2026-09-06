package com.blackbox.wow.blizzard;

import com.blackbox.wow.blizzard.BlizzardApiClient.ApiSnapshot;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record CommodityMarket(Map<Long, List<Offer>> offers, Instant sourceUpdatedAt, Instant fetchedAt) {

    public CommodityMarket {
        Map<Long, List<Offer>> copy = new HashMap<>();
        offers.forEach((itemId, rows) -> copy.put(itemId, rows.stream()
                .sorted(Comparator.comparingLong(Offer::unitCopper))
                .toList()));
        offers = Map.copyOf(copy);
    }

    static CommodityMarket fromSnapshot(ApiSnapshot snapshot, Set<Long> itemIds) {
        JsonNode auctions = snapshot.data().path("auctions");
        if (!auctions.isArray()) {
            throw new IllegalStateException("Blizzard commodity data has no auction array.");
        }
        Map<Long, List<Offer>> offers = new HashMap<>();
        for (JsonNode row : auctions) {
            long itemId = positiveLong(row.path("item").path("id"));
            if (itemIds.contains(itemId)) {
                addOffer(offers, itemId, row);
            }
        }
        return new CommodityMarket(offers, snapshot.sourceUpdatedAt(), snapshot.fetchedAt());
    }

    private static void addOffer(Map<Long, List<Offer>> offers, long itemId, JsonNode row) {
        long unitCopper = positiveLong(row.path("unit_price"));
        long quantity = positiveLong(row.path("quantity"));
        if (unitCopper > 0 && quantity > 0) {
            offers.computeIfAbsent(itemId, _ -> new ArrayList<>()).add(new Offer(unitCopper, quantity));
        }
    }

    private static long positiveLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() ? Math.max(0, value.longValue()) : 0;
    }

    public Optional<BigDecimal> lowestUnitPrice(long itemId) {
        List<Offer> rows = offers.getOrDefault(itemId, List.of());
        return rows.isEmpty() ? Optional.empty() : Optional.of(BigDecimal.valueOf(rows.getFirst().unitCopper()));
    }

    public Optional<BigDecimal> purchaseCost(long itemId, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Purchase quantity must be positive.");
        }
        long remaining = quantity;
        BigDecimal cost = BigDecimal.ZERO;
        for (Offer offer : offers.getOrDefault(itemId, List.of())) {
            long purchased = Math.min(remaining, offer.quantity());
            cost = cost.add(BigDecimal.valueOf(offer.unitCopper()).multiply(BigDecimal.valueOf(purchased)));
            remaining -= purchased;
            if (remaining == 0) {
                return Optional.of(cost);
            }
        }
        return Optional.empty();
    }

    public record Offer(long unitCopper, long quantity) {
        public Offer {
            if (unitCopper <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("Commodity price and quantity must be positive.");
            }
        }
    }
}
