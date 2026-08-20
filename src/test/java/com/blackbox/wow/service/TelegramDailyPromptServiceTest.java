package com.blackbox.wow.service;

import com.blackbox.wow.properties.TelegramDailyPromptProperties;
import com.blackbox.wow.repository.TelegramDailyPromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramDailyPromptServiceTest {

    private static final long TARGET_USER_ID = 1_699_671_723L;
    private static final long GROUP_CHAT_ID = -100123456789L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);
    private static final String PROMPT_KEY = "telegram-user:" + TARGET_USER_ID;

    @Mock private TelegramDailyPromptRepository repository;
    @Mock private BlackBoxBotNotifier notifier;

    @Test
    void sendsOnlyWhenTheDailyDeliveryIsClaimed() {
        when(repository.claimDelivery(PROMPT_KEY, TODAY)).thenReturn(true, false);
        when(notifier.send(GROUP_CHAT_ID, "Jope jesi ok?")).thenReturn(true);
        TelegramDailyPromptService service = service();

        service.onMessage(TARGET_USER_ID);
        service.onMessage(TARGET_USER_ID);

        verify(notifier).send(GROUP_CHAT_ID, "Jope jesi ok?");
    }

    @Test
    void releasesTheClaimWhenTelegramDeliveryFails() {
        when(repository.claimDelivery(PROMPT_KEY, TODAY)).thenReturn(true);
        when(notifier.send(GROUP_CHAT_ID, "Jope jesi ok?")).thenReturn(false);

        service().onMessage(TARGET_USER_ID);

        verify(repository).releaseDelivery(PROMPT_KEY, TODAY);
    }

    @Test
    void ignoresMessagesFromOtherUsers() {
        service().onMessage(123L);

        verify(repository, never()).claimDelivery(PROMPT_KEY, TODAY);
        verify(notifier, never()).send(GROUP_CHAT_ID, "Jope jesi ok?");
    }

    private TelegramDailyPromptService service() {
        ZoneId zone = ZoneId.of("Europe/Zagreb");
        return new TelegramDailyPromptService(
                repository,
                notifier,
                new TelegramDailyPromptProperties(
                        true,
                        TARGET_USER_ID,
                        GROUP_CHAT_ID,
                        "Jope jesi ok?",
                        zone
                ),
                Clock.fixed(TODAY.atStartOfDay(zone).toInstant(), ZoneOffset.UTC)
        );
    }
}
