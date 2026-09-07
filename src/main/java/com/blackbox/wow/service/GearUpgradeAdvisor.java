package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardEquipmentService.EquipmentSnapshot;
import com.blackbox.wow.service.GearUpgradeRules.UpgradeStep;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class GearUpgradeAdvisor {

    private static final int LARGE_LEVEL_GAP = 13;
    private static final String MAIN_HAND = "MAIN_HAND";
    private static final String OFF_HAND = "OFF_HAND";
    private static final String HEAD = "HEAD";
    private static final String CHEST = "CHEST";
    private static final String LEGS = "LEGS";
    private static final String SHOULDER = "SHOULDER";
    private static final String HANDS = "HANDS";
    private static final String WAIST = "WAIST";
    private static final String FEET = "FEET";
    private static final String TRINKET_1 = "TRINKET_1";
    private static final String TRINKET_2 = "TRINKET_2";
    private static final String CRAFTED_BY = "crafted_by";
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    private static final Map<String, String> SLOT_LABELS = Map.ofEntries(
            Map.entry(MAIN_HAND, "Main hand"), Map.entry(OFF_HAND, "Off hand"),
            Map.entry(HEAD, "Head"), Map.entry("NECK", "Neck"), Map.entry(SHOULDER, "Shoulders"),
            Map.entry("BACK", "Cloak"), Map.entry(CHEST, "Chest"), Map.entry("WRIST", "Bracers"),
            Map.entry(HANDS, "Gloves"), Map.entry(WAIST, "Belt"), Map.entry(LEGS, "Legs"),
            Map.entry(FEET, "Boots"), Map.entry("FINGER_1", "Ring 1"), Map.entry("FINGER_2", "Ring 2"),
            Map.entry(TRINKET_1, "Trinket 1"), Map.entry(TRINKET_2, "Trinket 2")
    );

    private GearUpgradeAdvisor() {
    }

    public static List<Advice> advise(EquipmentSnapshot snapshot, int specializationId) {
        int median = medianLevel(snapshot);
        return snapshot.items().entrySet().stream()
                .filter(entry -> SLOT_LABELS.containsKey(entry.getKey()))
                .map(entry -> advice(entry.getKey(), entry.getValue(), specializationId, median))
                .sorted(Comparator.comparing(Advice::status)
                        .thenComparing(Advice::priority)
                        .thenComparingInt(Advice::slotOrder)
                        .thenComparing(Comparator.comparingInt(Advice::levelGain).reversed())
                        .thenComparingInt(Advice::itemLevel)
                        .thenComparing(Advice::slot))
                .toList();
    }

    public static List<String> missingSlots(EquipmentSnapshot snapshot) {
        JsonNode mainHand = snapshot.items().get(MAIN_HAND);
        boolean needsOffHand = mainHand != null && List.of("WEAPON", "WEAPONMAINHAND")
                .contains(mainHand.path("inventory_type").path("type").asText());
        return SLOT_LABELS.entrySet().stream()
                .filter(entry -> needsOffHand || !OFF_HAND.equals(entry.getKey()))
                .filter(entry -> !snapshot.items().containsKey(entry.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
    }

    private static int medianLevel(EquipmentSnapshot snapshot) {
        List<Integer> levels = snapshot.items().entrySet().stream()
                .filter(entry -> SLOT_LABELS.containsKey(entry.getKey()))
                .map(entry -> itemLevel(entry.getValue())).filter(level -> level > 0).sorted().toList();
        return levels.isEmpty() ? 0 : levels.get(levels.size() / 2);
    }

    private static Advice advice(String slot, JsonNode item, int specializationId, int median) {
        int level = itemLevel(item);
        UpgradeStep step = GearUpgradeRules.resolve(item.path("bonus_list"), level).orElse(null);
        Status status = status(item, step);
        int order = slotOrder(slot, specializationId, item);
        Priority priority = priority(order);
        boolean catchUp = level > 0 && median - level >= LARGE_LEVEL_GAP && priority != Priority.HIGH;
        if (catchUp) {
            priority = priority == Priority.LOW ? Priority.MEDIUM : Priority.HIGH;
        }
        return new Advice(slot, SLOT_LABELS.get(slot), safeText(item.path("name").asText("Unnamed item")),
                level, step, status, priority, order, reason(slot, catchUp));
    }

    private static int itemLevel(JsonNode item) {
        JsonNode level = item.path("level").path("value");
        return level.isIntegralNumber() && level.canConvertToInt() && level.intValue() > 0
                ? level.intValue() : 0;
    }

    private static Status status(JsonNode item, UpgradeStep step) {
        if (item.path(CRAFTED_BY).isObject() && !item.path(CRAFTED_BY).isEmpty()) {
            return Status.CRAFTED;
        }
        if (step == null) {
            return Status.UNKNOWN;
        }
        return step.maxed() ? Status.MAXED : Status.UPGRADE;
    }

    private static int slotOrder(String slot, int specializationId, JsonNode item) {
        return switch (slot) {
            case MAIN_HAND -> 0;
            // Fury and dual-wield Frost use both weapons extensively; other off-hands use the fallback below.
            case OFF_HAND -> isPriorityOffHand(specializationId, item) ? 0 : 3;
            case TRINKET_1, TRINKET_2 -> 1;
            case HEAD, CHEST, LEGS -> 2;
            case SHOULDER, HANDS, WAIST, FEET -> 3;
            default -> 4;
        };
    }

    private static boolean isPriorityOffHand(int specializationId, JsonNode item) {
        return (specializationId == 72 || specializationId == 251) && item.path("item_class").path("id").asInt() == 2;
    }

    private static Priority priority(int order) {
        if (order <= 2) {
            return Priority.HIGH;
        }
        return order == 3 ? Priority.MEDIUM : Priority.LOW;
    }

    private static String reason(String slot, boolean catchUp) {
        if (catchUp) {
            return "At least 13 ilvls below your median equipped item; catch-up priority raised.";
        }
        return switch (slot) {
            case MAIN_HAND -> "Weapon damage / primary-stat scaling; usually the first crest investment.";
            case OFF_HAND -> "Off-hand value depends on specialization and weapon setup.";
            case TRINKET_1, TRINKET_2 -> "Potentially strong effect scaling; compare this trinket for your spec.";
            case HEAD, CHEST, LEGS -> "Large primary-stat budget; upgrade a piece you expect to keep.";
            case SHOULDER, HANDS, WAIST, FEET -> "Medium primary-stat budget.";
            default -> "Usually lower priority; secondary stats and special effects can change the order.";
        };
    }

    public static String safeText(String value) {
        String clean = CONTROL_CHARACTERS.matcher(value).replaceAll(" ").strip();
        int length = clean.codePointCount(0, clean.length());
        return length > 100 ? clean.substring(0, clean.offsetByCodePoints(0, 100)) + "…" : clean;
    }

    public enum Status { UPGRADE, MAXED, CRAFTED, UNKNOWN }

    public enum Priority {
        HIGH("🔴 High"), MEDIUM("🟡 Medium"), LOW("🟢 Low");

        private final String label;

        Priority(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Advice(String slot, String slotLabel, String name, int itemLevel, UpgradeStep step,
                         Status status, Priority priority, int slotOrder, String reason) {
        public int levelGain() {
            return status == Status.UPGRADE ? step.nextLevel() - itemLevel : 0;
        }
    }
}
