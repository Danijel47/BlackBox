package com.blackbox.wow.service;

import com.blackbox.wow.properties.GamingWeekCountdownProperties;
import com.blackbox.wow.repository.TelegramDailyPromptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
public class GamingWeekCountdownService {

    private static final String DELIVERY_KEY_PREFIX = "gaming-week-countdown:";

    private final TelegramDailyPromptRepository repository;
    private final BlackBoxBotNotifier notifier;
    private final GamingWeekCountdownProperties properties;
    private final Clock clock;

    public GamingWeekCountdownService(
            TelegramDailyPromptRepository repository,
            BlackBoxBotNotifier notifier,
            GamingWeekCountdownProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${telegram.gaming-week-countdown.cron:0 0 10 * * *}",
            zone = "${telegram.gaming-week-countdown.zone:Europe/Zagreb}"
    )
    public void sendScheduledReminder() {
        if (!properties.enabled() || properties.chatId() == 0) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        long remainingDays = ChronoUnit.DAYS.between(today, properties.targetDate());
        if (remainingDays < 0) {
            return;
        }

        String deliveryKey = DELIVERY_KEY_PREFIX + properties.targetDate();
        if (!repository.claimDelivery(deliveryKey, today)) {
            return;
        }
        if (!notifier.send(properties.chatId(), countdownMessage(remainingDays))) {
            repository.releaseDelivery(deliveryKey, today);
            log.warn("Could not deliver the Gaming Week countdown reminder");
        }
    }

    private static String countdownMessage(long remainingDays) {
        if (remainingDays == 0) {
            return "🎮 Gaming Week is today!";
        }
        return "🎮 Gaming Week in " + remainingDays + (remainingDays == 1 ? " day!" : " days!");
    }
}
