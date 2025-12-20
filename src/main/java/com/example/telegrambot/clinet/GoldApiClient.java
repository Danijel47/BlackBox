package com.example.telegrambot.clinet;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Component
public class GoldApiClient {

    private final RestClient rest;

    public GoldApiClient(RestClient rest) {
        this.rest = rest;
    }

    public PricePoint fetchXau() {
        Map<?, ?> json = rest.get()
                .uri("https://api.gold-api.com/price/XAU")
                .retrieve()
                .body(Map.class);

        if (json == null || json.get("price") == null) {
            throw new IllegalStateException("Gold API returned empty response");
        }

        BigDecimal price = new BigDecimal(json.get("price").toString());

        Instant updatedAt = Instant.now();
        Object updatedAtRaw = json.get("updatedAt");
        if (updatedAtRaw != null) {
            try {
                updatedAt = Instant.parse(updatedAtRaw.toString());
            } catch (Exception _) {
            }
        }

        return new PricePoint("XAU", price, updatedAt);
    }

    public record PricePoint(String symbol, BigDecimal price, Instant updatedAt) {
    }
}

