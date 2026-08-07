package com.example.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "blizzard")
public record BlizzardApiProperties(
        String clientId,
        String clientSecret,
        String authBaseUrl,
        String apiBaseUrl,
        String namespace,
        String locale,
        Cache cache
) {
    public record Cache(int auctionsTtlSeconds, int commoditiesTtlSeconds, int realmTtlSeconds) {}
}
