package com.blackbox.wow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RaiderIoCharacterRaidParserTest {

    @Test
    void parsesTheConfiguredCharacterRaidProgress() throws Exception {
        var profile = new ObjectMapper().readTree("""
                {
                  "name":"Bucothered",
                  "realm":"Stormscale",
                  "region":"eu",
                  "raid_progression":{"the-venomous-abyss":{
                    "total_bosses":8,
                    "normal_bosses_killed":4,
                    "heroic_bosses_killed":1,
                    "mythic_bosses_killed":0
                  }}
                }
                """);

        RaiderIoClient.CharacterRaidProgress progress = RaiderIoClient.parseCharacterRaidProgress(
                profile, "the-venomous-abyss", "fallback", "fallback", "eu"
        );

        assertThat(progress.totalBosses()).isEqualTo(8);
        assertThat(progress.normalBossesKilled()).isEqualTo(4);
        assertThat(progress.heroicBossesKilled()).isEqualTo(1);
        assertThat(progress.mythicBossesKilled()).isZero();
    }
}
