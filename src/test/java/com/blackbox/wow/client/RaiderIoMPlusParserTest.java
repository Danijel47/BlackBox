package com.blackbox.wow.client;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RaiderIoMPlusParserTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void mergesTheSameRunAcrossRaiderIoCollections() throws Exception {
        String response = """
                {
                  "name": "Bucothered",
                  "realm": "Stormscale",
                  "region": "eu",
                  "last_crawled_at": "2026-08-19T08:30:00Z",
                  "mythic_plus_scores_by_season": [{
                    "season": "season-mn-2",
                    "scores": {"all": 1234.5, "dps": 1234.5, "healer": 0, "tank": 0}
                  }],
                  "mythic_plus_recent_runs": [{
                    "keystone_run_id": 42,
                    "dungeon": "Windrunner Spire",
                    "short_name": "WS",
                    "map_challenge_mode_id": 777,
                    "mythic_level": 10,
                    "completed_at": "2026-08-19T07:00:00Z",
                    "clear_time_ms": 1800000,
                    "par_time_ms": 2100000,
                    "num_keystone_upgrades": 2,
                    "score": 301.2
                  }],
                  "mythic_plus_best_runs": [{
                    "keystone_run_id": 42,
                    "dungeon": "Windrunner Spire",
                    "short_name": "WS",
                    "mythic_level": 10,
                    "completed_at": "2026-08-19T07:00:00Z",
                    "clear_time_ms": 1800000,
                    "par_time_ms": 2100000,
                    "num_keystone_upgrades": 2,
                    "score": 301.2
                  }],
                  "mythic_plus_weekly_highest_level_runs": [],
                  "mythic_plus_previous_weekly_highest_level_runs": [{
                    "keystone_run_id": 41,
                    "dungeon": "Alpha Dungeon",
                    "short_name": "AD",
                    "mythic_level": 9,
                    "completed_at": "2026-08-12T07:00:00Z",
                    "clear_time_ms": 1900000,
                    "par_time_ms": 2100000,
                    "num_keystone_upgrades": 1,
                    "score": 280.0
                  }]
                }
                """;

        MPlusObservation observation = RaiderIoClient.parseMPlusObservation(
                json.readTree(response), "eu", "stormscale", "Bucothered"
        );

        assertThat(observation.season()).isEqualTo("season-mn-2");
        assertThat(observation.scoreAll()).isEqualByComparingTo(new BigDecimal("1234.5"));
        assertThat(observation.runs()).hasSize(2);
        assertThat(observation.runs()).filteredOn(run -> run.raiderIoRunId() == 42L).singleElement().satisfies(run -> {
            assertThat(run.raiderIoRunId()).isEqualTo(42L);
            assertThat(run.recent()).isTrue();
            assertThat(run.best()).isTrue();
            assertThat(run.weekly()).isFalse();
        });
        assertThat(observation.previousWeekAvailable()).isTrue();
        assertThat(observation.previousWeeklyRuns()).singleElement().satisfies(run ->
                assertThat(run.raiderIoRunId()).isEqualTo(41L));
    }

    @Test
    void parsesRosterAndWeeklyModifiers() throws Exception {
        String response = """
                {
                  "roster": [{
                    "role": "dps",
                    "character": {
                      "name": "Thelinq",
                      "region": {"short_name": "EU"},
                      "realm": {"name": "Stormscale"},
                      "class": {"name": "Mage"},
                      "spec": {"name": "Arcane", "role": "dps"}
                    }
                  }],
                  "weekly_modifiers": [{"id": 9, "name": "Tyrannical", "slug": "tyrannical"}]
                }
                """;

        MPlusObservation.RunDetails details = RaiderIoClient.parseRunDetails(json.readTree(response));

        assertThat(details.members()).singleElement().satisfies(member -> {
            assertThat(member.characterName()).isEqualTo("Thelinq");
            assertThat(member.realm()).isEqualTo("Stormscale");
            assertThat(member.className()).isEqualTo("Mage");
        });
        assertThat(details.modifiers()).singleElement().satisfies(modifier ->
                assertThat(modifier.slug()).isEqualTo("tyrannical"));
    }

    @Test
    void parsesCurrentSeasonDungeonStaticData() throws Exception {
        String response = """
                {
                  "seasons": [{
                    "slug": "season-mn-2",
                    "name": "Midnight Season 2",
                    "short_name": "MN S2",
                    "dungeons": [{
                      "id": 100,
                      "challenge_mode_id": 777,
                      "slug": "alpha-dungeon",
                      "name": "Alpha Dungeon",
                      "short_name": "AD",
                      "keystone_timer_seconds": 1800
                    }]
                  }]
                }
                """;

        MPlusStaticData staticData = RaiderIoClient.parseMPlusStaticData(json.readTree(response));

        assertThat(staticData.seasons()).singleElement().satisfies(season -> {
            assertThat(season.key()).isEqualTo("season-mn-2");
            assertThat(season.dungeons()).singleElement().satisfies(dungeon ->
                    assertThat(dungeon.challengeModeId()).isEqualTo(777));
        });
    }
}
