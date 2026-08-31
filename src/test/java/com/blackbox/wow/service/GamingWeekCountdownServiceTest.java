package com.blackbox.wow.service;

import com.blackbox.wow.properties.GamingWeekCountdownProperties;
import com.blackbox.wow.repository.TelegramDailyPromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GamingWeekCountdownServiceTest {

    private static final long CHAT_ID = -100123456789L;
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 9, 10);
    private static final ZoneId ZONE = ZoneId.of("Europe/Zagreb");
    private static final String DELIVERY_KEY = "gaming-week-countdown:" + TARGET_DATE;

    @Mock private TelegramDailyPromptRepository repository;
    @Mock private BlackBoxBotNotifier notifier;

    @Test
    void sendsTheRemainingDayCountOnce() {
        LocalDate today = LocalDate.of(2026, 8, 31);
        when(repository.claimDelivery(DELIVERY_KEY, today)).thenReturn(true);
        when(notifier.send(CHAT_ID, "🎮 Gaming Week in 10 days!")).thenReturn(true);

        service(today).sendScheduledReminder();

        verify(notifier).send(CHAT_ID, "🎮 Gaming Week in 10 days!");
    }

    @Test
    void sendsATodayMessageOnTheTargetDate() {
        when(repository.claimDelivery(DELIVERY_KEY, TARGET_DATE)).thenReturn(true);
        when(notifier.send(CHAT_ID, "🎮 Gaming Week is today!")).thenReturn(true);

        service(TARGET_DATE).sendScheduledReminder();

        verify(notifier).send(CHAT_ID, "🎮 Gaming Week is today!");
    }

    @Test
    void stopsAfterGamingWeekStarts() {
        service(TARGET_DATE.plusDays(1)).sendScheduledReminder();

        verify(repository, never()).claimDelivery(DELIVERY_KEY, TARGET_DATE.plusDays(1));
        verify(notifier, never()).send(CHAT_ID, "🎮 Gaming Week is today!");
    }

    @Test
    void releasesTheDailyClaimWhenDeliveryFails() {
        LocalDate today = LocalDate.of(2026, 9, 9);
        when(repository.claimDelivery(DELIVERY_KEY, today)).thenReturn(true);
        when(notifier.send(CHAT_ID, "🎮 Gaming Week in 1 day!")).thenReturn(false);

        service(today).sendScheduledReminder();

        verify(repository).releaseDelivery(DELIVERY_KEY, today);
    }

    private GamingWeekCountdownService service(LocalDate date) {
        return new GamingWeekCountdownService(
                repository,
                notifier,
                new GamingWeekCountdownProperties(true, CHAT_ID, TARGET_DATE, ZONE),
                Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE)
        );
    }
}
