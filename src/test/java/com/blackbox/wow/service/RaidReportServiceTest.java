package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardApiClient;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.properties.RaceToWorldFirstProperties;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RaidReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");
    private static final Instant RESET = Instant.parse("2026-08-26T04:00:00Z");
    private static final TrackedPlayer BUCO =
            new TrackedPlayer(1L, "Buco", "eu", "stormscale", "Bucothered");

    @Mock private RaiderIoClient raiderIoClient;
    @Mock private BlizzardApiClient blizzardApiClient;

    private RaidReportService service;

    @BeforeEach
    void setUp() {
        service = new RaidReportService(
                raiderIoClient,
                blizzardApiClient,
                new RaceToWorldFirstProperties(
                        true, 123L, "the-venomous-abyss", "The Venomous Abyss", 8, 11
                ),
                new MPlusProgressProperties(
                        ZoneId.of("UTC"), DayOfWeek.WEDNESDAY, LocalTime.of(4, 0),
                        List.of(BigDecimal.valueOf(1_000))
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void formatsConfiguredRaidProgressForProfiles() {
        when(raiderIoClient.getCharacterRaidProgress(
                "eu", "stormscale", "Bucothered", "the-venomous-abyss"
        )).thenReturn(new RaiderIoClient.CharacterRaidProgress(
                "Bucothered", "Stormscale", "eu", "the-venomous-abyss", 8, 4, 1, 0
        ));

        assertThat(service.progress(List.of(BUCO)))
                .contains("Raid Progress — The Venomous Abyss")
                .contains("• Buco (Bucothered)\n  4/8 NM | 1/8 HC | 0/8 M");
    }

    @Test
    void showsOnlyWeeklyRaidVaultSlotsAndUsesTheHighestKillDifficulty() throws Exception {
        JsonNode raids = weeklyRaidResponse();
        when(blizzardApiClient.profileQuery()).thenReturn(Map.of("namespace", "profile-eu"));
        when(blizzardApiClient.get(anyString(), anyMap(), anyMap())).thenReturn(raids);

        String report = service.weeklyVault(List.of(BUCO));

        assertThat(report)
                .contains("Great Vault — Raid only — current week")
                .contains("Bosses this reset: 4/6")
                .contains("Slot 1 (2 bosses): Heroic")
                .contains("Slot 2 (4 bosses): Normal")
                .contains("Slot 3 (6 bosses): locked (2 more)")
                .doesNotContain("Mythic+");
    }

    private static JsonNode weeklyRaidResponse() throws Exception {
        long currentKill = RESET.plusSeconds(1_800).toEpochMilli();
        long oldKill = RESET.minusSeconds(1_800).toEpochMilli();
        return new ObjectMapper().readTree("""
                {"expansions":[{"instances":[{
                  "instance":{"name":"The Venomous Abyss"},
                  "modes":[
                    {"difficulty":{"type":"NORMAL"},"progress":{"encounters":[
                      {"encounter":{"id":1,"name":"One"},"completed_count":1,"last_kill_timestamp":%d},
                      {"encounter":{"id":3,"name":"Three"},"completed_count":1,"last_kill_timestamp":%d},
                      {"encounter":{"id":4,"name":"Four"},"completed_count":1,"last_kill_timestamp":%d},
                      {"encounter":{"id":5,"name":"Old"},"completed_count":1,"last_kill_timestamp":%d}
                    ]}},
                    {"difficulty":{"type":"HEROIC"},"progress":{"encounters":[
                      {"encounter":{"id":1,"name":"One"},"completed_count":1,"last_kill_timestamp":%d},
                      {"encounter":{"id":2,"name":"Two"},"completed_count":1,"last_kill_timestamp":%d}
                    ]}}
                  ]
                }]}]}
                """.formatted(currentKill, currentKill, currentKill, oldKill, currentKill, currentKill));
    }
}
