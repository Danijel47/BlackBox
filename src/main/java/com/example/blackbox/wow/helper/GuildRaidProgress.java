package com.example.blackbox.wow.helper;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GuildRaidProgress {

    private static final Pattern FRACTION = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    public static String formatRaidLine(JsonNode guild, String raidKey) {
        JsonNode raid = guild.path("raid_progression").path(raidKey);
        if (raid.isMissingNode() || raid.isNull()) {
            return raidKey + ": n/a";
        }

        String heroic = normalize(extractProgress(raid, "heroic"), "H");
        String mythic = normalize(extractProgress(raid, "mythic"), "M");
        String normal = normalize(extractProgress(raid, "normal"), "N");

        // Prefer H+M, but if only normal exists, show it too
        StringBuilder sb = new StringBuilder();
        sb.append(raidKey).append(": ");
        boolean any = false;

        if (!heroic.equals("n/a")) { sb.append(heroic); any = true; }
        if (!mythic.equals("n/a")) { sb.append(any ? ", " : "").append(mythic); any = true; }
        if (!normal.equals("n/a") && (!any)) { sb.append(normal); any = true; }

        if (!any) sb.append("n/a");

        return sb.toString();
    }

    /** Lists raids sorted by “best progress” so current raid usually appears at top. */
    public static List<String> listRaidKeysSorted(JsonNode guild) {
        JsonNode rp = guild.path("raid_progression");
        if (rp.isMissingNode() || rp.isNull() || !rp.isObject()) return List.of();

        List<RaidScore> scores = new ArrayList<>();
        rp.fields().forEachRemaining(e -> {
            String key = e.getKey();
            JsonNode raid = e.getValue();

            double score = scoreRaid(raid);
            scores.add(new RaidScore(key, score));
        });

        scores.sort(Comparator.comparingDouble(RaidScore::score).reversed());
        return scores.stream().map(RaidScore::key).toList();
    }

    /** Picks the best “current raid” candidate. */
    public static Optional<String> pickBestRaidKey(JsonNode guild) {
        List<String> keys = listRaidKeysSorted(guild);
        return keys.isEmpty() ? Optional.empty() : Optional.of(keys.get(0));
    }

    // -------- internals --------

    private static String extractProgress(JsonNode raid, String difficulty) {
        // Try multiple shapes; Raider.IO payloads can differ.
        // 1) "heroic_progression": "8/8"
        String a = raid.path(difficulty + "_progression").asText("").trim();
        if (!a.isBlank()) return a;

        // 2) "heroic": { "summary": "8/8" }
        String b = raid.path(difficulty).path("summary").asText("").trim();
        if (!b.isBlank()) return b;

        // 3) "summary": "8/8H" (fallback)
        String c = raid.path("summary").asText("").trim();
        if (!c.isBlank() && c.toLowerCase().contains(difficulty)) return c;

        return "n/a";
    }

    private static String normalize(String s, String suffix) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("n/a")) return "n/a";
        // if it's "8/8" add suffix "H/M/N"
        if (s.matches("\\d+/\\d+")) return s + suffix;
        return s;
    }

    private static double scoreRaid(JsonNode raid) {
        // Mythic should weigh more than Heroic, Heroic more than Normal
        double mythic = completionFraction(extractProgress(raid, "mythic"));
        double heroic = completionFraction(extractProgress(raid, "heroic"));
        double normal = completionFraction(extractProgress(raid, "normal"));

        return mythic * 3.0 + heroic * 2.0 + normal * 1.0;
    }

    private static double completionFraction(String progress) {
        if (progress == null) return 0.0;
        Matcher m = FRACTION.matcher(progress);
        if (!m.find()) return 0.0;
        int killed = Integer.parseInt(m.group(1));
        int total  = Integer.parseInt(m.group(2));
        if (total <= 0) return 0.0;
        return (double) killed / (double) total;
    }

    private record RaidScore(String key, double score) {}
}
