package com.blackbox.wow.blizzard;

import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BlizzardEquipmentService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String TYPE_FIELD = "type";
    private static final String ITEM_FIELD = "item";
    private static final String ID_FIELD = "id";
    private static final String MAIN_HAND = "MAIN_HAND";
    private static final String OFF_HAND = "OFF_HAND";
    private static final String INVALID_EQUIPMENT = "Equipment data is incomplete.";
    // Midnight: cloak/bracer enchants were replaced by helm/shoulder enchants.
    // Legs use a spellthread or armor kit, also reported as a permanent enchant.
    private static final Map<String, String> ENCHANT_SLOTS = Map.of(
            "HEAD", "Head", "SHOULDER", "Shoulders", "CHEST", "Chest", "LEGS", "Legs",
            "FEET", "Boots", "FINGER_1", "Ring 1", "FINGER_2", "Ring 2", MAIN_HAND, "Main hand"
    );

    private final BlizzardApiClient api;
    private final BlizzardCache<EquipmentData> cache = new BlizzardCache<>(256, value -> 1);

    public BlizzardEquipmentService(BlizzardApiClient api) {
        this.api = api;
    }

    public EquipmentCheck check(TrackedPlayer player) {
        return data(player).check();
    }

    public EquipmentSnapshot snapshot(TrackedPlayer player) {
        return data(player).snapshot();
    }

    private EquipmentData data(TrackedPlayer player) {
        Map<String, String> query = api.profileQuery();
        String region = player.region().toLowerCase(Locale.ROOT);
        if (!("profile-" + region).equals(query.get("namespace"))) {
            throw new IllegalArgumentException("Character region is not supported by the configured Blizzard API.");
        }
        String realm = player.realm().toLowerCase(Locale.ROOT).replace(' ', '-');
        String name = player.name().toLowerCase(Locale.ROOT);
        String key = region + ":" + realm + ":" + name;
        return cache.getOrCompute(key, CACHE_TTL, () -> load(realm, name, query));
    }

    private EquipmentData load(String realm, String name, Map<String, String> query) {
        var snapshot = api.getSnapshot(
                "/profile/wow/character/{realm}/{name}/equipment",
                Map.of("realm", realm, "name", name), query
        );
        var equipment = new EquipmentSnapshot(equippedItems(snapshot.data()), snapshot.sourceUpdatedAt());
        return new EquipmentData(equipment, check(equipment));
    }

    private static EquipmentCheck check(EquipmentSnapshot snapshot) {
        List<String> missingEnchants = new ArrayList<>();
        List<String> emptySockets = new ArrayList<>();
        int enchantSlots = 0;
        int sockets = 0;
        int filledSockets = 0;
        for (var entry : snapshot.items().entrySet()) {
            String slot = entry.getKey();
            JsonNode item = entry.getValue();
            String label = slotLabel(slot);
            if (needsEnchant(slot, item)) {
                enchantSlots++;
                if (!hasPermanentEnchant(item)) {
                    missingEnchants.add(label);
                }
            }
            JsonNode itemSockets = item.path("sockets");
            requireOptionalArray(itemSockets);
            int filled = countFilledSockets(itemSockets);
            sockets += itemSockets.size();
            filledSockets += filled;
            int empty = itemSockets.size() - filled;
            if (empty > 0) {
                emptySockets.add(label + " (" + empty + ")");
            }
        }
        return new EquipmentCheck(enchantSlots, missingEnchants, sockets, filledSockets,
                emptySockets, snapshot.sourceUpdatedAt());
    }

    private static Map<String, JsonNode> equippedItems(JsonNode data) {
        JsonNode equipped = data.path("equipped_items");
        if (!equipped.isArray() || equipped.isEmpty()) {
            throw new IllegalStateException(INVALID_EQUIPMENT);
        }
        Map<String, JsonNode> items = new LinkedHashMap<>();
        for (JsonNode item : equipped) {
            String slot = item.path("slot").path(TYPE_FIELD).asText();
            if (slot.isBlank() || item.path(ITEM_FIELD).path(ID_FIELD).asLong() <= 0
                    || items.putIfAbsent(slot, item) != null) {
                throw new IllegalStateException(INVALID_EQUIPMENT);
            }
        }
        if (!items.keySet().containsAll(ENCHANT_SLOTS.keySet())) {
            throw new IllegalStateException(INVALID_EQUIPMENT);
        }
        return items;
    }

    private static boolean needsEnchant(String slot, JsonNode item) {
        if (MAIN_HAND.equals(slot) || OFF_HAND.equals(slot)) {
            JsonNode itemClass = item.path("item_class").path(ID_FIELD);
            if (!itemClass.isIntegralNumber()) {
                throw new IllegalStateException(INVALID_EQUIPMENT);
            }
            return itemClass.asInt() == 2; // Weapon; shields and held off-hands are armor.
        }
        return ENCHANT_SLOTS.containsKey(slot);
    }

    private static boolean hasPermanentEnchant(JsonNode item) {
        JsonNode enchants = item.path("enchantments");
        requireOptionalArray(enchants);
        for (JsonNode enchant : enchants) {
            if ("PERMANENT".equals(enchant.path("enchantment_slot").path(TYPE_FIELD).asText())
                    && enchant.path("enchantment_id").asInt() > 0) {
                return true;
            }
        }
        return false;
    }

    private static int countFilledSockets(JsonNode sockets) {
        int filled = 0;
        for (JsonNode socket : sockets) {
            if (!socket.isObject() || !socket.path("socket_type").path(TYPE_FIELD).isTextual()) {
                throw new IllegalStateException(INVALID_EQUIPMENT);
            }
            if (socket.path(ITEM_FIELD).path(ID_FIELD).asLong() > 0) {
                filled++;
            }
        }
        return filled;
    }

    private static void requireOptionalArray(JsonNode value) {
        if (!value.isMissingNode() && !value.isArray()) {
            throw new IllegalStateException(INVALID_EQUIPMENT);
        }
    }

    private static String slotLabel(String slot) {
        if (OFF_HAND.equals(slot)) {
            return "Off hand";
        }
        return ENCHANT_SLOTS.getOrDefault(slot, slot.toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    public record EquipmentCheck(
            int enchantSlots, List<String> missingEnchants, int sockets, int filledSockets,
            List<String> emptySockets, Instant sourceUpdatedAt
    ) {
        public EquipmentCheck {
            missingEnchants = List.copyOf(missingEnchants);
            emptySockets = List.copyOf(emptySockets);
        }

        public boolean complete() {
            return missingEnchants.isEmpty() && emptySockets.isEmpty();
        }
    }

    public record EquipmentSnapshot(Map<String, JsonNode> items, Instant sourceUpdatedAt) {
        public EquipmentSnapshot {
            items = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }
    }

    private record EquipmentData(EquipmentSnapshot snapshot, EquipmentCheck check) {}
}
