package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.Milestone;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusProgressServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
    private static final Instant RESET = Instant.parse("2026-08-19T05:00:00Z");

    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private MPlusProgressRepository repository;

    @Test
    void ranksTiedScoresByProfileOrderAndFindsTheBiggestResetGain() {
        TrackedPlayer buco = player(1, "Buco", "Bucothered");
        TrackedPlayer linq = player(2, "Linq", "Thelinq");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, linq));
        when(repository.latestScore(1)).thenReturn(Optional.of(score(1, "season-mn-2", "2000", NOW, "Bucothered")));
        when(repository.latestScore(2)).thenReturn(Optional.of(score(2, "season-mn-2", "2000", NOW, "Thelinq")));
        when(repository.latestScoreAtOrBefore(1, "season-mn-2", RESET))
                .thenReturn(Optional.of(score(1, "season-mn-2", "1900", RESET, "Bucothered")));
        when(repository.latestScoreAtOrBefore(2, "season-mn-2", RESET))
                .thenReturn(Optional.of(score(2, "season-mn-2", "1800", RESET, "Thelinq")));

        String message = service().progressMessage("", 123L);

        assertThat(message)
                .containsSubsequence("1. Buco — 2000.0", "2. Linq — 2000.0")
                .contains("Biggest reset gain: Linq (+200.0)")
                .contains("milestone dates are first observed");
    }

    @Test
    void usesTheConfiguredEuResetBoundary() {
        TrackedPlayer buco = player(1, "Buco", "Bucothered");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco));
        when(repository.latestScore(1)).thenReturn(Optional.of(score(1, "season-mn-2", "2000", NOW, "Bucothered")));

        service().progressMessage("", 123L);

        verify(repository).latestScoreAtOrBefore(1, "season-mn-2", RESET);
    }

    @Test
    void doesNotCreateAGainWithoutASameSeasonBaseline() {
        TrackedPlayer buco = player(1, "Buco", "Bucothered");
        ScorePoint latest = score(1, "season-mn-2", "300", NOW, "Bucothered");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco));
        when(repository.latestScore(1)).thenReturn(Optional.of(latest));
        when(repository.firstScore(1, "season-mn-2")).thenReturn(Optional.of(latest));
        when(repository.milestones(1, "season-mn-2")).thenReturn(List.of());

        String message = service().progressMessage("Buco", 123L);

        assertThat(message)
                .contains("Last 24h: unavailable")
                .contains("This reset: unavailable")
                .doesNotContain("-300");
    }

    @Test
    void keepsProgressAcrossAMainChangeAndShowsFirstObservedMilestones() {
        TrackedPlayer buco = player(1, "Buco", "Bucomonk");
        ScorePoint latest = score(1, "season-mn-2", "2100", NOW, "Bucomonk");
        ScorePoint oldMainBaseline = score(
                1, "season-mn-2", "1800", NOW.minusSeconds(90_000), "Bucothered"
        );
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco));
        when(repository.latestScore(1)).thenReturn(Optional.of(latest));
        when(repository.latestScoreAtOrBefore(1, "season-mn-2", NOW.minusSeconds(86_400)))
                .thenReturn(Optional.of(oldMainBaseline));
        when(repository.latestScoreAtOrBefore(1, "season-mn-2", RESET))
                .thenReturn(Optional.of(oldMainBaseline));
        when(repository.firstScore(1, "season-mn-2")).thenReturn(Optional.of(oldMainBaseline));
        when(repository.milestones(1, "season-mn-2")).thenReturn(List.of(
                new Milestone(new BigDecimal("2000"), new BigDecimal("2100"), NOW,
                        "eu", "Stormscale", "Bucomonk")
        ));

        String message = service().progressMessage("Buco", 123L);

        assertThat(message)
                .contains("Last 24h: +300.0")
                .contains("Since first observed: +300.0")
                .contains("Observed character: Bucomonk-Stormscale")
                .contains("2000.0 on 2026-08-19");
    }

    private MPlusProgressService service() {
        MPlusProgressProperties properties = new MPlusProgressProperties(
                ZoneId.of("Europe/Paris"),
                DayOfWeek.WEDNESDAY,
                LocalTime.of(7, 0),
                List.of(new BigDecimal("1000"), new BigDecimal("2000"), new BigDecimal("2500"))
        );
        return new MPlusProgressService(
                trackedPlayerService,
                repository,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static TrackedPlayer player(long profileId, String profileName, String characterName) {
        return new TrackedPlayer(profileId, profileName, "eu", "Stormscale", characterName);
    }

    private static ScorePoint score(
            long profileId,
            String season,
            String score,
            Instant capturedAt,
            String characterName
    ) {
        return new ScorePoint(
                profileId,
                season,
                new BigDecimal(score),
                capturedAt,
                "eu",
                "Stormscale",
                characterName
        );
    }
}
