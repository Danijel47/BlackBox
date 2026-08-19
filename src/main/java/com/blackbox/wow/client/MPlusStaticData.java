package com.blackbox.wow.client;

import java.util.List;

public record MPlusStaticData(List<Season> seasons) {

    public record Season(String key, String name, String shortName, List<Dungeon> dungeons) {
    }

    public record Dungeon(
            int id,
            int challengeModeId,
            String slug,
            String name,
            String shortName,
            int keystoneTimerSeconds
    ) {
    }
}
