package com.example.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "wow.watchlist")
public record WowWatchlistProperties(
        List<String> ores,
        List<String> herbs
) {
    private static final List<String> DEFAULT_ORES = List.of(
            "Refulgent Copper Ore",
            "Umbral Tin Ore",
            "Brilliant Silver Ore",
            "Dazzling Thorium"
    );

    private static final List<String> DEFAULT_HERBS = List.of(
            "Tranquility Bloom",
            "Argentleaf",
            "Azeroot",
            "Mana Lily",
            "Sanguithorn",
            "Nocturnal Lotus"
    );

    public WowWatchlistProperties {
        ores = (ores == null || ores.isEmpty()) ? DEFAULT_ORES : ores;
        herbs = (herbs == null || herbs.isEmpty()) ? DEFAULT_HERBS : herbs;
    }
}
