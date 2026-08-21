package com.blackbox.wow.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class WowTokenHistoryClient {

    private static final String EU_RETAIL_30_DAY_PATH = "/v2/relative/retail/eu/30d.json";
    private static final int MAXIMUM_HISTORY_ROWS = 5_000;
    private static final long MAXIMUM_TOKEN_PRICE_GOLD = 10_000_000L;
    private static final Duration MAXIMUM_HISTORY_AGE = Duration.ofDays(35);
    private static final Duration MAXIMUM_FUTURE_DRIFT = Duration.ofHours(1);

    private final RestClient restClient;
    private final JsonMapper json;

    public WowTokenHistoryClient(
            @Qualifier("wowTokenHistoryRestClient") RestClient restClient,
            JsonMapper json
    ) {
        this.restClient = restClient;
        this.json = json;
    }

    public List<HistoricalTokenPrice> getEuRetailThirtyDayHistory() {
        String body = restClient.get()
                .uri(EU_RETAIL_30_DAY_PATH)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("WoW Token history provider returned an empty response.");
        }
        try {
            return parseHistory(json.readTree(body), Instant.now());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WoW Token history provider returned invalid JSON.", e);
        }
    }

    static List<HistoricalTokenPrice> parseHistory(JsonNode response, Instant now) {
        if (!response.isArray() || response.isEmpty() || response.size() > MAXIMUM_HISTORY_ROWS) {
            throw new IllegalStateException("WoW Token history response has an invalid row count.");
        }
        Instant oldestAllowed = now.minus(MAXIMUM_HISTORY_AGE);
        Instant newestAllowed = now.plus(MAXIMUM_FUTURE_DRIFT);
        List<HistoricalTokenPrice> prices = new ArrayList<>(response.size());
        Instant previousTimestamp = null;
        for (JsonNode row : response) {
            HistoricalTokenPrice price = parseRow(row, oldestAllowed, newestAllowed);
            if (previousTimestamp != null && !price.capturedAt().isAfter(previousTimestamp)) {
                throw new IllegalStateException("WoW Token history timestamps are not strictly increasing.");
            }
            prices.add(price);
            previousTimestamp = price.capturedAt();
        }
        return List.copyOf(prices);
    }

    private static HistoricalTokenPrice parseRow(JsonNode row, Instant oldestAllowed, Instant newestAllowed) {
        if (!row.isArray() || row.size() != 2 || !row.get(0).isTextual() || !row.get(1).canConvertToLong()) {
            throw new IllegalStateException("WoW Token history contains an invalid row.");
        }
        Instant capturedAt;
        try {
            capturedAt = Instant.parse(row.get(0).asText());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("WoW Token history contains an invalid timestamp.", e);
        }
        long priceGold = row.get(1).asLong();
        if (capturedAt.isBefore(oldestAllowed)
                || capturedAt.isAfter(newestAllowed)
                || priceGold <= 0
                || priceGold > MAXIMUM_TOKEN_PRICE_GOLD) {
            throw new IllegalStateException("WoW Token history contains an out-of-range value.");
        }
        return new HistoricalTokenPrice(capturedAt, Math.multiplyExact(priceGold, 10_000L));
    }

    public record HistoricalTokenPrice(Instant capturedAt, long priceCopper) {
    }
}
