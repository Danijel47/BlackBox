package com.blackbox.wow.blizzard;

import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

@Service
public class BlizzardItemLevelService {
    private final BlizzardApiClient api;

    public BlizzardItemLevelService(BlizzardApiClient api) {
        this.api = api;
    }

    public BigDecimal equippedItemLevel(TrackedPlayer player) {
        Map<String, String> query = api.profileQuery();
        String region = player.region().toLowerCase(Locale.ROOT);
        if (!("profile-" + region).equals(query.get("namespace"))) {
            throw new IllegalArgumentException("Character region is not supported by the configured Blizzard API.");
        }
        String realm = player.realm().toLowerCase(Locale.ROOT).replace(' ', '-');
        String name = player.name().toLowerCase(Locale.ROOT);
        var character = api.get("/profile/wow/character/{realm}/{name}",
                Map.of("realm", realm, "name", name), query);
        var level = character.path("equipped_item_level");
        return level.isNumber() && level.decimalValue().signum() > 0 ? level.decimalValue() : null;
    }
}
