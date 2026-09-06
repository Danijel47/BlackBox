package com.blackbox.wow.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ProspectingBatch(long oreId, long oreQuantity, Map<Long, Long> outputs) {

    private static final int MAXIMUM_OUTPUTS = 20;
    private static final int MAXIMUM_ARGUMENT_LENGTH = 1_500;
    private static final long MAXIMUM_QUANTITY = 1_000_000L;
    private static final String INVALID_BATCH =
            "Enter an ore ID, the ore quantity used, then each output as itemID:quantity.";

    public ProspectingBatch {
        if (oreId <= 0 || !validQuantity(oreQuantity)) {
            throw new IllegalArgumentException("Use a positive ore ID and an ore quantity from 1 to 1,000,000.");
        }
        if (outputs == null || outputs.isEmpty() || outputs.size() > MAXIMUM_OUTPUTS) {
            throw new IllegalArgumentException("Include between 1 and 20 different output item IDs.");
        }
        outputs.forEach((itemId, quantity) -> validateOutput(oreId, itemId, quantity));
        outputs = Map.copyOf(outputs);
    }

    public static ProspectingBatch parse(String arguments) {
        if (arguments == null || arguments.length() > MAXIMUM_ARGUMENT_LENGTH) {
            throw new IllegalArgumentException(INVALID_BATCH);
        }
        String[] parts = arguments.strip().split("\\s+");
        if (parts.length < 3 || parts.length > MAXIMUM_OUTPUTS + 2) {
            throw new IllegalArgumentException(INVALID_BATCH);
        }
        try {
            long oreId = ProspectingOre.fromAlias(parts[0]).map(ProspectingOre::itemId)
                    .orElseGet(() -> Long.parseLong(parts[0]));
            Map<Long, Long> outputs = new LinkedHashMap<>();
            for (int index = 2; index < parts.length; index++) {
                parseOutput(parts[index], outputs);
            }
            return new ProspectingBatch(oreId, Long.parseLong(parts[1]), outputs);
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("Item IDs and quantities must be whole numbers.");
        }
    }

    private static void parseOutput(String value, Map<Long, Long> outputs) {
        String[] pair = value.split(":", -1);
        if (pair.length != 2) {
            throw new IllegalArgumentException(INVALID_BATCH);
        }
        long itemId = Long.parseLong(pair[0]);
        if (outputs.putIfAbsent(itemId, Long.parseLong(pair[1])) != null) {
            throw new IllegalArgumentException("Combine quantities for repeated output item IDs.");
        }
    }

    private static void validateOutput(long oreId, Long itemId, Long quantity) {
        if (itemId == null || itemId <= 0 || itemId == oreId || quantity == null || !validQuantity(quantity)) {
            throw new IllegalArgumentException(
                    "Output IDs must be positive and different from the ore; quantities must be from 1 to 1,000,000."
            );
        }
    }

    private static boolean validQuantity(long quantity) {
        return quantity > 0 && quantity <= MAXIMUM_QUANTITY;
    }

    public Set<Long> itemIds() {
        Set<Long> itemIds = new HashSet<>(outputs.keySet());
        itemIds.add(oreId);
        return Set.copyOf(itemIds);
    }
}
