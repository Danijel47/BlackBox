package com.example.blackbox.wow.helper;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

public class RaidPicker {

    public static String pickBestRaidKey(JsonNode guildProfile) {
        JsonNode rp = guildProfile.path("raid_progression");
        if (!rp.isObject()) return null;

        String bestKey = null;
        double bestScore = -1;

        Iterator<Map.Entry<String, JsonNode>> it = rp.fields();
        while (it.hasNext()) {
            var e = it.next();
            String key = e.getKey();
            JsonNode raid = e.getValue();

            int total = raid.path("total_bosses").asInt(0);
            if (total <= 0) continue;

            int mythic = raid.path("mythic_bosses_killed").asInt(0);
            int heroic = raid.path("heroic_bosses_killed").asInt(0);
            int normal = raid.path("normal_bosses_killed").asInt(0);

            // weight mythic highest
            double score = (mythic * 1000.0) + (heroic * 10.0) + normal;

            if (score > bestScore) {
                bestScore = score;
                bestKey = key;
            }
        }
        return bestKey;
    }
}
