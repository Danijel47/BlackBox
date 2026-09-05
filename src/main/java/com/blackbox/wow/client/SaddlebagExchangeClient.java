package com.blackbox.wow.client;

import com.blackbox.wow.properties.HousingMarketProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class SaddlebagExchangeClient {

    private static final String TSM_STATS_PATH = "/api/wow/v2/tsmstats";
    private static final String USER_AGENT =
            "BlackBox-WoW-Market (+https://github.com/Danijel47/TelegramBot)";
    private static final String RESPONSE_ERROR_PREFIX = "Saddlebag Exchange returned ";
    private static final String DATA_FIELD = "data";
    private static final String ITEM_ID_FIELD = "itemID";
    private static final String ITEM_NAME_FIELD = "itemName";
    private static final String EU_MARKET_VALUE_FIELD = "eu_market_value";
    private static final String EU_HISTORICAL_FIELD = "eu_historical";
    private static final String EU_AVERAGE_PRICE_FIELD = "eu_average_price";
    private static final String EU_SALE_RATE_FIELD = "eu_sale_rate";
    private static final String EU_SOLD_PER_DAY_FIELD = "eu_sold_per_day";
    private static final int MAXIMUM_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MAXIMUM_ITEM_IDS = 10_000;
    private static final int MAXIMUM_ITEM_NAME_LENGTH = 250;
    private static final int MAXIMUM_CACHE_ENTRIES = 4;
    private static final long COPPER_PER_GOLD = 10_000L;
    private static final double MAXIMUM_SOLD_PER_DAY = 1_000_000D;

    private final RestClient restClient;
    private final JsonMapper json;
    private final Clock clock;
    private final Cache<List<Long>, List<RegionItem>> cache;

    public SaddlebagExchangeClient(
            @Qualifier("saddlebagExchangeRestClient") RestClient restClient,
            JsonMapper json,
            Clock clock,
            HousingMarketProperties properties
    ) {
        this.restClient = restClient;
        this.json = json;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_CACHE_ENTRIES)
                .expireAfterWrite(Duration.ofHours(properties.tsmCacheHours()))
                .build();
    }

    public List<RegionItem> getEuRetailItems(Set<Long> itemIds) {
        List<Long> requestedIds = validateAndSortItemIds(itemIds);
        if (requestedIds.isEmpty()) {
            return List.of();
        }
        return cache.get(requestedIds, this::fetchEuRetailItems);
    }

    private List<RegionItem> fetchEuRetailItems(List<Long> itemIds) {
        byte[] response;
        try {
            response = restClient.post()
                    .uri(TSM_STATS_PATH)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new TsmStatsRequest(itemIds, false, "retail"))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            log.warn("Saddlebag Exchange TSM request failed with HTTP {}", e.getStatusCode().value());
            throw e;
        } catch (RestClientException e) {
            log.warn("Saddlebag Exchange TSM request failed ({})", e.getClass().getSimpleName());
            throw e;
        }
        validateResponseSize(response);
        return parseRegionItems(response, Set.copyOf(itemIds), Instant.now(clock));
    }

    private static List<Long> validateAndSortItemIds(Set<Long> itemIds) {
        if (itemIds == null) {
            throw new IllegalArgumentException("Item IDs are required.");
        }
        if (itemIds.size() > MAXIMUM_ITEM_IDS) {
            throw new IllegalArgumentException("Too many item IDs were requested.");
        }
        if (itemIds.stream().anyMatch(itemId -> itemId == null || itemId <= 0)) {
            throw new IllegalArgumentException("Item IDs must be positive.");
        }
        return itemIds.stream().sorted().toList();
    }

    private static void validateResponseSize(byte[] response) {
        if (response == null || response.length == 0) {
            throw new IllegalStateException(RESPONSE_ERROR_PREFIX + "an empty response.");
        }
        if (response.length > MAXIMUM_RESPONSE_BYTES) {
            throw new IllegalStateException("Saddlebag Exchange exceeded the maximum response size.");
        }
    }

    private List<RegionItem> parseRegionItems(byte[] response, Set<Long> requestedIds, Instant fetchedAt) {
        JsonNode root;
        try {
            root = json.readTree(response);
        } catch (IOException e) {
            throw new IllegalStateException(RESPONSE_ERROR_PREFIX + "invalid JSON data.", e);
        }
        JsonNode data = root.get(DATA_FIELD);
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("Saddlebag Exchange response has no data array.");
        }

        List<RegionItem> items = new ArrayList<>(data.size());
        Set<Long> seenItemIds = new HashSet<>();
        for (JsonNode row : data) {
            RegionItem item = parseRegionItem(row, fetchedAt);
            if (!requestedIds.contains(item.itemId())) {
                throw new IllegalStateException(RESPONSE_ERROR_PREFIX + "an unexpected item ID.");
            }
            if (!seenItemIds.add(item.itemId())) {
                throw new IllegalStateException(RESPONSE_ERROR_PREFIX + "a duplicate item ID.");
            }
            items.add(item);
        }
        return List.copyOf(items);
    }

    private static RegionItem parseRegionItem(JsonNode row, Instant fetchedAt) {
        if (!row.isObject()) {
            throw new IllegalStateException(RESPONSE_ERROR_PREFIX + "an invalid item row.");
        }
        long itemId = positiveLong(row.get(ITEM_ID_FIELD), "item ID");
        String name = requiredText(row.get(ITEM_NAME_FIELD), "item name");
        long marketValue = goldToCopper(nonNegativeLong(row.get(EU_MARKET_VALUE_FIELD), "market value"));
        long historical = goldToCopper(nonNegativeLong(row.get(EU_HISTORICAL_FIELD), "historical price"));
        long averagePrice = goldToCopper(nonNegativeLong(row.get(EU_AVERAGE_PRICE_FIELD), "average price"));
        double saleRate = boundedDouble(row.get(EU_SALE_RATE_FIELD), "sale rate", 1D);
        double soldPerDay = boundedDouble(
                row.get(EU_SOLD_PER_DAY_FIELD),
                "sold per day",
                MAXIMUM_SOLD_PER_DAY
        );
        return new RegionItem(
                itemId,
                name,
                marketValue,
                historical,
                averagePrice,
                saleRate,
                soldPerDay,
                fetchedAt
        );
    }

    private static long positiveLong(JsonNode node, String label) {
        long value = nonNegativeLong(node, label);
        if (value == 0) {
            throw invalidField(label);
        }
        return value;
    }

    private static long nonNegativeLong(JsonNode node, String label) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalidField(label);
        }
        long value = node.longValue();
        if (value < 0) {
            throw invalidField(label);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String label) {
        if (node == null || !node.isTextual()) {
            throw invalidField(label);
        }
        String value = node.textValue().trim();
        if (value.isEmpty() || value.length() > MAXIMUM_ITEM_NAME_LENGTH) {
            throw invalidField(label);
        }
        return value;
    }

    private static double boundedDouble(JsonNode node, String label, double maximum) {
        if (node == null || !node.isNumber()) {
            throw invalidField(label);
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < 0D || value > maximum) {
            throw invalidField(label);
        }
        return value;
    }

    private static long goldToCopper(long gold) {
        try {
            return Math.multiplyExact(gold, COPPER_PER_GOLD);
        } catch (ArithmeticException e) {
            throw new IllegalStateException(RESPONSE_ERROR_PREFIX + "an invalid price.", e);
        }
    }

    private static IllegalStateException invalidField(String label) {
        return new IllegalStateException(RESPONSE_ERROR_PREFIX + "an invalid " + label + ".");
    }

    private record TsmStatsRequest(
            @JsonProperty("item_ids") List<Long> itemIds,
            boolean pets,
            @JsonProperty("game_edition") String gameEdition
    ) {
    }

    public record RegionItem(
            long itemId,
            String name,
            long marketValueCopper,
            long historicalCopper,
            long averageSalePriceCopper,
            double saleRate,
            double soldPerDay,
            Instant updatedAt
    ) {
    }
}
