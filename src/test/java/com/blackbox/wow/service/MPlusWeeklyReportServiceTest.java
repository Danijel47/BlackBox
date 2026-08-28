package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.properties.MPlusWeeklyReportProperties;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.repository.MPlusProgressRepository.WeeklyRun;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusWeeklyReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T07:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-08-26T04:00:00Z");
    private static final Instant PERIOD_START = Instant.parse("2026-08-19T04:00:00Z");
    private static final String SEASON = "season-mn-2";

    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private MPlusProgressRepository repository;
    @Mock private BlackBoxBotNotifier notifier;

    private MPlusWeeklyReportService service;

    @BeforeEach
    void setUp() {
        MPlusProgressProperties progressProperties = new MPlusProgressProperties(
                ZoneOffset.UTC,
                DayOfWeek.WEDNESDAY,
                LocalTime.of(4, 0),
                List.of()
        );
        service = new MPlusWeeklyReportService(
                trackedPlayerService,
                repository,
                notifier,
                new MPlusWeeklyReportProperties(true, 99L, ZoneId.of("Europe/Zagreb")),
                progressProperties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void reportsWeeklyItemLevelRatingKeysAndVaultForEveryProfile() {
        TrackedPlayer player = new TrackedPlayer(7L, "Buco", "eu", "Stormscale", "Bucothered");
        ScorePoint start = score(new BigDecimal("300"), new BigDecimal("2600"), PERIOD_START);
        ScorePoint end = score(new BigDecimal("303"), new BigDecimal("2837"), PERIOD_END);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(player));
        when(repository.latestScoreAtOrBefore(7L, PERIOD_END)).thenReturn(Optional.of(end));
        when(repository.latestScoreAtOrBefore(7L, SEASON, PERIOD_START, "Bucothered"))
                .thenReturn(Optional.of(start));
        when(repository.latestItemLevelAtOrBefore(7L, "Bucothered", PERIOD_END))
                .thenReturn(Optional.of(new BigDecimal("303")));
        when(repository.latestItemLevelAtOrBefore(7L, "Bucothered", PERIOD_START))
                .thenReturn(Optional.of(new BigDecimal("300")));
        when(repository.weeklyRuns(7L, SEASON, PERIOD_START, PERIOD_END, "Bucothered")).thenReturn(List.of(
                new WeeklyRun(14, true),
                new WeeklyRun(12, true),
                new WeeklyRun(10, false),
                new WeeklyRun(9, true)
        ));

        assertThat(service.reportMessage())
                .startsWith("sretna srijeda kojima slave")
                .contains("Weekly profile report — 19 Aug–26 Aug")
                .contains("• Buco (Bucothered)")
                .contains("Item level: 303 (+3)")
                .contains("M+ rating: 2,837 (+237)")
                .contains("Keys: 4 completed · 3 timed")
                .contains("Highest key: +14")
                .contains("Vault: +14 / +9 / —");
    }

    @Test
    void scheduledReportUsesConfiguredChat() {
        when(trackedPlayerService.activePlayers()).thenReturn(List.of());
        when(notifier.send(99L, service.reportMessage())).thenReturn(true);

        service.sendScheduledReport();

        verify(notifier).send(99L, service.reportMessage());
    }

    private static ScorePoint score(BigDecimal itemLevel, BigDecimal rating, Instant capturedAt) {
        return new ScorePoint(
                7L,
                SEASON,
                itemLevel,
                rating,
                capturedAt,
                "eu",
                "Stormscale",
                "Bucothered"
        );
    }
}
