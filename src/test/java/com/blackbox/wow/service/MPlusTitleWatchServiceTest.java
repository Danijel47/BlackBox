package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusTitleWatchServiceTest {

    private static final String REGION = "eu";
    private static final String POINT_ONE_PERCENTILE = "p999";
    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");

    @Mock private RaiderIoClient raiderIoClient;
    @Mock private TrackedPlayerService trackedPlayerService;

    private MPlusTitleWatchService service;

    @BeforeEach
    void setUp() {
        service = new MPlusTitleWatchService(
                raiderIoClient,
                trackedPlayerService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void reportsUnavailableWhenTheOnePercentCutoffHasNoScore() {
        TrackedPlayer player = player(1L, "Buco", "stormscale", "Bucothered");
        when(trackedPlayerService.titleWatchPlayers()).thenReturn(List.of(player));
        when(raiderIoClient.getCurrentMPlusTitleCutoff(REGION)).thenReturn(cutoff(null));

        String report = service.onePercentReport();

        assertThat(report).contains("unavailable", "no cutoff score");
        verify(raiderIoClient, never()).getCurrentMPlusScore(REGION, "stormscale", "Bucothered");
    }

    @Test
    void includesEveryActiveProfileInThePointOnePercentReportAndSortsByScore() {
        TrackedPlayer buco = player(1L, "Buco", "stormscale", "Bucothered");
        TrackedPlayer linq = player(2L, "Linq", "draenor", "Thelinq");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, linq));
        when(raiderIoClient.getCurrentMPlusTitleCutoff(REGION, POINT_ONE_PERCENTILE))
                .thenReturn(cutoff(new BigDecimal("3000")));
        when(raiderIoClient.getCurrentMPlusScore(REGION, "stormscale", "Bucothered"))
                .thenReturn(score("Bucothered", "Stormscale", new BigDecimal("2900")));
        when(raiderIoClient.getCurrentMPlusScore(REGION, "draenor", "Thelinq"))
                .thenReturn(score("Thelinq", "Draenor", new BigDecimal("3100")));

        String report = service.pointOnePercentReport();

        assertThat(report)
                .contains("M+ 0.1% title watch", "• Bucothered:", "• Thelinq:")
                .containsSubsequence("• Thelinq:", "• Bucothered:");
        verify(raiderIoClient).getCurrentMPlusScore(REGION, "stormscale", "Bucothered");
        verify(raiderIoClient).getCurrentMPlusScore(REGION, "draenor", "Thelinq");
    }

    @Test
    void doesNotExposeRaiderIoFailureDetailsInTheReport() {
        TrackedPlayer player = player(1L, "Buco", "stormscale", "Bucothered");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(player));
        when(raiderIoClient.getCurrentMPlusTitleCutoff(REGION, POINT_ONE_PERCENTILE))
                .thenReturn(cutoff(new BigDecimal("3000")));
        when(raiderIoClient.getCurrentMPlusScore(REGION, "stormscale", "Bucothered"))
                .thenThrow(new IllegalStateException("sensitive upstream URL"));

        String report = service.pointOnePercentReport();

        assertThat(report)
                .contains("• Bucothered: error: profile data unavailable")
                .doesNotContain("sensitive upstream URL");
    }

    private static TrackedPlayer player(long id, String profile, String realm, String character) {
        return new TrackedPlayer(id, profile, REGION, realm, character);
    }

    private static RaiderIoClient.MPlusTitleCutoff cutoff(BigDecimal score) {
        return new RaiderIoClient.MPlusTitleCutoff(REGION, "season-mn-2", score, 1000, "");
    }

    private static RaiderIoClient.RaiderIoScore score(String name, String realm, BigDecimal score) {
        return new RaiderIoClient.RaiderIoScore(
                name,
                realm,
                REGION,
                BigDecimal.valueOf(303),
                score,
                null,
                null,
                null,
                "",
                NOW
        );
    }
}
