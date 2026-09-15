package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarcraftLogsRaidStatisticsServiceTest {
    @Test
    void formatsDifficultiesAndUsesProviderVariables() throws Exception {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        when(client.query(anyString(), anyMap())).thenReturn(new ObjectMapper().readTree("""
                {"characterData":{"character":{"name":"Bucothered",
                  "normal":{"bestPerformanceAverage":81.68728134929741},
                  "heroic":{"bestPerformanceAverage":24.692101313564862},"mythic":null}}}
                """));
        String message = new WarcraftLogsRaidStatisticsService(client).raidCombatMessage(List.of(
                new TrackedPlayer(1, "Buco", "eu", "Stormscale", "Bucothered")));
        assertThat(message).contains("Mythic: —", "Heroic: 24.69%", "Normal: 81.69%");
        verify(client).query(anyString(), eq(Map.of(
                "name", "Bucothered", "server", "stormscale", "region", "EU")));
    }

    @Test
    void sortsByHighestAvailableDifficultyAndShowsFailures() throws Exception {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        ObjectMapper mapper = new ObjectMapper();
        when(client.query(anyString(), anyMap())).thenReturn(
                mapper.readTree("""
                        {"characterData":{"character":{"name":"Thelinq",
                          "normal":{"bestPerformanceAverage":99},
                          "heroic":{"bestPerformanceAverage":80},"mythic":null}}}
                        """),
                mapper.readTree("""
                        {"characterData":{"character":{"name":"Bucothered",
                          "normal":{"bestPerformanceAverage":40},"heroic":null,
                          "mythic":{"bestPerformanceAverage":10}}}}
                        """))
                .thenThrow(new IllegalStateException("unavailable"));
        String message = new WarcraftLogsRaidStatisticsService(client).raidCombatMessage(List.of(
                new TrackedPlayer(2, "Linq", "eu", "Draenor", "Thelinq"),
                new TrackedPlayer(1, "Buco", "eu", "Stormscale", "Bucothered"),
                new TrackedPlayer(3, "Unavailable", "eu", "Draenor", "Missing")));
        assertThat(message).containsSubsequence("• Buco", "• Linq", "• Unavailable (Missing): unavailable");
    }
}
