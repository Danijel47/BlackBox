package com.example.telegrambot.helper;

import com.fasterxml.jackson.databind.JsonNode;

public class RaidProgressFormatter {

    private RaidProgressFormatter() {
    }

    public static String formatRaidLine(JsonNode guildProfile, String raidKey) {
        JsonNode raid = guildProfile.path("raid_progression").path(raidKey);
        if (raid.isMissingNode() || raid.isNull()) {
            return raidKey + ": n/a";
        }

        int total = raid.path("total_bosses").asInt(0);
        int heroicKilled = raid.path("heroic_bosses_killed").asInt(0);
        int mythicKilled = raid.path("mythic_bosses_killed").asInt(0);

        if (total <= 0) {
            // fallback to summary if total isn't present
            String summary = raid.path("summary").asText("n/a");
            return raidKey + ": " + summary;
        }

        return raidKey + ": " + heroicKilled + "/" + total + "H, " + mythicKilled + "/" + total + "M";
    }

}
