package com.blackbox.wow.client;

import com.blackbox.wow.properties.HousingMarketProperties;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.databind.MappingIterator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TsmPublicDataClient {

    private static final String EU_RETAIL_REGION_ITEMS_PATH = "/retail/eu/region/items.csv";
    private static final String REGION_ITEMS_CACHE_KEY = "retail-eu-region-items";
    private static final int MAXIMUM_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MAXIMUM_ROWS = 100_000;
    private static final int MAXIMUM_ITEM_NAME_LENGTH = 250;
    private static final double MAXIMUM_SOLD_PER_DAY = 1_000_000D;
    private static final CsvMapper CSV_MAPPER = CsvMapper.builder().build();
    private static final CsvSchema CSV_SCHEMA = CsvSchema.emptySchema().withHeader();

    private final RestClient restClient;
    private final Cache<String, List<RegionItem>> cache;

    public TsmPublicDataClient(
            @Qualifier("tsmPublicDataRestClient") RestClient restClient,
            HousingMarketProperties properties
    ) {
        this.restClient = restClient;
        this.cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(Duration.ofHours(properties.tsmCacheHours()))
                .build();
    }

    public List<RegionItem> getEuRetailRegionItems() {
        return cache.get(REGION_ITEMS_CACHE_KEY, ignored -> fetchEuRetailRegionItems());
    }

    private List<RegionItem> fetchEuRetailRegionItems() {
        byte[] response;
        try {
            response = restClient.get()
                    .uri(EU_RETAIL_REGION_ITEMS_PATH)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            log.warn("TSM EU regional data request failed with HTTP {}", e.getStatusCode().value());
            throw e;
        } catch (RestClientException e) {
            log.warn("TSM EU regional data request failed ({})", e.getClass().getSimpleName());
            throw e;
        }
        if (response == null || response.length == 0) {
            throw new IllegalStateException("TSM public data returned an empty response.");
        }
        if (response.length > MAXIMUM_RESPONSE_BYTES) {
            throw new IllegalStateException("TSM public data exceeded the maximum response size.");
        }
        return parseRegionItems(response);
    }

    static List<RegionItem> parseRegionItems(byte[] response) {
        Map<Long, RegionItem> items = new LinkedHashMap<>();
        try (MappingIterator<RegionItemCsvRow> rows = CSV_MAPPER
                .readerFor(RegionItemCsvRow.class)
                .with(CSV_SCHEMA)
                .readValues(response)) {
            while (rows.hasNextValue()) {
                if (items.size() >= MAXIMUM_ROWS) {
                    throw new IllegalStateException("TSM public data exceeded the maximum row count.");
                }
                RegionItem item = validate(rows.nextValue());
                if (items.putIfAbsent(item.itemId(), item) != null) {
                    throw new IllegalStateException("TSM public data contains a duplicate item ID.");
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("TSM public data contains invalid CSV data.", e);
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("TSM public data contains no item rows.");
        }
        return List.copyOf(items.values());
    }

    private static RegionItem validate(RegionItemCsvRow row) {
        long itemId = parsePositiveLong(row.itemId(), "item ID");
        String name = row.name() == null ? "" : row.name().trim();
        if (name.isEmpty() || name.length() > MAXIMUM_ITEM_NAME_LENGTH) {
            throw new IllegalArgumentException("Invalid item name.");
        }
        long marketValue = parseNonNegativeLong(row.marketValue(), "market value");
        long historical = parseNonNegativeLong(row.historical(), "historical price");
        long averageSalePrice = parseNonNegativeLong(row.avgSalePrice(), "average sale price");
        double saleRate = parseBoundedDouble(row.saleRate(), "sale rate", 1D);
        double soldPerDay = parseBoundedDouble(row.soldPerDay(), "sold per day", MAXIMUM_SOLD_PER_DAY);
        Instant updatedAt = parseInstant(row.updatedAt());
        return new RegionItem(
                itemId,
                name,
                marketValue,
                historical,
                averageSalePrice,
                saleRate,
                soldPerDay,
                updatedAt
        );
    }

    private static long parsePositiveLong(String value, String label) {
        long parsed = parseNonNegativeLong(value, label);
        if (parsed == 0) {
            throw new IllegalArgumentException("Invalid " + label + ".");
        }
        return parsed;
    }

    private static long parseNonNegativeLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("Invalid " + label + ".");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ".", e);
        }
    }

    private static double parseBoundedDouble(String value, String label, double maximum) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0D || parsed > maximum) {
                throw new IllegalArgumentException("Invalid " + label + ".");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ".", e);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid update timestamp.");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid update timestamp.", e);
        }
    }

    private record RegionItemCsvRow(
            String itemId,
            String name,
            String marketValue,
            String historical,
            String avgSalePrice,
            String saleRate,
            String soldPerDay,
            String updatedAt
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
