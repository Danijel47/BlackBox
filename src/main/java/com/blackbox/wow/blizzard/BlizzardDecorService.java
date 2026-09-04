package com.blackbox.wow.blizzard;

import com.blackbox.wow.properties.HousingMarketProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class BlizzardDecorService {

    private static final String DECOR_SEARCH_PATH = "/data/wow/search/decor";
    private static final String DECOR_CACHE_KEY = "decor-auction-item-ids";
    private static final String PAGE_QUERY_PARAM = "_page";
    private static final String ORDER_BY_QUERY_PARAM = "orderby";
    private static final String DECOR_ID_ORDER = "id:asc";
    private static final int FIRST_PAGE = 1;
    private static final int MAXIMUM_PAGES = 50;
    private static final int MAXIMUM_DECOR_ITEMS = 10_000;

    private final BlizzardApiClient api;
    private final Cache<String, Set<Long>> cache;

    public BlizzardDecorService(BlizzardApiClient api, HousingMarketProperties properties) {
        this.api = api;
        this.cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(Duration.ofHours(properties.catalogCacheHours()))
                .build();
    }

    public Set<Long> getDecorAuctionItemIds() {
        return cache.get(DECOR_CACHE_KEY, ignored -> fetchDecorAuctionItemIds());
    }

    private Set<Long> fetchDecorAuctionItemIds() {
        Set<Long> itemIds = new LinkedHashSet<>();
        int pageCount = FIRST_PAGE;
        for (int page = FIRST_PAGE; page <= pageCount; page++) {
            JsonNode payload = fetchPage(page);
            int reportedPageCount = validatedPageCount(payload);
            if (page > FIRST_PAGE && reportedPageCount != pageCount) {
                throw new IllegalStateException("Blizzard decor catalog page count changed during retrieval.");
            }
            pageCount = reportedPageCount;
            collectItemIds(payload, itemIds);
        }
        if (itemIds.isEmpty()) {
            throw new IllegalStateException("Blizzard decor catalog contains no auction item IDs.");
        }
        return Set.copyOf(itemIds);
    }

    private JsonNode fetchPage(int page) {
        Map<String, Object> query = new HashMap<>(api.staticQuery());
        query.put(PAGE_QUERY_PARAM, page);
        query.put(ORDER_BY_QUERY_PARAM, DECOR_ID_ORDER);
        return api.get(DECOR_SEARCH_PATH, null, query);
    }

    private static int validatedPageCount(JsonNode payload) {
        int pageCount = payload.path("pageCount").asInt(0);
        if (pageCount < FIRST_PAGE || pageCount > MAXIMUM_PAGES) {
            throw new IllegalStateException("Blizzard decor catalog contains an invalid page count.");
        }
        return pageCount;
    }

    private static void collectItemIds(JsonNode payload, Set<Long> itemIds) {
        JsonNode results = payload.path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("Blizzard decor catalog contains invalid results.");
        }
        for (JsonNode result : results) {
            long itemId = result.path("data").path("item").path("id").asLong(0);
            if (itemId <= 0) {
                continue;
            }
            itemIds.add(itemId);
            if (itemIds.size() > MAXIMUM_DECOR_ITEMS) {
                throw new IllegalStateException("Blizzard decor catalog exceeded the maximum item count.");
            }
        }
    }
}
