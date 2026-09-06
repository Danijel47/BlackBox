package com.blackbox.wow.service;

import java.util.Arrays;
import java.util.Optional;

public enum ProspectingOre {
    COPPER_ONE("copper1", 237359L, "Refulgent Copper Ore — Q1"),
    COPPER_TWO("copper2", 237361L, "Refulgent Copper Ore — Q2"),
    TIN_ONE("tin1", 237362L, "Umbral Tin Ore — Q1"),
    TIN_TWO("tin2", 237363L, "Umbral Tin Ore — Q2"),
    SILVER_ONE("silver1", 237364L, "Brilliant Silver Ore — Q1"),
    SILVER_TWO("silver2", 237365L, "Brilliant Silver Ore — Q2"),
    THORIUM("thorium", 237366L, "Dazzling Thorium");

    private final String alias;
    private final long itemId;
    private final String label;

    ProspectingOre(String alias, long itemId, String label) {
        this.alias = alias;
        this.itemId = itemId;
        this.label = label;
    }

    public String alias() {
        return alias;
    }

    public long itemId() {
        return itemId;
    }

    public String label() {
        return label;
    }

    public static Optional<ProspectingOre> fromAlias(String alias) {
        return Arrays.stream(values()).filter(ore -> ore.alias.equalsIgnoreCase(alias)).findFirst();
    }

    public static Optional<ProspectingOre> fromItemId(long itemId) {
        return Arrays.stream(values()).filter(ore -> ore.itemId == itemId).findFirst();
    }
}
