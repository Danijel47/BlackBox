package com.blackbox.wow.service;

import com.blackbox.wow.properties.TelegramDailyPromptProperties;
import com.blackbox.wow.repository.TelegramDailyPromptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
public class TelegramDailyPromptService {

    private static final String PROMPT_KEY_PREFIX = "telegram-user:";
    private static final int MINIMUM_TRIGGER_MESSAGE = 2;
    private static final int MAXIMUM_TRIGGER_MESSAGE = 5;

    private final TelegramDailyPromptRepository repository;
    private final BlackBoxBotNotifier notifier;
    private final TelegramDailyPromptProperties properties;
    private final Clock clock;

    public TelegramDailyPromptService(
            TelegramDailyPromptRepository repository,
            BlackBoxBotNotifier notifier,
            TelegramDailyPromptProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    public void onMessage(Long senderUserId) {
        if (!properties.enabled()
                || properties.chatId() == 0
                || senderUserId == null
                || senderUserId.longValue() != properties.targetUserId()) {
            return;
        }
        try {
            sendDailyPrompt();
        } catch (RuntimeException exception) {
            log.warn("Could not process the daily Telegram prompt ({})",
                    exception.getClass().getSimpleName());
        }
    }

    private void sendDailyPrompt() {
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        String promptKey = PROMPT_KEY_PREFIX + properties.targetUserId();
        int triggerMessageNumber = randomTriggerMessageNumber();
        if (!repository.recordMessageAndClaimDelivery(promptKey, today, triggerMessageNumber)) {
            return;
        }
        if (!notifier.send(properties.chatId(), properties.message())) {
            repository.releasePromptDelivery(promptKey, today);
        }
    }

    private static int randomTriggerMessageNumber() {
        return ThreadLocalRandom.current().nextInt(
                MINIMUM_TRIGGER_MESSAGE,
                MAXIMUM_TRIGGER_MESSAGE + 1
        );
    }
}
