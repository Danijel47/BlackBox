package com.example.telegrambot.helper;

import com.fasterxml.jackson.databind.JsonNode;

public class AffixFormatter {

    public static String formatWeeklyAffixes(JsonNode root) {
        String region = root.path("region").asText("?");
        String title = root.path("title").asText(""); // often something like "Fortified, ..."

        // try common keys for the list
        JsonNode list = root.path("affix_details");
        if (!list.isArray()) list = root.path("affixes");

        StringBuilder sb = new StringBuilder();
        sb.append("Weekly affixes (").append(region.toUpperCase()).append(")\n");

        if (!title.isBlank()) sb.append(title).append("\n");

        if (list.isArray()) {
            for (JsonNode a : list) {
                sb.append("• ").append(a.path("name").asText("Unknown")).append(": ").append(a.path("description").asText("Unknown")).append("\n");
            }
        } else {
            sb.append("No affix list found in response.");
        }

        return sb.toString().trim();
    }
}
