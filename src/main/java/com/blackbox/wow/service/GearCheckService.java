package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardEquipmentService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

@Service
public class GearCheckService {

    public static final String COMMAND = "/gearcheck";
    private static final int MAX_MESSAGE_LENGTH = 3500;
    private static final String HEADER = "Gear check — current mains (Midnight)\n";

    private final TrackedPlayerService players;
    private final BlizzardEquipmentService equipment;

    public GearCheckService(TrackedPlayerService players, BlizzardEquipmentService equipment) {
        this.players = players;
        this.equipment = equipment;
    }

    public List<String> messages() {
        List<TrackedPlayer> mains = players.activePlayers();
        if (mains.isEmpty()) {
            return List.of("No active group mains are configured.");
        }
        List<String> messages = new ArrayList<>();
        StringBuilder message = new StringBuilder(HEADER);
        for (TrackedPlayer player : mains) {
            String result = playerResult(player);
            if (message.length() + result.length() > MAX_MESSAGE_LENGTH) {
                messages.add(message.toString().strip());
                message = new StringBuilder(HEADER);
            }
            message.append(result);
        }
        messages.add(message.toString().strip());
        return List.copyOf(messages);
    }

    private String playerResult(TrackedPlayer player) {
        StringBuilder result = new StringBuilder("\n• ").append(player.profileName())
                .append(" — ").append(player.name()).append("-").append(player.realm()).append("\n");
        try {
            var check = equipment.check(player);
            result.append(check.complete() ? "✅ Complete" : "⚠️ Needs attention")
                    .append(" — enchants ").append(check.enchantSlots() - check.missingEnchants().size())
                    .append("/").append(check.enchantSlots())
                    .append(", gems ").append(check.filledSockets()).append("/").append(check.sockets()).append("\n");
            appendMissing(result, "Missing enchants: ", check.missingEnchants());
            appendMissing(result, "Empty sockets: ", check.emptySockets());
            if (check.sourceUpdatedAt() != null) {
                result.append("Gear updated: ").append(check.sourceUpdatedAt()).append("\n");
            }
        } catch (RestClientException | IllegalStateException | IllegalArgumentException _) {
            // Do not expose upstream response bodies, URLs or credentials to Telegram.
            result.append("❔ Unavailable — equipment could not be checked. "
                    + "The character may be unavailable, missing gear, or in an unsupported region.\n");
        }
        return result.toString();
    }

    private static void appendMissing(StringBuilder result, String label, List<String> missing) {
        if (!missing.isEmpty()) {
            result.append(label).append(String.join(", ", missing)).append("\n");
        }
    }
}
