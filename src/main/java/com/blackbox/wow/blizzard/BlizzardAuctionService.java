package com.blackbox.wow.blizzard;

import com.blackbox.wow.properties.BlizzardApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class BlizzardAuctionService {

    private final BlizzardApiClient api;
    private final BlizzardApiProperties props;

    private final BlizzardCache<JsonNode> auctionsCache;
    private final BlizzardCache<JsonNode> commoditiesCache;
    private final BlizzardCache<Long> realmCache;

    public BlizzardAuctionService(BlizzardApiClient api, BlizzardApiProperties props) {
        this.api = api;
        this.props = props;
        long maximumAuctionRows = Math.max(1, props.cache().maxAuctionRows());
        this.auctionsCache = new BlizzardCache<>(maximumAuctionRows, BlizzardAuctionService::auctionRowWeight);
        this.commoditiesCache = new BlizzardCache<>(maximumAuctionRows, BlizzardAuctionService::auctionRowWeight);
        this.realmCache = new BlizzardCache<>(Math.max(1, props.cache().maxRealmEntries()), value -> 1);
    }

    public PriceResult getRegionAverage(long itemId) {
        Duration ttl = Duration.ofSeconds(props.cache().commoditiesTtlSeconds());
        JsonNode data = commoditiesCache.getOrCompute("commodities", ttl,
                () -> api.get("/data/wow/auctions/commodities", null, api.defaultQuery()));

        long totalQty = 0;
        long totalValue = 0;

        for (JsonNode auction : data.path("auctions")) {
            if (auction.path("item").path("id").asLong() != itemId) continue;

            long qty = auction.path("quantity").asLong(1);
            long unitPrice = auction.path("unit_price").asLong(0);
            long buyout = auction.path("buyout").asLong(0);

            long unit;
            if (unitPrice > 0) {
                unit = unitPrice;
            } else if (buyout > 0 && qty > 0) {
                unit = buyout / qty;
            } else {
                continue;
            }

            totalQty += qty;
            totalValue += unit * qty;
        }

        if (totalQty == 0) {
            return PriceResult.notAvailable();
        }

        long avgUnit = totalValue / totalQty;
        return PriceResult.fromCopper(avgUnit);
    }

    public PriceResult getRealmAverage(String realmSlug, long itemId) {
        Long connectedRealmId = resolveConnectedRealmId(realmSlug);
        if (connectedRealmId == null) {
            return PriceResult.notAvailable();
        }

        Long auctionHouseId = resolveFirstAuctionHouseId(connectedRealmId);
        if (auctionHouseId == null) {
            return PriceResult.notAvailable();
        }

        return getAuctionHouseAverage(connectedRealmId, auctionHouseId, itemId);
    }

    public PriceResult getAuctionHouseAverage(long connectedRealmId, long auctionHouseId, long itemId) {
        Duration ttl = Duration.ofSeconds(props.cache().auctionsTtlSeconds());
        String key = connectedRealmId + ":" + auctionHouseId;

        JsonNode data = auctionsCache.getOrCompute(key, ttl,
                () -> api.get("/data/wow/connected-realm/{connectedRealmId}/auctions/{auctionHouseId}",
                        Map.of("connectedRealmId", connectedRealmId, "auctionHouseId", auctionHouseId),
                        api.defaultQuery()));

        long totalQty = 0;
        long totalValue = 0;

        for (JsonNode auction : data.path("auctions")) {
            if (auction.path("item").path("id").asLong() != itemId) continue;

            long qty = auction.path("quantity").asLong(1);
            long buyout = auction.path("buyout").asLong(0);
            long unitPrice = auction.path("unit_price").asLong(0);

            long unit;
            if (unitPrice > 0) {
                unit = unitPrice;
            } else if (buyout > 0 && qty > 0) {
                unit = buyout / qty;
            } else {
                continue;
            }

            totalQty += qty;
            totalValue += unit * qty;
        }

        if (totalQty == 0) {
            return PriceResult.notAvailable();
        }

        long avgUnit = totalValue / totalQty;
        return PriceResult.fromCopper(avgUnit);
    }

    public PriceResult getWowTokenPrice() {
        JsonNode data = api.get("/data/wow/token/index", null, api.defaultQuery());
        long price = data.path("price").asLong(0);
        if (price <= 0) {
            return PriceResult.notAvailable();
        }
        return PriceResult.fromCopper(price);
    }

    private Long resolveConnectedRealmId(String realmSlug) {
        Duration ttl = Duration.ofSeconds(props.cache().realmTtlSeconds());
        String key = "realm:" + realmSlug.toLowerCase();

        return realmCache.getOrCompute(key, ttl, () -> {
            Map<String, Object> query = new HashMap<>(api.defaultQuery());
            query.put("realms.slug", realmSlug.toLowerCase());

            JsonNode result = api.get("/data/wow/search/connected-realm", null, query);
            JsonNode first = result.path("results").isArray() && result.path("results").size() > 0
                    ? result.path("results").get(0)
                    : null;

            if (first == null) return null;
            return first.path("data").path("id").asLong(0) == 0 ? null : first.path("data").path("id").asLong();
        });
    }

    private Long resolveFirstAuctionHouseId(long connectedRealmId) {
        JsonNode index = api.get(
                "/data/wow/connected-realm/{connectedRealmId}/auctions/index",
                Map.of("connectedRealmId", connectedRealmId),
                api.defaultQuery());

        JsonNode auctions = index.path("auctions");
        if (!auctions.isArray() || auctions.isEmpty()) return null;

        JsonNode first = auctions.get(0);
        return first.path("id").asLong(0) == 0 ? null : first.path("id").asLong();
    }

    private static int auctionRowWeight(JsonNode payload) {
        long rows = payload.path("auctions").size();
        return Math.clamp(Math.max(1, rows), 1, Integer.MAX_VALUE);
    }

    public record PriceResult(boolean available, long avgCopper, long gold, long silver, long copper) {
        static PriceResult notAvailable() {
            return new PriceResult(false, 0, 0, 0, 0);
        }

        static PriceResult fromCopper(long avgCopper) {
            long gold = avgCopper / 10_000;
            long silver = (avgCopper % 10_000) / 100;
            long copper = avgCopper % 100;
            return new PriceResult(true, avgCopper, gold, silver, copper);
        }
    }
}
